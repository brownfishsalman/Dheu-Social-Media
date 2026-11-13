-- =====================================================================
--  Dheu — Infinite Swipe Deck (Keep all profiles in the deck)
--  No profiles are ever removed from the deck. Whether swiped right,
--  swiped left, matched, or unmatched, all active swipe profiles stay
--  available. Once you finish scrolling through them, the deck loops
--  indefinitely so the swipe list is never emptied out.
--  Run AFTER 0028_signup_settings.sql
-- =====================================================================

create or replace function public.swipe_deck(p_exclude uuid[] default '{}', p_limit integer default 30)
returns table (user_id uuid, full_name text, bio text, age smallint, interests text[], photos text[], looped boolean)
language plpgsql security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  me uuid := auth.uid();
  v_count integer;
begin
  if me is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.swipe_profiles where user_id = me and active) then
    raise exception 'NO_PROFILE';
  end if;

  -- Check if there are profiles outside p_exclude
  select count(*) into v_count
  from public.swipe_profiles t
  join public.profiles p on p.id = t.user_id and p.banned_at is null
  where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
    and t.user_id <> all (coalesce(p_exclude, '{}'));

  -- If all active profiles are currently in p_exclude (i.e. user swiped through all of them),
  -- return all active profiles so the deck loops without ever running dry:
  if v_count = 0 then
    return query
      select t.user_id, p.full_name, t.bio, t.age, t.interests, t.photos, true as looped
      from public.swipe_profiles t
      join public.profiles p on p.id = t.user_id and p.banned_at is null
      where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
      order by random()
      limit least(greatest(coalesce(p_limit, 30), 1), 50);
    return;
  end if;

  return query
    select t.user_id, p.full_name, t.bio, t.age, t.interests, t.photos, false as looped
    from public.swipe_profiles t
    join public.profiles p on p.id = t.user_id and p.banned_at is null
    where t.active and t.user_id <> me and not public.blocked_either(me, t.user_id)
      and t.user_id <> all (coalesce(p_exclude, '{}'))
    order by random()
    limit least(greatest(coalesce(p_limit, 30), 1), 50);
end;
$$;

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

  -- If already matched: return match
  select m.conversation_id into conv from public.swipe_matches m
   where m.user_a = least(me, p_target) and m.user_b = greatest(me, p_target);
  if found then
    return query select true, conv;
    return;
  end if;

  -- Upsert swipe with new action
  insert into public.swipes as s (swiper_id, target_id, action, round, swiped_at)
  values (me, p_target, case when p_like then 'like' else 'pass' end, my_round, clock_timestamp())
  on conflict (swiper_id, target_id) do update
    set action = excluded.action, round = excluded.round, swiped_at = excluded.swiped_at;

  if p_like
     and exists (select 1 from public.swipes s where s.swiper_id = me and s.target_id = p_target and s.action = 'like')
     and exists (select 1 from public.swipes s where s.swiper_id = p_target and s.target_id = me and s.action = 'like') then
    conv := public.get_or_create_conversation(p_target);
    update public.conversations set last_message_at = now() where id = conv and last_message_at is null;
    insert into public.swipe_matches (user_a, user_b, matched_by, conversation_id, a_seen_at, b_seen_at)
    values (least(me, p_target), greatest(me, p_target), me, conv,
            case when me < p_target then now() end, case when me > p_target then now() end)
    on conflict (user_a, user_b) do update
      set conversation_id = excluded.conversation_id, matched_by = excluded.matched_by;
    return query select true, conv;
    return;
  end if;
  return query select false, null::uuid;
end;
$$;
