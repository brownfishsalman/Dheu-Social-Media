"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { Sheet } from "@/components/ui/Sheet";
import { REPORT_DETAILS_MAX, REPORT_REASONS, TARGET_NOUNS, reportColumns, type ReportReason, type ReportTarget } from "@/lib/reports";

type Props = { target: ReportTarget; onClose: () => void };

// "Report post / story / comment / account": a reason, an optional note, and
// a thank-you. Only the admin reads reports; the person isn't told who sent one.
export function ReportSheet({ target, onClose }: Props) {
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [details, setDetails] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!reason) return;
    setBusy(true);
    setError(null);
    const supabase = createClient();
    const {
      data: { session },
    } = await supabase.auth.getSession();
    if (!session) {
      setBusy(false);
      return setError("You're signed out. Log in again and retry.");
    }
    const { error: err } = await supabase.from("reports").insert({
      reporter_id: session.user.id,
      target_type: target.type,
      reason,
      details: details.trim(),
      ...reportColumns(target),
    });
    setBusy(false);
    if (err?.code === "23505") return setDone("You've already reported this. The admin will look at it.");
    if (err) return setError("Couldn't send your report. Please try again.");
    setDone("Thanks for telling us. The admin will review it.");
  }

  return (
    <Sheet title={`Report ${TARGET_NOUNS[target.type]}`} onClose={onClose}>
      {done ? (
        <div className="space-y-4 pt-1">
          <p className="text-sm">{done}</p>
          <button type="button" onClick={onClose} className="btn-primary w-full">
            Done
          </button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-3">
          <p className="text-sm text-ink-muted">
            Why are you reporting this? Only the admin sees reports, and the person isn&apos;t told who sent one.
          </p>
          <fieldset className="-mx-1 space-y-0.5">
            <legend className="sr-only">Reason</legend>
            {REPORT_REASONS.map((r) => (
              <label
                key={r.value}
                className={`flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition hover:bg-surface-2 ${
                  reason === r.value ? "bg-surface-2 font-medium" : ""
                }`}
              >
                <input
                  type="radio"
                  name="reason"
                  value={r.value}
                  checked={reason === r.value}
                  onChange={() => setReason(r.value)}
                  className="size-4 accent-brand"
                />
                {r.label}
              </label>
            ))}
          </fieldset>
          <textarea
            value={details}
            onChange={(e) => setDetails(e.target.value)}
            maxLength={REPORT_DETAILS_MAX}
            rows={3}
            placeholder="Anything the admin should know? (optional)"
            className="input resize-none"
          />
          {error && (
            <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
              {error}
            </p>
          )}
          <button type="submit" disabled={!reason || busy} className="btn-danger w-full">
            {busy && <Loader2 className="size-4 animate-spin" />}
            Send report
          </button>
        </form>
      )}
    </Sheet>
  );
}
