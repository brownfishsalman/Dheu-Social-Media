import "server-only";
import { cache } from "react";
import { createClient } from "@/lib/supabase/server";
import type { Profile, MessageKind } from "@/lib/database.types";
import type { SystemEvent } from "@/lib/chat-events";
import type { BlockStatus } from "@/lib/data/profiles";
import type { Reaction, Receipt } from "@/lib/receipts";
import { MESSAGE_PAGE } from "@/lib/constants";

export type ChatPeer = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

const MEMBER_EMBED = "members:conversation_members(joined_at, user:profiles!conversation_members_user_id_fkey(id, username, full_name, avatar_path))";

// Members visible to me, oldest first. Someone who blocked me is hidden by
// RLS on profiles (their embed comes back null) and is simply left out.
function visibleMembers(rows: { joined_at: string; user: ChatPeer | null }[]): ChatPeer[] {
  return [...rows]
    .sort((a, b) => a.joined_at.localeCompare(b.joined_at))
    .map((r) => r.user)
    .filter((u): u is ChatPeer => u !== null);
}

export type ConversationSummary = {
  id: string;
  isGroup: boolean;
  title: string; // the other person's name for a DM
  peer: ChatPeer | null; // DMs only
  members: ChatPeer[]; // groups: for the stacked avatar
  memberCount: number;
  last_message_at: string | null;
  last: {
    body: string;
    sender_id: string;
    sender_name: string | null;
    created_at: string;
    story_id: string | null;
    image_path: string | null;
    audio_path: string | null;
    note_text: string | null;
    kind: MessageKind;
    view_once: boolean;
  } | null;
  unread: number;
  receipts: Receipt[]; // when the others last read it, for "Seen" under my last message
};

export async function getConversations(meId: string): Promise<ConversationSummary[]> {
  const supabase = await createClient();
  const [{ data: convs }, { data: unread }, { data: receipts }] = await Promise.all([
    supabase
      .from("conversations")
      .select(
        `id, is_group, title, last_message_at, ${MEMBER_EMBED}, reads:conversation_reads(cleared_at), messages(body, sender_id, created_at, story_id, image_path, audio_path, note_text, kind, view_once, sender:profiles!messages_sender_id_fkey(full_name))`,
      )
      .order("last_message_at", { ascending: false, nullsFirst: false })
      .order("created_at", { referencedTable: "messages", ascending: false })
      .limit(1, { referencedTable: "messages" }),
    supabase.rpc("unread_counts"),
    supabase.rpc("read_receipts_inbox"),
  ]);

  const unreadBy = new Map((unread ?? []).map((u) => [u.conversation_id, Number(u.unread)]));
  const receiptsBy = new Map<string, Receipt[]>();
  for (const r of receipts ?? []) {
    const list = receiptsBy.get(r.conversation_id) ?? [];
    list.push({ user_id: r.user_id, last_read_at: r.last_read_at, joined_at: r.joined_at });
    receiptsBy.set(r.conversation_id, list);
  }

  const out: ConversationSummary[] = [];
  for (const c of convs ?? []) {
    const members = visibleMembers(c.members);
    const peer = c.is_group ? null : (members.find((m) => m.id !== meId) ?? null);
    if (!c.is_group && !peer) continue; // DM partner hidden (they blocked me) or gone
    const m = c.messages[0];
    // I deleted this chat and nothing new has arrived since. (RLS only returns
    // messages sent after the delete, and reads holds only my own row.)
    if (c.reads[0]?.cleared_at && !m) continue;
    out.push({
      id: c.id,
      isGroup: c.is_group,
      title: c.is_group ? (c.title ?? "Group") : peer!.full_name,
      peer,
      members: members.filter((x) => x.id !== meId),
      memberCount: c.members.length,
      last_message_at: c.last_message_at,
      last: m ? { ...m, sender_name: m.sender?.full_name ?? null } : null,
      unread: unreadBy.get(c.id) ?? 0,
      receipts: receiptsBy.get(c.id) ?? [],
    });
  }
  return out;
}

export type ConversationInfo = {
  id: string;
  isGroup: boolean;
  title: string;
  peer: ChatPeer | null; // DMs only
  members: ChatPeer[]; // everyone I can see, including me
  ownerId: string | null; // groups only
  blocked: BlockStatus; // DMs only (groups: null)
};

