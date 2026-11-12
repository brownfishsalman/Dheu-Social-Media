// Find friends (the flame tab, 0027): shared limits and shapes. Works on
// server and client.

export const SWIPE_PHOTOS_MAX = 6;
export const SWIPE_BIO_MAX = 300;
export const SWIPE_INTERESTS_MAX = 5;
export const SWIPE_INTEREST_LENGTH = 24;
export const SWIPE_AGE_MIN = 13;
export const SWIPE_AGE_MAX = 99;

// One card in the deck. looped: the deck ran out and started over with the
// people passed on before.
export type SwipeCard = {
  user_id: string;
  full_name: string;
  bio: string;
  age: number | null;
  interests: string[];
  photos: string[]; // paths in the "swipe" bucket
  looped: boolean;
};

export type SwipeMatch = {
  user_id: string;
  username: string;
  full_name: string;
  avatar_path: string | null;
  conversation_id: string | null;
  matched_at: string;
  seen: boolean; // has had the "It's a match" screen
};

export type MySwipeProfile = {
  bio: string;
  age: number | null;
  interests: string[];
  photos: string[];
  active: boolean;
};

// Same tidying as the database does (prepare_swipe_profile in 0027), so
// what someone types is what they see saved.
export function cleanInterest(text: string): string {
  return text.trim().replace(/\s+/g, " ").slice(0, SWIPE_INTEREST_LENGTH);
}

// Error codes the swipe functions raise, in words.
export function swipeErrorMessage(message: string | undefined): string {
  if (message?.includes("BAD_PHOTOS")) return "One of the photos didn't upload. Remove it and add it again.";
  if (message?.includes("NO_PROFILE")) return "Make your swipe profile first, or resume it if it's paused.";
  if (message?.includes("NOT_AVAILABLE")) return "That profile isn't available any more.";
  return "Something went wrong. Check your connection and try again.";
}
