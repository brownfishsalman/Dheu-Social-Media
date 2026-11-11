import "server-only";
import { createAdminClient } from "@/lib/supabase/admin";
import type { Bucket } from "@/lib/storage";

// Signed links made with the server key, which the photo access rules (0025)
// don't apply to. For the admin's pages, which must show what RLS hides even
// from the admin (close friends stories, reported content), and for push
// notification icons, which are fetched without a session.
export async function adminSignedUrls(
  bucket: Bucket,
  paths: (string | null | undefined)[],
  seconds = 60 * 60,
): Promise<Map<string, string>> {
  const unique = [...new Set(paths.filter((p): p is string => Boolean(p)))];
  const urls = new Map<string, string>();
  if (unique.length === 0) return urls;
  const { data } = await createAdminClient().storage.from(bucket).createSignedUrls(unique, seconds);
  for (const d of data ?? []) if (d.path && d.signedUrl) urls.set(d.path, d.signedUrl);
  return urls;
}
