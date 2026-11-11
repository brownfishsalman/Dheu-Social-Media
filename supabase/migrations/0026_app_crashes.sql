-- =====================================================================
--  Dheu — crash reports from the Android app
--  When the app crashes it writes the details to a file on the phone and
--  sends them on the next launch, once the member is signed in. Only the
--  admin reads them (Admin → Crashes on the website, with the server key);
--  the nightly cleanup deletes them after 90 days. A crash loop can't
--  flood the table: past twenty a day per member, more are dropped.
--  Run AFTER 0025_safety_privacy.sql
-- =====================================================================

create table public.app_crashes (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid references public.profiles (id) on delete set null,
  app_version  text not null check (char_length(app_version) between 1 and 40),
  version_code integer not null check (version_code >= 0),
  android      text not null default '' check (char_length(android) <= 40),
  device       text not null default '' check (char_length(device) <= 120),
  thread       text not null default '' check (char_length(thread) <= 120),
  exception    text not null check (char_length(exception) between 1 and 1000),
  stack        text not null check (char_length(stack) <= 20000),
  happened_at  timestamptz not null,
  created_at   timestamptz not null default now()
);
create index app_crashes_created_idx on public.app_crashes (created_at desc);
create index app_crashes_user_idx on public.app_crashes (user_id, created_at desc);

-- Past twenty reports a day from one member, the rest are dropped quietly.
create or replace function public.limit_app_crashes()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  if (select count(*) from public.app_crashes
      where user_id = new.user_id and created_at > now() - interval '1 day') >= 20 then
    return null;
  end if;
  return new;
end;
$$;
revoke execute on function public.limit_app_crashes() from public, anon, authenticated;

create trigger on_app_crash_insert
  before insert on public.app_crashes
  for each row execute function public.limit_app_crashes();

alter table public.app_crashes enable row level security;
-- Members hand in their own crashes and can read back only those; nobody
-- but the admin's server key reads all of them, or changes or deletes any.
create policy "members see their own crash reports" on public.app_crashes
  for select to authenticated using (user_id = (select auth.uid()));
create policy "members send their own crash reports" on public.app_crashes
  for insert to authenticated with check (user_id = (select auth.uid()));
revoke update, delete on public.app_crashes from authenticated;
revoke all on public.app_crashes from anon;
