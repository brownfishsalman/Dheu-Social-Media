"use client";

import { useEffect } from "react";
import Link from "next/link";
import { MessageCircle } from "lucide-react";

type Face = { name: string; image: string | null };
type Props = { me: Face; them: Face; href: string; onClose: () => void };

// "It's a match": both faces, a way into the chat, and back to swiping.
export function MatchOverlay({ me, them, href, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="It's a match"
      className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-black/85 px-6 text-center text-white animate-fade-in"
    >
      <p className="font-display text-4xl font-semibold">It&apos;s a match</p>
      <p className="mt-2 max-w-xs text-sm text-white/80">You and {them.name} both swiped right.</p>

      <div className="mt-8 flex items-center">
        <FaceCircle face={me} className="-rotate-6" />
        <FaceCircle face={them} className="-ml-5 rotate-6" />
      </div>

      <div className="mt-10 flex w-full max-w-xs flex-col gap-3">
        <Link href={href} className="btn-primary w-full">
          <MessageCircle className="size-4" /> Say hi
        </Link>
        <button type="button" onClick={onClose} className="btn w-full border border-white/40 text-white hover:bg-white/10">
          Keep swiping
        </button>
      </div>
    </div>
  );
}

function FaceCircle({ face, className }: { face: Face; className: string }) {
  const initials = face.name
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join("")
    .toUpperCase();
  return (
    <div className={`size-28 overflow-hidden rounded-full border-4 border-white bg-surface-2 ${className}`}>
      {face.image ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={face.image} alt={face.name} className="size-full object-cover" />
      ) : (
        <span className="flex size-full items-center justify-center text-2xl font-semibold text-ink">{initials}</span>
      )}
    </div>
  );
}
