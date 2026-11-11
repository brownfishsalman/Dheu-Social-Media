import type { Metadata } from "next";
import { ChevronDown } from "lucide-react";
import { createAdminClient } from "@/lib/supabase/admin";
import { daysAgo, timeAgo } from "@/lib/time";

export const metadata: Metadata = { title: "Crashes" };

const DAYS = 30;

type Crash = {
  id: string;
  app_version: string;
  android: string;
  device: string;
  thread: string;
  exception: string;
  stack: string;
  happened_at: string;
  member: { username: string } | null;
};

type Group = { key: string; kind: string; frame: string; crashes: Crash[] };

// Same crash = same exception class thrown at the same place in the app's
// own code (or the first frame, when it came from a library).
function signature(c: Crash) {
  const kind = c.exception.split(":")[0].trim();
  const frames = c.stack.split("\n").map((l) => l.trim()).filter((l) => l.startsWith("at "));
  const frame = (frames.find((l) => l.startsWith("at com.salmansaadiq.dheu.")) ?? frames[0] ?? "").replace(/^at /, "");
  return { key: `${kind}|${frame}`, kind, frame };
}

// Admin → Crashes (0026): what the Android app sent in after crashing, read
// with the server key. Each report arrives the next time that member opens
// the app; reports are deleted after 90 days.
export default async function AdminCrashesPage() {
  const admin = createAdminClient();
  const since = daysAgo(DAYS);
  const { data, error } = await admin
    .from("app_crashes")
    .select("id, app_version, android, device, thread, exception, stack, happened_at, member:profiles!app_crashes_user_id_fkey(username)")
    .gte("happened_at", since)
    .order("happened_at", { ascending: false })
    .limit(500);

  if (error) {
    return (
      <p className="rounded-xl bg-danger-soft px-4 py-3 text-sm text-danger">
        Couldn&apos;t load crash reports. Has <code>supabase/migrations/0026_app_crashes.sql</code> been run? ({error.message})
      </p>
    );
  }

  const groups = new Map<string, Group>();
  for (const c of (data ?? []) as Crash[]) {
    const s = signature(c);
    const g = groups.get(s.key) ?? { key: s.key, kind: s.kind, frame: s.frame, crashes: [] };
    g.crashes.push(c);
    groups.set(s.key, g);
  }
  // Most frequent first; the list within each group is newest first already.
  const sorted = [...groups.values()].sort((a, b) => b.crashes.length - a.crashes.length);

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-muted">
        Crashes of the Android app in the last {DAYS} days, grouped by where they happened. A crash arrives the next time that
        member opens the app.
      </p>
      {sorted.length === 0 ? (
        <p className="card px-4 py-8 text-center text-sm text-ink-muted">No crashes in the last {DAYS} days.</p>
      ) : (
        <ul className="space-y-3">
          {sorted.map((g) => {
            const latest = g.crashes[0];
            const versions = [...new Set(g.crashes.map((c) => c.app_version))].join(", ");
            const members = new Set(g.crashes.map((c) => c.member?.username ?? "?")).size;
            return (
              <li key={g.key} className="card overflow-hidden">
                <details className="group">
                  <summary className="flex cursor-pointer list-none items-start gap-3 p-4 [&::-webkit-details-marker]:hidden">
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-mono text-sm font-semibold">{g.kind}</p>
                      <p className="truncate font-mono text-xs text-ink-muted">{g.frame || "no stack frame"}</p>
                      <p className="mt-1 text-xs text-ink-faint">
                        {versions} · {members} {members === 1 ? "member" : "members"} · last {timeAgo(latest.happened_at)}
                      </p>
                    </div>
                    <span className="shrink-0 rounded-full bg-danger-soft px-2 py-0.5 text-xs font-semibold text-danger">{g.crashes.length}×</span>
                    <ChevronDown className="size-4 shrink-0 text-ink-faint transition group-open:rotate-180" />
                  </summary>
                  <div className="border-t border-line px-4 py-3 text-xs">
                    <p className="mb-2 text-ink-muted">
                      Latest: {latest.device || "unknown phone"} · Android {latest.android || "?"} · {latest.thread || "?"} thread
                      {latest.member ? ` · @${latest.member.username}` : ""}
                    </p>
                    <pre className="max-h-96 overflow-auto rounded-lg bg-surface-2 p-3 font-mono text-[11px] leading-relaxed whitespace-pre">
                      {latest.stack}
                    </pre>
                  </div>
                </details>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
