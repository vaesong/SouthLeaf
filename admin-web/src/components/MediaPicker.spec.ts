import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import type { MediaView } from "../content/api";
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

function mountPicker() {
  const listMedia = vi.fn(async (query = "") => {
    if (query === "beach") return [beach];
    return [];
  });
  const wrapper = mount(MediaPicker, {
    props: { open: true, media: [beach, other, described] },
    global: {
      provide: {
        [contentApiKey as symbol]: { listMedia },
      },
    },
  });
  return { wrapper, listMedia };
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

  it("shows only filename matches", async () => {
    const { wrapper, listMedia } = mountPicker();

    expect(wrapper.findAll("[data-testid=media-picker-item]")).toHaveLength(3);
    await wrapper.get("[data-testid=media-picker-search]").setValue("beach");
    await flushPromises();

    expect(
      wrapper
        .findAll("[data-testid=media-picker-item]")
        .map((item) => item.attributes("data-media-id")),
    ).toEqual(["beach"]);

    await wrapper.get("[data-testid=media-picker-search]").setValue("日落");
    await flushPromises();
    expect(wrapper.findAll("[data-testid=media-picker-item]")).toHaveLength(0);
    expect(listMedia).toHaveBeenCalledWith("日落");
  });

  it("does not match a description typed on the current work", async () => {
    const work = asset("work", "studio-window.jpg");
    const listMedia = vi.fn(async () => []);
    const wrapper = mount(MediaPicker, {
      props: { open: true, media: [beach, work] },
      global: {
        provide: {
          [contentApiKey as symbol]: { listMedia },
        },
      },
    });

    await wrapper.get("[data-testid=media-picker-search]").setValue("窗边");
    await flushPromises();

    expect(wrapper.find("[data-media-id=work]").exists()).toBe(false);
    expect(wrapper.find("[data-media-id=beach]").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("缺描述");
  });
});
