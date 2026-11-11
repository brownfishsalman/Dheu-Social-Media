import "server-only";
import { cache } from "react";
import { createClient } from "@/lib/supabase/server";
import type { Profile } from "@/lib/database.types";
import type { GridPost } from "@/lib/data/profiles";
import { FEED_PAGE_SIZE } from "@/lib/constants";

export type PostAuthor = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

export type FeedImage = { id: string; path: string; thumb_path: string | null; width: number; height: number; position: number };

export type FeedPost = {
  id: string;
  author_id: string;
  caption: string;
  created_at: string;
  expires_at: string;
  like_count: number;
  comment_count: number;
  liked_by_me: boolean;
  saved_by_me: boolean; // 0024
  author: PostAuthor;
  images: FeedImage[];
};

const FEED_SELECT =
  "id, author_id, caption, created_at, expires_at, like_count, comment_count, liked_by_me, saved_by_me, author:profiles!posts_author_id_fkey(id, username, full_name, avatar_path), images:post_images(id, path, thumb_path, width, height, position)";


type RawFeedRow = {
  id: string;
  author_id: string;
  caption: string;
  created_at: string;
  expires_at: string;
  like_count: number;
  comment_count: number;
  liked_by_me: boolean;
  saved_by_me: boolean;
  author: PostAuthor | null;
  images: FeedImage[];
};

function normalize(rows: RawFeedRow[]): FeedPost[] {
  return rows
    .filter((r): r is RawFeedRow & { author: PostAuthor } => r.author !== null)
    .map((r) => ({ ...r, images: [...r.images].sort((a, b) => a.position - b.position) }));
}

// Chronological feed of people I follow + me. The home_feed view picks those
// authors from the session itself, so this needn't wait for the following list.
// Cursor = created_at of the last post seen.
export async function getFeedPage(before?: string): Promise<{ posts: FeedPost[]; nextCursor: string | null }> {
  const supabase = await createClient();
  let q = supabase
    .from("home_feed")
    .select(FEED_SELECT)
    .gt("expires_at", new Date().toISOString())
    .order("created_at", { ascending: false })
    .limit(FEED_PAGE_SIZE + 1);
  if (before) q = q.lt("created_at", before);

  const { data } = await q;
  const rows = normalize((data ?? []) as RawFeedRow[]);
  const hasMore = rows.length > FEED_PAGE_SIZE;
  const posts = hasMore ? rows.slice(0, FEED_PAGE_SIZE) : rows;
  return { posts, nextCursor: hasMore ? posts[posts.length - 1].created_at : null };
}

// cache(): generateMetadata and the page both ask for the same post in one request.
export const getPost = cache(async (id: string): Promise<FeedPost | null> => {
  const supabase = await createClient();
  const { data } = await supabase.from("post_feed").select(FEED_SELECT).eq("id", id).maybeSingle();
  if (!data) return null;
  return normalize([data as RawFeedRow])[0] ?? null;
});

export type CommentItem = {
  id: string;
  post_id: string;
  author_id: string;
  body: string;
  created_at: string;
  parent_id: string | null; // a reply hangs off a top comment (one level, 0024)
  likes: { user_id: string }[];
  author: PostAuthor;
};

export async function getComments(postId: string): Promise<CommentItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("comments")
    .select(
      "id, post_id, author_id, body, created_at, parent_id, likes:comment_likes(user_id), author:profiles!comments_author_id_fkey(id, username, full_name, avatar_path)",
    )
    .eq("post_id", postId)
    .order("created_at", { ascending: true })
    .limit(500);
  return (data ?? []).filter((c) => c.author !== null) as CommentItem[];
}

// The posts I saved, newest save first, for the Saved tab on my profile. RLS
// leaves out posts that expired or that I can no longer see.
export async function getSavedPosts(userId: string): Promise<GridPost[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("saved_posts")
    .select("created_at, post:posts(id, created_at, images:post_images(path, thumb_path, width, height, position))")
    .eq("user_id", userId)
    .order("created_at", { ascending: false })
    .limit(200);
  return (data ?? [])
    .map((r) => r.post)
    .filter((p): p is NonNullable<typeof p> => Boolean(p))
    .map((p) => ({ ...p, images: [...p.images].sort((a, b) => a.position - b.position) }));
}

export async function getLikers(postId: string): Promise<PostAuthor[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("post_likes")
    .select("profile:profiles!post_likes_user_id_fkey(id, username, full_name, avatar_path)")
    .eq("post_id", postId)
    .order("created_at", { ascending: false })
    .limit(200);
  return (data ?? []).map((r) => r.profile).filter(Boolean) as PostAuthor[];
}
