import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import type { MediaView } from "../content/api";
import { catalogStoreKey } from "../content/catalogStore";
import { contentApiKey } from "../content/inject";
import MediaField from "./MediaField.vue";
import MediaThumb from "./MediaThumb.vue";

const provide = {
  [contentApiKey as symbol]: {
    listMedia: async () => [],
    getMedia: async () => {
      throw new Error("unused");
    },
  },
  [catalogStoreKey as symbol]: { state: { draft: null } },
};

const sample: MediaView = {
  mediaId: "m1",
  status: "READY",
  sourceFormat: "JPEG",
  sourceWidth: 10,
  sourceHeight: 8,
  createdAt: "2026-11-01T00:00:00Z",
  sourceFileName: "cover.jpg",
  variants: [],
};

describe("MediaField", () => {
  it("opens the picker when the empty placeholder is clicked", async () => {
    const wrapper = mount(MediaField, {
      props: { modelValue: null, media: [] },
      global: { provide },
    });

    expect(wrapper.text()).toContain("选择图片");
    expect(wrapper.find("[role=dialog]").exists()).toBe(false);
    await wrapper.get("[data-testid=media-field-empty]").trigger("click");

    expect(wrapper.get("[role=dialog]").attributes("aria-label")).toBe("从图片库选择");
    expect(wrapper.get("[data-testid=media-picker-file]").attributes("multiple")).toBe("");
  });

  it("does not offer the empty placeholder once a picture is chosen", () => {
    const wrapper = mount(MediaField, {
      props: { modelValue: sample.mediaId, media: [sample] },
      global: { provide },
    });

    expect(wrapper.find("[data-testid=media-field-empty]").exists()).toBe(false);
  });
});

describe("MediaThumb", () => {
  it("keeps a quiet list placeholder from opening a picker", async () => {
    const wrapper = mount(MediaThumb, { props: { mediaId: null, quiet: true } });

    expect(wrapper.text()).not.toContain("选择图片");
    expect(wrapper.find("button").exists()).toBe(false);
    await wrapper.trigger("click");
    expect(wrapper.text()).not.toContain("从图片库选择");
  });
});
