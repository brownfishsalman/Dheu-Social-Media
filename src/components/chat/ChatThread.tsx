"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Send, Loader2, ImagePlus, X, Reply, Clock, Mic, Square, Trash2, MoreHorizontal, Copy, Undo2, Eye, EyeOff } from "lucide-react";
import type { RealtimeChannel } from "@supabase/supabase-js";
import { Sheet } from "@/components/ui/Sheet";
import { createClient } from "@/lib/supabase/client";
import { ensureRealtimeAuth } from "@/lib/supabase/realtime";
import { QUICK_REACTIONS, groupReactions, receiptLabel, type Receipt } from "@/lib/receipts";
import { publicUrl } from "@/lib/storage";
import { processImage, POST_MAX, ACCEPT_ATTR, newImagePath, type ProcessedImage } from "@/lib/images";
import { newAudioPath, baseMime, formatDuration } from "@/lib/audio";
import { Avatar } from "@/components/ui/Avatar";
import { GroupAvatar } from "@/components/chat/GroupAvatar";
import { MentionText, MentionMenu, useMentions } from "@/components/ui/Mentions";
import { VoiceNote, useVoiceRecorder } from "@/components/chat/VoiceNote";
import { loadOlderMessages } from "@/app/(app)/messages/actions";
import type { ChatMessage, ChatPeer, ReplyPreview } from "@/lib/data/chat";
import { eventPeople } from "@/lib/chat-events";
import type { BlockStatus } from "@/lib/data/profiles";
import { MESSAGE_PAGE, CHAT_PHOTO_DAYS, CHAT_VOICE_DAYS, VOICE_MAX_SECONDS } from "@/lib/constants";

type Props = {
  conversationId: string;
  meId: string;
  peer: ChatPeer | null; // the other person in a DM; null for groups
  group: { title: string; memberCount: number } | null;
  members: ChatPeer[]; // everyone whose name may appear (current members + people who left)
  mentionable: ChatPeer[]; // groups: the other current members, offered when typing @
  meUsername: string;
  initialMessages: ChatMessage[];
  blocked: BlockStatus;
};

const FORMER = "Former member";

function sameDay(a: string, b: string) {
  return new Date(a).toDateString() === new Date(b).toDateString();
}
function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return "Today";
  const y = new Date(today);
  y.setDate(today.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: d.getFullYear() !== today.getFullYear() ? "numeric" : undefined });
}
function clock(iso: string) {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}
function daysLeft(iso: string) {
  return Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000));
}
// One-line label for a quoted message, valid even after its media expired.
function replyLabel(r: Pick<ReplyPreview, "body" | "image_path" | "image_expires_at" | "audio_path" | "audio_expires_at">) {
  if (r.audio_path || r.audio_expires_at) return "🎤 Voice message";
  if (r.image_path || r.image_expires_at) return "📷 Photo";
  return r.body;
}
function toReplyPreview(m: ChatMessage): ReplyPreview {
  return {
    id: m.id,
    body: m.body,
    sender_id: m.sender_id,
    image_path: m.image_path,
    image_expires_at: m.image_expires_at,
    audio_path: m.audio_path,
    audio_expires_at: m.audio_expires_at,
  };
}
const REPLY_SELECT = "id, body, sender_id, image_path, image_expires_at, audio_path, audio_expires_at";
// Keep in sync with MESSAGE_COLUMNS in lib/data/chat.ts (server-only, so not importable here).
const MESSAGE_COLUMNS =
  "id, conversation_id, sender_id, body, story_id, image_path, image_expires_at, audio_path, audio_duration_ms, audio_expires_at, reply_to_id, kind, event, note_text, view_once, once_path, created_at";
const TYPING_SHOWN_MS = 5000; // how long "typing…" stays after the last signal
const TYPING_EVERY_MS = 2500; // how often we say we're typing while the text changes

// Drop an unsent message, and its quote from any reply to it.
function withoutMessage(list: ChatMessage[], id: string): ChatMessage[] {
  if (!list.some((m) => m.id === id || m.reply?.id === id)) return list;
  return list.filter((m) => m.id !== id).map((m) => (m.reply?.id === id ? { ...m, reply: null } : m));
}

// One member's reaction on one message: set, changed, or (emoji null) taken back.
function withReaction(list: ChatMessage[], messageId: string, userId: string, emoji: string | null): ChatMessage[] {
  if (!list.some((m) => m.id === messageId)) return list;
  return list.map((m) => {
    if (m.id !== messageId) return m;
    const others = m.reactions.filter((r) => r.user_id !== userId);
    return { ...m, reactions: emoji ? [...others, { user_id: userId, emoji }] : others };
  });
}

function withOpen(list: ChatMessage[], messageId: string, userId: string, openedAt: string): ChatMessage[] {
  if (!list.some((m) => m.id === messageId)) return list;
  return list.map((m) =>
    m.id === messageId && !m.opens.some((o) => o.user_id === userId) ? { ...m, opens: [...m.opens, { user_id: userId, opened_at: openedAt }] } : m,
  );
}

// "You added Rafi Karim, Tania Sultana" — rendered from the event so the
// viewer's own name becomes "You"; the stored body is the fallback.
function systemText(m: ChatMessage, nameOf: (id: string) => string): string {
  const e = m.event;
  if (!e) return m.body;
  const actor = nameOf(e.actor);
  const targets = (e.targets ?? []).map(nameOf).join(", ");
  switch (e.type) {
    case "created":
      return `${actor} created the group`;
    case "added":
      return `${actor} added ${targets || "someone"}`;
    case "removed":
      return `${actor} removed ${targets || "someone"}`;
    case "left":
      return `${actor} left`;
    case "renamed":
      return `${actor} renamed the group to “${e.title ?? ""}”`;
    default:
      return m.body;
  }
}

