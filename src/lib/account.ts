import "server-only";
import { createClient } from "@supabase/supabase-js";
import { createAdminClient } from "@/lib/supabase/admin";

// Every bucket keeps a member's files in a folder named after their id.
const BUCKETS = ["avatars", "posts", "stories", "chat", "once", "swipe"] as const;

// Removes all of a member's files, a thousand at a time.
export async function removeUserFiles(userId: string) {
  const admin = createAdminClient();
  for (const bucket of BUCKETS) {
    for (;;) {
      const { data: files } = await admin.storage.from(bucket).list(userId, { limit: 1000 });
      if (!files || files.length === 0) break;
      const { error } = await admin.storage.from(bucket).remove(files.map((f) => `${userId}/${f.name}`));
      if (error || files.length < 1000) break;
    }
  }
}

export type DeleteAccountResult = { ok: true } | { ok: false; error: string };

// A member deletes their own account (Settings, on the website or in the
// app): password first, then their files, then the auth user. Deleting the
// auth user removes their profile, and with it their posts, stories,
// comments, likes, follows, messages and one-to-one chats (all cascade).
// The admin account can't go this way: the community needs its admin.
export async function deleteOwnAccount(userId: string, password: string): Promise<DeleteAccountResult> {
  if (!password) return { ok: false, error: "Enter your password." };
  const admin = createAdminClient();
  const [{ data: profile }, { data: auth }] = await Promise.all([
    admin.from("profiles").select("is_admin").eq("id", userId).maybeSingle(),
    admin.auth.admin.getUserById(userId),
  ]);
  const email = auth?.user?.email;
  if (!profile || !email) return { ok: false, error: "Couldn't find your account." };
  if (profile.is_admin) return { ok: false, error: "The admin account can't be deleted." };

  // Proves it's them: a throwaway sign-in that keeps no session.
  const check = createClient(process.env.NEXT_PUBLIC_SUPABASE_URL!, process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { error: signInError } = await check.auth.signInWithPassword({ email, password });
  if (signInError) {
    return {
      ok: false,
      error: signInError.status === 429 ? "Too many tries. Wait a minute, then try again." : "That password isn't right.",
    };
  }
  await check.auth.signOut({ scope: "local" }).catch(() => {});

  await removeUserFiles(userId);
  const { error } = await admin.auth.admin.deleteUser(userId);
  if (error) return { ok: false, error: "Couldn't delete your account. Please try again." };
  return { ok: true };
}
