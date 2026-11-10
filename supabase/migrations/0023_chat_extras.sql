-- =====================================================================
--  Dheu — chat extras
--  1) Reactions: one emoji per member per message, live over Realtime,
--     with a push to the message's sender (see /api/push).
--  2) Typing indicator: private Realtime channel "typing:<conversation>",
--     which only members of that conversation may join or send on.
--  3) View-once photos, kept in the private "once" bucket. A recipient
--     opens one with open_view_once(), which records the open; that person
--     may then download the file for two minutes, and never again. The
--     sender can't reopen it. Old clients see "Photo expired".
--  4) read_receipts_inbox(): read_receipts() for every chat at once, so
--     the inbox can say "Seen" under your last message.
--  Everything here is new; nothing existing changes behaviour.
--  Run AFTER 0022_read_receipts.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Reactions
-- ---------------------------------------------------------------------

create table public.message_reactions (
  message_id      uuid not null references public.messages (id) on delete cascade,
  user_id         uuid not null references public.profiles (id) on delete cascade,
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  emoji           text not null check (char_length(emoji) between 1 and 16 and emoji !~ '[[:alnum:][:space:]]'),
  created_at      timestamptz not null default now(),
  primary key (message_id, user_id)
);
create index message_reactions_conversation_idx on public.message_reactions (conversation_id);
create index message_reactions_user_idx on public.message_reactions (user_id);

-- The conversation and the time come from the database, never the client.
create or replace function public.fill_message_child()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  select m.conversation_id into new.conversation_id from public.messages m where m.id = new.message_id;
  if tg_table_name = 'message_reactions' then new.created_at := now(); end if;
  return new;
end;
$$;
revoke execute on function public.fill_message_child() from public, anon, authenticated;

create trigger on_reaction_fill
  before insert or update on public.message_reactions
  for each row execute function public.fill_message_child();

alter table public.message_reactions enable row level security;

-- Whoever can see the message sees its reactions (RLS on messages applies
-- inside the subquery), except from someone on either side of a block.
create policy "members read reactions" on public.message_reactions
  for select to authenticated using (
    exists (select 1 from public.messages m where m.id = message_id)
    and not public.blocked_either((select auth.uid()), user_id)
  );
create policy "members react to messages they can see" on public.message_reactions
  for insert to authenticated with check (
    user_id = (select auth.uid())
    and exists (
      select 1 from public.messages m
      where m.id = message_id and m.kind = 'text' and not public.conversation_blocked(m.conversation_id)
    )
  );
create policy "members change their own reaction" on public.message_reactions
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
create policy "members remove their own reaction" on public.message_reactions
  for delete to authenticated using (user_id = (select auth.uid()));

revoke insert, update on public.message_reactions from authenticated;
grant insert (message_id, user_id, emoji) on public.message_reactions to authenticated;
grant update (emoji) on public.message_reactions to authenticated;

-- React, change the emoji, or (emoji null) take the reaction back. Runs as
-- the caller, so the policies above decide.
create or replace function public.react_to_message(p_message_id uuid, p_emoji text)
returns void
language plpgsql security invoker
set search_path = public
as $$
begin
  if p_emoji is null then
    delete from public.message_reactions where message_id = p_message_id and user_id = auth.uid();
  else
    insert into public.message_reactions (message_id, user_id, emoji)
    values (p_message_id, auth.uid(), p_emoji)
    on conflict (message_id, user_id) do update set emoji = excluded.emoji;
  end if;
end;
$$;
revoke execute on function public.react_to_message(uuid, text) from public, anon;
grant execute on function public.react_to_message(uuid, text) to authenticated;

alter publication supabase_realtime add table public.message_reactions;

-- "Rafi reacted ❤️ to your message" (the site decides who hears about it).
create trigger push_on_reaction
  after insert on public.message_reactions
  for each row execute function public.notify_push_webhook();

-- ---------------------------------------------------------------------
-- 2) Typing indicator
-- ---------------------------------------------------------------------
-- Realtime Authorization: a private channel is joined (select) and sent on
-- (insert) only when these policies allow it for the channel's topic.

create or replace function public.can_use_typing_topic(p_topic text)
returns boolean
language plpgsql stable security definer
set search_path = public
as $$
declare
  v uuid;
begin
  if p_topic is null or p_topic not like 'typing:%' then return false; end if;
  begin
    v := substring(p_topic from 8)::uuid;
  exception when others then
    return false;
  end;
  return public.is_conversation_member(v) and not public.conversation_blocked(v);
end;
$$;
revoke execute on function public.can_use_typing_topic(text) from public, anon;
grant execute on function public.can_use_typing_topic(text) to authenticated;

create policy "members hear typing in their chats" on realtime.messages
  for select to authenticated
  using (extension = 'broadcast' and public.can_use_typing_topic((select realtime.topic())));
create policy "members say they are typing in their chats" on realtime.messages
  for insert to authenticated
  with check (extension = 'broadcast' and public.can_use_typing_topic((select realtime.topic())));

-- ---------------------------------------------------------------------
-- 3) View-once photos
-- ---------------------------------------------------------------------

alter table public.messages
  add column once_path text,                          -- the file in "once"; null after it's gone
  add column view_once boolean not null default false; -- set by the database from once_path

alter table public.messages add constraint messages_once_check
  check (not view_once or (image_path is null and audio_path is null));
