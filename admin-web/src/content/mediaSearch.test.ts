import { describe, expect, it, vi } from "vitest";
import type { MediaView } from "./api";
import { MEDIA_SEARCH_LIMIT, altMatchIds, searchMedia } from "./mediaSearch";

function asset(id: string, sourceFileName: string | null): MediaView {
  return {
    mediaId: id,
    status: "READY",
    sourceFormat: "JPEG",
    sourceWidth: 10,
    sourceHeight: 8,
    createdAt: "2026-11-01T00:00:00Z",
    sourceFileName,
    variants: [],
  };
}

describe("searchMedia", () => {
  it("matches a filename or a catalog description and skips unrelated pictures", async () => {
    const beach = asset("beach", "beach-sunset.jpg");
    const other = asset("other", "studio.png");
    const described = asset("described", "plain.jpg");
    const getMedia = vi.fn(async (id: string) => {
      if (id === "described") return described;
      throw new Error(`missing ${id}`);
    });
    const listMedia = vi.fn(async (query = "") => (query === "beach" ? [beach] : []));
    const api = { listMedia, getMedia };

    const byName = await searchMedia(api, "beach", { described: "门口" }, {}, [beach, other]);
    expect(byName.map((item) => item.mediaId)).toEqual(["beach"]);
    expect(getMedia).not.toHaveBeenCalled();

    const byAlt = await searchMedia(api, "门口", { described: "门口" }, {}, [beach, other]);
    expect(byAlt.map((item) => item.mediaId)).toEqual(["described"]);
    expect(getMedia).toHaveBeenCalledTimes(1);
  });

  it("uses the current work description without requiring it in the catalog", async () => {
    const work = asset("work", null);
    const api = {
      listMedia: vi.fn(async () => []),
      getMedia: vi.fn(async () => work),
    };

    const found = await searchMedia(api, "窗边", {}, { work: "窗边光" });

    expect(found.map((item) => item.mediaId)).toEqual(["work"]);
    expect(altMatchIds("窗边", {}, { work: "窗边光" })).toEqual(["work"]);
  });

  it("stops at 50 and does not fetch descriptions past the cap", async () => {
    const named = Array.from({ length: MEDIA_SEARCH_LIMIT }, (_, index) =>
      asset(`named-${index}`, `file-${index}.jpg`),
    );
    const getMedia = vi.fn();
    const found = await searchMedia({ listMedia: async () => named, getMedia }, "file", {
      extra: "file note",
    });

    expect(found).toHaveLength(MEDIA_SEARCH_LIMIT);
    expect(getMedia).not.toHaveBeenCalled();
  });

  it("loads missing description matches one at a time", async () => {
    let releaseFirst: () => void = () => {};
    let releaseSecond: () => void = () => {};
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const secondGate = new Promise<void>((resolve) => {
      releaseSecond = resolve;
    });
    const calls: string[] = [];
    const api = {
      listMedia: async () => [],
      getMedia: async (id: string) => {
        calls.push(id);
        if (id === "first") await firstGate;
        if (id === "second") await secondGate;
        return asset(id, null);
      },
    };

    const running = searchMedia(api, "光", { first: "光一", second: "光二" });
    await vi.waitFor(() => expect(calls).toEqual(["first"]));
    releaseFirst();
    await vi.waitFor(() => expect(calls).toEqual(["first", "second"]));
    releaseSecond();
    await expect(running).resolves.toHaveLength(2);
  });
});
