-- =====================================================================
--  Dheu — posts and stories extras
--  1) Close friends: each member keeps a private list, and a story can be
--     shared with close friends only. Nobody can see whose list they're on.
--  2) Saved posts: a private bookmark that goes when the post expires.
--  3) Comment replies (one level, as on Instagram) and comment likes. A
--     like reaches the comment's author as a push only: no Activity entry,
--     so app versions that don't know the type never show it wrongly.
--  Everything is additive; every story shared with everyone stays visible
--  exactly as before.
--  Run AFTER 0023_chat_extras.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Close friends
-- ---------------------------------------------------------------------

create table public.close_friends (
  owner_id   uuid not null references public.profiles (id) on delete cascade,
  friend_id  uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (owner_id, friend_id),
  check (owner_id <> friend_id)
);
create index close_friends_friend_idx on public.close_friends (friend_id);

alter table public.close_friends enable row level security;
create policy "owners see their close friends" on public.close_friends
  for select to authenticated using (owner_id = (select auth.uid()));
create policy "owners add close friends" on public.close_friends
  for insert to authenticated with check (owner_id = (select auth.uid()) and not public.blocked_either(owner_id, friend_id));
create policy "owners remove close friends" on public.close_friends
  for delete to authenticated using (owner_id = (select auth.uid()));
revoke update on public.close_friends from authenticated;

-- Am I on this member's close friends list? The stories policy below asks
-- this, but a member must not be able to ask it directly (that would tell
-- them whose list they're on). So it lives in a schema the API doesn't
-- expose, as Supabase advises for SECURITY DEFINER helpers that RLS uses:
-- policies can call it, members can't. Don't add "private" to the API's
-- exposed schemas.
create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create or replace function private.is_close_friend_of(p_owner uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (select 1 from public.close_friends where owner_id = p_owner and friend_id = auth.uid());
$$;
revoke execute on function private.is_close_friend_of(uuid) from public, anon;
grant execute on function private.is_close_friend_of(uuid) to authenticated;

alter table public.stories
  add column audience text not null default 'everyone' check (audience in ('everyone', 'close_friends'));

-- A close friends story reaches its author and the people on their list,
-- nobody else: not even the admin, whose story bar would otherwise show it.
-- (Moderation still sees every story: the admin pages read with the
-- server's key, which RLS doesn't apply to.)
drop policy "followers read stories" on public.stories;
create policy "followers read stories" on public.stories
  for select to authenticated using (
    public.can_view_content_of(author_id)
    and (
      audience = 'everyone'
      or author_id = (select auth.uid())
      or private.is_close_friend_of(author_id)
    )
  );

-- Blocking someone now also takes each of you off the other's close friends list.
create or replace function public.on_block()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  delete from public.follows
   where (follower_id = new.blocker_id and following_id = new.blocked_id)
      or (follower_id = new.blocked_id and following_id = new.blocker_id);
  delete from public.follow_requests
   where (requester_id = new.blocker_id and target_id = new.blocked_id)
      or (requester_id = new.blocked_id and target_id = new.blocker_id);
  delete from public.notifications
   where (user_id = new.blocker_id and actor_id = new.blocked_id)
      or (user_id = new.blocked_id and actor_id = new.blocker_id);
  delete from public.close_friends
   where (owner_id = new.blocker_id and friend_id = new.blocked_id)
      or (owner_id = new.blocked_id and friend_id = new.blocker_id);
  return new;
end;
$$;

-- ---------------------------------------------------------------------
-- 2) Saved posts
-- ---------------------------------------------------------------------

create table public.saved_posts (
  user_id    uuid not null references public.profiles (id) on delete cascade,
  post_id    uuid not null references public.posts (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, post_id)
);
create index saved_posts_post_idx on public.saved_posts (post_id);

alter table public.saved_posts enable row level security;
create policy "members see their saved posts" on public.saved_posts
  for select to authenticated using (user_id = (select auth.uid()));
create policy "members save posts they can see" on public.saved_posts
  for insert to authenticated with check (
    user_id = (select auth.uid()) and exists (select 1 from public.posts p where p.id = post_id)
  );
create policy "members unsave posts" on public.saved_posts
  for delete to authenticated using (user_id = (select auth.uid()));
revoke update on public.saved_posts from authenticated;

-- The feeds say whether I saved each post (a new last column; the rest is unchanged).
create or replace view public.post_feed with (security_invoker = true) as
  select p.id,
    p.author_id,
    p.caption,
    p.created_at,
    p.expires_at,
    (select count(*) from public.post_likes l where l.post_id = p.id)::integer as like_count,
    (select count(*) from public.comments c where c.post_id = p.id)::integer as comment_count,
    exists (select 1 from public.post_likes l where l.post_id = p.id and l.user_id = auth.uid()) as liked_by_me,
    exists (select 1 from public.saved_posts s where s.post_id = p.id and s.user_id = auth.uid()) as saved_by_me
  from public.posts p;

create or replace view public.home_feed with (security_invoker = true) as
  select id, author_id, caption, created_at, expires_at, like_count, comment_count, liked_by_me, saved_by_me
  from public.post_feed f
  where author_id = (select auth.uid())
     or author_id in (select fo.following_id from public.follows fo where fo.follower_id = (select auth.uid()));

-- ---------------------------------------------------------------------
-- 3) Comment replies and likes
-- ---------------------------------------------------------------------

alter table public.comments add column parent_id uuid references public.comments (id) on delete cascade;
create index comments_parent_idx on public.comments (parent_id) where parent_id is not null;

-- One level, on the same post: a reply to a reply hangs off the top comment.
create or replace function public.normalize_comment_parent()
returns trigger
language plpgsql security definer
set search_path = public
as $$
declare
  v_parent public.comments%rowtype;
begin
  if new.parent_id is null then return new; end if;
  select * into v_parent from public.comments where id = new.parent_id;
  if not found or v_parent.post_id <> new.post_id then raise exception 'BAD_PARENT'; end if;
  if v_parent.parent_id is not null then new.parent_id := v_parent.parent_id; end if;
  return new;
end;
$$;
revoke execute on function public.normalize_comment_parent() from public, anon, authenticated;

create trigger on_comment_parent
  before insert on public.comments
  for each row execute function public.normalize_comment_parent();

create table public.comment_likes (
  comment_id uuid not null references public.comments (id) on delete cascade,
  user_id    uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (comment_id, user_id)
);
create index comment_likes_user_idx on public.comment_likes (user_id);

alter table public.comment_likes enable row level security;
-- Whoever can see the comment sees its likes (RLS on comments applies
-- inside the subquery), except from someone on either side of a block.
create policy "members read comment likes" on public.comment_likes
  for select to authenticated using (
    exists (select 1 from public.comments c where c.id = comment_id)
    and not public.blocked_either((select auth.uid()), user_id)
  );
create policy "members like comments they can see" on public.comment_likes
  for insert to authenticated with check (
    user_id = (select auth.uid()) and exists (select 1 from public.comments c where c.id = comment_id)
  );
create policy "members unlike comments" on public.comment_likes
  for delete to authenticated using (user_id = (select auth.uid()));
revoke update on public.comment_likes from authenticated;

-- "Rafi liked your comment" (the site decides who hears about it).
create trigger push_on_comment_like
  after insert on public.comment_likes
  for each row execute function public.notify_push_webhook();
