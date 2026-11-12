// Browser-only image preparation: HEIC → JPEG, EXIF rotation applied, resized
// to fit within a box (aspect ratio preserved, never cropped unless `square`),
// re-encoded as JPEG. Re-encoding through a canvas also strips all metadata
// (GPS location etc.), which is what we want for privacy.

export type ProcessedImage = {
  blob: Blob;
  width: number;
  height: number;
  previewUrl: string; // object URL; call URL.revokeObjectURL when done
};

export const POST_MAX = { maxW: 1080, maxH: 1920, quality: 0.9 };
// Square grid tile: ~3x the largest tile's CSS size, so it stays sharp on phones.
export const POST_THUMB = { maxW: 480, maxH: 480, quality: 0.8, square: true };
export const STORY_MAX = { maxW: 1080, maxH: 1920, quality: 0.9 };
export const AVATAR_MAX = { maxW: 320, maxH: 320, quality: 0.88, square: true };
// Find friends cards are portrait; the photo is cropped to fit when shown.
export const SWIPE_MAX = { maxW: 1080, maxH: 1440, quality: 0.88 };

type Options = { maxW: number; maxH: number; quality?: number; square?: boolean };

const ACCEPTED = ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/gif", "image/avif"];
export const ACCEPT_ATTR = "image/*,.heic,.heif";

function looksHeic(file: File) {
  return /image\/hei[cf]/.test(file.type) || /\.hei[cf]$/i.test(file.name);
}

async function toDecodable(file: File): Promise<Blob> {
  if (!looksHeic(file)) return file;
  // Chrome/Firefox can't decode HEIC; convert with libheif (wasm, loaded on demand).
  const { heicTo } = await import("heic-to");
  return heicTo({ blob: file, type: "image/jpeg", quality: 1 });
}

async function decode(blob: Blob): Promise<ImageBitmap | HTMLImageElement> {
  try {
    return await createImageBitmap(blob, { imageOrientation: "from-image" });
  } catch {
    // Fallback for browsers without createImageBitmap orientation support.
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image();
      img.decoding = "async";
      img.src = url;
      await img.decode();
      return img;
    } finally {
      URL.revokeObjectURL(url);
    }
  }
}

function dims(src: ImageBitmap | HTMLImageElement) {
  return "naturalWidth" in src
    ? { w: src.naturalWidth, h: src.naturalHeight }
    : { w: src.width, h: src.height };
}

type Source = ImageBitmap | HTMLImageElement;

async function load(file: File): Promise<Source> {
  if (!ACCEPTED.includes(file.type) && !looksHeic(file) && !file.type.startsWith("image/")) {
    throw new Error("That file isn't an image.");
  }

  const decodable = await toDecodable(file);
  const source = await decode(decodable);
  const { w, h } = dims(source);
  if (!w || !h) {
    release(source);
    throw new Error("Couldn't read that image.");
  }
  return source;
}

function release(source: Source) {
  if ("close" in source) source.close();
}

async function render(source: Source, opts: Options): Promise<{ blob: Blob; width: number; height: number }> {
  const { w, h } = dims(source);

  // Source rectangle (center-crop only for square avatars and thumbnails)
  let sx = 0, sy = 0, sw = w, sh = h;
  if (opts.square) {
    const side = Math.min(w, h);
    sx = Math.floor((w - side) / 2);
    sy = Math.floor((h - side) / 2);
    sw = sh = side;
  }

  const scale = Math.min(1, opts.maxW / sw, opts.maxH / sh);
  const outW = Math.max(1, Math.round(sw * scale));
  const outH = Math.max(1, Math.round(sh * scale));

  const canvas = document.createElement("canvas");
  canvas.width = outW;
  canvas.height = outH;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas not supported.");
  ctx.imageSmoothingQuality = "high";
  // JPEG has no alpha; flatten transparent PNGs onto white.
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, outW, outH);
  ctx.drawImage(source, sx, sy, sw, sh, 0, 0, outW, outH);

  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, "image/jpeg", opts.quality ?? 0.9),
  );
  if (!blob) throw new Error("Couldn't encode the image.");

  return { blob, width: outW, height: outH };
}

export async function processImage(file: File, opts: Options): Promise<ProcessedImage> {
  const source = await load(file);
  try {
    const out = await render(source, opts);
    return { ...out, previewUrl: URL.createObjectURL(out.blob) };
  } finally {
    release(source);
  }
}

// A post photo plus its square grid thumbnail, both cut from one decode.
export type ProcessedPostImage = ProcessedImage & { thumb: Blob };

export async function processPostImage(file: File): Promise<ProcessedPostImage> {
  const source = await load(file);
  try {
    const out = await render(source, POST_MAX);
    const thumb = await render(source, POST_THUMB);
    return { ...out, previewUrl: URL.createObjectURL(out.blob), thumb: thumb.blob };
  } finally {
    release(source);
  }
}

export function newImagePath(userId: string) {
  return `${userId}/${crypto.randomUUID()}.jpg`;
}

// "<user>/<uuid>.jpg" → "<user>/<uuid>-thumb.jpg", so a thumbnail sits next to its photo.
export function thumbPathFor(path: string) {
  return path.replace(/\.jpg$/, "-thumb.jpg");
}
