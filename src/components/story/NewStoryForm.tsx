"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ImagePlus, X, Loader2, Star } from "lucide-react";
import { createClient } from "@/lib/supabase/client";
import { processImage, STORY_MAX, ACCEPT_ATTR, newImagePath, type ProcessedImage } from "@/lib/images";

export function NewStoryForm({ userId }: { userId: string }) {
  const router = useRouter();
  const fileRef = useRef<HTMLInputElement>(null);
  const [image, setImage] = useState<ProcessedImage | null>(null);
  const [processing, setProcessing] = useState(false);
  const [posting, setPosting] = useState<"everyone" | "close_friends" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setError(null);
    setProcessing(true);
    try {
      if (image) URL.revokeObjectURL(image.previewUrl);
      setImage(await processImage(file, STORY_MAX));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't read that image.");
    } finally {
      setProcessing(false);
    }
  }

  // Everyone who can see my stories, or only my close friends (0024).
  async function share(audience: "everyone" | "close_friends") {
    if (!image) return;
    setPosting(audience);
    setError(null);
    const supabase = createClient();
    const path = newImagePath(userId);
    const { error: upErr } = await supabase.storage
      .from("stories")
      .upload(path, image.blob, { contentType: "image/jpeg", cacheControl: "86400" });
    if (upErr) {
      setPosting(null);
      return setError(upErr.message);
    }
    const { error: dbErr } = await supabase
      .from("stories")
      .insert({ author_id: userId, image_path: path, width: image.width, height: image.height, audience });
    if (dbErr) {
      await supabase.storage.from("stories").remove([path]);
      setPosting(null);
      return setError(dbErr.message);
    }
    URL.revokeObjectURL(image.previewUrl);
    router.replace(`/stories/${userId}`);
    router.refresh();
  }

  return (
    <div className="space-y-4 px-4 py-4">
      {image ? (
        <div className="relative mx-auto flex aspect-[9/16] max-h-[70vh] items-center justify-center overflow-hidden rounded-card bg-black">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={image.previewUrl} alt="" className="max-h-full max-w-full object-contain" />
          <button
            type="button"
            aria-label="Remove"
            onClick={() => {
              URL.revokeObjectURL(image.previewUrl);
              setImage(null);
            }}
            className="absolute top-3 right-3 rounded-full bg-black/60 p-1.5 text-white"
          >
            <X className="size-5" />
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => fileRef.current?.click()}
          disabled={processing}
          className="mx-auto flex aspect-[9/16] w-full max-w-[320px] flex-col items-center justify-center gap-3 rounded-card border-2 border-dashed border-line bg-surface text-ink-muted transition hover:border-brand hover:text-brand"
        >
          {processing ? <Loader2 className="size-10 animate-spin" /> : <ImagePlus className="size-10" strokeWidth={1.5} />}
          <span className="font-medium">{processing ? "Preparing…" : "Choose a photo"}</span>
          <span className="text-xs text-ink-faint">Any shape · visible for 24 hours</span>
        </button>
      )}

      <input ref={fileRef} type="file" accept={ACCEPT_ATTR} className="hidden" onChange={onPick} />

      {error && (
        <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}

      {image && (
        <button type="button" onClick={() => fileRef.current?.click()} className="btn-secondary w-full" disabled={posting !== null}>
          Change photo
        </button>
      )}
      <div className="flex gap-3">
        <button type="button" onClick={() => share("everyone")} className="btn-primary flex-1" disabled={!image || posting !== null}>
          {posting === "everyone" && <Loader2 className="size-4 animate-spin" />}
          {posting === "everyone" ? "Sharing…" : "Your story"}
        </button>
        <button
          type="button"
          onClick={() => share("close_friends")}
          className="btn bg-close flex-1 text-white hover:brightness-95"
          disabled={!image || posting !== null}
        >
          {posting === "close_friends" ? <Loader2 className="size-4 animate-spin" /> : <Star className="size-4 fill-current" />}
          {posting === "close_friends" ? "Sharing…" : "Close friends"}
        </button>
      </div>
      <p className="text-center text-xs text-ink-faint">
        Close friends stories only reach the people on your list (Settings → Close friends), with a green ring.
      </p>
    </div>
  );
}
