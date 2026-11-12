"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Ellipsis, Flag, Flame, MessageCircle, User, UserMinus } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { Avatar } from "@/components/ui/Avatar";
import { Sheet } from "@/components/ui/Sheet";
import { ReportSheet } from "@/components/report/ReportSheet";
import { timeAgo } from "@/lib/time";
import type { SwipeMatch } from "@/lib/friends";

// Everyone this member matched with, newest first: open the chat, see their
// profile, unmatch, or report them.
export function MatchList({ initial }: { initial: SwipeMatch[] }) {
  const [matches, setMatches] = useState(initial);
  const [menuFor, setMenuFor] = useState<SwipeMatch | null>(null);
  const [reporting, setReporting] = useState<SwipeMatch | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Seen here, so the deck won't show their "It's a match" screen again.
  const unseen = initial.some((m) => !m.seen);
  useEffect(() => {
    if (unseen) void createClient().rpc("mark_swipe_matches_seen");
  }, [unseen]);

  async function unmatch(m: SwipeMatch) {
    if (!confirm(`Unmatch ${m.full_name}? You won't see each other in Find friends again. Your chat stays in Messages.`)) return;
    setMenuFor(null);
    setError(null);
    const { error: err } = await createClient().rpc("swipe_unmatch", { p_other: m.user_id });
    if (err) return setError("Couldn't unmatch. Check your connection and try again.");
    setMatches((list) => list.filter((x) => x.user_id !== m.user_id));
  }

  if (matches.length === 0) {
    return (
      <div className="card px-6 py-12 text-center">
        <Flame className="mx-auto size-10 text-ink-muted" strokeWidth={1.5} />
        <p className="mt-3 font-display text-lg font-semibold">No matches yet</p>
        <p className="mx-auto mt-1 max-w-xs text-sm text-ink-muted">
          When you and someone both swipe right, they show up here and a chat opens in Messages.
        </p>
        <Link href="/friends" className="btn-primary mt-5">
          Keep swiping
        </Link>
      </div>
    );
  }

  return (
    <>
      {error && (
        <p role="alert" className="mb-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}
      <ul className="divide-y divide-line">
        {matches.map((m) => (
          <li key={m.user_id} className="flex items-center gap-3 py-3">
            <Link href={`/u/${m.username}`} className="shrink-0">
              <Avatar path={m.avatar_path} name={m.full_name} size={48} />
            </Link>
            <div className="min-w-0 flex-1">
              <Link href={`/u/${m.username}`} className="flex items-center gap-2 font-semibold">
                <span className="truncate">{m.full_name}</span>
                {!m.seen && (
                  <span className="shrink-0 rounded-full bg-brand-soft px-2 py-0.5 text-[11px] font-semibold text-brand-strong">New</span>
                )}
              </Link>
              <p className="truncate text-sm text-ink-muted">
                @{m.username} · matched {timeAgo(m.matched_at)}
              </p>
            </div>
            <Link
              href={m.conversation_id ? `/messages/${m.conversation_id}` : `/messages/with/${m.user_id}`}
              className="btn-primary shrink-0 px-3 py-2 text-sm"
            >
              <MessageCircle className="size-4" /> Message
            </Link>
            <button
              type="button"
              onClick={() => setMenuFor(m)}
              aria-label={`More for ${m.full_name}`}
              className="shrink-0 rounded-full p-1.5 text-ink-muted hover:bg-surface-2 hover:text-ink"
            >
              <Ellipsis className="size-5" />
            </button>
          </li>
        ))}
      </ul>

      {menuFor && (
        <Sheet title={menuFor.full_name} onClose={() => setMenuFor(null)}>
          <div className="flex flex-col gap-1">
            <Link href={`/u/${menuFor.username}`} className="btn-ghost justify-start">
              <User className="size-5" /> View profile
            </Link>
            <button type="button" onClick={() => unmatch(menuFor)} className="btn-ghost justify-start">
              <UserMinus className="size-5" /> Unmatch
            </button>
            <button
              type="button"
              onClick={() => {
                setReporting(menuFor);
                setMenuFor(null);
              }}
              className="btn-ghost justify-start text-danger hover:text-danger"
            >
              <Flag className="size-5" /> Report
            </button>
          </div>
        </Sheet>
      )}
      {reporting && <ReportSheet target={{ type: "profile", id: reporting.user_id }} onClose={() => setReporting(null)} />}
    </>
  );
}
