"use client";

import Link from "next/link";
import { useState, useTransition } from "react";
import { Ban, Check, ExternalLink, Loader2, Star, Trash2, X } from "lucide-react";
import { timeAgo } from "@/lib/time";
import { TARGET_NOUNS, reasonLabel, type ReportTargetType } from "@/lib/reports";
import { actOnReports } from "@/app/(app)/admin/actions";

// Everyone's open reports about one thing (built in admin/reports/page.tsx).
// image: a link signed with the server key; gone: deleted since it was reported.
export type ReportGroup = {
  key: string;
  type: ReportTargetType;
  targetId: string | null;
  targetUser: { id: string | null; username: string; fullName: string; banned: boolean } | null;
  gone: boolean;
  image: string | null;
  text: string | null;
  closeFriends: boolean;
  href: string | null;
  reports: { id: string; reason: string; details: string; reporter: string | null; createdAt: string }[];
};

export type ClosedReport = {
  id: string;
  type: ReportTargetType;
  reason: string;
  status: string;
  resolvedAt: string | null;
  target: string | null;
  resolver: string | null;
};

type Action = "remove" | "suspend" | "resolve" | "dismiss";

export function ReportQueue({ groups, closed }: { groups: ReportGroup[]; closed: ClosedReport[] }) {
  const [busy, setBusy] = useState<string | null>(null); // `${key}:${action}`
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [, startTransition] = useTransition();

  function act(g: ReportGroup, action: Action) {
    const noun = TARGET_NOUNS[g.type];
    const who = g.targetUser ? `@${g.targetUser.username}` : "this member";
    const question =
      action === "remove"
        ? `Remove this ${noun} for everyone? This can't be undone.`
        : action === "suspend"
          ? `Suspend ${who}? They can't log in until you reinstate them in Members.`
          : null;
    if (question && !confirm(question)) return;
    setBusy(`${g.key}:${action}`);
    setNotice(null);
    startTransition(async () => {
      const r = await actOnReports({
        reportIds: g.reports.map((x) => x.id),
        action,
        targetType: g.type,
        targetId: g.targetId,
        targetUserId: g.targetUser?.id ?? null,
      });
      setNotice(r.ok ? { ok: true, text: r.message ?? "Done." } : { ok: false, text: r.error });
      setBusy(null);
    });
  }

  const spin = (g: ReportGroup, action: Action) => busy === `${g.key}:${action}`;

  return (
    <div className="space-y-6">
      {notice && (
        <p
          role="status"
          className={`rounded-xl px-3.5 py-2.5 text-sm ${notice.ok ? "bg-brand-soft text-brand-strong" : "bg-danger-soft text-danger"}`}
        >
          {notice.text}
        </p>
      )}

      <section className="space-y-3">
        <h2 className="font-display text-lg font-semibold">Open reports ({groups.reduce((n, g) => n + g.reports.length, 0)})</h2>
        {groups.length === 0 ? (
          <p className="card px-4 py-8 text-center text-sm text-ink-muted">Nothing to review. Members can report a post, story, comment or account from its menu.</p>
        ) : (
          <ul className="space-y-3">
            {groups.map((g) => {
              const noun = TARGET_NOUNS[g.type];
              return (
                <li key={g.key} className="card overflow-hidden">
                  <div className="flex gap-3 p-4">
                    {g.image && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={g.image}
                        alt=""
                        className={`shrink-0 rounded-lg bg-surface-2 object-cover ${g.type === "story" ? "h-24 w-14" : "size-16"}`}
                      />
                    )}
                    <div className="min-w-0 flex-1 text-sm">
                      <p>
                        <span className="font-semibold capitalize">{noun}</span>
                        {g.targetUser && (
                          <>
                            {g.type === "profile" ? " " : " by "}
                            <Link href={`/u/${g.targetUser.username}`} className="font-semibold hover:underline">
                              @{g.targetUser.username}
                            </Link>
                          </>
                        )}
                        {g.closeFriends && (
                          <span className="bg-close ml-1.5 inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 align-middle text-[11px] font-semibold text-white">
                            <Star className="size-3 fill-current" /> Close friends
                          </span>
                        )}
                        {g.targetUser?.banned && (
                          <span className="ml-1.5 rounded-full bg-danger-soft px-1.5 py-0.5 text-[11px] font-semibold text-danger">Suspended</span>
                        )}
                      </p>
                      {g.gone ? (
                        <p className="mt-0.5 text-ink-muted italic">Already removed.</p>
                      ) : (
                        g.text && <p className="mt-0.5 line-clamp-3 break-words whitespace-pre-wrap text-ink-muted">{g.text}</p>
                      )}
                      {g.href && !g.gone && (
                        <Link href={g.href} className="mt-1 inline-flex items-center gap-1 text-xs font-semibold text-brand hover:underline">
                          Open <ExternalLink className="size-3" />
                        </Link>
                      )}
                    </div>
                    <span className="h-fit shrink-0 rounded-full bg-danger-soft px-2 py-0.5 text-xs font-semibold text-danger">
                      {g.reports.length} {g.reports.length === 1 ? "report" : "reports"}
                    </span>
                  </div>

                  <ul className="space-y-2 border-t border-line px-4 py-3 text-sm">
                    {g.reports.map((r) => (
                      <li key={r.id}>
                        <span className="font-medium">{reasonLabel(r.reason)}</span>
                        <span className="text-ink-faint">
                          {" "}
                          · {r.reporter ? `@${r.reporter}` : "a former member"} · {timeAgo(r.createdAt)}
                        </span>
                        {r.details && <p className="mt-0.5 break-words whitespace-pre-wrap text-ink-muted">&ldquo;{r.details}&rdquo;</p>}
                      </li>
                    ))}
                  </ul>

                  <div className="flex flex-wrap gap-2 border-t border-line px-4 py-3">
                    {g.type !== "profile" && !g.gone && (
                      <button type="button" disabled={busy !== null} onClick={() => act(g, "remove")} className="btn-danger px-3 py-2 text-sm">
                        {spin(g, "remove") ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
                        Remove {noun}
                      </button>
                    )}
                    {g.targetUser?.id && !g.targetUser.banned && (
                      <button type="button" disabled={busy !== null} onClick={() => act(g, "suspend")} className="btn-danger px-3 py-2 text-sm">
                        {spin(g, "suspend") ? <Loader2 className="size-4 animate-spin" /> : <Ban className="size-4" />}
                        Suspend @{g.targetUser.username}
                      </button>
                    )}
                    <button type="button" disabled={busy !== null} onClick={() => act(g, "resolve")} className="btn-secondary px-3 py-2 text-sm">
                      {spin(g, "resolve") ? <Loader2 className="size-4 animate-spin" /> : <Check className="size-4" />}
                      Mark handled
                    </button>
                    <button type="button" disabled={busy !== null} onClick={() => act(g, "dismiss")} className="btn-ghost px-3 py-2 text-sm">
                      {spin(g, "dismiss") ? <Loader2 className="size-4 animate-spin" /> : <X className="size-4" />}
                      Dismiss
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {closed.length > 0 && (
        <section className="card overflow-hidden">
          <div className="border-b border-line px-4 py-3">
            <h2 className="font-display text-lg font-semibold">Recently closed</h2>
          </div>
          <ul className="divide-y divide-line">
            {closed.map((r) => (
              <li key={r.id} className="flex items-center gap-2 px-4 py-2.5 text-sm">
                <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${r.status === "dismissed" ? "bg-surface-2 text-ink-muted" : "bg-brand-soft text-brand-strong"}`}>
                  {r.status === "dismissed" ? "Dismissed" : "Handled"}
                </span>
                <span className="min-w-0 flex-1 truncate">
                  <span className="capitalize">{TARGET_NOUNS[r.type]}</span>
                  {r.target && ` by @${r.target}`} · {reasonLabel(r.reason)}
                </span>
                <span className="shrink-0 text-xs text-ink-faint">
                  {r.resolver ? `@${r.resolver}` : ""}
                  {r.resolvedAt ? ` · ${timeAgo(r.resolvedAt)}` : ""}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
