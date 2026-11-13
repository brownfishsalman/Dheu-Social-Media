"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Flame, Heart, Loader2, Undo2, X } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { avatarUrl, mediaUrl } from "@/lib/storage";
import { swipeErrorMessage, type SwipeCard, type SwipeMatch } from "@/lib/friends";
import { MatchOverlay } from "@/components/friends/MatchOverlay";

type Props = {
  initialCards: SwipeCard[];
  unseenMatches: SwipeMatch[]; // made while this member was away: shown first
  me: { name: string; photo: string | null }; // photo: my first swipe photo
};

type ShownMatch = { name: string; image: string | null; href: string };
type Flight = { card: SwipeCard; dir: 1 | -1; from: { x: number; y: number }; key: number };

const SWIPE_AT = 110; // px a card has to travel to count as a swipe
const FLY_MS = 240;

function chatHref(conversationId: string | null, userId: string) {
  return conversationId ? `/messages/${conversationId}` : `/messages/with/${userId}`;
}

// The deck: drag a card (or use the buttons, or the arrow keys) right to
// like, left to pass. Tap the left or right of a photo for the others.
export function SwipeDeck({ initialCards, unseenMatches, me }: Props) {
  const router = useRouter();
  const [cards, setCards] = useState(initialCards);
  const [exhausted, setExhausted] = useState(initialCards.length === 0);
  const [looped, setLooped] = useState(initialCards[0]?.looped ?? false);
  const [loading, setLoading] = useState(false);
  const [lastSwiped, setLastSwiped] = useState<SwipeCard | null>(null);
  const [matches, setMatches] = useState<ShownMatch[]>(() =>
    unseenMatches.map((m) => ({ name: m.full_name, image: avatarUrl(m.avatar_path), href: chatHref(m.conversation_id, m.user_id) })),
  );
  const [error, setError] = useState<string | null>(null);
  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null);
  const [flight, setFlight] = useState<Flight | null>(null);
  const [photoIndex, setPhotoIndex] = useState(0);
  const pending = useRef<Promise<unknown>>(Promise.resolve()); // swipes and undos, in order
  const fetching = useRef(false);
  const flights = useRef(0);
  const start = useRef<{ x: number; y: number } | null>(null);
  const meImage = mediaUrl("swipe", me.photo);

  // The match screens for matches made while away are about to show.
  useEffect(() => {
    if (unseenMatches.length > 0) void createClient().rpc("mark_swipe_matches_seen");
  }, [unseenMatches.length]);

  const fetchMore = useCallback(
    async (holding: SwipeCard[]) => {
      if (fetching.current) return;
      fetching.current = true;
      setLoading(true);
      const { data, error: err } = await createClient().rpc("swipe_deck", {
        p_exclude: holding.map((c) => c.user_id),
        p_limit: 10,
      });
      fetching.current = false;
      setLoading(false);
      if (err) {
        if (err.message.includes("NO_PROFILE")) router.refresh();
        else setError(swipeErrorMessage(err.message));
        if (holding.length === 0) setExhausted(true); // shows "Check again"
        return;
      }
      const held = new Set(holding.map((c) => c.user_id));
      const raw = data ?? [];
      const isLooped = raw.some((c) => c.looped);
      const fresh = isLooped ? raw : raw.filter((c) => !held.has(c.user_id));
      if (fresh.length === 0) {
        if (holding.length === 0) setExhausted(true);
        return;
      }
      setExhausted(false);
      if (isLooped) setLooped(true);
      setCards((current) => {
        const have = new Set(current.map((c) => c.user_id));
        const toAdd = fresh.filter((c) => !have.has(c.user_id));
        return current.length === 0 ? fresh : [...current, ...toAdd];
      });
    },
    [router],
  );

  async function send(card: SwipeCard, like: boolean) {
    const { data, error: err } = await createClient().rpc("swipe", { p_target: card.user_id, p_like: like });
    if (err) {
      if (err.message.includes("NOT_AVAILABLE")) return; // paused or gone since; the card is gone anyway
      if (err.message.includes("NO_PROFILE")) return router.refresh();
      setError(swipeErrorMessage(err.message));
      setLastSwiped(null);
      setCards((current) => [card, ...current.filter((c) => c.user_id !== card.user_id)]);
      return;
    }
    const result = data?.[0];
    if (result?.matched) {
      setLastSwiped((l) => (l?.user_id === card.user_id ? null : l)); // a match can't be undone
      setMatches((m) => [
        ...m,
        { name: card.full_name, image: mediaUrl("swipe", card.photos[0]), href: chatHref(result.conversation_id, card.user_id) },
      ]);
    }
  }

  function decide(like: boolean, from = { x: 0, y: 0 }) {
    const card = cards[0];
    if (!card) return;
    flights.current += 1;
    setFlight({ card, dir: like ? 1 : -1, from, key: flights.current });
    setDrag(null);
    setPhotoIndex(0);
    setLooped(false);
    setError(null);
    const rest = cards.slice(1);
    setCards(rest);
    setLastSwiped(card);
    pending.current = pending.current.then(() => send(card, like));
    // Topping up waits for the swipes before it, or the server would hand
    // back a card whose swipe hasn't arrived yet.
    if (rest.length <= 2) pending.current = pending.current.then(() => fetchMore(rest));
  }

  function retry() {
    setError(null);
    setExhausted(false);
    pending.current = pending.current.then(() => fetchMore([]));
  }

  function undo() {
    const card = lastSwiped;
    if (!card) return;
    setLastSwiped(null);
    setError(null);
    pending.current = pending.current.then(async () => {
      const { data, error: err } = await createClient().rpc("swipe_undo", { p_target: card.user_id });
      if (err || !data) return setError("That swipe can't be undone any more.");
      setPhotoIndex(0);
      setExhausted(false);
      setCards((current) => [card, ...current.filter((c) => c.user_id !== card.user_id)]);
    });
  }

  // Arrow keys on a computer.
  const decideRef = useRef(decide);
  useEffect(() => {
    decideRef.current = decide;
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable)) return;
      if (e.key === "ArrowLeft") decideRef.current(false);
      if (e.key === "ArrowRight") decideRef.current(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const endFlight = useCallback(() => setFlight(null), []);
  const card = cards[0];
  const next = cards[1];
  const match = matches[0];

  return (
    <div className="mx-auto flex w-full max-w-[420px] flex-col px-4 pt-3 pb-4">
      {looped && (
        <p className="mb-3 rounded-xl bg-brand-soft px-3.5 py-2.5 text-sm text-brand-strong">
          You&apos;ve seen everyone. Starting over with everyone in a new order.
        </p>
      )}
      {error && (
        <p role="alert" className="mb-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}

      <div className="relative h-[min(64dvh,560px)] w-full">
        {card ? (
          <>
            {next && (
              <div className="absolute inset-0 scale-[0.96] opacity-70" aria-hidden="true">
                <CardFace card={next} photoIndex={0} />
              </div>
            )}
            <div
              key={card.user_id}
              className="absolute inset-0 cursor-grab touch-pan-y select-none active:cursor-grabbing"
              style={{
                transform: drag ? `translate(${drag.x}px, ${drag.y * 0.2}px) rotate(${drag.x / 18}deg)` : undefined,
                transition: drag ? "none" : "transform 200ms ease-out",
              }}
              onPointerDown={(e) => {
                if (e.button !== 0) return;
                start.current = { x: e.clientX, y: e.clientY };
                e.currentTarget.setPointerCapture(e.pointerId);
                setDrag({ x: 0, y: 0 });
              }}
              onPointerMove={(e) => {
                const s = start.current;
                if (s) setDrag({ x: e.clientX - s.x, y: e.clientY - s.y });
              }}
              onPointerUp={(e) => {
                const s = start.current;
                start.current = null;
                if (!s) return;
                const dx = e.clientX - s.x;
                const dy = e.clientY - s.y;
                if (Math.abs(dx) < 6 && Math.abs(dy) < 6) {
                  // A tap: the left third goes back a photo, the rest forward.
                  const box = e.currentTarget.getBoundingClientRect();
                  const back = e.clientX - box.left < box.width / 3;
                  setPhotoIndex((i) => Math.max(0, Math.min(card.photos.length - 1, i + (back ? -1 : 1))));
                  setDrag(null);
                } else if (Math.abs(dx) > SWIPE_AT) {
                  decide(dx > 0, { x: dx, y: dy * 0.2 });
                } else {
                  setDrag(null);
                }
              }}
              onPointerCancel={() => {
                start.current = null;
                setDrag(null);
              }}
            >
              <CardFace card={card} photoIndex={photoIndex} />
              {drag && Math.abs(drag.x) > 24 && (
                <span
                  aria-hidden="true"
                  className={`absolute top-6 flex size-14 items-center justify-center rounded-full bg-surface shadow-card ${drag.x > 0 ? "left-6 text-brand" : "right-6 text-danger"}`}
                  style={{ opacity: Math.min(1, (Math.abs(drag.x) - 24) / (SWIPE_AT - 24)) }}
                >
                  {drag.x > 0 ? <Heart className="size-7" /> : <X className="size-7" />}
                </span>
              )}
            </div>
          </>
        ) : (
          <EmptyDeck loading={loading} exhausted={exhausted} onRetry={retry} />
        )}
        {flight && <FlyingCard key={flight.key} flight={flight} onDone={endFlight} />}
      </div>

      <div className="mt-4 flex items-center justify-center gap-5">
        <button
          type="button"
          onClick={undo}
          disabled={!lastSwiped}
          aria-label="Undo the last swipe"
          title="Undo the last swipe"
          className="flex size-11 items-center justify-center rounded-full border border-line bg-surface text-ink-muted shadow-card transition hover:text-ink active:scale-95 disabled:opacity-40"
        >
          <Undo2 className="size-5" />
        </button>
        <button
          type="button"
          onClick={() => decide(false)}
          disabled={!card}
          aria-label="Pass"
          title="Pass (left arrow)"
          className="flex size-16 items-center justify-center rounded-full border border-line bg-surface text-danger shadow-card transition active:scale-95 disabled:opacity-40"
        >
          <X className="size-8" />
        </button>
        <button
          type="button"
          onClick={() => decide(true)}
          disabled={!card}
          aria-label="Like"
          title="Like (right arrow)"
          className="flex size-16 items-center justify-center rounded-full border border-line bg-surface text-brand shadow-card transition active:scale-95 disabled:opacity-40"
        >
          <Heart className="size-8" />
        </button>
      </div>

      {match && (
        <MatchOverlay
          me={{ name: me.name, image: meImage }}
          them={{ name: match.name, image: match.image }}
          href={match.href}
          onClose={() => setMatches((m) => m.slice(1))}
        />
      )}
    </div>
  );
}

function CardFace({ card, photoIndex }: { card: SwipeCard; photoIndex: number }) {
  const index = Math.min(photoIndex, card.photos.length - 1);
  return (
    <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-line bg-surface shadow-card">
      <div className="relative min-h-0 flex-1 bg-surface-2">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={mediaUrl("swipe", card.photos[index]) ?? undefined}
          alt=""
          draggable={false}
          className="pointer-events-none size-full object-cover select-none"
        />
        {card.photos.length > 1 && (
          <div className="absolute inset-x-2 top-2 flex gap-1" aria-hidden="true">
            {card.photos.map((p, i) => (
              <span key={p} className={`h-[3px] flex-1 rounded-full ${i === index ? "bg-white" : "bg-white/45"}`} />
            ))}
          </div>
        )}
        <div className="absolute inset-x-0 bottom-0 bg-linear-to-t from-black/70 to-transparent px-4 pt-12 pb-3 text-white">
          <p className="font-display text-2xl font-semibold leading-tight">
            {card.full_name}
            {card.age !== null && <span className="font-normal">, {card.age}</span>}
          </p>
        </div>
      </div>
      {(card.bio || card.interests.length > 0) && (
        <div className="shrink-0 space-y-2 px-4 py-3">
          {card.bio && <p className="line-clamp-3 text-sm break-words whitespace-pre-line text-ink">{card.bio}</p>}
          {card.interests.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {card.interests.map((t) => (
                <span key={t} className="rounded-full bg-brand-soft px-2.5 py-1 text-xs font-medium text-brand-strong">
                  {t}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// The card just decided on, flying off the side it went.
function FlyingCard({ flight, onDone }: { flight: Flight; onDone: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const { dir, from } = flight;
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const animation = el.animate(
      [
        { transform: `translate(${from.x}px, ${from.y}px) rotate(${from.x / 18}deg)`, opacity: 1 },
        { transform: `translate(${dir * 640}px, ${from.y + 60}px) rotate(${dir * 32}deg)`, opacity: 0.4 },
      ],
      { duration: FLY_MS, easing: "ease-in", fill: "forwards" },
    );
    animation.onfinish = onDone;
    return () => animation.cancel();
  }, [dir, from.x, from.y, onDone]);
  return (
    <div ref={ref} className="pointer-events-none absolute inset-0" aria-hidden="true">
      <CardFace card={flight.card} photoIndex={0} />
    </div>
  );
}

function EmptyDeck({ loading, exhausted, onRetry }: { loading: boolean; exhausted: boolean; onRetry: () => void }) {
  return (
    <div className="card flex h-full flex-col items-center justify-center px-6 text-center">
      {loading || !exhausted ? (
        <Loader2 className="size-8 animate-spin text-ink-muted" />
      ) : (
        <>
          <Flame className="size-10 text-ink-muted" strokeWidth={1.5} />
          <p className="mt-3 font-display text-lg font-semibold">No one new right now</p>
          <p className="mt-1 max-w-xs text-sm text-ink-muted">
            You&apos;ve swiped through everyone. When more people make a swipe profile, they&apos;ll show up here.
          </p>
          <div className="mt-5 flex gap-3">
            <button type="button" onClick={onRetry} className="btn-secondary">
              Check again
            </button>
            <Link href="/friends/matches" className="btn-primary">
              Your matches
            </Link>
          </div>
        </>
      )}
    </div>
  );
}
