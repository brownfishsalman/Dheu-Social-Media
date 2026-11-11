-- =====================================================================
--  Dheu — safety and privacy
--  1) Reports: members report a post, story, comment or person. Only the
--     admin reads them (Admin → Reports on the website, with the server
--     key), and gets a push for each new one.
--  2) The block check that RLS policies use moves out of the API's reach.
--     Until now any signed-in member could call blocked_either(a, b) and
--     learn whether two other members had blocked each other.
--  3) Who may fetch each photo through Storage. The website and the app
--     now ask for short-lived signed links, which these rules gate. The
--     buckets stay public for now, because older app versions use the
--     public links; once members have updated, making the buckets private
--     leaves these rules as the only way in (see docs/OWNER-GUIDE.md).
--  Account deletion needs no schema change: the website does it with the
--  server key (src/lib/account.ts).
--  Run AFTER 0024_posts_stories_extras.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Reports
-- ---------------------------------------------------------------------

-- Content links go to null when the content is deleted, so a report (and
-- what the admin decided) outlives what it was about.
create table public.reports (
  id             uuid primary key default gen_random_uuid(),
  reporter_id    uuid references public.profiles (id) on delete set null,
  target_type    text not null check (target_type in ('post', 'story', 'comment', 'profile')),
  post_id        uuid references public.posts (id) on delete set null,
  story_id       uuid references public.stories (id) on delete set null,
  comment_id     uuid references public.comments (id) on delete set null,
  target_user_id uuid references public.profiles (id) on delete set null,
  reason         text not null check (reason in ('spam', 'harassment', 'hate', 'nudity', 'violence', 'self_harm', 'other')),
  details        text not null default '' check (char_length(details) <= 500),
  status         text not null default 'open' check (status in ('open', 'resolved', 'dismissed')),
  created_at     timestamptz not null default now(),
  resolved_at    timestamptz,
  resolved_by    uuid references public.profiles (id) on delete set null
);
create index reports_open_idx on public.reports (created_at desc) where status = 'open';
create index reports_target_user_idx on public.reports (target_user_id);
-- One open report per member per thing (a second tap says "already reported").
create unique index reports_one_open_per_target on public.reports
  (reporter_id, target_type, coalesce(post_id, story_id, comment_id, target_user_id))
  where status = 'open';

-- Fills in whose content it is, and keeps a new report to exactly one target.
create or replace function public.prepare_report()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  new.status := 'open';
  new.resolved_at := null;
  new.resolved_by := null;
  if new.target_type = 'post' then
    if new.post_id is null or new.story_id is not null or new.comment_id is not null then raise exception 'BAD_TARGET'; end if;
    select author_id into new.target_user_id from public.posts where id = new.post_id;
  elsif new.target_type = 'story' then
    if new.story_id is null or new.post_id is not null or new.comment_id is not null then raise exception 'BAD_TARGET'; end if;
    select author_id into new.target_user_id from public.stories where id = new.story_id;
  elsif new.target_type = 'comment' then
    if new.comment_id is null or new.post_id is not null or new.story_id is not null then raise exception 'BAD_TARGET'; end if;
    select author_id into new.target_user_id from public.comments where id = new.comment_id;
  elsif new.post_id is not null or new.story_id is not null or new.comment_id is not null then
    raise exception 'BAD_TARGET';
  end if;
  if new.target_user_id is null then raise exception 'NOT_FOUND'; end if;
  if new.target_user_id = new.reporter_id then raise exception 'OWN_CONTENT'; end if;
  return new;
end;
$$;
revoke execute on function public.prepare_report() from public, anon, authenticated;

create trigger on_report_insert
  before insert on public.reports
  for each row execute function public.prepare_report();

alter table public.reports enable row level security;
-- Members can file a report about something they can see (RLS on the
-- target applies inside the check) and read back only their own. Nobody
-- but the admin's server key reads all of them, or changes or deletes any.
create policy "members see their own reports" on public.reports
  for select to authenticated using (reporter_id = (select auth.uid()));
create policy "members report what they can see" on public.reports
  for insert to authenticated with check (
    reporter_id = (select auth.uid())
    and case target_type
      when 'post'    then exists (select 1 from public.posts p where p.id = reports.post_id)
      when 'story'   then exists (select 1 from public.stories s where s.id = reports.story_id)
      when 'comment' then exists (select 1 from public.comments c where c.id = reports.comment_id)
      when 'profile' then exists (select 1 from public.profiles pr where pr.id = reports.target_user_id)
      else false
    end
  );
revoke update, delete on public.reports from authenticated;
revoke all on public.reports from anon;

-- "New report about a comment: harassment", to the admin.
create trigger push_on_report
  after insert on public.reports
  for each row execute function public.notify_push_webhook();

