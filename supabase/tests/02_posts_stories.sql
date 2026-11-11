-- Access-rule tests: close friends stories, saved posts, comment replies and
-- likes, expired posts (0024). See 01_privacy_follows_blocks.sql for how
-- these files work.
begin;

do $test$
declare
  v_me text := current_user;
  v_admin uuid := gen_random_uuid();
  v_alice uuid := gen_random_uuid(); -- private, has a close friends list
  v_bob uuid := gen_random_uuid();   -- follows alice, on her list
  v_dave uuid := gen_random_uuid();  -- follows alice, not on her list
  v_pa uuid; v_pa2 uuid; v_old uuid; v_story uuid; v_c1 uuid; v_r1 uuid; v_r2_parent uuid;
  n int;
  b boolean;
  results jsonb := '{}'::jsonb;
  expected jsonb;
  fails text[] := '{}';
  rec record;
begin
  insert into auth.users (id, email, raw_user_meta_data)
  select u.id, 'rt-' || u.id || '@example.test',
         jsonb_build_object('username', 'rt_' || substr(replace(u.id::text, '-', ''), 1, 12), 'full_name', u.name, 'is_private', true)
  from (values (v_admin, 'Test Admin'), (v_alice, 'Test Alice'), (v_bob, 'Test Bob'), (v_dave, 'Test Dave')) as u(id, name);
  update public.profiles set is_admin = (id = v_admin) where id in (v_admin, v_alice, v_bob, v_dave);

  insert into public.follows (follower_id, following_id) values (v_bob, v_alice), (v_dave, v_alice);
  insert into public.posts (author_id, caption) values (v_alice, 'one') returning id into v_pa;
  insert into public.posts (author_id, caption) values (v_alice, 'two') returning id into v_pa2;
  insert into public.posts (author_id, caption) values (v_alice, 'old') returning id into v_old;
  update public.posts set expires_at = now() - interval '1 day' where id = v_old;
  insert into public.stories (author_id, image_path, width, height, audience)
    values (v_alice, v_alice::text || '/test-cf.jpg', 9, 16, 'close_friends') returning id into v_story;

  -- ---- close friends: alice puts bob on her list ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  set local role authenticated;
  insert into public.close_friends (owner_id, friend_id) values (v_alice, v_bob);
  select count(*) into n from public.close_friends where owner_id = v_alice;
  results := results || jsonb_build_object('owner_reads_her_list', n);
  select count(*) into n from public.stories where id = v_story;
  results := results || jsonb_build_object('author_sees_cf_story', n);
  execute format('set local role %I', v_me);

  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.stories where id = v_story;
  results := results || jsonb_build_object('close_friend_sees_cf_story', n);
  select count(*) into n from public.close_friends where owner_id = v_alice;
  results := results || jsonb_build_object('friend_reads_the_list', n);
  select count(*) into n from public.posts where id = v_old;
  results := results || jsonb_build_object('follower_sees_expired_post', n);
  -- saved posts
  insert into public.saved_posts (user_id, post_id) values (v_bob, v_pa);
  select saved_by_me into b from public.post_feed where id = v_pa;
  results := results || jsonb_build_object('saved_by_me', b);
  begin
    insert into public.saved_posts (user_id, post_id) values (v_dave, v_pa2);
    results := results || jsonb_build_object('save_for_someone_else', 'allowed');
  exception when others then results := results || jsonb_build_object('save_for_someone_else', 'rejected');
  end;
  -- comments and replies
  insert into public.comments (post_id, author_id, body) values (v_pa, v_bob, 'top') returning id into v_c1;
  execute format('set local role %I', v_me);

  perform set_config('request.jwt.claims', json_build_object('sub', v_dave, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.stories where id = v_story;
  results := results || jsonb_build_object('follower_off_list_sees_cf_story', n);
  begin
    insert into public.close_friends (owner_id, friend_id) values (v_alice, v_dave);
    results := results || jsonb_build_object('add_self_to_someones_list', 'allowed');
  exception when others then results := results || jsonb_build_object('add_self_to_someones_list', 'rejected');
  end;
  select count(*) into n from public.saved_posts where user_id = v_bob;
  results := results || jsonb_build_object('reads_someones_saved_posts', n);
  insert into public.comments (post_id, author_id, body, parent_id) values (v_pa, v_dave, 'reply', v_c1) returning id into v_r1;
  insert into public.comments (post_id, author_id, body, parent_id) values (v_pa, v_dave, 'reply to reply', v_r1) returning parent_id into v_r2_parent;
  results := results || jsonb_build_object('reply_to_reply_goes_under_top', v_r2_parent = v_c1);
  begin
    insert into public.comments (post_id, author_id, body, parent_id) values (v_pa2, v_dave, 'elsewhere', v_c1);
    results := results || jsonb_build_object('reply_on_another_post', 'allowed');
  exception when others then results := results || jsonb_build_object('reply_on_another_post', sqlerrm);
  end;
  insert into public.comment_likes (comment_id, user_id) values (v_c1, v_dave);
  begin
    insert into public.comment_likes (comment_id, user_id) values (v_c1, v_dave);
    results := results || jsonb_build_object('like_a_comment_twice', 'allowed');
  exception when unique_violation then results := results || jsonb_build_object('like_a_comment_twice', 'rejected');
  end;
  execute format('set local role %I', v_me);

  perform set_config('request.jwt.claims', json_build_object('sub', v_admin, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.stories where id = v_story;
  results := results || jsonb_build_object('admin_sees_cf_story', n);
  execute format('set local role %I', v_me);

  -- comment likes across a block: bob blocks dave
  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.comment_likes where comment_id = v_c1;
  results := results || jsonb_build_object('sees_like_before_blocking', n);
  execute format('set local role %I', v_me);
  insert into public.blocks (blocker_id, blocked_id) values (v_bob, v_dave);
  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.comment_likes where comment_id = v_c1;
  results := results || jsonb_build_object('sees_like_of_blocked_member', n);
  execute format('set local role %I', v_me);

  expected := '{
    "owner_reads_her_list": 1, "author_sees_cf_story": 1, "close_friend_sees_cf_story": 1,
    "friend_reads_the_list": 0, "follower_sees_expired_post": 0, "saved_by_me": true,
    "save_for_someone_else": "rejected", "follower_off_list_sees_cf_story": 0,
    "add_self_to_someones_list": "rejected", "reads_someones_saved_posts": 0,
    "reply_to_reply_goes_under_top": true, "reply_on_another_post": "BAD_PARENT",
    "like_a_comment_twice": "rejected", "admin_sees_cf_story": 0,
    "sees_like_before_blocking": 1, "sees_like_of_blocked_member": 0
  }';
  for rec in select key, value from jsonb_each(expected) loop
    if results -> rec.key is distinct from rec.value then
      fails := fails || format('%s: expected %s, got %s', rec.key, rec.value, coalesce(results -> rec.key, 'null'::jsonb));
    end if;
  end loop;
  if cardinality(fails) > 0 then raise exception 'posts and stories: % failed: %', cardinality(fails), array_to_string(fails, '; '); end if;
  raise notice 'posts and stories: % checks passed', (select count(*) from jsonb_object_keys(expected));
end
$test$;

rollback;
