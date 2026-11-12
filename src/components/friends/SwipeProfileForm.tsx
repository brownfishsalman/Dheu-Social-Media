"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ImagePlus, Loader2, Pause, Play, Star, Trash2, X } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { mediaUrl } from "@/lib/storage";
import { ACCEPT_ATTR, newImagePath, processImage, SWIPE_MAX, type ProcessedImage } from "@/lib/images";
import {
  cleanInterest,
  swipeErrorMessage,
  SWIPE_AGE_MAX,
  SWIPE_AGE_MIN,
  SWIPE_BIO_MAX,
  SWIPE_INTERESTS_MAX,
  SWIPE_PHOTOS_MAX,
  type MySwipeProfile,
} from "@/lib/friends";

// A photo already saved (path in the "swipe" bucket) or just picked.
type Photo = { key: string; path: string; image?: undefined } | { key: string; path?: undefined; image: ProcessedImage };

type Props = { userId: string; name: string; initial: MySwipeProfile | null };

// Make or edit the swipe profile: 1 to 6 photos, a bio, an optional age and
// up to five interests. Also pauses it, or deletes it with its photos.
export function SwipeProfileForm({ userId, name, initial }: Props) {
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [photos, setPhotos] = useState<Photo[]>(() => (initial?.photos ?? []).map((path) => ({ key: path, path })));
  const [bio, setBio] = useState(initial?.bio ?? "");
  const [age, setAge] = useState(initial?.age != null ? String(initial.age) : "");
  const [interests, setInterests] = useState<string[]>(initial?.interests ?? []);
  const [draft, setDraft] = useState("");
  const [processing, setProcessing] = useState(false);
  const [busy, setBusy] = useState<"save" | "pause" | "delete" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length === 0) return;
    setError(null);
    const room = SWIPE_PHOTOS_MAX - photos.length;
    if (files.length > room) setError(`You can have up to ${SWIPE_PHOTOS_MAX} photos.`);
    setProcessing(true);
    try {
      const added: Photo[] = [];
      for (const file of files.slice(0, room)) {
        const image = await processImage(file, SWIPE_MAX);
        added.push({ key: image.previewUrl, image });
      }
      setPhotos((current) => [...current, ...added]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't read that image.");
    } finally {
      setProcessing(false);
    }
  }

  function removePhoto(key: string) {
    const gone = photos.find((p) => p.key === key);
    if (gone?.image) URL.revokeObjectURL(gone.image.previewUrl);
    setPhotos(photos.filter((p) => p.key !== key));
  }

  function makeMain(key: string) {
    const photo = photos.find((p) => p.key === key);
    if (photo) setPhotos([photo, ...photos.filter((p) => p.key !== key)]);
  }

  // Adds what's typed, unless it's empty, a repeat, or one too many.
  function withDraft(list: string[]): string[] | null {
    const text = cleanInterest(draft);
    if (!text || list.some((i) => i.toLowerCase() === text.toLowerCase())) return list;
    if (list.length >= SWIPE_INTERESTS_MAX) return null;
    return [...list, text];
  }

  function addInterest() {
    const next = withDraft(interests);
    if (!next) return setError(`You can have up to ${SWIPE_INTERESTS_MAX} interests.`);
    setError(null);
    setInterests(next);
    setDraft("");
  }

  async function save() {
    setError(null);
    if (photos.length === 0) return setError("Add at least one photo.");
    const ageText = age.trim();
    const ageNumber = ageText === "" ? null : Number(ageText);
    if (ageNumber !== null && (!Number.isInteger(ageNumber) || ageNumber < SWIPE_AGE_MIN || ageNumber > SWIPE_AGE_MAX)) {
      return setError(`Your age has to be a whole number from ${SWIPE_AGE_MIN} to ${SWIPE_AGE_MAX}, or left empty.`);
    }
    const list = withDraft(interests) ?? interests;

    setBusy("save");
    const supabase = createClient();
    const uploaded: string[] = [];
    const paths: string[] = [];
    for (const photo of photos) {
      const image = photo.image;
      if (!image) {
        if (photo.path) paths.push(photo.path);
        continue;
      }
      const path = newImagePath(userId);
      const { error: upErr } = await supabase.storage
        .from("swipe")
        .upload(path, image.blob, { contentType: "image/jpeg", cacheControl: "86400" });
      if (upErr) {
        if (uploaded.length > 0) await supabase.storage.from("swipe").remove(uploaded);
        setBusy(null);
        return setError("A photo didn't upload. Check your connection and try again.");
      }
      uploaded.push(path);
      paths.push(path);
    }

    const fields = { bio: bio.trim(), age: ageNumber, interests: list, photos: paths };
    const { error: dbErr } = initial
      ? await supabase.from("swipe_profiles").update(fields).eq("user_id", userId)
      : await supabase.from("swipe_profiles").insert({ user_id: userId, ...fields });
    if (dbErr) {
      if (uploaded.length > 0) await supabase.storage.from("swipe").remove(uploaded);
      setBusy(null);
      return setError(swipeErrorMessage(dbErr.message));
    }

    // Photos taken off the profile leave Storage too.
    const dropped = (initial?.photos ?? []).filter((p) => !paths.includes(p));
    if (dropped.length > 0) await supabase.storage.from("swipe").remove(dropped);
    for (const photo of photos) if (photo.image) URL.revokeObjectURL(photo.image.previewUrl);
    router.push("/friends");
    router.refresh();
  }

  async function setActive(active: boolean) {
    setBusy("pause");
    setError(null);
    const { error: err } = await createClient().from("swipe_profiles").update({ active }).eq("user_id", userId);
    setBusy(null);
    if (err) return setError(swipeErrorMessage(err.message));
    router.refresh();
  }

  async function remove() {
    if (!confirm("Delete your swipe profile? Its photos are deleted and nobody sees you in Find friends. Your matches and chats stay.")) return;
    setBusy("delete");
    setError(null);
    const supabase = createClient();
    const { error: err } = await supabase.from("swipe_profiles").delete().eq("user_id", userId);
    if (err) {
      setBusy(null);
      return setError(swipeErrorMessage(err.message));
    }
    const { data: files } = await supabase.storage.from("swipe").list(userId, { limit: 100 });
    if (files && files.length > 0) await supabase.storage.from("swipe").remove(files.map((f) => `${userId}/${f.name}`));
    router.push("/friends");
    router.refresh();
  }

  return (
    <div className="space-y-6">
      <section>
        <h2 className="text-sm font-semibold">Photos</h2>
        <p className="mt-0.5 text-xs text-ink-muted">Up to {SWIPE_PHOTOS_MAX}. The first one is what people see first.</p>
        <div className="mt-3 grid grid-cols-3 gap-2">
          {photos.map((photo, i) => (
            <div key={photo.key} className="relative aspect-[3/4] overflow-hidden rounded-xl bg-surface-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={photo.image ? photo.image.previewUrl : (mediaUrl("swipe", photo.path) ?? undefined)}
                alt=""
                className="size-full object-cover"
              />
              <button
                type="button"
                onClick={() => removePhoto(photo.key)}
                aria-label="Remove photo"
                className="absolute top-1.5 right-1.5 rounded-full bg-black/60 p-1 text-white"
              >
                <X className="size-4" />
              </button>
              {i === 0 ? (
                <span className="absolute bottom-1.5 left-1.5 rounded-full bg-black/60 px-2 py-0.5 text-[11px] font-medium text-white">
                  Main
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => makeMain(photo.key)}
                  className="absolute bottom-1.5 left-1.5 flex items-center gap-1 rounded-full bg-black/60 px-2 py-0.5 text-[11px] font-medium text-white"
                >
                  <Star className="size-3" /> Make main
                </button>
              )}
            </div>
          ))}
          {photos.length < SWIPE_PHOTOS_MAX && (
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              disabled={processing}
              className="flex aspect-[3/4] flex-col items-center justify-center gap-1.5 rounded-xl border-2 border-dashed border-line text-ink-muted transition hover:border-brand hover:text-brand"
            >
              {processing ? <Loader2 className="size-6 animate-spin" /> : <ImagePlus className="size-6" strokeWidth={1.5} />}
              <span className="text-xs font-medium">{processing ? "Preparing…" : "Add"}</span>
            </button>
          )}
        </div>
        <input ref={fileRef} type="file" accept={ACCEPT_ATTR} multiple className="hidden" onChange={onPick} />
      </section>

      <section>
        <label htmlFor="swipe-bio" className="block text-sm font-semibold">
          About you
        </label>
        <textarea
          id="swipe-bio"
          value={bio}
          onChange={(e) => setBio(e.target.value.slice(0, SWIPE_BIO_MAX))}
          rows={4}
          placeholder="New in town, looking for people to play football and try cafés with."
          className="input mt-2 resize-none"
        />
        <p className="mt-1 text-right text-xs text-ink-faint">
          {bio.length}/{SWIPE_BIO_MAX}
        </p>
      </section>

      <section>
        <label htmlFor="swipe-age" className="block text-sm font-semibold">
          Age <span className="font-normal text-ink-muted">(optional)</span>
        </label>
        <input
          id="swipe-age"
          type="number"
          inputMode="numeric"
          min={SWIPE_AGE_MIN}
          max={SWIPE_AGE_MAX}
          value={age}
          onChange={(e) => setAge(e.target.value)}
          className="input mt-2 w-28"
        />
      </section>

      <section>
        <label htmlFor="swipe-interest" className="block text-sm font-semibold">
          Interests <span className="font-normal text-ink-muted">(up to {SWIPE_INTERESTS_MAX})</span>
        </label>
        {interests.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {interests.map((t) => (
              <span key={t} className="flex items-center gap-1 rounded-full bg-brand-soft py-1 pr-1.5 pl-2.5 text-xs font-medium text-brand-strong">
                {t}
                <button type="button" onClick={() => setInterests(interests.filter((x) => x !== t))} aria-label={`Remove ${t}`}>
                  <X className="size-3.5" />
                </button>
              </span>
            ))}
          </div>
        )}
        {interests.length < SWIPE_INTERESTS_MAX && (
          <div className="mt-2 flex gap-2">
            <input
              id="swipe-interest"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === ",") {
                  e.preventDefault();
                  addInterest();
                }
              }}
              maxLength={40}
              placeholder="Football, coffee, photography…"
              className="input"
            />
            <button type="button" onClick={addInterest} className="btn-secondary shrink-0">
              Add
            </button>
          </div>
        )}
      </section>

      <p className="text-xs text-ink-muted">
        Your card shows your name, {name}. Your username and Dheu profile are only shown to people you match with.
      </p>

      {error && (
        <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}

      <button type="button" onClick={save} disabled={busy !== null || processing} className="btn-primary w-full">
        {busy === "save" && <Loader2 className="size-4 animate-spin" />}
        {busy === "save" ? "Saving…" : initial ? "Save" : "Make my swipe profile"}
      </button>

      {initial && (
        <div className="space-y-3 border-t border-line pt-5">
          <button
            type="button"
            onClick={() => setActive(!initial.active)}
            disabled={busy !== null}
            className="btn-secondary w-full"
          >
            {busy === "pause" ? <Loader2 className="size-4 animate-spin" /> : initial.active ? <Pause className="size-4" /> : <Play className="size-4" />}
            {initial.active ? "Pause my profile" : "Resume my profile"}
          </button>
          <p className="text-xs text-ink-muted">
            {initial.active
              ? "While it's paused, nobody sees your profile and you can't swipe. Your matches stay."
              : "Your profile is paused: nobody sees it and you can't swipe."}
          </p>
          <button type="button" onClick={remove} disabled={busy !== null} className="btn-danger w-full">
            {busy === "delete" ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
            Delete my swipe profile
          </button>
        </div>
      )}
    </div>
  );
}