-- ---------------------------------------------------------------------
-- 2) Block check out of the API's reach
-- ---------------------------------------------------------------------

-- Same answer as public.blocked_either, in the schema the API doesn't
-- expose (0024): RLS policies can call it, members can't.
create or replace function private.blocked_either(p_a uuid, p_b uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.blocks
    where (blocker_id = p_a and blocked_id = p_b) or (blocker_id = p_b and blocked_id = p_a)
  );
$$;
revoke execute on function private.blocked_either(uuid, uuid) from public, anon;
grant execute on function private.blocked_either(uuid, uuid) to authenticated;

-- The seven policies that asked public.blocked_either, unchanged otherwise.
alter policy "owners add close friends" on public.close_friends
  with check (owner_id = (select auth.uid()) and not private.blocked_either(close_friends.owner_id, close_friends.friend_id));

alter policy "members read comment likes" on public.comment_likes
  using (
    exists (select 1 from public.comments c where c.id = comment_likes.comment_id)
    and not private.blocked_either((select auth.uid()), comment_likes.user_id)
  );

alter policy "followers read comments" on public.comments
  using (
    exists (select 1 from public.posts p where p.id = comments.post_id)
    and not private.blocked_either((select auth.uid()), comments.author_id)
  );

alter policy "users send follow requests" on public.follow_requests
  with check (
    requester_id = (select auth.uid())
    and not private.blocked_either((select auth.uid()), follow_requests.target_id)
    and not exists (
      select 1 from public.follows
      where follows.follower_id = (select auth.uid()) and follows.following_id = follow_requests.target_id
    )
  );

alter policy "members read reactions" on public.message_reactions
  using (
    exists (select 1 from public.messages m where m.id = message_reactions.message_id)
    and not private.blocked_either((select auth.uid()), message_reactions.user_id)
  );

alter policy "author and mutual follows read live notes" on public.notes
  using (
    expires_at > now()
    and (
      author_id = (select auth.uid())
      or (
        exists (select 1 from public.follows f where f.follower_id = (select auth.uid()) and f.following_id = notes.author_id)
        and exists (select 1 from public.follows f where f.follower_id = notes.author_id and f.following_id = (select auth.uid()))
        and not private.blocked_either((select auth.uid()), notes.author_id)
      )
    )
  );

alter policy "followers read likes" on public.post_likes
  using (
    exists (select 1 from public.posts p where p.id = post_likes.post_id)
    and not private.blocked_either((select auth.uid()), post_likes.user_id)
  );

-- Database functions keep using public.blocked_either: they run as its
-- owner, so this doesn't touch them. Members lose direct access.
revoke execute on function public.blocked_either(uuid, uuid) from authenticated;

-- ---------------------------------------------------------------------
-- 3) Photo access rules
-- ---------------------------------------------------------------------

-- A file is readable by whoever can see the row that uses it (RLS on that
-- table applies inside the check), and always by the member whose folder
-- it is. These replace "any signed-in member reads any file".
drop policy "members read images" on storage.objects;
drop policy "members read chat photos" on storage.objects;

create policy "members read avatars of profiles they can see" on storage.objects
  for select to authenticated using (
    bucket_id = 'avatars' and (
      (storage.foldername(objects.name))[1] = (select auth.uid())::text
      or exists (select 1 from public.profiles pr where pr.avatar_path = objects.name)
    )
  );

create policy "members read photos of posts they can see" on storage.objects
  for select to authenticated using (
    bucket_id = 'posts' and (
      (storage.foldername(objects.name))[1] = (select auth.uid())::text
      or exists (select 1 from public.post_images i where i.path = objects.name or i.thumb_path = objects.name)
    )
  );

-- Close friends stories included: only their audience can fetch the photo.
create policy "members read stories they can see" on storage.objects
  for select to authenticated using (
    bucket_id = 'stories' and (
      (storage.foldername(objects.name))[1] = (select auth.uid())::text
      or exists (select 1 from public.stories s where s.image_path = objects.name)
    )
  );

create policy "members read chat files of messages they can see" on storage.objects
  for select to authenticated using (
    bucket_id = 'chat' and (
      (storage.foldername(objects.name))[1] = (select auth.uid())::text
      or exists (select 1 from public.messages m where m.image_path = objects.name or m.audio_path = objects.name)
    )
  );

-- The lookups above, by file path.
create index if not exists profiles_avatar_path_idx on public.profiles (avatar_path) where avatar_path is not null;
create index if not exists post_images_path_idx on public.post_images (path);
create index if not exists post_images_thumb_path_idx on public.post_images (thumb_path) where thumb_path is not null;
create index if not exists stories_image_path_idx on public.stories (image_path);
create index if not exists messages_image_path_idx on public.messages (image_path) where image_path is not null;
create index if not exists messages_audio_path_idx on public.messages (audio_path) where audio_path is not null;
