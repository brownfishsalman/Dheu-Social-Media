import { NextResponse, type NextRequest } from "next/server";
import { createClient } from "@/lib/supabase/server";
import { isBucket } from "@/lib/storage";

// Photos and voice messages for the signed-in member (see mediaUrl in
// src/lib/storage.ts). Storage hands out a short-lived signed link only if
// the photo access rules (0025) let this member see the file; otherwise 404.
// The browser keeps the redirect for most of the link's life, so a photo
// seen again soon doesn't come back here.
const LINK_SECONDS = 60 * 60;

export async function GET(_req: NextRequest, ctx: RouteContext<"/media/[bucket]/[...path]">) {
  const { bucket, path } = await ctx.params;
  if (!isBucket(bucket) || path.length === 0) return notFound();

  const supabase = await createClient();
  const { data, error } = await supabase.storage.from(bucket).createSignedUrl(path.join("/"), LINK_SECONDS);
  if (error || !data?.signedUrl) return notFound();

  return NextResponse.redirect(data.signedUrl, {
    status: 302,
    headers: { "Cache-Control": `private, max-age=${LINK_SECONDS - 5 * 60}` },
  });
}

function notFound() {
  return new NextResponse(null, { status: 404, headers: { "Cache-Control": "private, no-store" } });
}
