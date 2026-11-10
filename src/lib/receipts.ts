// Read receipts (0022) and reactions (0023), shared by the chat, the inbox
// and the server. Same wording as the Android app (ChatScreen.kt).
import { timeAgo } from "@/lib/time";

// When another member last read a chat (read_receipts() / read_receipts_inbox()).
// Only members who share their receipts are ever listed.
export type Receipt = { user_id: string; last_read_at: string; joined_at: string };

// "Sent", "Seen 5m ago" in a 1-to-1 chat, "Seen by Rafi and Tania" or "Seen by
// everyone" in a group. Someone added to a group after the message was sent
// doesn't count as having seen it.
export function receiptLabel(
  sentAt: string,
  receipts: Receipt[],
  isGroup: boolean,
  others: number,
  firstName: (id: string) => string,
  now = Date.now(),
): string {
  const sent = new Date(sentAt).getTime();
  const seen = receipts.filter(
    (r) => new Date(r.last_read_at).getTime() >= sent && new Date(r.joined_at).getTime() <= sent,
  );
  if (seen.length === 0) return "Sent";
  if (!isGroup) {
    const ago = timeAgo(seen[0].last_read_at, now);
    return ago === "now" ? "Seen just now" : `Seen ${ago} ago`;
  }
  if (others > 0 && seen.length >= others) return "Seen by everyone";
  const names = seen.map((r) => firstName(r.user_id));
  if (names.length === 1) return `Seen by ${names[0]}`;
  if (names.length === 2) return `Seen by ${names[0]} and ${names[1]}`;
  const rest = names.length - 2;
  return `Seen by ${names[0]}, ${names[1]} and ${rest} other${rest === 1 ? "" : "s"}`;
}

// The quick reactions offered on a message (any emoji is allowed by the database).
export const QUICK_REACTIONS = ["❤️", "😂", "😮", "😢", "😡", "👍"] as const;

export type Reaction = { user_id: string; emoji: string };

// Reactions grouped for the chip under a bubble: most used first.
export function groupReactions(reactions: Reaction[]): { emoji: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const r of reactions) counts.set(r.emoji, (counts.get(r.emoji) ?? 0) + 1);
  return [...counts.entries()].map(([emoji, count]) => ({ emoji, count })).sort((a, b) => b.count - a.count);
}
