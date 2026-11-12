import type { Metadata } from "next";
import { requireProfile } from "@/lib/data/session";
import { getMySwipeProfile } from "@/lib/data/friends";
import { PageHeader } from "@/components/ui/PageHeader";
import { SwipeProfileForm } from "@/components/friends/SwipeProfileForm";

export const metadata: Metadata = { title: "Your swipe profile" };

export default async function SwipeProfilePage() {
  const me = await requireProfile();
  const profile = await getMySwipeProfile(me.id);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title={profile ? "Your swipe profile" : "Make your swipe profile"} back="/friends" />
      <div className="px-4 py-5">
        <SwipeProfileForm userId={me.id} name={me.full_name} initial={profile} />
      </div>
    </div>
  );
}
