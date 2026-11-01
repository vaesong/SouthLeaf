import { newIdempotencyKey, type MediaView } from "./api";
import { describeFailure } from "./messages";

export function rememberUploadedMedia(
  current: readonly MediaView[],
  uploaded: MediaView,
): MediaView[] {
  return [uploaded, ...current.filter((item) => item.mediaId !== uploaded.mediaId)];
}

export function uploadFailureText(fileName: string, error: unknown): string {
  return `${fileName}：${describeFailure(error).text}`;
}

/**
 * Uploads one file at a time. Each file gets its own idempotency key. A failure stops the rest
 * and leaves earlier successes in place.
 */
export async function uploadFilesSequentially(
  files: readonly File[],
  uploadOne: (file: File, key: string) => Promise<void>,
  keyFor: (file: File) => string = () => newIdempotencyKey(),
): Promise<{ file: File; key: string; error: unknown } | null> {
  for (const file of files) {
    const key = keyFor(file);
    try {
      await uploadOne(file, key);
    } catch (error) {
      return { file, key, error };
    }
  }
  return null;
}
