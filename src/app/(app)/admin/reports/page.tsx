import type { Metadata } from "next";
import { createAdminClient } from "@/lib/supabase/admin";
import { adminSignedUrls } from "@/lib/storage-server";
import { ReportQueue, type ClosedReport, type ReportGroup } from "@/components/admin/ReportQueue";

export const metadata: Metadata = { title: "Reports" };

// Admin → Reports (0025): open reports, one card per reported thing, read
// with the server key (members can only read their own reports).
export default async function AdminReportsPage() {
  const admin = createAdminClient();
  const [{ data: open }, { data: closed }] = await Promise.all([
    admin
      .from("reports")
      .select(
        "id, target_type, reason, details, created_at, post_id, story_id, comment_id, target_user_id, reporter:profiles!reports_reporter_id_fkey(username), target:profiles!reports_target_user_id_fkey(username, full_name, banned_at), post:posts(caption, images:post_images(path, thumb_path, position)), story:stories(image_path, audience), comment:comments(body, post_id)",
      )
      .eq("status", "open")
      .order("created_at", { ascending: false })
      .limit(200),
    admin
      .from("reports")
      .select(
        "id, target_type, reason, status, resolved_at, target:profiles!reports_target_user_id_fkey(username), resolver:profiles!reports_resolved_by_fkey(username)",
      )
      .neq("status", "open")
      .order("resolved_at", { ascending: false })
      .limit(20),
  ]);

  const rows = open ?? [];
  // Small previews, signed with the server key (RLS would hide some from the admin).
  const postThumbs = rows.map((r) => {
    const first = [...(r.post?.images ?? [])].sort((a, b) => a.position - b.position)[0];
    return first ? (first.thumb_path ?? first.path) : null;
  });
  const [postUrls, storyUrls] = await Promise.all([
    adminSignedUrls("posts", postThumbs),
    adminSignedUrls("stories", rows.map((r) => r.story?.image_path)),
  ]);

  // Everyone's reports about the same thing go on one card, newest first.
  const groups = new Map<string, ReportGroup>();
  rows.forEach((r, i) => {
    const contentId = r.post_id ?? r.story_id ?? r.comment_id;
    const key = `${r.target_type}:${(r.target_type === "profile" ? r.target_user_id : contentId) ?? r.id}`;
    let group = groups.get(key);
    if (!group) {
      const thumb = postThumbs[i];
      group = {
        key,
        type: r.target_type,
        targetId: r.target_type === "profile" ? null : contentId,
        targetUser: r.target
          ? { id: r.target_user_id, username: r.target.username, fullName: r.target.full_name, banned: Boolean(r.target.banned_at) }
          : null,
        gone: r.target_type === "profile" ? !r.target : !contentId,
        image:
          r.target_type === "post" && thumb
            ? (postUrls.get(thumb) ?? null)
            : r.target_type === "story" && r.story
              ? (storyUrls.get(r.story.image_path) ?? null)
              : null,
        text: r.target_type === "post" ? (r.post?.caption ?? null) : r.target_type === "comment" ? (r.comment?.body ?? null) : null,
        closeFriends: r.story?.audience === "close_friends",
        href:
          r.target_type === "post" && r.post_id
            ? `/p/${r.post_id}`
            : r.target_type === "comment" && r.comment
              ? `/p/${r.comment.post_id}`
              : r.target_type === "story" && r.target
                ? `/stories/${r.target_user_id}`
                : r.target
                  ? `/u/${r.target.username}`
                  : null,
        reports: [],
      };
      groups.set(key, group);
    }
    group.reports.push({
      id: r.id,
      reason: r.reason,
      details: r.details,
      reporter: r.reporter?.username ?? null,
      createdAt: r.created_at,
    });
  });

  const recent: ClosedReport[] = (closed ?? []).map((r) => ({
    id: r.id,
    type: r.target_type,
    reason: r.reason,
    status: r.status,
    resolvedAt: r.resolved_at,
    target: r.target?.username ?? null,
    resolver: r.resolver?.username ?? null,
  }));

  return <ReportQueue groups={[...groups.values()]} closed={recent} />;
}
