// What members can report and why (0025). Shared by the report sheet and
// the admin's queue; the database checks the same lists.
import type { ReportReason, ReportTargetType } from "@/lib/database.types";

export type { ReportReason, ReportTargetType };

export const REPORT_REASONS: { value: ReportReason; label: string }[] = [
  { value: "spam", label: "Spam or a scam" },
  { value: "harassment", label: "Bullying or harassment" },
  { value: "hate", label: "Hate speech or symbols" },
  { value: "nudity", label: "Nudity or sexual content" },
  { value: "violence", label: "Violence or threats" },
  { value: "self_harm", label: "Self-harm or suicide" },
  { value: "other", label: "Something else" },
];

export const REPORT_DETAILS_MAX = 500;

export type ReportTarget = { type: ReportTargetType; id: string };

export const TARGET_NOUNS: Record<ReportTargetType, string> = {
  post: "post",
  story: "story",
  comment: "comment",
  profile: "account",
};

export function reasonLabel(reason: string): string {
  return REPORT_REASONS.find((r) => r.value === reason)?.label ?? reason;
}

// The reports row's id column for a target (the database fills in whose it is).
export function reportColumns(target: ReportTarget) {
  switch (target.type) {
    case "post":
      return { post_id: target.id };
    case "story":
      return { story_id: target.id };
    case "comment":
      return { comment_id: target.id };
    case "profile":
      return { target_user_id: target.id };
  }
}
