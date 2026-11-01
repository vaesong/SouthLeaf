import { describe, expect, it } from "vitest";
import { MAX_WORK_MEDIA, WORK_MEDIA_LIMIT_TEXT, addWorkMedia } from "./workMedia";

describe("addWorkMedia", () => {
  it("stops at 9 and leaves the extra id out", () => {
    const selected: string[] = [];
    for (let index = 0; index < MAX_WORK_MEDIA; index += 1) {
      expect(addWorkMedia(selected, `m${index}`)).toBe("added");
    }
    expect(addWorkMedia(selected, "m0")).toBe("duplicate");
    expect(addWorkMedia(selected, "extra")).toBe("full");
    expect(selected).toHaveLength(9);
    expect(selected).not.toContain("extra");
    expect(WORK_MEDIA_LIMIT_TEXT).toContain("9");
  });
});
