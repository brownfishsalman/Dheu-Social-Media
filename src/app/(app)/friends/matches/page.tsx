import type { Metadata } from "next";
import { requireProfile } from "@/lib/data/session";
import { getSwipeMatches } from "@/lib/data/friends";
import { PageHeader } from "@/components/ui/PageHeader";
import { MatchList } from "@/components/friends/MatchList";

export const metadata: Metadata = { title: "Your matches" };

export default async function MatchesPage() {
  await requireProfile();
  const matches = await getSwipeMatches();

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Your matches" back="/friends" />
      <div className="px-4 py-3">
        <MatchList initial={matches} />
      </div>
    </div>
  );
}
