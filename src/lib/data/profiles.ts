import "server-only";
import { cache } from "react";
import { createClient } from "@/lib/supabase/server";
import type { Profile } from "@/lib/database.types";

export const getProfileByUsername = cache(async (username: string): Promise<Profile | null> => {
  const supabase = await createClient();
  const { data } = await supabase
    .from("profiles")
    .select("*")
    .eq("username", username.toLowerCase())
    .maybeSingle();
  return data;
});

export type BlockStatus = "by_me" | "by_them" | "both" | null;

export type ProfileStats = {
  posts: number;
  followers: number;
  following: number;
  isFollowing: boolean;
  followsMe: boolean;
  requested: boolean; // I have a pending request to follow them
  requestedMe: boolean; // they have a pending request to follow me
  blockStatus: BlockStatus;
};

// One request (profile_stats), seen from the logged-in member's side. The post
// count goes through RLS, so it's 0 for people who can't see their posts.
export async function getProfileStats(profileId: string): Promise<ProfileStats> {
  const supabase = await createClient();
  const { data } = await supabase.rpc("profile_stats", { p_profile: profileId }).single();

  return {
    posts: data?.posts ?? 0,
    followers: data?.followers ?? 0,
    following: data?.following ?? 0,
    isFollowing: data?.is_following ?? false,
    followsMe: data?.follows_me ?? false,
    requested: data?.requested ?? false,
    requestedMe: data?.requested_me ?? false,
    blockStatus: (data?.block_status as BlockStatus) ?? null,
  };
}

export type FollowState = "none" | "requested" | "following";

// Who I follow and who I've asked to follow — for lists of people.
export type Relationships = { following: Set<string>; requested: Set<string> };

export async function getRelationships(userId: string): Promise<Relationships> {
  const supabase = await createClient();
  const [{ data: f }, { data: r }] = await Promise.all([
    supabase.from("follows").select("following_id").eq("follower_id", userId),
    supabase.from("follow_requests").select("target_id").eq("requester_id", userId),
  ]);
  return {
    following: new Set((f ?? []).map((x) => x.following_id)),
    requested: new Set((r ?? []).map((x) => x.target_id)),
  };
}

export function followStateFor(rel: Relationships, id: string): FollowState {
  if (rel.following.has(id)) return "following";
  if (rel.requested.has(id)) return "requested";
  return "none";
}

// Build the id -> state map ProfileList expects.
export function statesFor(rel: Relationships, people: { id: string }[]): Record<string, FollowState> {
  const out: Record<string, FollowState> = {};
  for (const p of people) out[p.id] = followStateFor(rel, p.id);
  return out;
}

// Am I allowed to see this member's content (own, admin, or approved follower; not blocked)?
export async function canViewContentOf(profileId: string): Promise<boolean> {
  const supabase = await createClient();
  const { data } = await supabase.rpc("can_view_content_of", { p_author: profileId });
  return data === true;
}

export async function getBlockedProfiles(userId: string): Promise<ProfileListItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("blocks")
    .select("profile:profiles!blocks_blocked_id_fkey(id, username, full_name, avatar_path)")
    .eq("blocker_id", userId)
    .order("created_at", { ascending: false });
  return (data ?? []).map((r) => r.profile).filter(Boolean) as ProfileListItem[];
}

export type GridPost = {
  id: string;
  created_at: string;
  images: { path: string; thumb_path: string | null; width: number; height: number; position: number }[];
};

export async function getProfilePosts(profileId: string): Promise<GridPost[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("posts")
    .select("id, created_at, images:post_images(path, thumb_path, width, height, position)")
    .eq("author_id", profileId)
    .gt("expires_at", new Date().toISOString())
    .order("created_at", { ascending: false })
    .limit(200);

  return (data ?? []).map((p) => ({
    ...p,
    images: [...p.images].sort((a, b) => a.position - b.position),
  }));
}

export type ProfileListItem = Pick<Profile, "id" | "username" | "full_name" | "avatar_path">;