export function ChatThread({ conversationId, meId, peer, group, members, mentionable, meUsername, initialMessages, blocked }: Props) {
  const isGroup = group !== null;
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  // Names and avatars by user id; grows if someone unknown (e.g. just added) posts.
  const [people, setPeople] = useState(() => new Map(members.map((p) => [p.id, p])));
  const nameOf = useCallback(
    (id: string) => (id === meId ? "You" : (people.get(id)?.full_name ?? FORMER)),
    [meId, people],
  );
  const [text, setText] = useState("");
  const [photo, setPhoto] = useState<ProcessedImage | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [sending, setSending] = useState(false);
  const [hasOlder, setHasOlder] = useState(initialMessages.length >= MESSAGE_PAGE);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lightbox, setLightbox] = useState<string | null>(null);
  const [actionFor, setActionFor] = useState<ChatMessage | null>(null); // long-press / ⋯ menu
  const closeActions = useCallback(() => setActionFor(null), []);
  const [reactorsFor, setReactorsFor] = useState<ChatMessage | null>(null); // who reacted
  const closeReactors = useCallback(() => setReactorsFor(null), []);
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [typing, setTyping] = useState<Map<string, number>>(() => new Map()); // user id -> shown until (ms)
  const [viewOnce, setViewOnce] = useState(false); // send the attached photo as view-once
  const [onceView, setOnceView] = useState<string | null>(null); // an opened view-once photo (object URL)
  const [opening, setOpening] = useState<string | null>(null); // view-once message being opened
  const [now, setNow] = useState(() => Date.now());
  const typingChannel = useRef<RealtimeChannel | null>(null);
  const lastTypingSent = useRef(0);
  const listRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const rec = useVoiceRecorder(VOICE_MAX_SECONDS);
  const mentions = useMentions({ value: text, onChange: setText, inputRef, people: mentionable });
  const clip = rec.clip;

  const markRead = useCallback(async () => {
    const supabase = createClient();
    await supabase.rpc("mark_conversation_read", { p_conversation_id: conversationId }); // server clock
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView();
    markRead();
  }, [markRead]);

  // When the others last read this chat (0022), for "Seen" under my last message.
  const refreshReceipts = useCallback(async () => {
    const { data } = await createClient().rpc("read_receipts", { p_conversation_id: conversationId });
    if (data) setReceipts(data);
  }, [conversationId]);
  useEffect(() => {
    let cancelled = false;
    createClient()
      .rpc("read_receipts", { p_conversation_id: conversationId })
      .then(({ data }) => {
        if (!cancelled && data) setReceipts(data);
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId]);

  // "Seen 5m ago" ages; "typing…" fades out on its own.
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (typing.size === 0) return;
    const t = setInterval(() => {
      setTyping((m) => {
        const n = Date.now();
        const next = new Map([...m].filter(([, until]) => until > n));
        return next.size === m.size ? m : next;
      });
    }, 1000);
    return () => clearInterval(t);
  }, [typing.size]);

  // The database nudges "reads:<id>" whenever someone reads this chat; the
  // nudge is empty, so ask again (read_receipts checks membership).
  useEffect(() => {
    const supabase = createClient();
    const channel = supabase.channel(`reads:${conversationId}`);
    channel.on("broadcast", { event: "read" }, () => {
      refreshReceipts();
    });
    channel.subscribe();
    return () => {
      supabase.removeChannel(channel);
    };
  }, [conversationId, refreshReceipts]);

  // Typing: a private channel only this conversation's members may use (0023).
  useEffect(() => {
    const supabase = createClient();
    let cancelled = false;
    const channel = supabase.channel(`typing:${conversationId}`, { config: { private: true } });
    channel.on("broadcast", { event: "typing" }, ({ payload }) => {
      const id = (payload as { user_id?: string } | undefined)?.user_id;
      if (!id || id === meId) return;
      setTyping((m) => new Map(m).set(id, Date.now() + TYPING_SHOWN_MS));
    });
    ensureRealtimeAuth(supabase).then(() => {
      if (cancelled) return;
      channel.subscribe();
      typingChannel.current = channel;
    });
    return () => {
      cancelled = true;
      typingChannel.current = null;
      supabase.removeChannel(channel);
    };
  }, [conversationId, meId]);

  const announceTyping = useCallback(() => {
    const channel = typingChannel.current;
    const t = Date.now();
    if (!channel || channel.state !== "joined" || t - lastTypingSent.current < TYPING_EVERY_MS) return;
    lastTypingSent.current = t;
    channel.send({ type: "broadcast", event: "typing", payload: { user_id: meId } });
  }, [meId]);

  // Latest messages / people, readable from inside the realtime callback
  const messagesRef = useRef(messages);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);
  const peopleRef = useRef(people);
  useEffect(() => {
    peopleRef.current = people;
  }, [people]);

  // Realtime: new messages in this conversation
  useEffect(() => {
    const supabase = createClient();
    let cancelled = false;
    const channel = supabase.channel(`conversation:${conversationId}:${Math.random().toString(36).slice(2)}`);
    channel.on(
      "postgres_changes",
      { event: "INSERT", schema: "public", table: "messages", filter: `conversation_id=eq.${conversationId}` },
      async (payload) => {
        const row = payload.new as Omit<ChatMessage, "story" | "reply">;
        // Learn anyone new this message names: the sender, or the people a
        // system line mentions ("Salman added Rafi").
        const unknown = [...new Set(eventPeople(row))].filter((id) => id !== meId && !peopleRef.current.has(id));
        if (unknown.length > 0) {
          const { data: ps } = await supabase.from("profiles").select("id, username, full_name, avatar_path").in("id", unknown);
          if (ps && ps.length > 0) {
            setPeople((prev) => {
              const next = new Map(prev);
              for (const p of ps) next.set(p.id, p);
              return next;
            });
          }
        }
        let story: ChatMessage["story"] = null;
        if (row.story_id) {
          const { data } = await supabase.from("stories").select("image_path, author_id").eq("id", row.story_id).maybeSingle();
          story = data ?? null;
        }
        let reply: ReplyPreview | null = null;
        if (row.reply_to_id) {
          const local = messagesRef.current.find((m) => m.id === row.reply_to_id);
          if (local) reply = toReplyPreview(local);
          else {
            const { data } = await supabase.from("messages").select(REPLY_SELECT).eq("id", row.reply_to_id).maybeSingle();
            reply = data ?? null;
          }
        }
        setMessages((m) => (m.some((x) => x.id === row.id) ? m : [...m, { ...row, story, reply, reactions: [], opens: [] }]));
        // Their message arrived, so they've stopped typing.
        setTyping((t) => {
          if (!t.has(row.sender_id)) return t;
          const next = new Map(t);
          next.delete(row.sender_id);
          return next;
        });
        if (row.sender_id !== meId && document.visibilityState === "visible") markRead();
      },
    );
    // Unsent messages. Realtime can't filter DELETE events (they carry only the
    // id), so every open chat hears every unsend and drops the ones it holds.
    channel.on("postgres_changes", { event: "DELETE", schema: "public", table: "messages" }, (payload) => {
      const id = (payload.old as { id?: string }).id;
      if (id) setMessages((m) => withoutMessage(m, id));
    });
    // Reactions (0023). Added and changed ones are filtered to this chat; a
    // removal carries only its key, so every chat hears it and keeps its own.
    const onReaction = (payload: { new: Record<string, unknown> }) => {
      const r = payload.new as { message_id?: string; user_id?: string; emoji?: string };
      if (r.message_id && r.user_id && r.emoji) setMessages((m) => withReaction(m, r.message_id!, r.user_id!, r.emoji!));
    };
    const reactionFilter = { schema: "public", table: "message_reactions", filter: `conversation_id=eq.${conversationId}` };
    channel.on("postgres_changes", { event: "INSERT", ...reactionFilter }, onReaction);
    channel.on("postgres_changes", { event: "UPDATE", ...reactionFilter }, onReaction);
    channel.on("postgres_changes", { event: "DELETE", schema: "public", table: "message_reactions" }, (payload) => {
      const r = payload.old as { message_id?: string; user_id?: string };
      if (r.message_id && r.user_id) setMessages((m) => withReaction(m, r.message_id!, r.user_id!, null));
    });
    // Someone opened a view-once photo I sent.
    channel.on(
      "postgres_changes",
      { event: "INSERT", schema: "public", table: "message_opens", filter: `conversation_id=eq.${conversationId}` },
      (payload) => {
        const o = payload.new as { message_id?: string; user_id?: string; opened_at?: string };
        if (o.message_id && o.user_id && o.opened_at) setMessages((m) => withOpen(m, o.message_id!, o.user_id!, o.opened_at!));
      },
    );
    ensureRealtimeAuth(supabase).then(() => {
      if (cancelled) return;
      channel.subscribe((status, err) => {
        if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") console.warn("[chat realtime]", status, err?.message ?? "");
      });
    });
    return () => {
      cancelled = true;
      supabase.removeChannel(channel);
    };
  }, [conversationId, meId, markRead]);

  // Keep pinned to the bottom when new messages arrive (if already near the bottom)
  const lastId = messages[messages.length - 1]?.id;
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 200;
    if (nearBottom) bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [lastId]);

  async function pickPhoto(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setError(null);
    setPreparing(true);
    try {
      if (photo) URL.revokeObjectURL(photo.previewUrl);
      setPhoto(await processImage(file, POST_MAX));
      inputRef.current?.focus();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't read that photo.");
    } finally {
      setPreparing(false);
    }
  }

  const startReply = useCallback((m: ChatMessage) => {
    setReplyTo(m);
    inputRef.current?.focus();
  }, []);

  // Unsend: delete my message for everyone, then its photo / voice file.
  async function unsend(m: ChatMessage) {
    setActionFor(null);
    if (!confirm("Unsend this message? It will be removed for everyone.")) return;
    const supabase = createClient();
    const { data, error: err } = await supabase.from("messages").delete().eq("id", m.id).select("id");
    if (err || !data?.length) return setError("Couldn't unsend that message.");
    setMessages((list) => withoutMessage(list, m.id));
    if (replyTo?.id === m.id) setReplyTo(null);
    const files = [m.image_path, m.audio_path].filter((p): p is string => Boolean(p));
    if (files.length) await supabase.storage.from("chat").remove(files);
    if (m.once_path) await supabase.storage.from("once").remove([m.once_path]);
  }

  // React, change the emoji, or (null) take it back. Shown at once; undone if it fails.
  const react = useCallback(
    async (m: ChatMessage, emoji: string | null) => {
      setActionFor(null);
      setReactorsFor(null);
      const before = m.reactions.find((r) => r.user_id === meId)?.emoji ?? null;
      setMessages((list) => withReaction(list, m.id, meId, emoji));
      const { error: err } = await createClient().rpc("react_to_message", { p_message_id: m.id, p_emoji: emoji });
      if (err) {
        setMessages((list) => withReaction(list, m.id, meId, before));
        setError("Couldn't react to that message.");
      }
    },
    [meId],
  );

  // Double-tap: ❤️, or take a ❤️ back.
  const toggleHeart = useCallback(
    (m: ChatMessage) => {
      const mineNow = m.reactions.find((r) => r.user_id === meId)?.emoji;
      react(m, mineNow === "❤️" ? null : "❤️");
    },
    [meId, react],
  );

  // A view-once photo sent to me: record the open, then fetch the file (the
  // database lets me download it for two minutes after opening).
  const openOnce = useCallback(
    async (m: ChatMessage) => {
      if (opening) return;
      setOpening(m.id);
      setError(null);
      const supabase = createClient();
      const { data: path, error: err } = await supabase.rpc("open_view_once", { p_message_id: m.id });
      if (err || !path) {
        setOpening(null);
        const why = err?.message ?? "";
        if (/ALREADY_OPENED/.test(why)) setMessages((list) => withOpen(list, m.id, meId, new Date().toISOString()));
        return setError(
          /ALREADY_OPENED/.test(why) ? "You've already opened this photo." : /EXPIRED/.test(why) ? "This photo has expired." : "Couldn't open this photo.",
        );
      }
      const { data: blob, error: dlErr } = await supabase.storage.from("once").download(path);
      setOpening(null);
      if (dlErr || !blob) return setError("Couldn't load the photo. Try again within two minutes.");
      setMessages((list) => withOpen(list, m.id, meId, new Date().toISOString()));
      setOnceView(URL.createObjectURL(blob));
    },
    [meId, opening],
  );

  function closeOnce() {
    if (onceView) URL.revokeObjectURL(onceView);
    setOnceView(null);
  }

  async function copyText(m: ChatMessage) {
    setActionFor(null);
    try {
      await navigator.clipboard.writeText(m.body);
    } catch {}
  }

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const body = text.trim();
    if ((!body && !photo && !clip) || sending) return;
    setSending(true);
    setError(null);
    const supabase = createClient();

    // A view-once photo goes to the private "once" bucket instead (0023).
    let image_path: string | null = null;
    let once_path: string | null = null;
    if (photo) {
      const path = newImagePath(meId);
      const { error: upErr } = await supabase.storage
        .from(viewOnce ? "once" : "chat")
        .upload(path, photo.blob, { contentType: "image/jpeg", cacheControl: viewOnce ? "0" : "604800" });
      if (upErr) {
        setSending(false);
        return setError("Couldn't upload the photo.");
      }
      if (viewOnce) once_path = path;
      else image_path = path;
    }

    let audio_path: string | null = null;
    let audio_duration_ms: number | null = null;
    if (clip) {
      audio_path = newAudioPath(meId, clip.mime);
      audio_duration_ms = Math.round(clip.durationMs);
      const { error: upErr } = await supabase.storage
        .from("chat")
        .upload(audio_path, clip.blob, { contentType: baseMime(clip.mime), cacheControl: "604800" });
      if (upErr) {
        setSending(false);
        return setError("Couldn't upload the voice message.");
      }
    }

    // Media expiry (image_expires_at / audio_expires_at) and view_once are stamped by the database.
    const { data, error: err } = await supabase
      .from("messages")
      .insert({
        conversation_id: conversationId,
        sender_id: meId,
        body,
        image_path,
        once_path,
        audio_path,
        audio_duration_ms,
        reply_to_id: replyTo?.id ?? null,
      })
      .select(MESSAGE_COLUMNS)
      .single();
    setSending(false);
    if (err || !data) {
      const uploaded = [image_path, audio_path].filter((p): p is string => Boolean(p));
      if (uploaded.length) await supabase.storage.from("chat").remove(uploaded);
      if (once_path) await supabase.storage.from("once").remove([once_path]);
      return setError(/blocked|violates/i.test(err?.message ?? "") ? "You can't message this person." : "Couldn't send. Check your connection.");
    }
    const reply = replyTo ? toReplyPreview(replyTo) : null;
    // Members can only insert kind = 'text', so event is always null here.
    setMessages((m) => (m.some((x) => x.id === data.id) ? m : [...m, { ...data, event: null, story: null, reply, reactions: [], opens: [] }]));
    setText("");
    setReplyTo(null);
    if (photo) URL.revokeObjectURL(photo.previewUrl);
    setPhoto(null);
    setViewOnce(false);
    rec.reset();
    inputRef.current?.focus();
    markRead();
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }));
  }

  async function loadOlder() {
    if (loadingOlder || messages.length === 0) return;
    setLoadingOlder(true);
    const el = listRef.current;
    const prevHeight = el?.scrollHeight ?? 0;
    const older = await loadOlderMessages(conversationId, messages[0].created_at);
    setMessages((m) => [...older, ...m]);
    setHasOlder(older.length >= MESSAGE_PAGE);
    setLoadingOlder(false);
    requestAnimationFrame(() => {
      if (el) el.scrollTop = el.scrollHeight - prevHeight;
    });
  }

  const canSend = blocked === null;

  // Typing in the composer re-renders this component on every keystroke. Build
  // the (possibly long) message list only when its inputs change, so React can
  // skip it entirely while someone types.
  const list = useMemo(() => {
    const byId = new Map(messages.map((m) => [m.id, m]));
    return messages.map((m, i) => {
      const mine = m.sender_id === meId;
      const prev = messages[i - 1];
      const next = messages[i + 1];
      const showDay = !prev || !sameDay(prev.created_at, m.created_at);
      if (m.kind === "system") {
        return (
          <div key={m.id}>
            {showDay && <div className="my-3 text-center text-xs font-medium text-ink-faint">{dayLabel(m.created_at)}</div>}
            <p className="my-2 px-6 text-center text-xs text-ink-faint" title={clock(m.created_at)}>
              {systemText(m, nameOf)}
            </p>
          </div>
        );
      }
      // Consecutive bubbles from one person within 5 minutes form a run; a
      // day change or a system line breaks it.
      const grouped = prev && prev.kind !== "system" && prev.sender_id === m.sender_id && !showDay &&
        new Date(m.created_at).getTime() - new Date(prev.created_at).getTime() < 5 * 60_000;
      // In groups: name above the first bubble of a run, avatar beside the last.
      const endOfRun = !next || next.kind === "system" || next.sender_id !== m.sender_id || !sameDay(m.created_at, next.created_at) ||
        new Date(next.created_at).getTime() - new Date(m.created_at).getTime() >= 5 * 60_000;
      return (
        <div key={m.id}>
          {showDay && <div className="my-3 text-center text-xs font-medium text-ink-faint">{dayLabel(m.created_at)}</div>}
          <Bubble
            m={m}
            mine={mine}
            meId={meId}
            isGroup={isGroup}
            grouped={grouped}
            sender={isGroup && !mine ? { name: nameOf(m.sender_id), avatar: people.get(m.sender_id) ?? null, showName: !grouped, showAvatar: endOfRun } : null}
            nameOf={nameOf}
            meUsername={meUsername}
            onReply={canSend ? () => startReply(m) : undefined}
            onActions={canSend || m.body || mine ? () => setActionFor(m) : undefined}
            onDoubleTap={canSend ? () => toggleHeart(m) : undefined}
            onShowReactions={() => setReactorsFor(m)}
            onOpenImage={setLightbox}
            onOpenOnce={() => openOnce(m)}
            opening={opening === m.id}
            scrollToReply={(id) => byId.has(id) && document.getElementById(`msg-${id}`)?.scrollIntoView({ behavior: "smooth", block: "center" })}
          />
        </div>
      );
    });
  }, [messages, meId, meUsername, isGroup, people, nameOf, canSend, startReply, toggleHeart, openOnce, opening]);

  // "Sent" / "Seen" under my message when it's the newest in the chat, as on Instagram.
  const lastText = useMemo(() => [...messages].reverse().find((m) => m.kind !== "system"), [messages]);
  const receipt =
    lastText && lastText.sender_id === meId
      ? receiptLabel(
          lastText.created_at,
          receipts,
          isGroup,
          isGroup ? Math.max(0, (group?.memberCount ?? 1) - 1) : 1,
          (id) => (people.get(id)?.full_name ?? "Someone").split(" ")[0],
          now,
        )
      : null;
  // Only people I can see (members who blocked me never appear).
  const typingPeople = [...typing.keys()].filter((id) => id !== meId && (!isGroup || people.has(id)));

  return (
    <div className="flex h-[calc(100dvh-3.5rem-3rem-3.5rem-env(safe-area-inset-bottom))] flex-col md:h-[calc(100dvh-3.5rem)]">
      <div ref={listRef} className="flex-1 overflow-y-auto px-3 py-3">
        {hasOlder && (
          <div className="mb-3 flex justify-center">
            <button type="button" onClick={loadOlder} disabled={loadingOlder} className="btn-ghost text-sm">
              {loadingOlder ? <Loader2 className="size-4 animate-spin" /> : "Load earlier messages"}
            </button>
          </div>
        )}

        {messages.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-16 text-center">
            {group ? (
              <>
                <GroupAvatar members={members.filter((p) => p.id !== meId)} size={72} />
                <p className="font-semibold">{group.title}</p>
                <p className="text-sm text-ink-muted">{group.memberCount} members · Say hi 👋</p>
              </>
            ) : peer ? (
              <>
                <Avatar path={peer.avatar_path} name={peer.full_name} size={72} />
                <p className="font-semibold">{peer.full_name}</p>
                <p className="text-sm text-ink-muted">@{peer.username} · Say hi 👋</p>
              </>
            ) : null}
          </div>
        )}

        {list}
        {receipt && <p className="mt-1 pr-1 text-right text-[11px] font-medium text-ink-faint">{receipt}</p>}
        {typingPeople.length > 0 && (
          <div className="mt-2 flex items-end gap-2" aria-live="polite">
            {isGroup && (
              <span className="w-7 shrink-0">
                <Avatar path={people.get(typingPeople[0])?.avatar_path} name={people.get(typingPeople[0])?.full_name ?? ""} size={28} />
              </span>
            )}
            <span className="flex items-center gap-1 rounded-2xl rounded-bl-md bg-surface-2 px-3.5 py-3" aria-label="typing">
              <span className="size-1.5 animate-bounce rounded-full bg-ink-faint [animation-delay:-0.3s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-ink-faint [animation-delay:-0.15s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-ink-faint" />
            </span>
            {isGroup && (
              <span className="pb-1 text-xs text-ink-faint">
                {typingPeople.map((id) => (people.get(id)?.full_name ?? "Someone").split(" ")[0]).join(", ")} typing…
              </span>
            )}
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {!canSend ? (
        <p className="border-t border-line bg-surface px-4 py-4 text-center text-sm text-ink-muted">
          {blocked === "by_me" || blocked === "both" ? "You blocked this member. Unblock them in Settings to message again." : "You can't message this member."}
        </p>
      ) : (
        <form onSubmit={send} className="border-t border-line bg-surface">
          {replyTo && (
            <div className="flex items-center gap-2 border-l-4 border-brand bg-surface-2/60 px-3 py-2 text-sm">
              <Reply className="size-4 shrink-0 text-brand" />
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold text-brand">{nameOf(replyTo.sender_id)}</p>
                <p className="truncate text-ink-muted">{replyLabel(replyTo)}</p>
              </div>
              <button type="button" onClick={() => setReplyTo(null)} aria-label="Cancel reply" className="rounded-full p-1 text-ink-muted hover:bg-surface-2">
                <X className="size-4" />
              </button>
            </div>
          )}
          {photo && (
            <div className="flex items-center gap-3 px-3 pt-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={photo.previewUrl} alt="" className="h-20 w-20 rounded-lg object-cover" />
              <div className="flex-1 space-y-1.5">
                <button
                  type="button"
                  onClick={() => setViewOnce((v) => !v)}
                  aria-pressed={viewOnce}
                  className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold transition ${
                    viewOnce ? "border-brand bg-brand text-brand-ink" : "border-line text-ink-muted hover:bg-surface-2"
                  }`}
                >
                  <Eye className="size-3.5" /> View once
                </button>
                <p className="text-xs text-ink-muted">
                  <Clock className="mr-1 inline size-3.5" />
                  {viewOnce ? "They can open it once. You won't see it again." : `Disappears ${CHAT_PHOTO_DAYS} days after sending.`}
                </p>
              </div>
              <button
                type="button"
                onClick={() => {
                  URL.revokeObjectURL(photo.previewUrl);
                  setPhoto(null);
                  setViewOnce(false);
                }}
                aria-label="Remove photo"
                className="rounded-full p-1.5 text-ink-muted hover:bg-surface-2"
              >
                <X className="size-4" />
              </button>
            </div>
          )}
          {clip && (
            <div className="px-3 pt-2">
              <div className="flex items-center gap-2">
                <VoiceNote src={clip.previewUrl} durationMs={clip.durationMs} className="min-w-0 flex-1 rounded-xl bg-surface-2 px-3 py-1.5 text-ink" />
                <button type="button" onClick={rec.reset} aria-label="Remove voice message" className="rounded-full p-1.5 text-ink-muted hover:bg-surface-2">
                  <X className="size-4" />
                </button>
              </div>
              <p className="mt-1 text-xs text-ink-muted">
                <Clock className="mr-1 inline size-3.5" />
                Disappears {CHAT_VOICE_DAYS} days after sending.
              </p>
            </div>
          )}
          {rec.recording ? (
            <div className="flex items-center gap-3 px-3 py-2">
              <button
                type="button"
                onClick={rec.cancel}
                aria-label="Discard recording"
                className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-danger"
              >
                <Trash2 className="size-5" />
              </button>
              <span className="size-2.5 shrink-0 animate-pulse rounded-full bg-danger" aria-hidden />
              <span className="flex-1 text-sm tabular-nums" aria-live="polite">
                {formatDuration(rec.elapsedMs)}
                <span className="text-ink-faint"> / {formatDuration(VOICE_MAX_SECONDS * 1000)}</span>
              </span>
              <button type="button" onClick={rec.stop} aria-label="Stop recording" className="btn-primary rounded-full p-2.5">
                <Square className="size-5 fill-current" />
              </button>
            </div>
          ) : (
            <div className="relative flex items-center gap-2 px-3 py-2">
              {isGroup && <MentionMenu m={mentions} />}
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                disabled={preparing || sending || Boolean(clip)}
                aria-label="Send a photo"
                className="rounded-full p-2 text-ink-muted hover:bg-surface-2 hover:text-ink disabled:opacity-50"
              >
                {preparing ? <Loader2 className="size-5 animate-spin" /> : <ImagePlus className="size-5" />}
              </button>
              <input ref={fileRef} type="file" accept={ACCEPT_ATTR} className="hidden" onChange={pickPhoto} />
              <input
                ref={inputRef}
                value={text}
                onChange={(e) => {
                  setText(e.target.value);
                  if (e.target.value.trim()) announceTyping();
                  if (isGroup) mentions.sync();
                }}
                onSelect={isGroup ? mentions.sync : undefined}
                onKeyDown={isGroup ? mentions.onKeyDown : undefined}
                onBlur={mentions.close}
                maxLength={2000}
                placeholder={photo || clip ? "Add a caption…" : "Message…"}
                autoComplete="off"
                enterKeyHint="send"
                className="input rounded-full"
              />
              {/* Mic until there's something to send, then the send button (WhatsApp-style) */}
              {!text.trim() && !photo && !clip && rec.supported ? (
                <button
                  type="button"
                  onClick={rec.start}
                  disabled={sending || preparing}
                  aria-label="Record a voice message"
                  className="btn-primary rounded-full p-2.5"
                >
                  <Mic className="size-5" />
                </button>
              ) : (
                <button
                  type="submit"
                  disabled={sending || (!text.trim() && !photo && !clip)}
                  aria-label="Send"
                  className="btn-primary rounded-full p-2.5"
                >
                  {sending ? <Loader2 className="size-5 animate-spin" /> : <Send className="size-5" />}
                </button>
              )}
            </div>
          )}
          {(error || rec.error) && <p className="px-4 pb-2 text-xs text-danger">{error ?? rec.error}</p>}
        </form>
      )}

      {actionFor && (
        <Sheet title={actionFor.sender_id === meId ? "Your message" : "Message"} onClose={closeActions}>
          {canSend && actionFor.kind === "text" && (
            <div className="mb-3 flex justify-between rounded-xl border border-line px-2 py-1.5">
              {QUICK_REACTIONS.map((e) => {
                const chosen = actionFor.reactions.find((r) => r.user_id === meId)?.emoji === e;
                return (
                  <button
                    key={e}
                    type="button"
                    onClick={() => react(actionFor, chosen ? null : e)}
                    aria-label={chosen ? `Remove ${e}` : `React ${e}`}
                    aria-pressed={chosen}
                    className={`rounded-full p-1.5 text-2xl leading-none transition hover:scale-110 ${chosen ? "bg-brand-soft" : "hover:bg-surface-2"}`}
                  >
                    {e}
                  </button>
                );
              })}
            </div>
          )}
          <div className="divide-y divide-line overflow-hidden rounded-xl border border-line">
            {canSend && (
              <button
                type="button"
                onClick={() => {
                  startReply(actionFor);
                  setActionFor(null);
                }}
                className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface-2"
              >
                <Reply className="size-4" /> Reply
              </button>
            )}
            {actionFor.body && (
              <button type="button" onClick={() => copyText(actionFor)} className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface-2">
                <Copy className="size-4" /> Copy text
              </button>
            )}
            {actionFor.sender_id === meId && actionFor.kind === "text" && (
              <button type="button" onClick={() => unsend(actionFor)} className="flex w-full items-center gap-3 px-4 py-3 text-left text-danger hover:bg-surface-2">
                <Undo2 className="size-4" /> Unsend
              </button>
            )}
          </div>
        </Sheet>
      )}

      {reactorsFor && (
        <Sheet title="Reactions" onClose={closeReactors}>
          <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
            {reactorsFor.reactions.map((r) => {
              const own = r.user_id === meId;
              const person = people.get(r.user_id);
              return (
                <li key={r.user_id}>
                  <button
                    type="button"
                    disabled={!own}
                    onClick={() => react(reactorsFor, null)}
                    className="flex w-full items-center gap-3 px-4 py-2.5 text-left enabled:hover:bg-surface-2"
                  >
                    <Avatar path={person?.avatar_path} name={own ? "You" : (person?.full_name ?? FORMER)} size={32} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-semibold">{own ? "You" : (person?.full_name ?? FORMER)}</span>
                      {own && <span className="block text-xs text-ink-muted">Tap to remove</span>}
                    </span>
                    <span className="text-2xl leading-none">{r.emoji}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Sheet>
      )}

      {onceView && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black p-4"
          onClick={closeOnce}
          onContextMenu={(e) => e.preventDefault()}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={onceView} alt="" draggable={false} className="pointer-events-none max-h-full max-w-full rounded-lg object-contain select-none" />
          <p className="absolute top-5 left-1/2 -translate-x-1/2 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold text-white">
            View once · closes for good
          </p>
          <button type="button" aria-label="Close" className="absolute top-4 right-4 rounded-full bg-white/15 p-2 text-white">
            <X className="size-6" />
          </button>
        </div>
      )}

      {lightbox && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/90 p-4" onClick={() => setLightbox(null)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={lightbox} alt="" className="max-h-full max-w-full rounded-lg object-contain" />
          <button type="button" aria-label="Close" className="absolute top-4 right-4 rounded-full bg-white/15 p-2 text-white">
            <X className="size-6" />
          </button>
        </div>
      )}
    </div>
  );
}

// One message. Swipe horizontally (touch) or hover → reply icon (mouse) to reply.
type SenderInfo = { name: string; avatar: ChatPeer | null; showName: boolean; showAvatar: boolean };

function Bubble({
  m,
  mine,
  meId,
  isGroup,
  grouped,
  sender,
  nameOf,
  meUsername,
  onReply,
  onActions,
  onDoubleTap,
  onShowReactions,
  onOpenImage,
  onOpenOnce,
  opening,
  scrollToReply,
}: {
  m: ChatMessage;
  mine: boolean;
  meId: string;
  isGroup: boolean;
  grouped: boolean;
  sender: SenderInfo | null; // set for other people's messages in a group
  nameOf: (id: string) => string;
  meUsername: string;
  onReply?: () => void;
  onActions?: () => void; // long-press (touch), right-click or ⋯: react / reply / copy / unsend
  onDoubleTap?: () => void; // ❤️
  onShowReactions: () => void;
  onOpenImage: (src: string) => void;
  onOpenOnce: () => void;
  opening: boolean;
  scrollToReply: (id: string) => void;
}) {
  const [dx, setDx] = useState(0);
  const start = useRef<{ x: number; y: number; id: number } | null>(null);
  const fired = useRef(false);
  const press = useRef<ReturnType<typeof setTimeout> | null>(null);
  const longPressed = useRef(false);
  const moved = useRef(false);
  const lastTap = useRef(0);
  const pointerKind = useRef("mouse");
  const THRESHOLD = 56;

  function cancelPress() {
    if (press.current) clearTimeout(press.current);
    press.current = null;
  }
  useEffect(() => {
    const timer = press;
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, []);

  function onPointerDown(e: React.PointerEvent) {
    pointerKind.current = e.pointerType;
    if (e.pointerType === "mouse" || (!onReply && !onActions && !onDoubleTap)) return;
    start.current = { x: e.clientX, y: e.clientY, id: e.pointerId };
    fired.current = false;
    longPressed.current = false;
    moved.current = false;
    cancelPress();
    // Holding still for half a second opens the message actions.
    if (onActions) {
      press.current = setTimeout(() => {
        press.current = null;
        start.current = null;
        longPressed.current = true;
        setDx(0);
        if (navigator.vibrate) navigator.vibrate(10);
        onActions();
      }, 500);
    }
  }
  function onPointerMove(e: React.PointerEvent) {
    const s = start.current;
    if (!s || e.pointerId !== s.id) return;
    const ddx = e.clientX - s.x;
    const ddy = e.clientY - s.y;
    if (Math.abs(ddx) > 8 || Math.abs(ddy) > 8) {
      moved.current = true;
      cancelPress();
    }
    if (!onReply) return;
    if (Math.abs(ddy) > Math.abs(ddx) + 8) return; // vertical scroll wins
    // Swipe toward the centre: right for their bubbles, left for mine
    const dir = mine ? -1 : 1;
    const amount = Math.max(0, ddx * dir);
    setDx(Math.min(amount, 90) * dir);
    if (amount >= THRESHOLD && !fired.current) {
      fired.current = true;
      if (navigator.vibrate) navigator.vibrate(10);
    }
  }
  function onPointerEnd() {
    cancelPress();
    if (fired.current && onReply) onReply();
    // Two quick taps in place: ❤️ (as on Instagram).
    else if (start.current && !moved.current && !longPressed.current && onDoubleTap) {
      const t = Date.now();
      if (t - lastTap.current < 300) {
        lastTap.current = 0;
        if (navigator.vibrate) navigator.vibrate(10);
        onDoubleTap();
      } else lastTap.current = t;
    }
    start.current = null;
    setDx(0);
  }

  const storySrc = m.story ? publicUrl("stories", m.story.image_path) : null;
  const imgSrc = m.image_path ? publicUrl("chat", m.image_path) : null;
  // A view-once photo has no image_path; it has its own states below.
  const photoExpired = !m.view_once && !m.image_path && m.image_expires_at !== null;
  const openedByMe = m.opens.some((o) => o.user_id === meId);
  const reactionGroups = groupReactions(m.reactions);
  const audioSrc = m.audio_path ? publicUrl("chat", m.audio_path) : null;
  const voiceExpired = !m.audio_path && m.audio_expires_at !== null;
  const mediaExpiresAt = imgSrc ? m.image_expires_at : audioSrc ? m.audio_expires_at : null;

  return (
    <div
      id={`msg-${m.id}`}
      className={`group relative flex touch-pan-y select-none [-webkit-touch-callout:none] ${mine ? "justify-end" : "justify-start"} ${grouped ? "mt-0.5" : "mt-2"}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      onContextMenu={(e) => {
        if (!onActions) return;
        e.preventDefault();
        onActions();
      }}
      onDoubleClick={(e) => {
        // Touch has its own double-tap above; this is the mouse.
        if (!onDoubleTap || pointerKind.current !== "mouse" || (e.target as HTMLElement).closest("button, a, audio")) return;
        onDoubleTap();
      }}
      style={{ transform: dx ? `translateX(${dx}px)` : undefined, transition: dx ? "none" : "transform 150ms ease-out" }}
    >
      {/* Group chats: the sender's avatar beside the last bubble of their run (a spacer otherwise) */}
      {sender && (
        <span className="mr-2 w-7 shrink-0 self-end">
          {sender.showAvatar && (
            <Avatar path={sender.avatar?.avatar_path} name={sender.avatar?.full_name ?? sender.name} size={28} />
          )}
        </span>
      )}

      <div className={`relative max-w-[78%] ${mine ? "items-end" : "items-start"} flex flex-col`}>
        {/* Beside the bubble: reply (also revealed by the swipe) and ⋯, on hover */}
        {(onReply || onActions) && (
          <span
            className={`absolute top-1/2 flex -translate-y-1/2 items-center ${mine ? "right-full mr-1 flex-row-reverse" : "left-full ml-1"}`}
          >
            {onReply && (
              <button
                type="button"
                onClick={onReply}
                aria-label="Reply"
                className={`rounded-full p-1.5 text-ink-faint transition hover:bg-surface-2 hover:text-ink ${
                  Math.abs(dx) >= THRESHOLD ? "text-brand opacity-100" : "opacity-0 group-hover:opacity-100"
                }`}
              >
                <Reply className="size-4" />
              </button>
            )}
            {onActions && (
              <button
                type="button"
                onClick={onActions}
                aria-label="More actions"
                className="hidden rounded-full p-1.5 text-ink-faint opacity-0 transition group-hover:opacity-100 hover:bg-surface-2 hover:text-ink md:block"
              >
                <MoreHorizontal className="size-4" />
              </button>
            )}
          </span>
        )}
        {sender?.showName && <span className="mb-0.5 ml-1 text-xs font-semibold text-ink-muted">{sender.name}</span>}
        {m.note_text && (
          <div className={`mb-1 flex flex-col gap-1 text-xs text-ink-faint ${mine ? "items-end" : "items-start"}`}>
            <span>{mine ? "You replied to their note" : "Replied to your note"}</span>
            <span className="max-w-full break-words rounded-2xl border border-line bg-surface px-3 py-1.5 text-ink-muted">
              {m.note_text}
            </span>
          </div>
        )}
        {m.story_id && (
          <div className={`mb-1 flex items-end gap-2 text-xs text-ink-faint ${mine ? "flex-row-reverse" : ""}`}>
            {storySrc ? (
              <Link href={`/stories/${m.story!.author_id}`} className="block h-20 w-12 overflow-hidden rounded-lg border border-line bg-surface-2">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={storySrc} alt="" className="h-full w-full object-cover" />
              </Link>
            ) : (
              <span className="flex h-20 w-12 items-center justify-center rounded-lg border border-dashed border-line text-center text-[10px] leading-tight">story expired</span>
            )}
            <span>{mine ? "You replied to their story" : "Replied to your story"}</span>
          </div>
        )}

        <div
          title={clock(m.created_at)}
          className={`overflow-hidden rounded-2xl text-[15px] ${mine ? "rounded-br-md bg-brand text-brand-ink" : "rounded-bl-md bg-surface-2 text-ink"}`}
        >
          {m.reply && (
            <button
              type="button"
              onClick={() => scrollToReply(m.reply!.id)}
              className={`mx-2 mt-2 block w-[calc(100%-1rem)] rounded-lg border-l-2 px-2.5 py-1.5 text-left text-xs ${
                mine ? "border-brand-ink/60 bg-black/10" : "border-brand bg-surface"
              }`}
            >
              <span className={`block font-semibold ${mine ? "opacity-90" : "text-brand"}`}>
                {nameOf(m.reply.sender_id)}
              </span>
              <span className="line-clamp-2 opacity-80">{replyLabel(m.reply)}</span>
            </button>
          )}

          {imgSrc && (
            <button type="button" onClick={() => onOpenImage(imgSrc)} className="block" aria-label="Open photo">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={imgSrc} alt="" loading="lazy" className="max-h-80 w-auto max-w-full object-cover" draggable={false} />
            </button>
          )}
          {m.view_once &&
            (mine ? (
              <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium">
                {m.opens.length > 0 ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                {m.opens.length === 0 ? "View-once photo" : isGroup ? `Opened by ${m.opens.length}` : "Opened"}
              </p>
            ) : openedByMe ? (
              <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
                <EyeOff className="size-4" /> Opened
              </p>
            ) : m.once_path ? (
              <button type="button" onClick={onOpenOnce} disabled={opening} className="flex items-center gap-1.5 px-3.5 py-2 text-sm font-semibold">
                {opening ? <Loader2 className="size-4 animate-spin" /> : <Eye className="size-4" />} Tap to view photo
              </button>
            ) : (
              <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
                <Clock className="size-3.5" /> Photo expired
              </p>
            ))}
          {photoExpired && (
            <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
              <Clock className="size-3.5" /> Photo expired
            </p>
          )}
          {audioSrc && (
            <VoiceNote src={audioSrc} durationMs={m.audio_duration_ms} mine={mine} className="w-64 max-w-full px-3 py-2" />
          )}
          {voiceExpired && (
            <p className="flex items-center gap-1.5 px-3.5 py-2 text-sm italic opacity-70">
              <Clock className="size-3.5" /> Voice message expired
            </p>
          )}
          {m.body && (
            <p className="whitespace-pre-wrap break-words px-3.5 py-2">
              <MentionText
                text={m.body}
                me={meUsername}
                className={mine ? "font-semibold underline underline-offset-2" : "font-semibold text-brand"}
                meClassName="rounded bg-brand/15 px-0.5 font-semibold text-brand"
              />
            </p>
          )}
          {mediaExpiresAt && (
            <p className={`px-3 pb-1.5 text-[10px] ${mine ? "text-brand-ink/70" : "text-ink-faint"} ${m.body ? "-mt-1" : "pt-1"}`}>
              disappears in {daysLeft(mediaExpiresAt)}d
            </p>
          )}
        </div>

        {/* Reactions, tucked under the bubble's edge as on Instagram */}
        {reactionGroups.length > 0 && (
          <button
            type="button"
            onClick={onShowReactions}
            aria-label="See who reacted"
            className={`relative z-10 -mt-1.5 flex items-center gap-0.5 rounded-full bg-surface-2 px-1.5 py-0.5 text-sm leading-none ring-2 ring-canvas ${mine ? "mr-2" : "ml-2"}`}
          >
            {reactionGroups.slice(0, 3).map((g) => (
              <span key={g.emoji}>{g.emoji}</span>
            ))}
            {m.reactions.length > 1 && <span className="ml-0.5 text-xs font-semibold text-ink-muted">{m.reactions.length}</span>}
          </button>
        )}
      </div>
    </div>
  );
}
