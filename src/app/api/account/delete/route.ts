import { NextResponse } from "next/server";
import { userIdFromBearer } from "@/lib/supabase/bearer";
import { deleteOwnAccount } from "@/lib/account";

// The Android app's Settings → Delete account. Authenticated with the
// member's access token (Authorization: Bearer); the password is checked
// again before anything is deleted.
export async function POST(request: Request) {
  const userId = await userIdFromBearer(request);
  if (!userId) return NextResponse.json({ ok: false, error: "Not signed in." }, { status: 401 });
  let password = "";
  try {
    const body = (await request.json()) as { password?: unknown };
    password = typeof body.password === "string" ? body.password : "";
  } catch {}
  const result = await deleteOwnAccount(userId, password);
  return NextResponse.json(result, { status: result.ok ? 200 : 400 });
}
