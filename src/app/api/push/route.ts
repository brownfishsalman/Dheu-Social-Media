import { NextResponse } from "next/server";
import { createAdminClient } from "@/lib/supabase/admin";
import { sendPush, type PushPayload } from "@/lib/push";
import { adminSignedUrls } from "@/lib/storage-server";
import { TARGET_NOUNS, reasonLabel, type ReportTargetType } from "@/lib/reports";

// Called by the database (pg_net trigger) whenever a notification, message,
// announcement or other watched row (reactions, comment likes, reports,
// Find friends matches) is inserted. Authenticated with a shared secret held in
// Supabase Vault and in the PUSH_WEBHOOK_SECRET environment variable.

type NotificationRow = {
  id: string;
  user_id: string;
  actor_id: string;
  type: "like" | "follow" | "comment" | "story_reaction" | "follow_request" | "follow_accepted" | "mention";
  post_id: string | null;
  comment_id: string | null;
  conversation_id: string | null; // mentions in a group chat
  message_id: string | null;
};
type MessageRow = {
  id: string;
  conversation_id: string;
  sender_id: string;
  body: string;
  image_path: string | null;
  audio_path: string | null;
  kind: "text" | "system";
  event: { type: string; actor: string; targets?: string[]; title?: string } | null;
  note_text?: string | null;
  view_once?: boolean; // 0023
};
type AnnouncementRow = { id: string; body: string };
type ReactionRow = { message_id: string; user_id: string; conversation_id: string; emoji: string }; // 0023
type CommentLikeRow = { comment_id: string; user_id: string }; // 0024
type ReportRow = { id: string; reporter_id: string | null; target_type: ReportTargetType; reason: string }; // 0025
type SwipeMatchRow = { user_a: string; user_b: string; matched_by: string; conversation_id: string | null }; // 0027

// A day is plenty: the phone or browser fetches the icon as the notification arrives.
const ICON_LINK_SECONDS = 24 * 60 * 60;

// Who did it: their username for the text, their photo for the notification.
// The photo is a signed link, since the notification fetches it without a session.
async function personOf(id: string): Promise<{ name: string; icon?: string }> {
  const admin = createAdminClient();
  const { data } = await admin.from("profiles").select("username, avatar_path").eq("id", id).maybeSingle();
  const path = data?.avatar_path;
  const icon = path ? (await adminSignedUrls("avatars", [path], ICON_LINK_SECONDS)).get(path) : undefined;
  return { name: data?.username ?? "Someone", icon };
}

