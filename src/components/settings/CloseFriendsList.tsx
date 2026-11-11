"use client";

import { useMemo, useState } from "react";
import { Search, Star } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { Avatar } from "@/components/ui/Avatar";
import type { ProfileListItem } from "@/lib/data/profiles";

// Settings → Close friends (0024): star the people who see your close friends
// stories. Only you can see this list; nobody is told they're on it.
export function CloseFriendsList({ meId, people, chosen }: { meId: string; people: ProfileListItem[]; chosen: string[] }) {
  const [on, setOn] = useState(() => new Set(chosen));
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);

  // Close friends first (as the page opened), then everyone else, each
  // alphabetical. The order stays put while you tap, so rows don't jump.
  const ordered = useMemo(() => {
    const first = new Set(chosen);
    return [...people].sort((a, b) => Number(first.has(b.id)) - Number(first.has(a.id)));
  }, [people, chosen]);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? ordered.filter((p) => p.username.includes(q) || p.full_name.toLowerCase().includes(q)) : ordered;
  }, [ordered, query]);

  async function toggle(p: ProfileListItem) {
    const adding = !on.has(p.id);
    const flip = (add: boolean) =>
      setOn((s) => {
        const next = new Set(s);
        if (add) next.add(p.id);
        else next.delete(p.id);
        return next;
      });
    flip(adding);
    setError(null);
    const supabase = createClient();
    const { error: err } = adding
      ? await supabase.from("close_friends").insert({ owner_id: meId, friend_id: p.id })
      : await supabase.from("close_friends").delete().match({ owner_id: meId, friend_id: p.id });
    if (err && !(adding && err.code === "23505")) {
      flip(!adding);
      setError("Couldn't save that. Please try again.");
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-muted">
        {on.size === 0 ? "Nobody yet." : `${on.size} ${on.size === 1 ? "person" : "people"}.`} Only you can see this list, and nobody is told
        when you add them.
      </p>
      <label className="relative block">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-ink-faint" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search people you follow or who follow you"
          className="input pl-9"
        />
      </label>
      {error && <p className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">{error}</p>}
      {people.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">Follow people, or wait for them to follow you, to add them here.</p>
      ) : (
        <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
          {shown.map((p) => {
            const isOn = on.has(p.id);
            return (
              <li key={p.id}>
                <button type="button" onClick={() => toggle(p)} aria-pressed={isOn} className="flex w-full items-center gap-3 px-3.5 py-2.5 text-left hover:bg-surface-2">
                  <Avatar path={p.avatar_path} name={p.full_name} size={40} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{p.full_name}</span>
                    <span className="block truncate text-sm text-ink-muted">@{p.username}</span>
                  </span>
                  <span
                    className={`flex size-8 items-center justify-center rounded-full border-2 transition ${
                      isOn ? "bg-close border-transparent text-white" : "border-line text-ink-faint"
                    }`}
                    aria-hidden
                  >
                    <Star className={`size-4 ${isOn ? "fill-current" : ""}`} />
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
