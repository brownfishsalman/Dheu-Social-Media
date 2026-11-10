// The schema's types come from the database itself. database.generated.ts is
// the output of Supabase's type generator and is never edited by hand: after a
// migration, generate it again (`supabase gen types typescript --project-id
// <ref> --schema public`) and replace the whole file.
//
// This file adds only what the generator can't know, and is what the rest of
// the code imports.

import type { Database as Generated, Json } from "./database.generated";

export type { Json };

export type NotificationType =
  | "like"
  | "follow"
  | "comment"
  | "story_reaction"
  | "follow_request"
  | "follow_accepted"
  | "mention"; // caption / comment (post_id, comment_id) or group message (conversation_id, message_id), see 0020
export type MessageKind = "text" | "system";

type Simplify<T> = { [K in keyof T]: T[K] } & {};

// B replaces A key by key, going a level deeper while both sides are objects.
type Merge<A, B> = Simplify<
  Omit<A, keyof B> & {
    [K in keyof B]: K extends keyof A
      ? B[K] extends readonly unknown[]
        ? B[K]
        : B[K] extends object
          ? A[K] extends object
            ? Merge<A[K], B[K]>
            : B[K]
          : B[K]
      : B[K];
  }
>;

type GeneratedViews = Generated["public"]["Views"];
type GeneratedFunctions = Generated["public"]["Functions"];

// Postgres reports every column of a view as nullable. In the feed views none are.
type NonNullRow<Row> = { [K in keyof Row]: NonNullable<Row[K]> };

type Overrides = {
  public: {
    Tables: {
      // Text columns that a check constraint holds to a short list.
      notifications: {
        Row: { type: NotificationType };
        Insert: { type: NotificationType };
        Update: { type?: NotificationType };
      };
      messages: {
        Row: { kind: MessageKind };
        Insert: { kind?: MessageKind };
        Update: { kind?: MessageKind };
      };
      push_devices: {
        Row: { platform: "android" };
        Insert: { platform?: "android" };
        Update: { platform?: "android" };
      };
    };
    Views: {
      post_feed: { Row: NonNullRow<GeneratedViews["post_feed"]["Row"]> };
      home_feed: { Row: NonNullRow<GeneratedViews["home_feed"]["Row"]> };
    };
    Functions: {
      follow_user: { Returns: "following" | "requested" };
      // null when neither has blocked the other, or the group is gone.
      block_status: { Returns: string | null };
      group_owner: { Returns: string | null };
      profile_stats: {
        Returns: Simplify<
          Omit<GeneratedFunctions["profile_stats"]["Returns"][number], "block_status"> & { block_status: string | null }
        >[];
      };
    };
  };
};

export type Database = Merge<Generated, Overrides>;

// Convenience aliases
export type Tables<T extends keyof Database["public"]["Tables"]> =
  Database["public"]["Tables"][T]["Row"];
export type Views<T extends keyof Database["public"]["Views"]> =
  Database["public"]["Views"][T]["Row"];

export type Profile = Tables<"profiles">;
export type Post = Tables<"posts">;
export type PostImage = Tables<"post_images">;
export type Comment = Tables<"comments">;
export type Story = Tables<"stories">;
export type Message = Tables<"messages">;
export type Conversation = Tables<"conversations">;
export type InviteCode = Tables<"invite_codes">;
export type StoryHighlight = Tables<"story_highlights">;
export type Notification = Tables<"notifications">;
export type Announcement = Tables<"announcements">;