export async function POST(request: Request) {
  const secret = process.env.PUSH_WEBHOOK_SECRET;
  if (!secret || request.headers.get("x-push-secret") !== secret) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const { table, record } = (await request.json()) as { table: string; record: unknown };
  const admin = createAdminClient();

  let recipients: string[] = [];
  let payload: PushPayload | null = null;

  if (table === "notifications") {
    const n = record as NotificationRow;
    const { name: actor, icon } = await personOf(n.actor_id);
    recipients = [n.user_id];
    const map: Record<Exclude<NotificationRow["type"], "mention">, { body: string; url: string }> = {
      like: { body: `${actor} liked your post`, url: n.post_id ? `/p/${n.post_id}` : "/activity" },
      comment: { body: `${actor} commented on your post`, url: n.post_id ? `/p/${n.post_id}` : "/activity" },
      follow: { body: `${actor} started following you`, url: `/u/${actor}` },
      follow_request: { body: `${actor} wants to follow you`, url: "/activity" },
      follow_accepted: { body: `${actor} accepted your follow request`, url: `/u/${actor}` },
      story_reaction: { body: `${actor} reacted to your story`, url: `/stories/${n.user_id}` },
    };
    if (n.type === "mention") {
      payload = await mentionPayload(n, actor, icon);
    } else {
      const m = map[n.type];
      if (!m) return NextResponse.json({ ok: true, skipped: "unknown type" });
      if (n.type === "comment" && n.comment_id) {
        const { data: c } = await admin.from("comments").select("body").eq("id", n.comment_id).maybeSingle();
        if (c?.body) m.body = `${actor} commented: ${c.body.slice(0, 80)}`;
      }
      payload = { title: "Dheu", body: m.body, url: m.url, tag: `notif-${n.type}-${n.actor_id}`, icon };
    }
  } else if (table === "messages") {
    const msg = record as MessageRow;
    const [{ data: conv }, { data: blocks }] = await Promise.all([
      admin
        .from("conversations")
        .select("is_group, title, members:conversation_members(user_id)")
        .eq("id", msg.conversation_id)
        .maybeSingle(),
      // In a group, people on either side of a block with the sender can't see the message — no push either.
      admin.from("blocks").select("blocker_id, blocked_id").or(`blocker_id.eq.${msg.sender_id},blocked_id.eq.${msg.sender_id}`),
    ]);
    if (!conv) return NextResponse.json({ ok: true, skipped: "no conversation" });
    const blocked = new Set((blocks ?? []).flatMap((b) => [b.blocker_id, b.blocked_id]));
    const { name: sender, icon } = await personOf(msg.sender_id);
    const url = `/messages/${msg.conversation_id}`;
    const tag = `chat-${msg.conversation_id}`;

    if (msg.kind === "system") {
      // System lines stay quiet, except that people who were just added should hear about it.
      if (msg.event?.type !== "added") return NextResponse.json({ ok: true, skipped: "system line" });
      recipients = (msg.event.targets ?? []).filter((id) => !blocked.has(id));
      payload = { title: conv.title ?? "Group", body: `${sender} added you to the group`, url, tag, icon };
    } else {
      // Members this message mentions get the "mentioned you" push instead (see 0020).
      const { data: mentioned } = conv.is_group
        ? await admin.from("notifications").select("user_id").eq("message_id", msg.id).eq("type", "mention")
        : { data: [] };
      const skip = new Set((mentioned ?? []).map((m) => m.user_id));
      recipients = conv.members
        .map((m) => m.user_id)
        .filter((id) => id !== msg.sender_id && !skip.has(id) && !(conv.is_group && blocked.has(id)));
      const content = msg.body
        ? msg.body.slice(0, 100)
        : msg.audio_path
          ? "🎤 Voice message"
          : msg.view_once
            ? "📷 View-once photo"
            : "📷 Photo";
      const text = msg.note_text ? `Replied to your note: ${content}` : content;
      payload = conv.is_group
        ? { title: conv.title ?? "Group", body: `${sender}: ${text}`, url, tag, icon }
        : { title: sender, body: text, url, tag, icon };
    }
  } else if (table === "message_reactions") {
    // "Rafi reacted ❤️ to your message", to the message's sender only.
    const r = record as ReactionRow;
    const { data: msg } = await admin
      .from("messages")
      .select("sender_id, body, kind, view_once, conversation:conversations(is_group, title)")
      .eq("id", r.message_id)
      .maybeSingle();
    if (!msg || msg.kind !== "text" || msg.sender_id === r.user_id) {
      return NextResponse.json({ ok: true, skipped: "nothing to tell" });
    }
    const [{ data: member }, { data: blocks }] = await Promise.all([
      // They may have left the group since.
      admin.from("conversation_members").select("user_id").eq("conversation_id", r.conversation_id).eq("user_id", msg.sender_id).maybeSingle(),
      // Never across a block.
      admin
        .from("blocks")
        .select("blocker_id")
        .or(`and(blocker_id.eq.${r.user_id},blocked_id.eq.${msg.sender_id}),and(blocker_id.eq.${msg.sender_id},blocked_id.eq.${r.user_id})`)
        .limit(1),
    ]);
    if (!member || (blocks ?? []).length > 0) return NextResponse.json({ ok: true, skipped: "not for them" });
    const { name: actor, icon } = await personOf(r.user_id);
    const conv = Array.isArray(msg.conversation) ? msg.conversation[0] : msg.conversation;
    const what = msg.body ? `: ${msg.body.slice(0, 60)}` : msg.view_once ? " (view-once photo)" : "";
    recipients = [msg.sender_id];
    payload = conv?.is_group
      ? { title: conv.title ?? "Group", body: `${actor} reacted ${r.emoji} to your message${what}`, url: `/messages/${r.conversation_id}`, tag: `chat-${r.conversation_id}`, icon }
      : { title: actor, body: `Reacted ${r.emoji} to your message${what}`, url: `/messages/${r.conversation_id}`, tag: `chat-${r.conversation_id}`, icon };
  } else if (table === "comment_likes") {
    // "Rafi liked your comment: …", to the comment's author only. Push only:
    // no Activity entry, so app versions that don't know the type never show it.
    const r = record as CommentLikeRow;
    const { data: c } = await admin.from("comments").select("author_id, body, post_id, post:posts(author_id)").eq("id", r.comment_id).maybeSingle();
    if (!c || c.author_id === r.user_id) return NextResponse.json({ ok: true, skipped: "nothing to tell" });
    const postAuthor = (Array.isArray(c.post) ? c.post[0] : c.post)?.author_id ?? null;
    const [{ data: blocks }, { data: canSee }] = await Promise.all([
      admin
        .from("blocks")
        .select("blocker_id")
        .or(`and(blocker_id.eq.${r.user_id},blocked_id.eq.${c.author_id}),and(blocker_id.eq.${c.author_id},blocked_id.eq.${r.user_id})`)
        .limit(1),
      // They may no longer see the post (a private account they unfollowed).
      postAuthor ? admin.rpc("can_user_view_content_of", { p_viewer: c.author_id, p_author: postAuthor }) : Promise.resolve({ data: false }),
    ]);
    if ((blocks ?? []).length > 0 || !canSee) return NextResponse.json({ ok: true, skipped: "not for them" });
    const { name: actor, icon } = await personOf(r.user_id);
    recipients = [c.author_id];
    payload = {
      title: "Dheu",
      body: `${actor} liked your comment: ${c.body.slice(0, 80)}`,
      url: `/p/${c.post_id}`,
      tag: `notif-comment-like-${r.comment_id}`,
      icon,
    };
  } else if (table === "reports") {
    // "New report about a comment: Bullying or harassment", to the admin,
    // who reviews it in Admin → Reports on the website.
    const r = record as ReportRow;
    const { data: admins } = await admin.from("profiles").select("id").eq("is_admin", true);
    recipients = (admins ?? []).map((a) => a.id).filter((id) => id !== r.reporter_id);
    const noun = TARGET_NOUNS[r.target_type] ?? "post";
    payload = {
      title: "Dheu admin",
      body: `New report about ${noun === "account" ? "an" : "a"} ${noun}: ${reasonLabel(r.reason)}`,
      url: "/admin/reports",
      tag: "admin-reports",
    };
  } else if (table === "swipe_matches") {
    // "It's a match", to whoever swiped right first: the other one just saw
    // it on screen. Tapping it opens their new chat.
    const r = record as SwipeMatchRow;
    const other = r.matched_by === r.user_a ? r.user_b : r.user_a;
    const { name: actor, icon } = await personOf(r.matched_by);
    recipients = [other];
    payload = {
      title: "It's a match",
      body: `You and ${actor} both swiped right in Find friends. Say hi!`,
      url: r.conversation_id ? `/messages/${r.conversation_id}` : "/friends/matches",
      tag: `match-${r.matched_by}`,
      icon,
    };
  } else if (table === "announcements") {
    const a = record as AnnouncementRow;
    const [{ data: subs }, { data: devices }] = await Promise.all([
      admin.from("push_subscriptions").select("user_id"),
      admin.from("push_devices").select("user_id"),
    ]);
    recipients = [...new Set([...(subs ?? []), ...(devices ?? [])].map((s) => s.user_id))];
    payload = { title: "Dheu announcements", body: a.body.slice(0, 140), url: "/messages/announcements", tag: "announcement" };
  } else {
    return NextResponse.json({ ok: true, skipped: "unknown table" });
  }

  try {
    const result = await sendPush(recipients, payload);
    return NextResponse.json({ ok: true, ...result });
  } catch (err) {
    // Surface the reason (this endpoint is secret-protected, so it's safe to be specific).
    console.error("[push] send failed:", err);
    return NextResponse.json({ ok: false, error: (err as Error).message }, { status: 500 });
  }
}

