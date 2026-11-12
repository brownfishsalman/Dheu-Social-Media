import type { Metadata } from "next";
import Link from "next/link";
import { ChevronRight, LogOut, Shield, Star } from "lucide-react";
import { requireProfile } from "@/lib/data/session";
import { getBlockedProfiles } from "@/lib/data/profiles";
import { getSiteUrl } from "@/lib/site-url";
import { isInviteOnly } from "@/lib/signup-settings";
import { createClient } from "@/lib/supabase/server";
import { MEMBER_INVITE_LIMIT, POST_LIFETIME_LABEL } from "@/lib/constants";
import { ShareWithFriends } from "@/components/settings/ShareWithFriends";
import { BlockedList } from "@/components/settings/BlockedList";
import { PushSettings } from "@/components/push/PushSettings";
import { PageHeader } from "@/components/ui/PageHeader";
import { EditProfileForm } from "@/components/settings/EditProfileForm";
import { ThemePicker, ChangePasswordForm } from "@/components/settings/AccountSettings";
import { PrivacySettings } from "@/components/settings/AccountPrivacy";
import { ReadReceiptsSetting } from "@/components/settings/ReadReceipts";
import { DeleteAccount } from "@/components/settings/DeleteAccount";

export const metadata: Metadata = { title: "Settings" };

export default async function SettingsPage() {
  const profile = await requireProfile();
  const supabase = await createClient();
  const [siteUrl, blocked, { data: myCodes }, { count: closeFriends }, inviteOnly] = await Promise.all([
    getSiteUrl(),
    getBlockedProfiles(profile.id),
    supabase.from("invite_codes").select("*").eq("created_by", profile.id).order("created_at", { ascending: false }),
    supabase.from("close_friends").select("friend_id", { count: "exact", head: true }).eq("owner_id", profile.id),
    isInviteOnly(),
  ]);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Settings" back={`/u/${profile.username}`} />

      <div className="space-y-6 px-4 py-6">
        <section className="card p-5">
          <h2 className="mb-4 font-display text-lg font-semibold">Profile</h2>
          <EditProfileForm profile={profile} />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Account privacy</h2>
          <p className="mb-3 text-sm text-ink-muted">Who can see your posts, stories and followers.</p>
          <PrivacySettings isPrivate={profile.is_private} />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Close friends</h2>
          <p className="mb-3 text-sm text-ink-muted">The people who see the stories you share to close friends, with a green ring.</p>
          <Link href="/settings/close-friends" className="btn-secondary">
            <Star className="size-4" />
            {closeFriends ? `Edit your list (${closeFriends})` : "Choose close friends"}
            <ChevronRight className="size-4" />
          </Link>
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Read receipts</h2>
          <p className="mb-3 text-sm text-ink-muted">Whether people see &ldquo;Seen&rdquo; when you&apos;ve read their messages.</p>
          <ReadReceiptsSetting userId={profile.id} on={profile.read_receipts} />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Notifications</h2>
          <PushSettings userId={profile.id} />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Share with friends</h2>
          <ShareWithFriends codes={myCodes ?? []} siteUrl={siteUrl} limit={MEMBER_INVITE_LIMIT} inviteOnly={inviteOnly} />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Appearance</h2>
          <p className="mb-3 text-sm text-ink-muted">Choose how Dheu looks on this device.</p>
          <ThemePicker />
        </section>

        <section className="card p-5">
          <h2 className="mb-4 font-display text-lg font-semibold">Password</h2>
          <ChangePasswordForm />
        </section>

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Blocked accounts</h2>
          <p className="mb-3 text-sm text-ink-muted">Blocked members can&apos;t see your photos or message you, and you won&apos;t see theirs.</p>
          <BlockedList people={blocked} meId={profile.id} />
        </section>

        {profile.is_admin && (
          <section className="card p-5">
            <h2 className="mb-1 font-display text-lg font-semibold">Admin</h2>
            <p className="mb-3 text-sm text-ink-muted">Invite codes, members and moderation.</p>
            <Link href="/admin" className="btn-secondary">
              <Shield className="size-4" /> Open admin panel
            </Link>
          </section>
        )}

        <section className="card p-5">
          <h2 className="mb-1 font-display text-lg font-semibold">Delete account</h2>
          {profile.is_admin ? (
            <p className="text-sm text-ink-muted">The admin account can&apos;t be deleted.</p>
          ) : (
            <>
              <p className="mb-3 text-sm text-ink-muted">Removes your account and everything you&apos;ve shared, for good.</p>
              <DeleteAccount />
            </>
          )}
        </section>

        <form action="/auth/signout" method="post" className="pt-2">
          <button type="submit" className="btn-danger w-full">
            <LogOut className="size-4" /> Log out
          </button>
        </form>

        <p className="text-center text-xs text-ink-faint">
          Dheu · posts expire after {POST_LIFETIME_LABEL} · stories after 24 hours
        </p>
      </div>
    </div>
  );
}