// Settings → Close friends (0024): everyone I follow or who follows me, and
// which of them are on my list. Only I can read my list.
export async function getCloseFriendChoices(meId: string): Promise<{ people: ProfileListItem[]; chosen: string[] }> {
  const supabase = await createClient();
  const [following, followers, { data: list }] = await Promise.all([
    getFollowing(meId),
    getFollowers(meId),
    supabase.from("close_friends").select("friend_id").eq("owner_id", meId),
  ]);
  const byId = new Map<string, ProfileListItem>();
  for (const p of [...following, ...followers]) if (p.id !== meId) byId.set(p.id, p);
  const people = [...byId.values()].sort((a, b) => a.full_name.localeCompare(b.full_name));
  return { people, chosen: (list ?? []).map((r) => r.friend_id) };
}

export async function getFollowers(profileId: string): Promise<ProfileListItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("follows")
    .select("profile:profiles!follows_follower_id_fkey(id, username, full_name, avatar_path)")
    .eq("following_id", profileId)
    .order("created_at", { ascending: false });
  return (data ?? []).map((r) => r.profile).filter(Boolean) as ProfileListItem[];
}

export async function getFollowing(profileId: string): Promise<ProfileListItem[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("follows")
    .select("profile:profiles!follows_following_id_fkey(id, username, full_name, avatar_path)")
    .eq("follower_id", profileId)
    .order("created_at", { ascending: false });
  return (data ?? []).map((r) => r.profile).filter(Boolean) as ProfileListItem[];
}

export async function getFollowingIds(userId: string): Promise<string[]> {
  const supabase = await createClient();
  const { data } = await supabase.from("follows").select("following_id").eq("follower_id", userId);
  return (data ?? []).map((r) => r.following_id);
}

export async function searchProfiles(query: string, limit = 30): Promise<ProfileListItem[]> {
  const supabase = await createClient();
  const q = query.trim().replace(/[%_]/g, "");
  let req = supabase
    .from("profiles")
    .select("id, username, full_name, avatar_path")
    .order("username")
    .limit(limit);
  if (q) req = req.or(`username.ilike.%${q}%,full_name.ilike.%${q}%`);
  const { data } = await req;
  return data ?? [];
}

// Admin-curated suggestions, minus people the viewer already follows,
// has requested, has blocked, or is. Profiles of people who blocked the
// viewer are hidden by RLS automatically.
export async function getSuggestedPeople(viewerId: string, limit = 10): Promise<ProfileListItem[]> {
  const supabase = await createClient();
  const [{ data: rows }, rel, { data: blocked }] = await Promise.all([
    supabase
      .from("suggested_people")
      .select("user_id, position, profile:profiles!suggested_people_user_id_fkey(id, username, full_name, avatar_path)")
      .order("position")
      .limit(50),
    getRelationships(viewerId),
    supabase.from("blocks").select("blocked_id").eq("blocker_id", viewerId),
  ]);
  const skip = new Set<string>([viewerId, ...rel.following, ...rel.requested, ...(blocked ?? []).map((b) => b.blocked_id)]);
  return (rows ?? [])
    .map((r) => r.profile)
    .filter((p): p is ProfileListItem => Boolean(p) && !skip.has(p!.id))
    .slice(0, limit);
}

export type SuggestedRow = ProfileListItem & { position: number };

export async function getAllSuggestions(): Promise<SuggestedRow[]> {
  const supabase = await createClient();
  const { data } = await supabase
    .from("suggested_people")
    .select("position, profile:profiles!suggested_people_user_id_fkey(id, username, full_name, avatar_path)")
    .order("position");
  return (data ?? []).filter((r) => r.profile).map((r) => ({ ...r.profile!, position: r.position }));
}

// The strip should appear "sometimes, not always": roughly 2 feed loads in 5.
export async function maybeSuggestedPeople(viewerId: string, chance = 0.4): Promise<ProfileListItem[]> {
  if (Math.random() >= chance) return [];
  return getSuggestedPeople(viewerId);
}
