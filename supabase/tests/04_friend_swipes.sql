-- Access-rule tests: Find friends (0027, deck rules from 0030). Who sees
-- which swipe profile and photo, secret likes, matches leaving the deck,
-- the deck starting over with everyone, undo, unmatch and matching again,
-- and blocks. See 01_privacy_follows_blocks.sql for how these files work.
begin;

do $test$
declare
  v_me text := current_user;
  v_ann uuid := gen_random_uuid(); -- has a profile
  v_ben uuid := gen_random_uuid(); -- has a profile
  v_cat uuid := gen_random_uuid(); -- has a profile
  v_dan uuid := gen_random_uuid(); -- no profile
  v_eve uuid := gen_random_uuid(); -- has a profile, blocked by ann
  v_fay uuid := gen_random_uuid(); -- has a paused profile
  v_matched boolean; v_conv uuid; v_seen boolean; v_looped boolean; v_ok boolean;
  n int;
  results jsonb := '{}'::jsonb;
  expected jsonb;
  fails text[] := '{}';
  rec record;
begin
  insert into auth.users (id, email, raw_user_meta_data)
  select u.id, 'rt-' || u.id || '@example.test',
         jsonb_build_object('username', 'rt_' || substr(replace(u.id::text, '-', ''), 1, 12), 'full_name', u.name, 'is_private', false)
  from (values (v_ann, 'Test Ann'), (v_ben, 'Test Ben'), (v_cat, 'Test Cat'), (v_dan, 'Test Dan'), (v_eve, 'Test Eve'), (v_fay, 'Test Fay')) as u(id, name);

  -- Stand-ins for the uploaded photos (the rules only look at bucket and path).
  insert into storage.objects (bucket_id, name)
  select 'swipe', u::text || '/photo.jpg' from unnest(array[v_ann, v_ben, v_cat, v_eve, v_fay]) as u;
  insert into public.swipe_profiles (user_id, bio, interests, photos)
  select u, 'hi', array['Football', ' football ', 'Coffee'], array[u::text || '/photo.jpg']
  from unnest(array[v_ann, v_ben, v_cat, v_eve, v_fay]) as u;
  update public.swipe_profiles set active = false where user_id = v_fay;
  -- Anyone else's profile (only when this runs on a real database, rolled back) stays out of the decks.
  update public.swipe_profiles set active = false where user_id <> all (array[v_ann, v_ben, v_cat, v_eve, v_fay]);
  insert into public.blocks (blocker_id, blocked_id) values (v_ann, v_eve);
  select cardinality(interests) into n from public.swipe_profiles where user_id = v_ann;
  results := results || jsonb_build_object('interests_deduplicated', n);

  -- ---- dan: no swipe profile ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_dan, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.swipe_profiles;
  results := results || jsonb_build_object('no_profile_sees_profiles', n);
  select count(*) into n from storage.objects where bucket_id = 'swipe';
  results := results || jsonb_build_object('no_profile_fetches_photos', n);
  begin
    perform public.swipe_deck();
    results := results || jsonb_build_object('no_profile_gets_deck', 'allowed');
  exception when others then results := results || jsonb_build_object('no_profile_gets_deck', sqlerrm);
  end;
  begin
    perform public.swipe(v_ann, true);
    results := results || jsonb_build_object('no_profile_swipes', 'allowed');
  exception when others then results := results || jsonb_build_object('no_profile_swipes', sqlerrm);
  end;
  begin
    insert into public.swipe_profiles (user_id, photos) values (v_dan, array[v_ann::text || '/photo.jpg']);
    results := results || jsonb_build_object('profile_with_someone_elses_photo', 'allowed');
  exception when others then results := results || jsonb_build_object('profile_with_someone_elses_photo', sqlerrm);
  end;
  begin
    insert into public.swipe_profiles (user_id, photos) values (v_ann, array[v_ann::text || '/photo.jpg']);
    results := results || jsonb_build_object('profile_for_someone_else', 'allowed');
  exception when others then results := results || jsonb_build_object('profile_for_someone_else', 'rejected');
  end;
  execute format('set local role %I', v_me);

  -- ---- ann: sees ben and cat; eve is blocked, fay paused ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_ann, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.swipe_deck();
  results := results || jsonb_build_object('deck_cards', n);
  select count(*) into n from public.swipe_profiles where user_id in (v_eve, v_fay);
  results := results || jsonb_build_object('sees_blocked_or_paused_profile', n);
  select count(*) into n from storage.objects where bucket_id = 'swipe' and name = v_ben::text || '/photo.jpg';
  results := results || jsonb_build_object('fetches_deck_photo', n);
  select count(*) into n from storage.objects where bucket_id = 'swipe' and name in (v_eve::text || '/photo.jpg', v_fay::text || '/photo.jpg');
  results := results || jsonb_build_object('fetches_blocked_or_paused_photo', n);
  begin
    insert into public.swipes (swiper_id, target_id, action) values (v_ann, v_cat, 'like');
    results := results || jsonb_build_object('writes_swipe_directly', 'allowed');
  exception when others then results := results || jsonb_build_object('writes_swipe_directly', 'rejected');
  end;
  begin
    update public.swipe_profiles set deck_round = 5 where user_id = v_ann;
    results := results || jsonb_build_object('moves_own_deck', 'allowed');
  exception when others then results := results || jsonb_build_object('moves_own_deck', 'rejected');
  end;
  begin
    perform public.swipe(v_eve, true);
    results := results || jsonb_build_object('swipes_blocked_person', 'allowed');
  exception when others then results := results || jsonb_build_object('swipes_blocked_person', sqlerrm);
  end;
  select s.matched into v_matched from public.swipe(v_ben, true) s;
  results := results || jsonb_build_object('one_sided_like_matches', v_matched);
  perform public.swipe(v_cat, false);
  select count(*), bool_and(d.looped) into n, v_looped from public.swipe_deck() d;
  results := results || jsonb_build_object('deck_after_everyone', n, 'deck_started_over', v_looped);
  select public.swipe_undo(v_ben) into v_ok;
  results := results || jsonb_build_object('undo_an_older_swipe', v_ok);
  select public.swipe_undo(v_cat) into v_ok;
  results := results || jsonb_build_object('undo_last_swipe', v_ok);
  select count(*) into n from public.swipe_deck() d where d.user_id = v_cat and not d.looped;
  results := results || jsonb_build_object('undone_card_is_fresh', n);
  execute format('set local role %I', v_me);

  -- ---- ben: likes ann back ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_ben, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.swipes where target_id = v_ben;
  results := results || jsonb_build_object('sees_who_liked_them', n);
  select s.matched, s.conversation_id into v_matched, v_conv from public.swipe(v_ann, true) s;
  results := results || jsonb_build_object('mutual_like_matches', v_matched, 'match_opens_chat', v_conv is not null);
  select m.seen into v_seen from public.my_swipe_matches() m where m.user_id = v_ann;
  results := results || jsonb_build_object('second_liker_saw_match', v_seen);
  execute format('set local role %I', v_me);
  select count(*) into n from public.conversation_members where conversation_id = v_conv and user_id in (v_ann, v_ben);
  results := results || jsonb_build_object('both_in_the_chat', n);

  -- ---- ann: the match is new to her; then she unmatches ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_ann, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select m.seen into v_seen from public.my_swipe_matches() m where m.user_id = v_ben;
  results := results || jsonb_build_object('first_liker_saw_match', v_seen);
  perform public.mark_swipe_matches_seen();
  select m.seen into v_seen from public.my_swipe_matches() m where m.user_id = v_ben;
  results := results || jsonb_build_object('match_seen_after_marking', v_seen);
  select public.swipe_undo(v_ben) into v_ok;
  results := results || jsonb_build_object('undo_a_match', v_ok);
  select count(*) into n from public.swipe_deck() d where d.user_id = v_ben;
  results := results || jsonb_build_object('match_in_deck', n);
  select s.matched into v_matched from public.swipe(v_ben, false) s;
  results := results || jsonb_build_object('pass_on_a_match_says_matched', v_matched);
  select count(*) into n from public.my_swipe_matches();
  results := results || jsonb_build_object('match_kept_after_pass', n);
  perform public.swipe_unmatch(v_ben);
  select count(*) into n from public.my_swipe_matches();
  results := results || jsonb_build_object('matches_after_unmatch', n);
  select count(*) into n from public.swipe_deck() d where d.user_id = v_ben;
  results := results || jsonb_build_object('unmatched_back_in_my_deck', n);
  execute format('set local role %I', v_me);

  -- ---- ben: the match is gone, and ann is back in his deck ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_ben, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.my_swipe_matches();
  results := results || jsonb_build_object('other_side_matches_after_unmatch', n);
  select count(*) into n from public.swipe_deck() d where d.user_id = v_ann;
  results := results || jsonb_build_object('unmatcher_back_in_their_deck', n);
  perform public.swipe(v_cat, true);
  execute format('set local role %I', v_me);

  -- ---- ann changes her mind: ben's like still stands, so it's a match again ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_ann, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select s.matched into v_matched from public.swipe(v_ben, true) s;
  results := results || jsonb_build_object('match_again_after_unmatch', v_matched);
  execute format('set local role %I', v_me);

  -- ---- cat likes ben back, then blocks him ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_cat, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select s.matched into v_matched from public.swipe(v_ben, true) s;
  results := results || jsonb_build_object('second_match', v_matched);
  insert into public.blocks (blocker_id, blocked_id) values (v_cat, v_ben);
  select count(*) into n from public.my_swipe_matches();
  results := results || jsonb_build_object('matches_after_block', n);
  execute format('set local role %I', v_me);
  select count(*) into n from public.swipes where (swiper_id, target_id) in ((v_cat, v_ben), (v_ben, v_cat));
  results := results || jsonb_build_object('swipes_left_after_block', n);

  expected := '{
    "interests_deduplicated": 2, "no_profile_sees_profiles": 0, "no_profile_fetches_photos": 0,
    "no_profile_gets_deck": "NO_PROFILE", "no_profile_swipes": "NO_PROFILE",
    "profile_with_someone_elses_photo": "BAD_PHOTOS", "profile_for_someone_else": "rejected",
    "deck_cards": 2, "sees_blocked_or_paused_profile": 0, "fetches_deck_photo": 1,
    "fetches_blocked_or_paused_photo": 0, "writes_swipe_directly": "rejected", "moves_own_deck": "rejected",
    "swipes_blocked_person": "NOT_AVAILABLE", "one_sided_like_matches": false, "deck_after_everyone": 2,
    "deck_started_over": true, "undo_an_older_swipe": false, "undo_last_swipe": true, "undone_card_is_fresh": 1,
    "sees_who_liked_them": 0, "mutual_like_matches": true, "match_opens_chat": true, "both_in_the_chat": 2,
    "second_liker_saw_match": true, "first_liker_saw_match": false, "match_seen_after_marking": true,
    "undo_a_match": false, "match_in_deck": 0, "pass_on_a_match_says_matched": false, "match_kept_after_pass": 1,
    "matches_after_unmatch": 0, "unmatched_back_in_my_deck": 1, "other_side_matches_after_unmatch": 0,
    "unmatcher_back_in_their_deck": 1, "match_again_after_unmatch": true,
    "second_match": true, "matches_after_block": 0, "swipes_left_after_block": 0
  }';
  for rec in select key, value from jsonb_each(expected) loop
    if results -> rec.key is distinct from rec.value then
      fails := fails || format('%s: expected %s, got %s', rec.key, rec.value, coalesce(results -> rec.key, 'null'::jsonb));
    end if;
  end loop;
  if cardinality(fails) > 0 then raise exception 'friend swipes: % failed: %', cardinality(fails), array_to_string(fails, '; '); end if;
  raise notice 'friend swipes: % checks passed', (select count(*) from jsonb_object_keys(expected));
end
$test$;

rollback;
