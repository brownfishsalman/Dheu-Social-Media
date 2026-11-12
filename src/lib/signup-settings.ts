import "server-only";
import { createAdminClient } from "@/lib/supabase/admin";

// Whether creating an account needs an invite code (0028). The admin flips it
// in Admin → Invites; it starts off, so anyone with the link can join. Only
// the server key can read it, so this file never reaches the browser.
export async function isInviteOnly(): Promise<boolean> {
  const { data, error } = await createAdminClient().from("app_settings").select("invite_only").maybeSingle();
  if (error) console.error("[signup] couldn't read app_settings:", error);
  return data?.invite_only ?? false;
}

export async function setInviteOnly(inviteOnly: boolean, adminId: string) {
  return createAdminClient()
    .from("app_settings")
    .update({ invite_only: inviteOnly, updated_at: new Date().toISOString(), updated_by: adminId })
    .eq("id", true);
}
