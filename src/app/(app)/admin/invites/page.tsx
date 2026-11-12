import type { Metadata } from "next";
import { createAdminClient } from "@/lib/supabase/admin";
import { InviteManager } from "@/components/admin/InviteManager";
import { SignupSwitch } from "@/components/admin/SignupSwitch";
import { getSiteUrl } from "@/lib/site-url";
import { isInviteOnly } from "@/lib/signup-settings";

export const metadata: Metadata = { title: "Invites" };

export default async function AdminInvitesPage() {
  const admin = createAdminClient();
  const [siteUrl, inviteOnly, { data: invites }] = await Promise.all([
    getSiteUrl(),
    isInviteOnly(),
    admin
      .from("invite_codes")
      .select("*, creator:profiles!invite_codes_created_by_fkey(username)")
      .order("created_at", { ascending: false }),
  ]);

  return (
    <div className="space-y-6">
      <SignupSwitch inviteOnly={inviteOnly} />
      <InviteManager
        invites={(invites ?? []).map((i) => ({ ...i, creator: i.creator?.username ?? null }))}
        siteUrl={siteUrl}
      />
    </div>
  );
}
