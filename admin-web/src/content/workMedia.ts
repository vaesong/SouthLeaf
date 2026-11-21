export type WorkMediaAdd = "added" | "duplicate";

/** Appends one id. A picture already on the work is left as it is. */
export function addWorkMedia(selected: string[], mediaId: string): WorkMediaAdd {
  if (selected.includes(mediaId)) return "duplicate";
  selected.push(mediaId);
  return "added";
}

/** Moves one selected photo. The first id is the cover. */
export function moveWorkMedia(selected: string[], from: number, to: number): void {
  if (from < 0 || to < 0 || from >= selected.length || to >= selected.length || from === to) return;
  const [item] = selected.splice(from, 1);
  selected.splice(to, 0, item);
}

/** Puts this photo first. The first id is the cover. */
export function placeWorkCover(selected: string[], mediaId: string): void {
  const index = selected.indexOf(mediaId);
  if (index <= 0) return;
  const [item] = selected.splice(index, 1);
  selected.unshift(item);
}

/** A known cover moves to the front. The returned id is the cover, or empty when nothing is selected. */
export function coverFirst(selected: string[], coverId: string): string {
  if (coverId) placeWorkCover(selected, coverId);
  return selected[0] ?? "";
}
