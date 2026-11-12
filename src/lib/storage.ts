// Where the website loads Storage files from. Works on server and client.
//
// /media/<bucket>/<path> answers with a short-lived signed link made with the
// member's own session (src/app/media/[bucket]/[...path]/route.ts), so the
// photo access rules in 0025 decide who sees what. The buckets are still
// public for older app versions; once they're switched to private (see
// docs/OWNER-GUIDE.md) those rules are the only way in.

// "swipe" (Find friends photos, 0027) was private from the start.
export type Bucket = "avatars" | "posts" | "stories" | "chat" | "swipe";

const BUCKETS: readonly string[] = ["avatars", "posts", "stories", "chat", "swipe"] satisfies Bucket[];

export function isBucket(name: string): name is Bucket {
  return BUCKETS.includes(name);
}

export function mediaUrl(bucket: Bucket, path: string | null | undefined): string | null {
  if (!path) return null;
  return `/media/${bucket}/${path}`;
}

export function avatarUrl(path: string | null | undefined): string | null {
  return mediaUrl("avatars", path);
}

// Every "posts" bucket file behind these images: the photo plus its thumbnail, if it has one.
export function postImageFiles(images: { path: string; thumb_path: string | null }[]): string[] {
  return images.flatMap((i) => (i.thumb_path ? [i.path, i.thumb_path] : [i.path]));
}
