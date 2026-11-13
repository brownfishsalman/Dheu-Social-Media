-- =====================================================================
--  Dheu — Find friends: the deck goes round and round
--  Replaces 0029's random deck. The deck works in rounds: people you
--  haven't seen yet come first, then everyone you've swiped (liked,
--  passed or unmatched) comes back once you've seen them all, in a new
--  order each round. The only people left out are your current matches.
--  Unmatching puts both people straight back in each other's deck, and a
--  right swipe after that can match them again.
--  Swiping on someone you're already matched with no longer reports a
--  match (0029 did, so a left swipe showed "It's a match").
--  Run AFTER 0029_infinite_swipe_deck.sql
-- =====================================================================

create or replace function public.swipe_deck(p_exclude uuid[] default '{}', p_limit integer default 30)
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

  -- Everyone (but matches) has been swiped this round: start the next one.
  if not exists (
       select 1 from public.swipe_profiles t
       join public.profiles p on p.id = t.user_id and p.banned_at is null
       left join public.swipes s on s.swiper_id = me and s.target_id = t.user_id
       where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
         and not exists (select 1 from public.swipe_matches m
                         where m.user_a = least(me, t.user_id) and m.user_b = greatest(me, t.user_id))
         and (s.target_id is null or s.round < my_round))
     and exists (
       select 1 from public.swipes s
       join public.swipe_profiles t on t.user_id = s.target_id and t.active
       join public.profiles p on p.id = t.user_id and p.banned_at is null
       where s.swiper_id = me and not public.blocked_either(me, s.target_id)
         and not exists (select 1 from public.swipe_matches m
                         where m.user_a = least(me, s.target_id) and m.user_b = greatest(me, s.target_id))) then
    select max(s.round) + 1 into my_round from public.swipes s where s.swiper_id = me;
    update public.swipe_profiles set deck_round = my_round where swipe_profiles.user_id = me;
    did_loop := true;
  end if;

  return query
    select t.user_id, p.full_name, t.bio, t.age, t.interests, t.photos, did_loop
    from public.swipe_profiles t
    join public.profiles p on p.id = t.user_id and p.banned_at is null
    left join public.swipes s on s.swiper_id = me and s.target_id = t.user_id
    where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
      and not exists (select 1 from public.swipe_matches m
                      where m.user_a = least(me, t.user_id) and m.user_b = greatest(me, t.user_id))
      and (s.target_id is null or s.round < my_round)
      and t.user_id <> all (coalesce(p_exclude, '{}'))
    order by (s.target_id is null) desc, md5(t.user_id::text || me::text || my_round::text)
    limit least(greatest(coalesce(p_limit, 30), 1), 50);
end;
$$;

-- Like or pass. A like that meets theirs makes a match (again, after an
-- unmatch). Already matched: nothing changes and no match is reported.
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

  if exists (select 1 from public.swipe_matches m
             where m.user_a = least(me, p_target) and m.user_b = greatest(me, p_target)) then
    return query select false, null::uuid;
    return;
  end if;

  -- clock_timestamp: undo needs the order of swipes, even within one transaction.
  insert into public.swipes as s (swiper_id, target_id, action, round, swiped_at)
  values (me, p_target, case when p_like then 'like' else 'pass' end, my_round, clock_timestamp())
  on conflict (swiper_id, target_id) do update
    set action = excluded.action, round = excluded.round, swiped_at = excluded.swiped_at;

  if p_like
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

-- Ends a match and puts both people straight back in each other's deck
-- (a round "before" the current one counts as not yet seen). The other
-- person's like stands, so a right swipe from the one who unmatched
-- matches them again. The chat stays.
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
  if not found then return; end if;
  update public.swipes s
     set action = case when s.swiper_id = me then 'unmatched' else s.action end,
         round = coalesce((select sp.deck_round from public.swipe_profiles sp where sp.user_id = s.swiper_id), 0) - 1
   where (s.swiper_id = me and s.target_id = p_other) or (s.swiper_id = p_other and s.target_id = me);
end;
$$;
