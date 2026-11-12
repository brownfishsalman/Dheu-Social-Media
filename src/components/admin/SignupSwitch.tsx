"use client";

import { useState, useTransition } from "react";
import { Loader2, Lock, LockOpen } from "lucide-react";
import { setSignupInviteOnly } from "@/app/(app)/admin/actions";

// Admin → Invites: whether creating an account needs an invite code (0028).
export function SignupSwitch({ inviteOnly }: { inviteOnly: boolean }) {
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  function flip() {
    const next = !inviteOnly;
    const q = next
      ? "Make sign-up invite-only? New people will need a code from you or a member."
      : "Open sign-up? Anyone with the link will be able to create an account.";
    if (!confirm(q)) return;
    setError(null);
    startTransition(async () => {
      const r = await setSignupInviteOnly(next);
      if (!r.ok) setError(r.error);
    });
  }

  return (
    <div className="card space-y-3 p-4">
      <div className="flex items-start gap-3">
        {inviteOnly ? <Lock className="mt-0.5 size-5 shrink-0" /> : <LockOpen className="mt-0.5 size-5 shrink-0" />}
        <div className="min-w-0 flex-1">
          <h2 className="font-display text-lg font-semibold">{inviteOnly ? "Sign-up is invite-only" : "Sign-up is open"}</h2>
          <p className="mt-0.5 text-sm text-ink-muted">
            {inviteOnly
              ? "New people need an invite code, from the list below or from a member's Settings."
              : "Anyone with the link can create an account; no code needed. Codes below aren't used while it's open."}
          </p>
        </div>
      </div>
      {error && <p className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">{error}</p>}
      <button type="button" onClick={flip} disabled={pending} className="btn-secondary">
        {pending && <Loader2 className="size-4 animate-spin" />}
        {inviteOnly ? "Open sign-up to everyone" : "Make sign-up invite-only"}
      </button>
    </div>
  );
}
