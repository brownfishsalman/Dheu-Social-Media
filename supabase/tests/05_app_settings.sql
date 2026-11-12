-- Access-rule tests: the invite-only switch (0028). Only the server key
-- reads or changes it; members and signed-out visitors can't even see it.
-- See 01_privacy_follows_blocks.sql for how these files work.
begin;

do $test$
declare
  v_me text := current_user;
  v_member uuid := gen_random_uuid();
  n int;
  results jsonb := '{}'::jsonb;
  expected jsonb;
  fails text[] := '{}';
  rec record;
begin
  insert into auth.users (id, email, raw_user_meta_data)
  values (v_member, 'rt-' || v_member || '@example.test',
          jsonb_build_object('username', 'rt_' || substr(replace(v_member::text, '-', ''), 1, 12), 'full_name', 'Test Member', 'is_private', false));

  select count(*) into n from public.app_settings where not invite_only;
  results := results || jsonb_build_object('one_row_starting_open', n);
  begin
    insert into public.app_settings (id) values (false);
    results := results || jsonb_build_object('second_row', 'allowed');
  exception when others then results := results || jsonb_build_object('second_row', 'rejected');
  end;

  -- ---- a member ----
  perform set_config('request.jwt.claims', json_build_object('sub', v_member, 'role', 'authenticated')::text, true);
  set local role authenticated;
  begin
    select count(*) into n from public.app_settings;
    results := results || jsonb_build_object('member_reads_settings', 'allowed');
  exception when others then results := results || jsonb_build_object('member_reads_settings', 'rejected');
  end;
  begin
    update public.app_settings set invite_only = true;
    results := results || jsonb_build_object('member_changes_settings', 'allowed');
  exception when others then results := results || jsonb_build_object('member_changes_settings', 'rejected');
  end;
  execute format('set local role %I', v_me);

  -- ---- signed out ----
  perform set_config('request.jwt.claims', json_build_object('role', 'anon')::text, true);
  set local role anon;
  begin
    select count(*) into n from public.app_settings;
    results := results || jsonb_build_object('visitor_reads_settings', 'allowed');
  exception when others then results := results || jsonb_build_object('visitor_reads_settings', 'rejected');
  end;
  execute format('set local role %I', v_me);

  select count(*) into n from public.app_settings where not invite_only;
  results := results || jsonb_build_object('still_open_after_attempts', n);

  expected := '{
    "one_row_starting_open": 1, "second_row": "rejected", "member_reads_settings": "rejected",
    "member_changes_settings": "rejected", "visitor_reads_settings": "rejected", "still_open_after_attempts": 1
  }';
  for rec in select key, value from jsonb_each(expected) loop
    if results -> rec.key is distinct from rec.value then
      fails := fails || format('%s: expected %s, got %s', rec.key, rec.value, coalesce(results -> rec.key, 'null'::jsonb));
    end if;
  end loop;
  if cardinality(fails) > 0 then raise exception 'app settings: % failed: %', cardinality(fails), array_to_string(fails, '; '); end if;
  raise notice 'app settings: % checks passed', (select count(*) from jsonb_object_keys(expected));
end
$test$;

rollback;
