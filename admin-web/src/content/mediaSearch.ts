import type { AdminContentApi, MediaView } from "./api";

export const MEDIA_SEARCH_LIMIT = 50;

function includesQuery(value: string, needle: string): boolean {
  return value.toLocaleLowerCase().includes(needle);
}

/** Ids whose catalog description or extra (work-form) description contains the query. */
export function altMatchIds(
  query: string,
  catalogAlts: Record<string, string>,
  extraAlts: Record<string, string> = {},
): string[] {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return [];
  const ids = new Set<string>([...Object.keys(catalogAlts), ...Object.keys(extraAlts)]);
  return [...ids].filter((id) => {
    const catalogAlt = catalogAlts[id] ?? "";
    const extraAlt = extraAlts[id] ?? "";
    return includesQuery(catalogAlt, needle) || includesQuery(extraAlt, needle);
  });
}

/**
 * Filename matches come from the server. Descriptions already on a draft are merged in, and any
 * description match missing from that page is loaded with the single-media read. The union stops
 * at 50.
 */
export async function searchMedia(
  api: Pick<AdminContentApi, "listMedia" | "getMedia">,
  query: string,
  catalogAlts: Record<string, string>,
  extraAlts: Record<string, string> = {},
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
  const knownById = new Map(known.map((item) => [item.mediaId, item]));
  for (const id of altMatchIds(query, catalogAlts, extraAlts)) {
    if (merged.length >= MEDIA_SEARCH_LIMIT) break;
    if (seen.has(id)) continue;
    const local = knownById.get(id);
    if (local) {
      push(local);
      continue;
    }
    try {
      push(await api.getMedia(id));
    } catch {
      // A description can point at media this session cannot read. Filename matches stay.
    }
  }
  return merged;
}
