import type { Metadata } from "next";
import { requireProfile } from "@/lib/data/session";
import { getCloseFriendChoices } from "@/lib/data/profiles";
import { PageHeader } from "@/components/ui/PageHeader";
import { CloseFriendsList } from "@/components/settings/CloseFriendsList";

export const metadata: Metadata = { title: "Close friends" };

export default async function CloseFriendsPage() {
  const me = await requireProfile();
  const { people, chosen } = await getCloseFriendChoices(me.id);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Close friends" back="/settings" />
      <div className="px-4 py-5">
        <CloseFriendsList meId={me.id} people={people} chosen={chosen} />
      </div>
    </div>
  );
}
