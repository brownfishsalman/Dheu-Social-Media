"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { getUserId } from "@/lib/data/session";
import { createClient } from "@/lib/supabase/server";
import { createMemberInviteCode, type InviteResult } from "@/lib/invites";
import { deleteOwnAccount } from "@/lib/account";

// A member creates a single-use invite code for a friend.
export async function createMyInviteCode(): Promise<InviteResult> {
  const userId = await getUserId();
  if (!userId) return { ok: false, error: "Not signed in." };
  const result = await createMemberInviteCode(userId);
  if (result.ok) revalidatePath("/settings");
  return result;
}

// Settings → Delete account. On success the session cookies are cleared and
// the member lands on the login page; otherwise the reason comes back.
export async function deleteMyAccount(password: string): Promise<{ ok: false; error: string }> {
  const userId = await getUserId();
  if (!userId) return { ok: false, error: "Not signed in." };
  const result = await deleteOwnAccount(userId, password);
  if (!result.ok) return result;
  const supabase = await createClient();
  await supabase.auth.signOut({ scope: "local" }).catch(() => {});
  redirect("/login?deleted=1");
}