// "rafi mentioned you in a comment: …" / "… in a post" / in a group chat.
async function mentionPayload(n: NotificationRow, actor: string, icon: string | undefined): Promise<PushPayload> {
  const admin = createAdminClient();
  if (n.conversation_id) {
    const [{ data: conv }, { data: msg }] = await Promise.all([
      admin.from("conversations").select("title").eq("id", n.conversation_id).maybeSingle(),
      n.message_id ? admin.from("messages").select("body").eq("id", n.message_id).maybeSingle() : Promise.resolve({ data: null }),
    ]);
    const text = msg?.body ? `: ${msg.body.slice(0, 100)}` : "";
    return {
      title: conv?.title ?? "Group",
      body: `${actor} mentioned you${text}`,
      url: `/messages/${n.conversation_id}`,
      tag: `chat-${n.conversation_id}`,
      icon,
    };
  }
  const url = n.post_id ? `/p/${n.post_id}` : "/activity";
  if (n.comment_id) {
    const { data: c } = await admin.from("comments").select("body").eq("id", n.comment_id).maybeSingle();
    const text = c?.body ? `: ${c.body.slice(0, 80)}` : "";
    return { title: "Dheu", body: `${actor} mentioned you in a comment${text}`, url, tag: `notif-mention-${n.comment_id}`, icon };
  }
  return { title: "Dheu", body: `${actor} mentioned you in a post`, url, tag: `notif-mention-${n.post_id}`, icon };
}
