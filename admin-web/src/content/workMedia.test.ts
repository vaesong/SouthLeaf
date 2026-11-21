import { describe, expect, it } from "vitest";
import { addWorkMedia, coverFirst, moveWorkMedia, placeWorkCover } from "./workMedia";

describe("addWorkMedia", () => {
  it("keeps every distinct image and ignores a duplicate", () => {
    const selected: string[] = [];
    for (let index = 0; index < 12; index += 1) {
      expect(addWorkMedia(selected, `m${index}`)).toBe("added");
    }
    expect(addWorkMedia(selected, "m0")).toBe("duplicate");
    expect(selected).toHaveLength(12);
  });
});

describe("work photo order", () => {
  it("treats the first photo as the cover", () => {
    const selected = ["m1", "m2", "m3"];
    expect(coverFirst(selected, "m3")).toBe("m3");
    expect(selected).toEqual(["m3", "m1", "m2"]);
    placeWorkCover(selected, "m2");
    expect(selected).toEqual(["m2", "m3", "m1"]);
    moveWorkMedia(selected, 2, 0);
    expect(selected[0]).toBe("m1");
  });
});
