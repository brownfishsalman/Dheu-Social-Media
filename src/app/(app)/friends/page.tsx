import type { Metadata } from "next";
import Link from "next/link";
import { Check, Flame, Pencil, Users } from "lucide-react";
import { requireProfile } from "@/lib/data/session";
import { getDeck, getMySwipeProfile, getSwipeMatches } from "@/lib/data/friends";
import { PageHeader } from "@/components/ui/PageHeader";
import { SwipeDeck } from "@/components/friends/SwipeDeck";
import { ResumeProfile } from "@/components/friends/ResumeProfile";

export const metadata: Metadata = { title: "Find friends" };

// The flame tab (0027): an introduction until the member makes a swipe
// profile, then the deck.
export default async function FriendsPage() {
  const me = await requireProfile();
  const profile = await getMySwipeProfile(me.id);

  if (!profile) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title="Find friends" />
        <div className="px-4 py-6">
          <div className="card px-6 py-10 text-center">
            <Flame className="mx-auto size-12 text-ink" strokeWidth={1.5} />
            <h2 className="mt-4 font-display text-2xl font-semibold">Meet new people on Dheu</h2>
            <p className="mx-auto mt-2 max-w-sm text-sm text-ink-muted">
              Make a swipe profile, then go through other people&apos;s. Swipe right if you&apos;d like to be friends, left to
              pass. If you both swipe right, it&apos;s a match and a chat opens in Messages.
            </p>
            <ul className="mx-auto mt-6 max-w-sm space-y-2 text-left text-sm">
              {[
                "Only people with a swipe profile can see yours.",
                "Nobody knows you liked them unless they like you back.",
                "Pause or delete your profile any time.",
              ].map((line) => (
                <li key={line} className="flex gap-2">
                  <Check className="mt-0.5 size-4 shrink-0 text-brand" />
                  {line}
                </li>
              ))}
            </ul>
            <Link href="/friends/profile" className="btn-primary mt-8">
              Make my swipe profile
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const actions = (
    <div className="flex items-center gap-1">
      <Link href="/friends/matches" aria-label="Your matches" title="Your matches" className="rounded-full p-1.5 hover:bg-surface-2">
        <Users className="size-6" />
      </Link>
      <Link
        href="/friends/profile"
        aria-label="Edit your swipe profile"
        title="Edit your swipe profile"
        className="rounded-full p-1.5 hover:bg-surface-2"
      >
        <Pencil className="size-5" />
      </Link>
    </div>
  );

  if (!profile.active) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title="Find friends" action={actions} />
        <div className="px-4 py-6">
          <div className="card px-6 py-10 text-center">
            <Flame className="mx-auto size-10 text-ink-muted" strokeWidth={1.5} />
            <p className="mt-3 font-display text-lg font-semibold">Your profile is paused</p>
            <p className="mx-auto mt-1 mb-6 max-w-xs text-sm text-ink-muted">
              Nobody sees it and you can&apos;t swipe until you resume. Your matches and chats are still there.
            </p>
            <ResumeProfile userId={me.id} />
          </div>
        </div>
      </div>
    );
  }

  const [cards, matches] = await Promise.all([getDeck(), getSwipeMatches()]);
  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Find friends" action={actions} />
      <SwipeDeck
        initialCards={cards}
        unseenMatches={matches.filter((m) => !m.seen)}
        me={{ name: me.full_name, photo: profile.photos[0] ?? null }}
      />
    </div>
  );
}
