import "server-only";
import { createClient } from "@/lib/supabase/server";
import type { MySwipeProfile, SwipeCard, SwipeMatch } from "@/lib/friends";

// Find friends (0027): the member's own swipe profile, their deck and their matches.

export async function getMySwipeProfile(userId: string): Promise<MySwipeProfile | null> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("swipe_profiles")
    .select("bio, age, interests, photos, active")
    .eq("user_id", userId)
    .maybeSingle();
  return data;
}

// The first cards. Starts the deck over if everyone has been swiped (see swipe_deck).
export async function getDeck(): Promise<SwipeCard[]> {
  const supabase = await createClient();
  const { data } = await supabase.rpc("swipe_deck", { p_exclude: [], p_limit: 10 });
  return data ?? [];
}

export async function getSwipeMatches(): Promise<SwipeMatch[]> {
  const supabase = await createClient();
  const { data } = await supabase.rpc("my_swipe_matches");
  return data ?? [];
}
