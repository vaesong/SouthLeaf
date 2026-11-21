import { describe, expect, it } from "vitest";
import { addWorkMedia } from "./workMedia";

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
