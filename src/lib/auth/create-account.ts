import "server-only";
import { z } from "zod";
import { createAdminClient } from "@/lib/supabase/admin";
import { isInviteOnly } from "@/lib/signup-settings";

const USERNAME_RE = /^[a-z0-9_.]{3,30}$/;

// The app shows the invite field when it gets this back (older versions just show the message).
export const INVITE_REQUIRED = "An invite code is required to join.";

export const signupSchema = z.object({
  fullName: z.string().trim().min(1, "Enter your name").max(60, "Name is too long"),
  username: z
    .string()
    .trim()
    .toLowerCase()
    .regex(USERNAME_RE, "3–30 characters: letters, numbers, dots or underscores"),
  email: z.email("Enter a valid email address"),
  password: z.string().min(8, "Use at least 8 characters").max(72, "Password is too long"),
  // Only needed while sign-up is invite-only (0028); otherwise ignored.
  inviteCode: z.string().trim().toUpperCase().default(""),
  accountType: z.enum(["public", "private"], { error: "Choose a public or private account" }),
});

export type SignupInput = z.infer<typeof signupSchema>;
export type CreateAccountResult = { ok: true } | { ok: false; error: string };

// Account creation with the secret key, shared by the website's sign-up form
// and the Android app (POST /api/auth/signup). Open to anyone, unless the
// admin has made sign-up invite-only (0028). It does not sign the person in;
// each caller does that its own way.
export async function createAccount(input: SignupInput): Promise<CreateAccountResult> {
  const { fullName, username, email, password, inviteCode, accountType } = input;
  const admin = createAdminClient();

  // The very first account on a fresh platform needs no invite (it becomes admin).
  const { count, error: countError } = await admin.from("profiles").select("id", { count: "exact", head: true });
  if (countError) {
    console.error("[signup] profile count failed:", countError);
    return { ok: false, error: `Couldn't reach the database (${countError.message}). Please try again.` };
  }
  const isFirstUser = (count ?? 0) === 0;
  const needsInvite = !isFirstUser && (await isInviteOnly());

  if (needsInvite && !inviteCode) return { ok: false, error: INVITE_REQUIRED };

  const { data: taken } = await admin.from("profiles").select("id").eq("username", username).maybeSingle();
  if (taken) return { ok: false, error: "That username is already taken." };

  // While sign-up is open, a code someone brings along is simply not used.
  let inviteCodeId: string | null = null;
  if (needsInvite) {
    const { data, error } = await admin.rpc("claim_invite_code", { p_code: inviteCode });
    if (error || !data) {
      const reason = error?.message ?? "";
      const msg = reason.includes("INVITE_USED_UP")
        ? "That invite code has already been used."
        : reason.includes("INVITE_EXPIRED")
          ? "That invite code has expired."
          : reason.includes("INVITE_DISABLED")
            ? "That invite code is no longer active."
            : "That invite code isn't valid.";
      return { ok: false, error: msg };
    }
    inviteCodeId = data;
  }

  const { error: createError } = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    // is_private: read by handle_new_user (0016)
    user_metadata: { username, full_name: fullName, invite_code_id: inviteCodeId, is_private: accountType === "private" },
  });

  if (createError) {
    console.error("[signup] createUser failed:", createError);
    if (inviteCodeId) await admin.rpc("release_invite_code", { p_id: inviteCodeId });
    const msg = /already|exists|registered/i.test(createError.message)
      ? "An account with this email already exists."
      : /username|profiles_username/i.test(createError.message)
        ? "That username is already taken."
        : "Could not create your account. Please try again.";
    return { ok: false, error: msg };
  }

  return { ok: true };
}
