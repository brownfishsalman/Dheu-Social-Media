import { NextResponse } from "next/server";
import { createAccount, signupSchema } from "@/lib/auth/create-account";
import { isInviteOnly } from "@/lib/signup-settings";

// Sign-up for the Android app, which can't hold the secret key account
// creation needs. Same rules as the website's form; the app signs in itself
// afterwards with the email and password it just sent.
export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid request." }, { status: 400 });
  }

  const parsed = signupSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ ok: false, error: parsed.error.issues[0].message }, { status: 400 });
  }

  const result = await createAccount(parsed.data);
  return NextResponse.json(result, { status: result.ok ? 201 : 400 });
}

// Whether sign-up needs an invite code right now (0028), so the app knows
// to ask for one and what "Share with friends" should share.
export async function GET() {
  return NextResponse.json({ inviteOnly: await isInviteOnly() }, { headers: { "Cache-Control": "no-store" } });
}
