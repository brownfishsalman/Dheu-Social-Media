"use client";

import { useState, useTransition } from "react";
import { Copy, Check, Share2, Loader2, Gift } from "lucide-react";
import { createMyInviteCode } from "@/app/(app)/settings/actions";
import type { InviteCode } from "@/lib/database.types";

type Props = { codes: InviteCode[]; siteUrl: string; limit: number; inviteOnly: boolean };

// While sign-up is open (0028), friends just need the link; while it's
// invite-only, each friend needs one of these single-use codes.
export function ShareWithFriends({ codes, siteUrl, limit, inviteOnly }: Props) {
  if (!inviteOnly) return <ShareLink siteUrl={siteUrl} />;
  return <InviteCodes codes={codes} siteUrl={siteUrl} limit={limit} />;
}

function ShareLink({ siteUrl }: { siteUrl: string }) {
  const [copied, setCopied] = useState(false);
  const link = `${siteUrl}/signup`;

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {}
  }

  async function share() {
    const text = `Join me on Dheu, a small photo community. Sign up here: ${link}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: "Join Dheu", text, url: link });
        return;
      } catch {}
    }
    await copy();
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-muted">Anyone with this link can create an account.</p>
      <div className="flex items-center gap-2 rounded-xl border border-line px-3.5 py-2.5">
        <span className="min-w-0 flex-1 truncate text-sm">{link}</span>
        <button type="button" onClick={copy} aria-label="Copy link" className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-ink">
          {copied ? <Check className="size-4 text-brand" /> : <Copy className="size-4" />}
        </button>
        <button type="button" onClick={share} className="btn-primary px-3 py-1.5 text-sm">
          <Share2 className="size-4" /> Share
        </button>
      </div>
    </div>
  );
}

function InviteCodes({ codes, siteUrl, limit }: Omit<Props, "inviteOnly">) {
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const linkFor = (code: string) => `${siteUrl}/signup?code=${code}`;
  const isLive = (c: InviteCode) =>
    !c.disabled && c.use_count < c.max_uses && (!c.expires_at || new Date(c.expires_at) > new Date());
  const live = codes.filter(isLive);
  const used = codes.filter((c) => !isLive(c));

  async function share(code: string) {
    const text = `Join me on Dheu — a small private photo community. Sign up here: ${linkFor(code)}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: "Join Dheu", text, url: linkFor(code) });
        return;
      } catch {}
    }
    await copy(code, text);
  }

  async function copy(code: string, text = linkFor(code)) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(code);
      setTimeout(() => setCopied(null), 1500);
    } catch {}
  }

  function generate() {
    setError(null);
    startTransition(async () => {
      const r = await createMyInviteCode();
      if (!r.ok) setError(r.error);
    });
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-muted">
        Each code lets one friend create an account and is valid for 30 days. You can hold {limit} unused codes at a time.
      </p>

      {live.length > 0 && (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {live.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3.5 py-2.5">
              <code className="font-mono text-[15px] font-semibold tracking-wider">{c.code}</code>
              <span className="text-xs text-ink-faint">
                until {c.expires_at ? new Date(c.expires_at).toLocaleDateString("en-GB") : "used"}
              </span>
              <span className="ml-auto flex items-center gap-1">
                <button type="button" onClick={() => copy(c.code)} aria-label="Copy link" className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-ink">
                  {copied === c.code ? <Check className="size-4 text-brand" /> : <Copy className="size-4" />}
                </button>
                <button type="button" onClick={() => share(c.code)} className="btn-primary px-3 py-1.5 text-sm">
                  <Share2 className="size-4" /> Share
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}

      {error && <p className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">{error}</p>}

      <button type="button" onClick={generate} disabled={pending || live.length >= limit} className="btn-secondary">
        {pending ? <Loader2 className="size-4 animate-spin" /> : <Gift className="size-4" />}
        {live.length >= limit ? `All ${limit} codes in use` : "Generate a new code"}
      </button>

      {used.length > 0 && (
        <p className="text-xs text-ink-faint">
          {used.length} earlier {used.length === 1 ? "code has" : "codes have"} been used or expired.
        </p>
      )}
    </div>
  );
}
