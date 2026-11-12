-- =====================================================================
--  Dheu — Find friends (the flame tab)
--  Members who want to meet new people make a swipe profile (1 to 6
--  photos, a short bio, an optional age, up to five interests) and swipe
--  through everyone else's: right to like, left to pass. Likes stay
--  secret; when two people like each other it's a match, and a chat
--  between them opens in Messages. Once someone has swiped through
--  everyone, the people they passed on come back in a new order.
--  Only members with an active swipe profile see anyone else's (nobody
--  browses without taking part), and never across a block. Swipes are
--  written only through the functions below, so nobody can read whom
--  someone else liked.
--  Run AFTER 0026_app_crashes.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------

create table public.swipe_profiles (
  user_id    uuid primary key references public.profiles (id) on delete cascade,
  bio        text not null default '' check (char_length(bio) <= 300),
  age        smallint check (age between 13 and 99),
  interests  text[] not null default '{}' check (cardinality(interests) <= 5),
  photos     text[] not null check (cardinality(photos) between 1 and 6),
  active     boolean not null default true, -- paused profiles leave every deck
  deck_round integer not null default 0,    -- how many times this member's deck has started over
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
-- Storage asks "which profile uses this file?" by path.
create index swipe_profiles_photos_idx on public.swipe_profiles using gin (photos);

-- One row per pair, the latest swipe. 'unmatched' keeps an unmatched
-- person out of the deck for good.
create table public.swipes (
  swiper_id uuid not null references public.profiles (id) on delete cascade,
  target_id uuid not null references public.profiles (id) on delete cascade,
  action    text not null check (action in ('like', 'pass', 'unmatched')),
  round     integer not null default 0, -- the swiper's deck_round at the time
  swiped_at timestamptz not null default now(),
  primary key (swiper_id, target_id),
  check (swiper_id <> target_id)
);
create index swipes_likes_received_idx on public.swipes (target_id, swiper_id) where action = 'like';
create index swipes_latest_idx on public.swipes (swiper_id, swiped_at desc);

-- matched_by swiped second: they see the match on screen, the other
-- one gets a push and sees it the next time they open the tab.
create table public.swipe_matches (
  user_a          uuid not null references public.profiles (id) on delete cascade,
  user_b          uuid not null references public.profiles (id) on delete cascade,
  matched_by      uuid not null references public.profiles (id) on delete cascade,
  conversation_id uuid references public.conversations (id) on delete set null,
  a_seen_at       timestamptz,
  b_seen_at       timestamptz,
  created_at      timestamptz not null default now(),
  primary key (user_a, user_b),
  check (user_a < user_b),
  check (matched_by in (user_a, user_b))
);
create index swipe_matches_user_b_idx on public.swipe_matches (user_b);

-- ---------------------------------------------------------------------
-- Swipe profiles: tidy input, photos from the member's own folder
-- ---------------------------------------------------------------------

create or replace function public.prepare_swipe_profile()
returns trigger
language plpgsql security definer
set search_path = public
as $$
declare
  v_path text;
  v_new_photos boolean := true;
begin
  if tg_op = 'UPDATE' then
    new.user_id := old.user_id;
    new.created_at := old.created_at;
    v_new_photos := new.photos is distinct from old.photos;
  else
    new.deck_round := 0;
    new.created_at := now();
  end if;

  new.bio := btrim(coalesce(new.bio, ''));
  -- Interests: trimmed, single spaces, at most 24 characters, no repeats
  -- in any case, the first five.
  new.interests := coalesce((
    select array_agg(d.t order by d.first_at)
    from (
      select distinct on (lower(c.t)) c.t, u.ord as first_at
      from unnest(new.interests) with ordinality as u(raw, ord)
      cross join lateral (select left(regexp_replace(btrim(u.raw), '\s+', ' ', 'g'), 24) as t) c
      where c.t <> ''
      order by lower(c.t), u.ord
    ) d
  ), '{}');
  new.interests := new.interests[1:5];

  -- Photos: files this member uploaded to their own folder, each once.
  if v_new_photos then
    if new.photos is null or cardinality(new.photos) not between 1 and 6
       or (select count(distinct x) from unnest(new.photos) as x) <> cardinality(new.photos) then
      raise exception 'BAD_PHOTOS';
    end if;
    foreach v_path in array new.photos loop
      if v_path is null or v_path not like new.user_id::text || '/%'
         or not exists (select 1 from storage.objects o where o.bucket_id = 'swipe' and o.name = v_path) then
        raise exception 'BAD_PHOTOS';
      end if;
    end loop;
  end if;

  if tg_op = 'INSERT' then
    new.updated_at := now();
  elsif (new.bio, new.age, new.interests, new.photos, new.active)
        is distinct from (old.bio, old.age, old.interests, old.photos, old.active) then
    new.updated_at := now();
  end if;
  return new;
end;
$$;
revoke execute on function public.prepare_swipe_profile() from public, anon, authenticated;

create trigger on_swipe_profile_write
  before insert or update on public.swipe_profiles
  for each row execute function public.prepare_swipe_profile();

-- Does this member have an active swipe profile? For RLS; in the schema
-- the API doesn't expose (0024), so members can't ask it about others.
create or replace function private.has_swipe_profile(p_user uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (select 1 from public.swipe_profiles where user_id = p_user and active);
$$;
revoke execute on function private.has_swipe_profile(uuid) from public, anon;
grant execute on function private.has_swipe_profile(uuid) to authenticated;

-- ---------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------

alter table public.swipe_profiles enable row level security;
create policy "members see their own swipe profile, and others' once they have one" on public.swipe_profiles
  for select to authenticated using (
    user_id = (select auth.uid())
    or (
      active
      and (select private.has_swipe_profile((select auth.uid())))
      and not private.blocked_either((select auth.uid()), swipe_profiles.user_id)
    )
  );
create policy "members make their own swipe profile" on public.swipe_profiles
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "members edit their own swipe profile" on public.swipe_profiles
  for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "members delete their own swipe profile" on public.swipe_profiles
  for delete to authenticated using (user_id = (select auth.uid()));
-- What members may change; the deck position is the functions' business.
revoke update on public.swipe_profiles from authenticated;
grant update (bio, age, interests, photos, active) on public.swipe_profiles to authenticated;
revoke all on public.swipe_profiles from anon;

-- Only their own swipes, and only through swipe() and swipe_undo().
alter table public.swipes enable row level security;
create policy "members see their own swipes" on public.swipes
  for select to authenticated using (swiper_id = (select auth.uid()));
revoke insert, update, delete on public.swipes from authenticated;
revoke all on public.swipes from anon;

alter table public.swipe_matches enable row level security;
create policy "members see their own matches" on public.swipe_matches
  for select to authenticated using (
    (select auth.uid()) in (user_a, user_b)
    and not private.blocked_either(swipe_matches.user_a, swipe_matches.user_b)
  );
revoke insert, update, delete on public.swipe_matches from authenticated;
revoke all on public.swipe_matches from anon;

-- ---------------------------------------------------------------------
-- The deck, swiping, undo, matches
-- ---------------------------------------------------------------------

-- The next cards, never-swiped people first. When everyone has been
-- swiped this round, the people passed on come back (looped = true), in
-- a different order each round. p_exclude: cards the app already holds.
create or replace function public.swipe_deck(p_exclude uuid[] default '{}', p_limit integer default 10)
returns table (user_id uuid, full_name text, bio text, age smallint, interests text[], photos text[], looped boolean)
language plpgsql security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  me uuid := auth.uid();
  my_round integer;
  did_loop boolean := false;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select sp.deck_round into my_round from public.swipe_profiles sp where sp.user_id = me and sp.active;
  if my_round is null then raise exception 'NO_PROFILE'; end if;

  if not exists (
       select 1 from public.swipe_profiles t
       join public.profiles p on p.id = t.user_id and p.banned_at is null
       left join public.swipes s on s.swiper_id = me and s.target_id = t.user_id
       where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
         and (s.target_id is null or (s.action = 'pass' and s.round < my_round)))
     and exists (
       select 1 from public.swipes s
       join public.swipe_profiles t on t.user_id = s.target_id and t.active
       join public.profiles p on p.id = t.user_id and p.banned_at is null
       where s.swiper_id = me and s.action = 'pass' and not public.blocked_either(me, s.target_id)) then
    select max(s.round) + 1 into my_round from public.swipes s where s.swiper_id = me and s.action = 'pass';
    update public.swipe_profiles set deck_round = my_round where swipe_profiles.user_id = me;
    did_loop := true;
  end if;

  return query
    select t.user_id, p.full_name, t.bio, t.age, t.interests, t.photos, did_loop
    from public.swipe_profiles t
    join public.profiles p on p.id = t.user_id and p.banned_at is null
    left join public.swipes s on s.swiper_id = me and s.target_id = t.user_id
    where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
      and (s.target_id is null or (s.action = 'pass' and s.round < my_round))
      and t.user_id <> all (coalesce(p_exclude, '{}'))
    order by (s.target_id is null) desc, md5(t.user_id::text || me::text || my_round::text)
    limit least(greatest(coalesce(p_limit, 10), 1), 30);
end;
$$;

-- Like or pass. A like that meets theirs makes a match, opens (or reuses)
-- the chat between them and puts it at the top of both inboxes.
create or replace function public.swipe(p_target uuid, p_like boolean)
returns table (matched boolean, conversation_id uuid)
language plpgsql security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  me uuid := auth.uid();
  my_round integer;
  conv uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select sp.deck_round into my_round from public.swipe_profiles sp where sp.user_id = me and sp.active;
  if my_round is null then raise exception 'NO_PROFILE'; end if;
  if p_target is null or p_target = me or p_like is null
     or not exists (
       select 1 from public.swipe_profiles t
       join public.profiles p on p.id = t.user_id and p.banned_at is null
       where t.user_id = p_target and t.active)
     or public.blocked_either(me, p_target) then
    raise exception 'NOT_AVAILABLE';
  end if;

  -- Already matched (a double tap, or a second phone): nothing changes.
  select m.conversation_id into conv from public.swipe_matches m
   where m.user_a = least(me, p_target) and m.user_b = greatest(me, p_target);
  if found then
    return query select true, conv;
    return;
  end if;

  -- clock_timestamp: undo needs the order of swipes, even within one transaction.
  insert into public.swipes as s (swiper_id, target_id, action, round, swiped_at)
  values (me, p_target, case when p_like then 'like' else 'pass' end, my_round, clock_timestamp())
  on conflict (swiper_id, target_id) do update
    set action = excluded.action, round = excluded.round, swiped_at = excluded.swiped_at
    where s.action <> 'unmatched';

  if p_like
     and exists (select 1 from public.swipes s where s.swiper_id = me and s.target_id = p_target and s.action = 'like')
     and exists (select 1 from public.swipes s where s.swiper_id = p_target and s.target_id = me and s.action = 'like') then
    conv := public.get_or_create_conversation(p_target);
    update public.conversations set last_message_at = now() where id = conv and last_message_at is null;
    insert into public.swipe_matches (user_a, user_b, matched_by, conversation_id, a_seen_at, b_seen_at)
    values (least(me, p_target), greatest(me, p_target), me, conv,
            case when me < p_target then now() end, case when me > p_target then now() end)
    on conflict do nothing;
    return query select true, conv;
    return;
  end if;
  return query select false, null::uuid;
end;
$$;

-- Takes back the caller's most recent swipe, if it's on p_target and
-- didn't make a match. The card is fresh again.
create or replace function public.swipe_undo(p_target uuid)
returns boolean
language plpgsql security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  v_last uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select s.target_id into v_last from public.swipes s
   where s.swiper_id = me and s.action in ('like', 'pass')
   order by s.swiped_at desc
   limit 1;
  if v_last is distinct from p_target then return false; end if;
  if exists (select 1 from public.swipe_matches m
             where m.user_a = least(me, p_target) and m.user_b = greatest(me, p_target)) then
    return false;
  end if;
  delete from public.swipes where swiper_id = me and target_id = p_target;
  return true;
end;
$$;

-- Ends a match. Neither sees the other in the deck again; the chat stays.
create or replace function public.swipe_unmatch(p_other uuid)
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
begin
  if me is null then raise exception 'not authenticated'; end if;
  delete from public.swipe_matches where user_a = least(me, p_other) and user_b = greatest(me, p_other);
  update public.swipes set action = 'unmatched' where swiper_id = me and target_id = p_other;
end;
$$;

-- The caller's matches, newest first; seen = they've had the match screen.
create or replace function public.my_swipe_matches()
returns table (user_id uuid, username text, full_name text, avatar_path text, conversation_id uuid, matched_at timestamptz, seen boolean)
language sql stable security definer
set search_path = public
as $$
  select o.id, o.username, o.full_name, o.avatar_path, m.conversation_id, m.created_at,
         (case when m.user_a = auth.uid() then m.a_seen_at else m.b_seen_at end) is not null
  from public.swipe_matches m
  join public.profiles o on o.id = case when m.user_a = auth.uid() then m.user_b else m.user_a end
  where auth.uid() in (m.user_a, m.user_b)
    and not public.blocked_either(m.user_a, m.user_b)
  order by m.created_at desc;
$$;

create or replace function public.mark_swipe_matches_seen()
returns void
language sql security definer
set search_path = public
as $$
  update public.swipe_matches set a_seen_at = now() where user_a = auth.uid() and a_seen_at is null;
  update public.swipe_matches set b_seen_at = now() where user_b = auth.uid() and b_seen_at is null;
$$;

revoke execute on function public.swipe_deck(uuid[], integer) from public, anon;
revoke execute on function public.swipe(uuid, boolean) from public, anon;
revoke execute on function public.swipe_undo(uuid) from public, anon;
revoke execute on function public.swipe_unmatch(uuid) from public, anon;
revoke execute on function public.my_swipe_matches() from public, anon;
revoke execute on function public.mark_swipe_matches_seen() from public, anon;
grant execute on function public.swipe_deck(uuid[], integer) to authenticated;
grant execute on function public.swipe(uuid, boolean) to authenticated;
grant execute on function public.swipe_undo(uuid) to authenticated;
grant execute on function public.swipe_unmatch(uuid) to authenticated;
grant execute on function public.my_swipe_matches() to authenticated;
grant execute on function public.mark_swipe_matches_seen() to authenticated;

-- A block ends a match and clears both people's swipes on each other,
-- so an unblock starts from nothing.
create or replace function public.on_block_swipes()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  delete from public.swipe_matches
   where user_a = least(new.blocker_id, new.blocked_id) and user_b = greatest(new.blocker_id, new.blocked_id);
  delete from public.swipes
   where (swiper_id = new.blocker_id and target_id = new.blocked_id)
      or (swiper_id = new.blocked_id and target_id = new.blocker_id);
  return new;
end;
$$;
revoke execute on function public.on_block_swipes() from public, anon, authenticated;

create trigger on_block_swipes
  after insert on public.blocks
  for each row execute function public.on_block_swipes();

-- "You and Rafi matched", to whoever swiped first.
create trigger push_on_swipe_match
  after insert on public.swipe_matches
  for each row execute function public.notify_push_webhook();

-- ---------------------------------------------------------------------
-- Photos: a private bucket. Readable by whoever can see the profile that
-- uses the file (RLS on swipe_profiles applies inside the check), and
-- always by the member whose folder it is.
-- ---------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('swipe', 'swipe', false, 6291456, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy "users upload swipe photos into own folder" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'swipe' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "members read swipe photos of profiles they can see" on storage.objects
  for select to authenticated using (
    bucket_id = 'swipe' and (
      (storage.foldername(objects.name))[1] = (select auth.uid())::text
      or exists (select 1 from public.swipe_profiles sp where sp.photos @> array[objects.name])
    )
  );
create policy "users or admin delete swipe photos" on storage.objects
  for delete to authenticated
  using (bucket_id = 'swipe' and ((storage.foldername(name))[1] = (select auth.uid())::text or (select public.is_admin())));
