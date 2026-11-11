-- Access-rule tests: group chats, reports (0025), who may fetch which photo
-- from Storage (0025), and crash reports from the app (0026). See
-- 01_privacy_follows_blocks.sql for how these files work.
begin;

do $test$
declare
  v_me text := current_user;
  v_admin uuid := gen_random_uuid();
  v_alice uuid := gen_random_uuid(); -- private
  v_bob uuid := gen_random_uuid();   -- follows alice, in a group with her, on her close friends list
  v_dave uuid := gen_random_uuid();  -- follows nobody
  v_pa uuid; v_story uuid; v_conv uuid; v_msg uuid;
  v_photo text; v_cf text; v_chat text; v_spare text;
  n int;
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

  v_photo := v_alice::text || '/test-photo.jpg';
  v_cf := v_alice::text || '/test-cf.jpg';
  v_chat := v_alice::text || '/test-chat.jpg';
  v_spare := v_alice::text || '/test-spare.jpg';
  insert into public.follows (follower_id, following_id) values (v_bob, v_alice);
  insert into public.posts (author_id, caption) values (v_alice, 'alice') returning id into v_pa;
  insert into public.post_images (post_id, path, thumb_path, width, height, position)
    values (v_pa, v_photo, v_alice::text || '/test-photo-thumb.jpg', 100, 100, 0);
  insert into public.stories (author_id, image_path, width, height, audience) values (v_alice, v_cf, 9, 16, 'close_friends') returning id into v_story;
  insert into public.close_friends (owner_id, friend_id) values (v_alice, v_bob);
  insert into public.conversations (is_group, title, created_by) values (true, 'Test group', v_alice) returning id into v_conv;
  insert into public.conversation_members (conversation_id, user_id) values (v_conv, v_alice), (v_conv, v_bob);
  insert into public.messages (conversation_id, sender_id, body, image_path) values (v_conv, v_alice, '', v_chat) returning id into v_msg;
  -- Stand-ins for the uploaded files (the rules only look at bucket and path).
  insert into storage.objects (bucket_id, name) values
    ('posts', v_photo), ('posts', v_spare), ('stories', v_cf), ('chat', v_chat);

  -- ---- bob: follower, group member, close friend ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_bob, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.messages where id = v_msg;
  results := results || jsonb_build_object('member_reads_group_message', n);
  select count(*) into n from storage.objects where bucket_id = 'posts' and name = v_photo;
  results := results || jsonb_build_object('follower_fetches_post_photo', n);
  select count(*) into n from storage.objects where bucket_id = 'posts' and name = v_spare;
  results := results || jsonb_build_object('fetches_unused_file_in_her_folder', n);
  select count(*) into n from storage.objects where bucket_id = 'stories' and name = v_cf;
  results := results || jsonb_build_object('close_friend_fetches_cf_photo', n);
  select count(*) into n from storage.objects where bucket_id = 'chat' and name = v_chat;
  results := results || jsonb_build_object('member_fetches_chat_photo', n);
  -- reports
  insert into public.reports (reporter_id, target_type, post_id, reason) values (v_bob, 'post', v_pa, 'spam');
  begin
    insert into public.reports (reporter_id, target_type, post_id, reason) values (v_bob, 'post', v_pa, 'hate');
    results := results || jsonb_build_object('report_the_same_post_twice', 'allowed');
  exception when unique_violation then results := results || jsonb_build_object('report_the_same_post_twice', 'rejected');
  end;
  select count(*) into n from public.reports where reporter_id = v_bob;
  results := results || jsonb_build_object('reads_own_reports', n);
  begin
    update public.reports set status = 'dismissed' where reporter_id = v_bob;
    get diagnostics n = row_count;
    results := results || jsonb_build_object('close_own_report', case when n > 0 then 'allowed' else 'rejected' end);
  exception when others then results := results || jsonb_build_object('close_own_report', 'rejected');
  end;
  -- crash reports
  insert into public.app_crashes (user_id, app_version, version_code, exception, stack, happened_at)
    values (v_bob, '0.3.0', 3, 'java.lang.IllegalStateException: test', 'at com.salmansaadiq.dheu.Test.run(Test.kt:1)', now());
  begin
    insert into public.app_crashes (user_id, app_version, version_code, exception, stack, happened_at)
      values (v_dave, '0.3.0', 3, 'java.lang.IllegalStateException: test', '', now());
    results := results || jsonb_build_object('send_crash_as_someone_else', 'allowed');
  exception when others then results := results || jsonb_build_object('send_crash_as_someone_else', 'rejected');
  end;
  insert into public.app_crashes (user_id, app_version, version_code, exception, stack, happened_at)
    select v_bob, '0.3.0', 3, 'java.lang.IllegalStateException: loop ' || i, '', now() from generate_series(1, 25) as i;
  select count(*) into n from public.app_crashes where user_id = v_bob;
  results := results || jsonb_build_object('crash_reports_kept_in_a_day', n);
  execute format('set local role %I', v_me);

  -- ---- dave: follows nobody, not in the group ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_dave, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from public.messages where id = v_msg;
  results := results || jsonb_build_object('outsider_reads_group_message', n);
  begin
    insert into public.messages (conversation_id, sender_id, body) values (v_conv, v_dave, 'let me in');
    results := results || jsonb_build_object('outsider_writes_into_group', 'allowed');
  exception when others then results := results || jsonb_build_object('outsider_writes_into_group', 'rejected');
  end;
  select count(*) into n from storage.objects where name in (v_photo, v_cf, v_chat, v_spare);
  results := results || jsonb_build_object('outsider_fetches_any_file', n);
  begin
    insert into public.reports (reporter_id, target_type, post_id, reason) values (v_dave, 'post', v_pa, 'spam');
    results := results || jsonb_build_object('report_a_post_out_of_sight', 'allowed');
  exception when others then results := results || jsonb_build_object('report_a_post_out_of_sight', 'rejected');
  end;
  select count(*) into n from public.reports where reporter_id = v_bob;
  results := results || jsonb_build_object('reads_someone_elses_reports', n);
  select count(*) into n from public.app_crashes where user_id = v_bob;
  results := results || jsonb_build_object('reads_someone_elses_crashes', n);
  execute format('set local role %I', v_me);

  -- ---- alice: her own folder, her own post ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_alice, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from storage.objects where bucket_id = 'posts' and name = v_spare;
  results := results || jsonb_build_object('owner_fetches_own_unused_file', n);
  begin
    insert into public.reports (reporter_id, target_type, post_id, reason) values (v_alice, 'post', v_pa, 'spam');
    results := results || jsonb_build_object('report_own_post', 'allowed');
  exception when others then results := results || jsonb_build_object('report_own_post', sqlerrm);
  end;
  execute format('set local role %I', v_me);

  -- ---- the admin: not on alice's list ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_admin, 'role', 'authenticated')::text, true);
  set local role authenticated;
  select count(*) into n from storage.objects where bucket_id = 'stories' and name = v_cf;
  results := results || jsonb_build_object('admin_fetches_cf_photo', n);
  execute format('set local role %I', v_me);

  expected := '{
    "member_reads_group_message": 1, "follower_fetches_post_photo": 1, "fetches_unused_file_in_her_folder": 0,
    "close_friend_fetches_cf_photo": 1, "member_fetches_chat_photo": 1, "report_the_same_post_twice": "rejected",
    "reads_own_reports": 1, "close_own_report": "rejected", "send_crash_as_someone_else": "rejected",
    "crash_reports_kept_in_a_day": 20, "outsider_reads_group_message": 0, "outsider_writes_into_group": "rejected",
    "outsider_fetches_any_file": 0, "report_a_post_out_of_sight": "rejected", "reads_someone_elses_reports": 0,
    "reads_someone_elses_crashes": 0, "owner_fetches_own_unused_file": 1, "report_own_post": "OWN_CONTENT",
    "admin_fetches_cf_photo": 0
  }';
  for rec in select key, value from jsonb_each(expected) loop
    if results -> rec.key is distinct from rec.value then
      fails := fails || format('%s: expected %s, got %s', rec.key, rec.value, coalesce(results -> rec.key, 'null'::jsonb));
    end if;
  end loop;
  if cardinality(fails) > 0 then raise exception 'chat, reports, storage: % failed: %', cardinality(fails), array_to_string(fails, '; '); end if;
  raise notice 'chat, reports, storage: % checks passed', (select count(*) from jsonb_object_keys(expected));
end
$test$;

rollback;
