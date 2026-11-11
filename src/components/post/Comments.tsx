"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Trash2, Send, Loader2, Heart, X } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { Avatar } from "@/components/ui/Avatar";
import { MentionText, MentionMenu, useMentions } from "@/components/ui/Mentions";
import { timeAgo } from "@/lib/time";
import type { CommentItem, PostAuthor } from "@/lib/data/posts";
import type { Viewer } from "@/components/post/PostCard";

type Props = {
  postId: string;
  postAuthorId: string;
  initialComments: CommentItem[];
  viewer: Viewer & PostAuthor;
};

// Comments with one level of replies and likes, as on Instagram (0024). A
// reply starts with "@name", so its author hears about it as a mention.
export function Comments({ postId, postAuthorId, initialComments, viewer }: Props) {
  const router = useRouter();
  const [comments, setComments] = useState(initialComments);
  const [body, setBody] = useState("");
  const [replyTo, setReplyTo] = useState<{ id: string; username: string } | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const mentions = useMentions({ value: body, onChange: setBody, inputRef });

  const tops = comments.filter((c) => !c.parent_id);
  const repliesTo = (id: string) => comments.filter((c) => c.parent_id === id);

  function startReply(c: CommentItem) {
    // Replies hang off the top comment; replying to a reply names its author.
    setReplyTo({ id: c.parent_id ?? c.id, username: c.author.username });
    setBody(`@${c.author.username} `);
    requestAnimationFrame(() => {
      const el = inputRef.current;
      if (!el) return;
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const text = body.trim();
    if (!text) return;
    setSending(true);
    setError(null);
    const supabase = createClient();
    const { data, error: err } = await supabase
      .from("comments")
      .insert({ post_id: postId, author_id: viewer.id, body: text, parent_id: replyTo?.id ?? null })
      .select("id, post_id, author_id, body, created_at, parent_id")
      .single();
    setSending(false);
    if (err || !data) return setError("Couldn't post your comment.");
    setComments((c) => [
      ...c,
      { ...data, likes: [], author: { id: viewer.id, username: viewer.username, full_name: viewer.full_name, avatar_path: viewer.avatar_path } },
    ]);
    setBody("");
    setReplyTo(null);
    router.refresh();
  }

  async function remove(c: CommentItem) {
    const replies = repliesTo(c.id).length;
    if (!confirm(replies > 0 ? `Delete this comment and its ${replies === 1 ? "reply" : `${replies} replies`}?` : "Delete this comment?")) return;
    const supabase = createClient();
    const { error: err } = await supabase.from("comments").delete().eq("id", c.id);
    if (err) return alert("Couldn't delete the comment.");
    // Its replies go with it (on delete cascade).
    setComments((list) => list.filter((x) => x.id !== c.id && x.parent_id !== c.id));
    router.refresh();
  }

  async function toggleLike(c: CommentItem) {
    const liked = c.likes.some((l) => l.user_id === viewer.id);
    const flip = (list: CommentItem[], on: boolean) =>
      list.map((x) =>
        x.id !== c.id
          ? x
          : { ...x, likes: on ? [...x.likes.filter((l) => l.user_id !== viewer.id), { user_id: viewer.id }] : x.likes.filter((l) => l.user_id !== viewer.id) },
      );
    setComments((list) => flip(list, !liked));
    const supabase = createClient();
    const { error: err } = liked
      ? await supabase.from("comment_likes").delete().match({ comment_id: c.id, user_id: viewer.id })
      : await supabase.from("comment_likes").insert({ comment_id: c.id, user_id: viewer.id });
    if (err && !(!liked && err.code === "23505")) setComments((list) => flip(list, liked));
  }

  const rowProps = (c: CommentItem, reply: boolean) => ({
    c,
    reply,
    meId: viewer.id,
    canDelete: c.author_id === viewer.id || postAuthorId === viewer.id || viewer.isAdmin,
    onReply: () => startReply(c),
    onDelete: () => remove(c),
    onLike: () => toggleLike(c),
  });

  return (
    <div className="border-t border-line">
      <ul className="max-h-[50vh] divide-y divide-line/60 overflow-y-auto md:max-h-none">
        {tops.length === 0 && <li className="px-4 py-8 text-center text-sm text-ink-muted">No comments yet. Say something nice.</li>}
        {tops.map((c) => (
          <li key={c.id}>
            <CommentRow {...rowProps(c, false)} />
            {repliesTo(c.id).map((r) => (
              <CommentRow key={r.id} {...rowProps(r, true)} />
            ))}
          </li>
        ))}
      </ul>

      {replyTo && (
        <div className="flex items-center gap-2 border-t border-line bg-surface-2/60 px-4 py-1.5 text-xs text-ink-muted">
          <span className="flex-1">Replying to @{replyTo.username}</span>
          <button
            type="button"
            aria-label="Cancel reply"
            onClick={() => {
              setReplyTo(null);
              setBody("");
            }}
            className="rounded-full p-1 hover:bg-surface-2"
          >
            <X className="size-3.5" />
          </button>
        </div>
      )}
      <form onSubmit={submit} className="relative flex items-center gap-2 border-t border-line px-3 py-2">
        <MentionMenu m={mentions} />
        <Avatar path={viewer.avatar_path} name={viewer.full_name} size={30} />
        <input
          ref={inputRef}
          value={body}
          onChange={(e) => {
            setBody(e.target.value);
            mentions.sync();
          }}
          onSelect={mentions.sync}
          onKeyDown={mentions.onKeyDown}
          onBlur={mentions.close}
          maxLength={1000}
          placeholder={replyTo ? `Reply to @${replyTo.username}…` : "Add a comment…"}
          className="min-w-0 flex-1 bg-transparent px-1 py-2 text-[15px] outline-none placeholder:text-ink-faint"
        />
        <button type="submit" disabled={sending || !body.trim()} aria-label="Post comment" className="rounded-full p-2 text-brand disabled:opacity-40">
          {sending ? <Loader2 className="size-5 animate-spin" /> : <Send className="size-5" />}
        </button>
      </form>
      {error && <p className="px-4 pb-2 text-xs text-danger">{error}</p>}
    </div>
  );
}

// One comment: a reply is indented under its top comment.
function CommentRow({
  c,
  reply,
  meId,
  canDelete,
  onReply,
  onDelete,
  onLike,
}: {
  c: CommentItem;
  reply: boolean;
  meId: string;
  canDelete: boolean;
  onReply: () => void;
  onDelete: () => void;
  onLike: () => void;
}) {
  const liked = c.likes.some((l) => l.user_id === meId);
  return (
    <div className={`group flex gap-3 py-3 pr-3 ${reply ? "pl-14" : "pl-4"}`}>
      <Link href={`/u/${c.author.username}`} className="shrink-0">
        <Avatar path={c.author.avatar_path} name={c.author.full_name} size={reply ? 26 : 32} />
      </Link>
      <div className="min-w-0 flex-1 text-[15px]">
        <p className="whitespace-pre-wrap break-words">
          <Link href={`/u/${c.author.username}`} className="mr-1.5 font-semibold">
            {c.author.username}
          </Link>
          <MentionText text={c.body} />
        </p>
        <p className="mt-0.5 flex items-center gap-3 text-xs text-ink-faint">
          <span>{timeAgo(c.created_at)}</span>
          {c.likes.length > 0 && <span className="font-semibold">{c.likes.length === 1 ? "1 like" : `${c.likes.length} likes`}</span>}
          <button type="button" onClick={onReply} className="font-semibold hover:text-ink">
            Reply
          </button>
          {canDelete && (
            <button
              type="button"
              aria-label="Delete comment"
              onClick={onDelete}
              className="opacity-0 transition hover:text-danger group-hover:opacity-100 focus:opacity-100 max-md:opacity-100"
            >
              <Trash2 className="size-3.5" />
            </button>
          )}
        </p>
      </div>
      <button
        type="button"
        onClick={onLike}
        aria-pressed={liked}
        aria-label={liked ? "Unlike comment" : "Like comment"}
        className="self-start rounded-full p-1 transition active:scale-90"
      >
        <Heart className={`size-3.5 ${liked ? "fill-like text-like" : "text-ink-faint"}`} />
      </button>
    </div>
  );
}
