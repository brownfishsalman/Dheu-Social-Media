import type { Metadata } from "next";
import { createAdminClient } from "@/lib/supabase/admin";
import { adminSignedUrls } from "@/lib/storage-server";
import { ContentManager, type AdminPost, type AdminStory } from "@/components/admin/ContentManager";

export const metadata: Metadata = { title: "Content" };

export default async function AdminContentPage() {
  const admin = createAdminClient();
  const now = new Date().toISOString();

  const [{ data: posts }, { data: stories }] = await Promise.all([
    admin
      .from("posts")
      .select("id, caption, created_at, author:profiles!posts_author_id_fkey(username), images:post_images(path, thumb_path, position)")
      .gt("expires_at", now)
      .order("created_at", { ascending: false })
      .limit(60),
    admin
      .from("stories")
      .select("id, created_at, author_id, image_path, audience, author:profiles!stories_author_id_fkey(username)")
      .gt("expires_at", now)
      .order("created_at", { ascending: false }),
  ]);

  const covers = (posts ?? []).map((p) => {
    const first = [...p.images].sort((a, b) => a.position - b.position)[0];
    return first ? (first.thumb_path ?? first.path) : null; // 56 px tile: thumbnail when there is one
  });
  // Signed with the server key: the admin sees every story here, close friends ones too.
  const [postUrls, storyUrls] = await Promise.all([
    adminSignedUrls("posts", covers),
    adminSignedUrls("stories", (stories ?? []).map((s) => s.image_path)),
  ]);

  const adminPosts: AdminPost[] = (posts ?? []).map((p, i) => ({
    id: p.id,
    caption: p.caption,
    created_at: p.created_at,
    username: p.author?.username ?? "?",
    cover: covers[i] ? (postUrls.get(covers[i]) ?? null) : null,
  }));

  const adminStories: AdminStory[] = (stories ?? []).map((s) => ({
    id: s.id,
    created_at: s.created_at,
    author_id: s.author_id,
    image: storyUrls.get(s.image_path) ?? null,
    closeFriends: s.audience === "close_friends",
    username: s.author?.username ?? "?",
  }));

  return <ContentManager posts={adminPosts} stories={adminStories} />;
}
