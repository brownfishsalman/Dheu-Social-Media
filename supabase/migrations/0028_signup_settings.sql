-- =====================================================================
--  Dheu — open or invite-only sign-up
--  One switch the admin flips in Admin → Invites: whether creating an
--  account needs an invite code. It starts off, so anyone with the link
--  can join. Only the website's server (secret key) reads or changes it;
--  the sign-up page and the app ask the website (GET /api/auth/signup).
--  Run AFTER 0027_friend_swipes.sql
-- =====================================================================

-- Settings for the whole site: a single row (id is always true).
create table public.app_settings (
  id          boolean primary key default true check (id),
  invite_only boolean not null default false,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references public.profiles (id) on delete set null
);
insert into public.app_settings (id) values (true) on conflict (id) do nothing;

-- No policies: only the server key gets in.
alter table public.app_settings enable row level security;
revoke all on public.app_settings from anon, authenticated;
