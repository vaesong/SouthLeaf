import { describe, expect, it, vi } from "vitest";
import type { MediaView } from "./api";
import { MEDIA_SEARCH_LIMIT, searchMedia } from "./mediaSearch";

function asset(id: string, sourceFileName: string | null): MediaView {
  return {
    mediaId: id,
    status: "READY",
    sourceFormat: "JPEG",
    sourceWidth: 10,
    sourceHeight: 8,
    createdAt: "2026-11-01T00:00:00Z",
    sourceFileName,
    folderId: null,
    variants: [],
  };
}

describe("searchMedia", () => {
  it("matches a filename and skips pictures that only match a description", async () => {
    const beach = asset("beach", "beach-sunset.jpg");
    const other = asset("other", "studio.png");
    const described = asset("described", "plain.jpg");
    const listMedia = vi.fn(async (query = "") => (query === "beach" ? [beach] : []));
    const api = { listMedia };

    const byName = await searchMedia(api, "beach", [beach, other, described]);
    expect(byName.map((item) => item.mediaId)).toEqual(["beach"]);

    const byAlt = await searchMedia(api, "门口", [beach, other, described]);
    expect(byAlt).toEqual([]);
    expect(listMedia).toHaveBeenLastCalledWith("门口");
  });

  it("does not search the current work description", async () => {
    const work = asset("work", null);
    const listMedia = vi.fn(async () => []);

    const found = await searchMedia({ listMedia }, "窗边", [work]);

    expect(found).toEqual([]);
    expect(listMedia).toHaveBeenCalledWith("窗边");
  });

  it("stops at 50", async () => {
    const named = Array.from({ length: MEDIA_SEARCH_LIMIT }, (_, index) =>
      asset(`named-${index}`, `file-${index}.jpg`),
    );
    const found = await searchMedia({ listMedia: async () => named }, "file");

    expect(found).toHaveLength(MEDIA_SEARCH_LIMIT);
  });

  it("adds a loaded picture whose filename matches when the server page omitted it", async () => {
    const local = asset("local", "window-light.jpg");
    const found = await searchMedia({ listMedia: async () => [] }, "window", [local]);

    expect(found.map((item) => item.mediaId)).toEqual(["local"]);
  });
});
