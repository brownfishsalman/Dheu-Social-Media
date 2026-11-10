import type { Metadata } from "next";
import Link from "next/link";
import { SquarePen, Megaphone } from "lucide-react";
import { requireProfile } from "@/lib/data/session";
import { getConversations, getAnnouncementSummary } from "@/lib/data/chat";
import { WaveMark } from "@/components/brand/Logo";
import { PageHeader } from "@/components/ui/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { GroupAvatar } from "@/components/chat/GroupAvatar";
import { NotesTray } from "@/components/chat/NotesTray";
import { ConversationMenu } from "@/components/chat/ConversationMenu";
import { getNotes } from "@/lib/data/notes";
import { timeAgo } from "@/lib/time";
import { receiptLabel } from "@/lib/receipts";

export const metadata: Metadata = { title: "Messages" };

export default async function MessagesPage() {
  const me = await requireProfile();
  const [conversations, ann, notes] = await Promise.all([getConversations(me.id), getAnnouncementSummary(), getNotes()]);

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader
        title="Messages"
        action={
          <Link href="/messages/new" aria-label="New message" className="rounded-full p-2 hover:bg-surface-2">
            <SquarePen className="size-5" />
          </Link>
        }
      />

      <NotesTray me={{ id: me.id, username: me.username, full_name: me.full_name, avatar_path: me.avatar_path }} notes={notes} />

      {/* Pinned announcements thread */}
      <Link href="/messages/announcements" className="flex items-center gap-3 border-b border-line bg-brand-soft/40 px-4 py-3 hover:bg-brand-soft/70">
        <span className="flex size-[52px] items-center justify-center rounded-full bg-surface ring-1 ring-line">
          <WaveMark size={30} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline justify-between gap-2">
            <span className={`flex items-center gap-1.5 truncate ${ann.unread > 0 ? "font-bold" : "font-semibold"}`}>
              <Megaphone className="size-4 text-brand" /> Dheu announcements
            </span>
            {ann.latest && <span className="shrink-0 text-xs text-ink-faint">{timeAgo(ann.latest.created_at)}</span>}
          </span>
          <span className={`block truncate text-sm ${ann.unread > 0 ? "font-semibold text-ink" : "text-ink-muted"}`}>
            {ann.latest ? ann.latest.body.split(/\r?\n/)[0] : "News and new features will appear here."}
          </span>
        </span>
        {ann.unread > 0 && (
          <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-brand px-1.5 text-xs font-bold text-brand-ink">
            {ann.unread}
          </span>
        )}
      </Link>

      {conversations.length === 0 ? (
        <div className="px-6 py-16 text-center">
          <p className="font-display text-lg font-semibold">No messages yet</p>
          <p className="mt-1 text-sm text-ink-muted">Start a conversation with someone you follow.</p>
          <Link href="/messages/new" className="btn-primary mt-5">
            <SquarePen className="size-4" /> New message
          </Link>
        </div>
      ) : (
        <ul className="divide-y divide-line">
          {conversations.map((c) => {
            // In a group, say who sent the last message; in a DM only mark my own.
            const who = c.last
              ? c.last.sender_id === me.id
                ? "You: "
                : c.isGroup
                  ? `${(c.last.sender_name ?? "Someone").split(" ")[0]}: `
                  : ""
              : "";
            // My message was the last one and someone has seen it: say so, as on Instagram.
            const seen =
              c.last && c.last.kind !== "system" && c.last.sender_id === me.id
                ? receiptLabel(c.last.created_at, c.receipts, c.isGroup, c.isGroup ? c.memberCount - 1 : 1, (id) =>
                    (c.members.find((p) => p.id === id)?.full_name ?? "Someone").split(" ")[0],
                  )
                : null;
            const preview = c.last
              ? c.last.kind === "system"
                ? c.last.body // "Salman added Rafi Karim"
                : seen && seen !== "Sent"
                  ? c.isGroup
                    ? seen
                    : "Seen"
                  : `${who}${c.last.story_id ? "Replied to a story: " : ""}${c.last.note_text ? "Replied to a note: " : ""}${c.last.image_path ? "📷 Photo " : ""}${c.last.view_once ? "📷 View-once photo " : ""}${c.last.audio_path ? "🎤 Voice message " : ""}${c.last.body}`
              : c.isGroup
                ? `${c.memberCount} members · No messages yet`
                : "No messages yet";
            return (
              <li key={c.id} className="flex items-center pr-2 hover:bg-surface-2/60">
                <Link href={`/messages/${c.id}`} className="flex min-w-0 flex-1 items-center gap-3 py-3 pl-4">
                  {c.isGroup ? (
                    <GroupAvatar members={c.members} size={52} />
                  ) : (
                    <Avatar path={c.peer!.avatar_path} name={c.peer!.full_name} size={52} />
                  )}
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline justify-between gap-2">
                      <span className={`truncate ${c.unread > 0 ? "font-bold" : "font-semibold"}`}>
                        {c.title}
                      </span>
                      {c.last && <span className="shrink-0 text-xs text-ink-faint">{timeAgo(c.last.created_at)}</span>}
                    </span>
                    <span
                      className={`block truncate text-sm ${c.unread > 0 ? "font-semibold text-ink" : "text-ink-muted"}`}
                    >
                      {preview}
                    </span>
                  </span>
                  {c.unread > 0 && (
                    <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-brand px-1.5 text-xs font-bold text-brand-ink">
                      {c.unread}
                    </span>
                  )}
                </Link>
                <ConversationMenu conversationId={c.id} title={c.title} isGroup={c.isGroup} />
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
