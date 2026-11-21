export type WorkMediaAdd = "added" | "duplicate";

/** Appends one id. A picture already on the work is left as it is. */
export function addWorkMedia(selected: string[], mediaId: string): WorkMediaAdd {
  if (selected.includes(mediaId)) return "duplicate";
  selected.push(mediaId);
  return "added";
}
