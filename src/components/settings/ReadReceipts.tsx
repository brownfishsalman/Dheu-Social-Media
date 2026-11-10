"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, Loader2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";

// Settings → Read receipts (profiles.read_receipts, 0022). Off hides my reads
// from others everywhere; I still see theirs, as on Instagram.
export function ReadReceiptsSetting({ userId, on: saved }: { userId: string; on: boolean }) {
  const [on, setOn] = useState(saved);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();
  const router = useRouter();

  async function toggle() {
    const next = !on;
    setOn(next);
    setBusy(true);
    setError(null);
    const { error: err } = await createClient().from("profiles").update({ read_receipts: next }).eq("id", userId);
    setBusy(false);
    if (err) {
      setOn(!next);
      return setError("Couldn't save that. Please try again.");
    }
    startTransition(() => router.refresh());
  }

  return (
    <div className="space-y-3">
      <p className="flex items-start gap-2 text-sm text-ink-muted">
        {on ? <Eye className="mt-0.5 size-4 shrink-0 text-brand" /> : <EyeOff className="mt-0.5 size-4 shrink-0" />}
        {on
          ? "On. People can see when you've read their messages."
          : "Off. People won't see when you've read their messages. You'll still see when they've read yours."}
      </p>
      <button type="button" onClick={toggle} disabled={busy} className={on ? "btn-secondary" : "btn-primary"}>
        {busy ? <Loader2 className="size-4 animate-spin" /> : on ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
        {on ? "Turn off read receipts" : "Turn on read receipts"}
      </button>
      {error && <p className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">{error}</p>}
    </div>
  );
}
