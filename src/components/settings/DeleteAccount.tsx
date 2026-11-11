"use client";

import { useState } from "react";
import { Loader2, Trash2 } from "lucide-react";
import { Sheet } from "@/components/ui/Sheet";
import { deleteMyAccount } from "@/app/(app)/settings/actions";

// Settings → Delete account: says what goes, asks for the password, then
// deletes for good (src/lib/account.ts).
export function DeleteAccount() {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // On success the action redirects to the login page.
      const result = await deleteMyAccount(password);
      if (result && !result.ok) setError(result.error);
    } finally {
      setBusy(false);
    }
  }

  function close() {
    if (busy) return;
    setOpen(false);
    setPassword("");
    setError(null);
  }

  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className="btn-danger">
        <Trash2 className="size-4" /> Delete my account
      </button>
      {open && (
        <Sheet title="Delete your account?" onClose={close}>
          <form onSubmit={submit} className="space-y-3">
            <p className="text-sm text-ink-muted">
              Your profile, posts, stories, comments, likes and messages are deleted for good, along with your one-to-one
              chats. This can&apos;t be undone.
            </p>
            <div>
              <label htmlFor="deletePassword" className="mb-1.5 block text-sm font-medium">
                Your password
              </label>
              <input
                id="deletePassword"
                type="password"
                autoComplete="current-password"
                className="input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoFocus
              />
            </div>
            {error && (
              <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
                {error}
              </p>
            )}
            <button type="submit" disabled={busy || !password} className="btn-danger w-full">
              {busy && <Loader2 className="size-4 animate-spin" />}
              Delete my account for good
            </button>
          </form>
        </Sheet>
      )}
    </>
  );
}
