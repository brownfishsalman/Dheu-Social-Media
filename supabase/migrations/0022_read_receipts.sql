-- =====================================================================
--  Dheu — read receipts ("Seen" under your last message)
--  1) profiles.read_receipts: whether other members may see when this
--     member has read their messages. On by default; switched off in the
--     app's Settings. Turning it off hides your reads from others; you
--     still see theirs (as on Instagram).
--  2) read_receipts(conversation): for each other current member who
--     shares receipts, when they last read this conversation and when
--     they joined it. conversation_reads itself stays private to its
--     owner (it also holds cleared_at, see 0019): this returns only
--     those two times, and only to a member of the conversation.
--  3) A nudge over Realtime whenever someone reads a conversation, so an
--     open chat refreshes its receipts at once. The broadcast carries no
--     data: the chat asks read_receipts() again, which checks membership.
--     It goes to the public topic "reads:<conversation id>", so someone
--     who knows a conversation's id could see *that* it was read, never
--     by whom or what.
--  Run AFTER 0021_function_grants.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) The member's choice
-- ---------------------------------------------------------------------

alter table public.profiles add column read_receipts boolean not null default true;

-- Members may already update their own row (policy "users edit own
-- profile"), but only the columns granted in 0001. Add this one.
grant update (read_receipts) on public.profiles to authenticated;

-- ---------------------------------------------------------------------
-- 2) Who has read a conversation
-- ---------------------------------------------------------------------
-- joined_at lets the app ignore people added to a group after a message
-- was sent: joining stamps last_read_at with the time they were added.

create or replace function public.read_receipts(p_conversation_id uuid)
returns table (user_id uuid, last_read_at timestamptz, joined_at timestamptz)
language sql stable security definer
set search_path = public
as $$
  select m.user_id, r.last_read_at, m.joined_at
  from public.conversation_members m
  join public.profiles p on p.id = m.user_id and p.read_receipts
  join public.conversation_reads r on r.conversation_id = m.conversation_id and r.user_id = m.user_id
  where m.conversation_id = p_conversation_id
    and m.user_id <> auth.uid()
    and public.is_conversation_member(p_conversation_id)
    and not public.blocked_either(auth.uid(), m.user_id);
$$;

revoke execute on function public.read_receipts(uuid) from public, anon;
grant execute on function public.read_receipts(uuid) to authenticated;

-- ---------------------------------------------------------------------
-- 3) The nudge
-- ---------------------------------------------------------------------

create or replace function public.broadcast_read()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  if tg_op = 'UPDATE' and new.last_read_at is not distinct from old.last_read_at then
    return null;
  end if;
  begin
    perform realtime.send('{}'::jsonb, 'read', 'reads:' || new.conversation_id::text, false);
  exception when others then
    null; -- a Realtime hiccup must never stop a read from being saved
  end;
  return null;
end;
$$;

-- Only its trigger runs it (see 0021).
revoke execute on function public.broadcast_read() from public, anon, authenticated;

create trigger on_read_broadcast
  after insert or update of last_read_at on public.conversation_reads
  for each row execute function public.broadcast_read();