// cache(): generateMetadata and the page both ask for the same conversation in one request.
export const getConversation = cache(async (id: string, meId: string): Promise<ConversationInfo | null> => {
  const supabase = await createClient();
  const { data } = await supabase
    .from("conversations")
    .select(`id, is_group, title, created_by, ${MEMBER_EMBED}`)
    .eq("id", id)
    .maybeSingle();
  if (!data) return null;

  const members = visibleMembers(data.members);
  if (data.is_group) {
    const owner = data.created_by && members.some((m) => m.id === data.created_by) ? data.created_by : (members[0]?.id ?? null);
    return { id: data.id, isGroup: true, title: data.title ?? "Group", peer: null, members, ownerId: owner, blocked: null };
  }

  const peer = members.find((m) => m.id !== meId) ?? null;
  if (!peer) return null;
  const { data: blocked } = await supabase.rpc("block_status", { p_other: peer.id });
  return {
    id: data.id,
    isGroup: false,
    title: peer.full_name,
    peer,
    members,
    ownerId: null,
    blocked: (blocked as BlockStatus) ?? null,
  };
});

// Members' profiles for a set of ids (people who left a group but whose
// messages remain). RLS hides anyone who blocked me; they come back missing.
export async function getPeers(ids: string[]): Promise<ChatPeer[]> {
  if (ids.length === 0) return [];
  const supabase = await createClient();
  const { data } = await supabase.from("profiles").select("id, username, full_name, avatar_path").in("id", ids);
  return data ?? [];
}

// Enough of the quoted message to label it ("📷 Photo", "🎤 Voice message", or the text),
// even after its media has expired.
export type ReplyPreview = {
  id: string;
  body: string;
  sender_id: string;
  image_path: string | null;
  image_expires_at: string | null;
  audio_path: string | null;
  audio_expires_at: string | null;
};

export type ChatMessage = {
  id: string;
  conversation_id: string;
  sender_id: string;
  body: string;
  story_id: string | null;
  image_path: string | null;
  image_expires_at: string | null;
  audio_path: string | null;
  audio_duration_ms: number | null;
  audio_expires_at: string | null;
  reply_to_id: string | null;
  kind: MessageKind;
  event: SystemEvent | null;
  note_text: string | null; // set when this message replies to a note
  view_once: boolean; // a photo in the private "once" bucket (0023)
  once_path: string | null; // its file; null once it's gone
  created_at: string;
  story: { image_path: string; author_id: string } | null;
  reply: ReplyPreview | null;
  reactions: Reaction[];
  opens: { user_id: string; opened_at: string }[]; // view-once: my own open, or who opened mine
};

export const MESSAGE_COLUMNS =
  "id, conversation_id, sender_id, body, story_id, image_path, image_expires_at, audio_path, audio_duration_ms, audio_expires_at, reply_to_id, kind, event, note_text, view_once, once_path, created_at";

export const MESSAGE_SELECT = `${MESSAGE_COLUMNS}, story:stories(image_path, author_id), reply:reply_to_id(id, body, sender_id, image_path, image_expires_at, audio_path, audio_expires_at), reactions:message_reactions(user_id, emoji), opens:message_opens(user_id, opened_at)`;


export async function getMessages(conversationId: string, before?: string): Promise<ChatMessage[]> {
  const supabase = await createClient();
  let q = supabase
    .from("messages")
    .select(MESSAGE_SELECT)
    .eq("conversation_id", conversationId)
    .order("created_at", { ascending: false })
    .limit(MESSAGE_PAGE);
  if (before) q = q.lt("created_at", before);
  const { data } = await q;
  // PostgREST types a self-referencing embed as an array; it is a single row here.
  const rows = (data ?? []).map((r) => ({
    ...r,
    reply: Array.isArray(r.reply) ? ((r.reply[0] as ReplyPreview | undefined) ?? null) : ((r.reply as ReplyPreview | null) ?? null),
    reactions: r.reactions ?? [],
    opens: r.opens ?? [],
  })) as unknown as ChatMessage[];
  return rows.reverse();
}

export type AnnouncementItem = { id: string; body: string; created_at: string; author_id: string | null };

export async function getAnnouncements(limit = 100): Promise<AnnouncementItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("announcements")
    .select("id, body, created_at, author_id")
    .order("created_at", { ascending: false })
    .limit(limit);
  return (data ?? []).reverse();
}

// Latest announcement + unread count, for the pinned inbox row.
export async function getAnnouncementSummary(): Promise<{ latest: AnnouncementItem | null; unread: number }> {
  const supabase = await createClient();
  const [{ data: latest }, { data: unread }] = await Promise.all([
    supabase.from("announcements").select("id, body, created_at, author_id").order("created_at", { ascending: false }).limit(1).maybeSingle(),
    supabase.rpc("unread_announcement_count"),
  ]);
  return { latest: latest ?? null, unread: unread ?? 0 };
}
