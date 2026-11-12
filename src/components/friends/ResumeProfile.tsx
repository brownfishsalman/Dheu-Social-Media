"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, Play } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { swipeErrorMessage } from "@/lib/friends";

// "Resume my profile" on the paused screen.
export function ResumeProfile({ userId }: { userId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function resume() {
    setBusy(true);
    setError(null);
    const { error: err } = await createClient().from("swipe_profiles").update({ active: true }).eq("user_id", userId);
    if (err) {
      setBusy(false);
      return setError(swipeErrorMessage(err.message));
    }
    router.refresh();
  }

  return (
    <>
      <button type="button" onClick={resume} disabled={busy} className="btn-primary">
        {busy ? <Loader2 className="size-4 animate-spin" /> : <Play className="size-4" />}
        Resume my profile
      </button>
      {error && (
        <p role="alert" className="mt-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}
    </>
  );
}
