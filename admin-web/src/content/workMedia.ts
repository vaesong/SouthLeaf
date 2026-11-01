export const MAX_WORK_MEDIA = 9;
export const WORK_MEDIA_LIMIT_TEXT = "作品最多 9 张图片，多出来的没有加入。";

export type WorkMediaAdd = "added" | "duplicate" | "full";

/** Appends one id when the work is below the cap. Does not remove existing ids. */
export function addWorkMedia(selected: string[], mediaId: string): WorkMediaAdd {
  if (selected.includes(mediaId)) return "duplicate";
  if (selected.length >= MAX_WORK_MEDIA) return "full";
  selected.push(mediaId);
  return "added";
}
