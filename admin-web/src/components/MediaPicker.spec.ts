import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import type { MediaView } from "../content/api";
import { catalogStoreKey } from "../content/catalogStore";
import { contentApiKey } from "../content/inject";
import MediaPicker from "./MediaPicker.vue";

function asset(id: string, sourceFileName: string | null): MediaView {
  return {
    mediaId: id,
    status: "READY",
    sourceFormat: "JPEG",
    sourceWidth: 4,
    sourceHeight: 3,
    createdAt: "2026-11-01T00:00:00Z",
    sourceFileName,
    variants: [],
  };
}

const beach = asset("beach", "beach-sunset.jpg");
const other = asset("other", "studio.png");
const described = asset("described", "plain.jpg");

function mountPicker(extraAlts: Record<string, string> = {}) {
  const listMedia = vi.fn(async (query = "") => {
    if (query === "beach") return [beach];
    return [];
  });
  const getMedia = vi.fn(async (id: string) => {
    if (id === "described") return described;
    throw new Error("missing");
  });
  const wrapper = mount(MediaPicker, {
    props: { open: true, media: [beach, other], extraAlts },
    global: {
      provide: {
        [contentApiKey as symbol]: { listMedia, getMedia },
        [catalogStoreKey as symbol]: {
          state: { draft: { mediaAltTexts: { described: "海边日落" } } },
        },
      },
    },
  });
  return { wrapper, listMedia, getMedia };
}

describe("MediaPicker", () => {
  it("emits every selected file instead of only the first", async () => {
    const { wrapper } = mountPicker();
    const input = wrapper.get("[data-testid=media-picker-file]");
    expect(input.attributes("accept")).toBe("image/jpeg,image/png");
    expect(input.attributes("multiple")).toBe("");

    const files = [
      new File(["a"], "a.jpg", { type: "image/jpeg" }),
      new File(["b"], "b.png", { type: "image/png" }),
    ];
    const fileList = {
      0: files[0],
      1: files[1],
      length: 2,
      item: (index: number) => files[index] ?? null,
      *[Symbol.iterator]() {
        yield* files;
      },
    };
    Object.defineProperty(input.element, "files", { configurable: true, value: fileList });
    await input.trigger("change");

    const emitted = wrapper.emitted("upload")?.[0]?.[0] as File[];
    expect(emitted.map((file) => file.name)).toEqual(["a.jpg", "b.png"]);
  });

  it("shows only filename or description matches", async () => {
    const { wrapper, getMedia } = mountPicker();

    expect(wrapper.findAll("[data-testid=media-picker-item]")).toHaveLength(2);
    await wrapper.get("[data-testid=media-picker-search]").setValue("beach");
    await flushPromises();

    expect(
      wrapper
        .findAll("[data-testid=media-picker-item]")
        .map((item) => item.attributes("data-media-id")),
    ).toEqual(["beach"]);
    expect(getMedia).not.toHaveBeenCalled();

    await wrapper.get("[data-testid=media-picker-search]").setValue("日落");
    await flushPromises();
    expect(
      wrapper
        .findAll("[data-testid=media-picker-item]")
        .map((item) => item.attributes("data-media-id")),
    ).toEqual(["described"]);
  });

  it("matches a description typed on the current work", async () => {
    const work = asset("work", null);
    const listMedia = vi.fn(async () => []);
    const getMedia = vi.fn(async () => work);
    const wrapper = mount(MediaPicker, {
      props: { open: true, media: [beach], extraAlts: { work: "窗边光" } },
      global: {
        provide: {
          [contentApiKey as symbol]: { listMedia, getMedia },
          [catalogStoreKey as symbol]: { state: { draft: { mediaAltTexts: {} } } },
        },
      },
    });

    await wrapper.get("[data-testid=media-picker-search]").setValue("窗边");
    await flushPromises();

    expect(wrapper.find("[data-media-id=work]").exists()).toBe(true);
    expect(wrapper.find("[data-media-id=beach]").exists()).toBe(false);
  });
});
