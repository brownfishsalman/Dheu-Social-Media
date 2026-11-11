import { NextResponse } from "next/server";
import { createAdminClient } from "@/lib/supabase/admin";
import { postImageFiles } from "@/lib/storage";

// Runs daily (see vercel.json). Deletes expired posts and stories together
// with their image files so storage is reclaimed. Protected by CRON_SECRET,
// which Vercel sends automatically as a bearer token for cron invocations.
export async function GET(request: Request) {
  const auth = request.headers.get("authorization");
  if (!process.env.CRON_SECRET || auth !== `Bearer ${process.env.CRON_SECRET}`) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const admin = createAdminClient();
  const now = new Date().toISOString();
  const report = { posts: 0, postImages: 0, stories: 0, chatPhotos: 0, voiceMessages: 0, viewOncePhotos: 0, emptyGroups: 0, errors: [] as string[] };

  // ---- expired posts -------------------------------------------------
  const { data: posts, error: pErr } = await admin
    .from("posts")
    .select("id, images:post_images(path, thumb_path)")
    .lte("expires_at", now)
    .limit(500);
  if (pErr) report.errors.push(`posts: ${pErr.message}`);

  if (posts && posts.length > 0) {
    const paths = posts.flatMap((p) => postImageFiles(p.images));
    for (let i = 0; i < paths.length; i += 100) {
      const { error } = await admin.storage.from("posts").remove(paths.slice(i, i + 100));
      if (error) report.errors.push(`post files: ${error.message}`);
    }
    const { error } = await admin.from("posts").delete().in("id", posts.map((p) => p.id));
    if (error) report.errors.push(`post rows: ${error.message}`);
    else {
      report.posts = posts.length;
      report.postImages = posts.reduce((n, p) => n + p.images.length, 0);
    }
  }

  // ---- expired stories not saved in a highlight ------------------------
  const { data: stories, error: sErr } = await admin
    .from("stories")
    .select("id, image_path, items:highlight_items(highlight_id)")
    .lte("expires_at", now)
    .limit(1000);
  if (sErr) report.errors.push(`stories: ${sErr.message}`);

  const removable = (stories ?? []).filter((s) => s.items.length === 0);
  if (removable.length > 0) {
    const paths = removable.map((s) => s.image_path);
    for (let i = 0; i < paths.length; i += 100) {
      const { error } = await admin.storage.from("stories").remove(paths.slice(i, i + 100));
      if (error) report.errors.push(`story files: ${error.message}`);
    }
    const { error } = await admin.from("stories").delete().in("id", removable.map((s) => s.id));
    if (error) report.errors.push(`story rows: ${error.message}`);
    else report.stories = removable.length;
  }

  // ---- chat photos older than their expiry (message text stays) -----------
  const { data: photos, error: cpErr } = await admin
    .from("messages")
    .select("id, image_path")
    .not("image_path", "is", null)
    .lte("image_expires_at", now)
    .limit(1000);
  if (cpErr) report.errors.push(`chat photos: ${cpErr.message}`);
  if (photos && photos.length > 0) {
    const paths = photos.map((p) => p.image_path!).filter(Boolean);
    for (let i = 0; i < paths.length; i += 100) {
      const { error } = await admin.storage.from("chat").remove(paths.slice(i, i + 100));
      if (error) report.errors.push(`chat photo files: ${error.message}`);
    }
    const { error } = await admin.from("messages").update({ image_path: null }).in("id", photos.map((p) => p.id));
    if (error) report.errors.push(`chat photo rows: ${error.message}`);
    else report.chatPhotos = photos.length;
  }

  // ---- voice messages older than their expiry (row stays as "expired") -----
  const { data: voices, error: vErr } = await admin
    .from("messages")
    .select("id, audio_path")
    .not("audio_path", "is", null)
    .lte("audio_expires_at", now)
    .limit(1000);
  if (vErr) report.errors.push(`voice messages: ${vErr.message}`);
  if (voices && voices.length > 0) {
    const paths = voices.map((v) => v.audio_path!).filter(Boolean);
    for (let i = 0; i < paths.length; i += 100) {
      const { error } = await admin.storage.from("chat").remove(paths.slice(i, i + 100));
      if (error) report.errors.push(`voice files: ${error.message}`);
    }
    const { error } = await admin.from("messages").update({ audio_path: null }).in("id", voices.map((v) => v.id));
    if (error) report.errors.push(`voice rows: ${error.message}`);
    else report.voiceMessages = voices.length;
  }

  // ---- view-once photos: past their expiry, or opened by everyone (0023) ---
  const { data: spent, error: oErr } = await admin.rpc("spent_view_once_photos");
  if (oErr) report.errors.push(`view-once photos: ${oErr.message}`);
  if (spent && spent.length > 0) {
    const paths = spent.map((s) => s.once_path);
    for (let i = 0; i < paths.length; i += 100) {
      const { error } = await admin.storage.from("once").remove(paths.slice(i, i + 100));
      if (error) report.errors.push(`view-once files: ${error.message}`);
    }
    const { error } = await admin.from("messages").update({ once_path: null }).in("id", spent.map((s) => s.id));
    if (error) report.errors.push(`view-once rows: ${error.message}`);
    else report.viewOncePhotos = spent.length;
  }

  // ---- groups everyone has left (once their media files are gone) ---------
  const { data: gone, error: gErr } = await admin.rpc("delete_empty_groups");
  if (gErr) report.errors.push(`empty groups: ${gErr.message}`);
  else report.emptyGroups = gone ?? 0;

  // ---- notes past their 24 hours (already hidden by RLS) ----------------
  const { error: noteErr } = await admin.from("notes").delete().lte("expires_at", now);
  if (noteErr) report.errors.push(`notes: ${noteErr.message}`);

  // ---- old notifications (keep the activity page light) -----------------
  const cutoff = new Date(Date.now() - 30 * 86_400_000).toISOString();
  const { error: nErr } = await admin.from("notifications").delete().lt("created_at", cutoff);
  if (nErr) report.errors.push(`notifications: ${nErr.message}`);

  // ---- app crash reports after 90 days (0026) ------------------------------
  const crashCutoff = new Date(Date.now() - 90 * 86_400_000).toISOString();
  const { error: crErr } = await admin.from("app_crashes").delete().lt("created_at", crashCutoff);
  if (crErr) report.errors.push(`crash reports: ${crErr.message}`);

  return NextResponse.json({ ok: report.errors.length === 0, ranAt: now, ...report });
}
