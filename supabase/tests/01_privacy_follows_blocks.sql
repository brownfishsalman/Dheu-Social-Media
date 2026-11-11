-- Access-rule tests: private and public accounts, follows, blocks,
-- notifications, and what members may change.
--
-- Each test file creates its own members and content, checks what each of
-- them can see or do as a signed-in member (RLS applies), and rolls it all
-- back. The "Database" workflow runs every file in supabase/tests against a
-- throwaway database built from supabase/migrations (see
-- .github/workflows/database.yml). A failure lists every check that didn't
-- match.
begin;

do $test$
declare
  v_me text := current_user;
  v_admin uuid := gen_random_uuid();
  v_alice uuid := gen_random_uuid(); -- private
  v_bob uuid := gen_random_uuid();   -- follows alice
  v_carol uuid := gen_random_uuid(); -- public
  v_dave uuid := gen_random_uuid();  -- follows nobody
  v_pa uuid; v_pc uuid; v_dave_comment uuid;
  n int;
  results jsonb := '{}'::jsonb;
  expected jsonb;
  fails text[] := '{}';
  rec record;
begin
  -- ---- members (the sign-up trigger makes their profiles) ----
  insert into auth.users (id, email, raw_user_meta_data)
  select u.id, 'rt-' || u.id || '@example.test',
         jsonb_build_object('username', 'rt_' || substr(replace(u.id::text, '-', ''), 1, 12), 'full_name', u.name, 'is_private', u.private)
  from (values (v_admin, 'Test Admin', true), (v_alice, 'Test Alice', true), (v_bob, 'Test Bob', true),
               (v_carol, 'Test Carol', false), (v_dave, 'Test Dave', true)) as u(id, name, private);
  update public.profiles set is_admin = (id = v_admin) where id in (v_admin, v_alice, v_bob, v_carol, v_dave);

  insert into public.follows (follower_id, following_id) values (v_bob, v_alice);
  insert into public.posts (author_id, caption) values (v_alice, 'alice') returning id into v_pa;
  insert into public.posts (author_id, caption) values (v_carol, 'carol') returning id into v_pc;
  insert into public.comments (post_id, author_id, body) values (v_pc, v_dave, 'hi from dave') returning id into v_dave_comment;
  insert into public.notifications (user_id, actor_id, type, post_id) values (v_alice, v_bob, 'like', v_pa);

  -- ---- bob: follows alice ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.posts where id = v_pa;
  results := results || jsonb_build_object('follower_sees_private_post', n);
  select count(*) into n from public.notifications where user_id = v_alice;
  results := results || jsonb_build_object('others_read_my_notifications', n);
  -- (No rows changed, or no permission at all: both mean "can't".)
  begin
    update public.posts set caption = 'changed' where id = v_pa;
    get diagnostics n = row_count;
  exception when others then n := 0;
  end;
  results := results || jsonb_build_object('edit_someone_elses_post', n);
  begin
    update public.profiles set bio = 'changed' where id = v_alice;
    get diagnostics n = row_count;
  exception when others then n := 0;
  end;
  results := results || jsonb_build_object('edit_someone_elses_profile', n);
  begin
    insert into public.posts (author_id, caption) values (v_alice, 'as alice');
    results := results || jsonb_build_object('post_as_someone_else', 'allowed');
  exception when others then results := results || jsonb_build_object('post_as_someone_else', 'rejected');
  end;
  select count(*) into n from public.comments where id = v_dave_comment;
  results := results || jsonb_build_object('sees_comment_before_blocking', n);
  execute format('set local role %I', v_me);

  -- ---- dave: follows nobody ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_dave, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.posts where id = v_pa;
  results := results || jsonb_build_object('stranger_sees_private_post', n);
  select count(*) into n from public.posts where id = v_pc;
  results := results || jsonb_build_object('stranger_sees_public_post', n);
  insert into public.follow_requests (requester_id, target_id) values (v_dave, v_alice);
  select count(*) into n from public.posts where id = v_pa;
  results := results || jsonb_build_object('requester_sees_private_post', n);
  execute format('set local role %I', v_me);

  -- ---- alice and the admin ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.posts where id = v_pa;
  results := results || jsonb_build_object('author_sees_own_post', n);
  select count(*) into n from public.notifications where user_id = v_alice and type = 'like' and post_id = v_pa;
  results := results || jsonb_build_object('reads_own_notifications', n);
  execute format('set local role %I', v_me);
  perform set_config('request.jwt.claims', json_build_object('sub', v_admin, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.posts where id = v_pa;
  results := results || jsonb_build_object('admin_sees_private_post', n);
  execute format('set local role %I', v_me);

  -- ---- blocks ----
  insert into public.blocks (blocker_id, blocked_id) values (v_bob, v_dave);
  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.comments where id = v_dave_comment;
  results := results || jsonb_build_object('sees_comment_of_blocked_member', n);
  execute format('set local role %I', v_me);
  perform set_config('request.jwt.claims', json_build_object('sub', v_carol, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.comments where id = v_dave_comment;
  results := results || jsonb_build_object('others_still_see_that_comment', n);
  execute format('set local role %I', v_me);
  insert into public.blocks (blocker_id, blocked_id) values (v_carol, v_dave);
  perform set_config('request.jwt.claims', json_build_object('sub', v_dave, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.posts where id = v_pc;
  results := results || jsonb_build_object('blocked_member_sees_public_post', n);
  execute format('set local role %I', v_me);

  -- ---- what members may call ----
  results := results || jsonb_build_object(
    'members_call_two_person_block_check', has_function_privilege('authenticated', 'public.blocked_either(uuid, uuid)', 'execute'),
    'signed_out_call_block_status', has_function_privilege('anon', 'public.block_status(uuid)', 'execute')
  );

  expected := '{
    "follower_sees_private_post": 1, "others_read_my_notifications": 0, "edit_someone_elses_post": 0,
    "edit_someone_elses_profile": 0, "post_as_someone_else": "rejected", "sees_comment_before_blocking": 1,
    "stranger_sees_private_post": 0, "stranger_sees_public_post": 1, "requester_sees_private_post": 0,
    "author_sees_own_post": 1, "reads_own_notifications": 1, "admin_sees_private_post": 1,
    "sees_comment_of_blocked_member": 0, "others_still_see_that_comment": 1, "blocked_member_sees_public_post": 0,
    "members_call_two_person_block_check": false, "signed_out_call_block_status": false
  }';
  for rec in select key, value from jsonb_each(expected) loop
    if results -> rec.key is distinct from rec.value then
      fails := fails || format('%s: expected %s, got %s', rec.key, rec.value, coalesce(results -> rec.key, 'null'::jsonb));
    end if;
  end loop;
  if cardinality(fails) > 0 then raise exception 'privacy, follows, blocks: % failed: %', cardinality(fails), array_to_string(fails, '; '); end if;
  raise notice 'privacy, follows, blocks: % checks passed', (select count(*) from jsonb_object_keys(expected));
end
$test$;

rollback;
