import type { AdminContentApi, MediaView } from "./api";

export const MEDIA_SEARCH_LIMIT = 50;

function includesQuery(value: string, needle: string): boolean {
  return value.toLocaleLowerCase().includes(needle);
}

/**
 * Filename matches come from the server. Pictures already loaded whose filename contains the
 * query are merged in. The union stops at 50.
 */
export async function searchMedia(
  api: Pick<AdminContentApi, "listMedia">,
  query: string,
  known: readonly MediaView[] = [],
): Promise<MediaView[]> {
  const needle = query.trim().toLocaleLowerCase();
  const named = await api.listMedia(query.trim());
  const merged: MediaView[] = [];
  const seen = new Set<string>();
  const push = (item: MediaView) => {
    if (merged.length >= MEDIA_SEARCH_LIMIT || seen.has(item.mediaId)) return;
    seen.add(item.mediaId);
    merged.push(item);
  };
  for (const item of named) push(item);
  if (!needle) return merged;
  for (const item of known) {
    if (includesQuery(item.sourceFileName ?? "", needle)) push(item);
  }
  return merged;
}