create index messages_once_path_idx on public.messages (once_path) where once_path is not null;

grant insert (once_path) on public.messages to authenticated;

-- A view-once photo expires like any chat photo (image_expires_at), which
-- also lets a media-only message pass messages_body_check.
create or replace function public.stamp_message_media_expiry()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.view_once := new.once_path is not null;
  if new.image_path is not null or new.once_path is not null then new.image_expires_at := now() + interval '7 days'; end if;
  if new.audio_path is not null then new.audio_expires_at := now() + interval '7 days'; end if;
  return new;
end;
$$;

create table public.message_opens (
  message_id      uuid not null references public.messages (id) on delete cascade,
  user_id         uuid not null references public.profiles (id) on delete cascade,
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  opened_at       timestamptz not null default now(),
  primary key (message_id, user_id)
);
create index message_opens_conversation_idx on public.message_opens (conversation_id);
create index message_opens_user_idx on public.message_opens (user_id);

alter table public.message_opens enable row level security;
revoke insert, update, delete on public.message_opens from authenticated;

-- Your own opens, and who opened the photos you sent.
create policy "members see opens of view-once photos" on public.message_opens
  for select to authenticated using (
    user_id = (select auth.uid())
    or exists (select 1 from public.messages m where m.id = message_id and m.sender_id = (select auth.uid()))
  );

alter publication supabase_realtime add table public.message_opens;

-- Opening: allowed once per recipient (a retry within two minutes returns
-- the same file, in case the download failed). Returns the file's path.
create or replace function public.open_view_once(p_message_id uuid)
returns text
language plpgsql security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  m  public.messages%rowtype;
  v_opened timestamptz;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select * into m from public.messages where id = p_message_id;
  if not found or not m.view_once or not public.can_read_message(m.conversation_id, m.sender_id, m.created_at) then
    raise exception 'NOT_FOUND';
  end if;
  if m.sender_id = me then raise exception 'OWN_PHOTO'; end if;
  if m.once_path is null then raise exception 'EXPIRED'; end if;

  insert into public.message_opens (message_id, user_id, conversation_id)
  values (m.id, me, m.conversation_id)
  on conflict (message_id, user_id) do nothing;
  if not found then
    select opened_at into v_opened from public.message_opens where message_id = m.id and user_id = me;
    if v_opened < now() - interval '2 minutes' then raise exception 'ALREADY_OPENED'; end if;
  end if;
  return m.once_path;
end;
$$;
revoke execute on function public.open_view_once(uuid) from public, anon;
grant execute on function public.open_view_once(uuid) to authenticated;

-- Storage: may this member download this view-once file right now?
create or replace function public.can_download_once(p_name text)
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.messages m
    join public.message_opens o on o.message_id = m.id and o.user_id = auth.uid()
    where m.once_path = p_name and o.opened_at > now() - interval '2 minutes'
  );
$$;
revoke execute on function public.can_download_once(text) from public, anon;
grant execute on function public.can_download_once(text) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('once', 'once', false, 6291456, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy "users upload view-once photos into own folder" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'once' and (storage.foldername(name))[1] = (select auth.uid())::text);
-- The sender can read their own file only so that unsending can delete it
-- (a delete returns the row); the apps never show it to them again.
create policy "view-once photos: sender, or a recipient just after opening" on storage.objects
  for select to authenticated
  using (bucket_id = 'once' and ((storage.foldername(name))[1] = (select auth.uid())::text or public.can_download_once(name)));
create policy "users or admin delete view-once photos" on storage.objects
  for delete to authenticated
  using (bucket_id = 'once' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select public.is_admin())));

-- For the nightly cleanup (service role only): files that are past their
-- expiry, or that every other member opened more than ten minutes ago.
create or replace function public.spent_view_once_photos()
returns table (id uuid, once_path text)
language sql stable security definer
set search_path = public
as $$
  select m.id, m.once_path
  from public.messages m
  where m.once_path is not null
    and (
      m.image_expires_at <= now()
      or not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = m.conversation_id
          and cm.user_id <> m.sender_id
          and not exists (
            select 1 from public.message_opens o
            where o.message_id = m.id and o.user_id = cm.user_id and o.opened_at <= now() - interval '10 minutes'
          )
      )
    )
  limit 1000;
$$;
revoke execute on function public.spent_view_once_photos() from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- 4) Receipts for the inbox
-- ---------------------------------------------------------------------
-- Same rules as read_receipts(), for every conversation I'm in.

create or replace function public.read_receipts_inbox()
returns table (conversation_id uuid, user_id uuid, last_read_at timestamptz, joined_at timestamptz)
language sql stable security definer
set search_path = public
as $$
  select m.conversation_id, m.user_id, r.last_read_at, m.joined_at
  from public.conversation_members mine
  join public.conversation_members m on m.conversation_id = mine.conversation_id and m.user_id <> mine.user_id
  join public.profiles p on p.id = m.user_id and p.read_receipts
  join public.conversation_reads r on r.conversation_id = m.conversation_id and r.user_id = m.user_id
  where mine.user_id = auth.uid()
    and not public.blocked_either(auth.uid(), m.user_id);
$$;
revoke execute on function public.read_receipts_inbox() from public, anon;
grant execute on function public.read_receipts_inbox() to authenticated;
