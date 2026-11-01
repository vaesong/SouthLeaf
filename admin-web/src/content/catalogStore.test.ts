import { flushPromises } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { AdminApiError } from "../auth/api";
import type { AdminContentApi, CatalogDraftInput, CatalogView } from "./api";
import {
  normalizeDraft,
  referencedMediaIds,
  toggleLimitedIds,
  unpublishedHint,
} from "./catalogDraft";
import { createCatalogStore } from "./catalogStore";

function draft(overrides: Partial<CatalogDraftInput> = {}): CatalogDraftInput {
  return {
    brand: {
      name: "甲",
      englishName: "A",
      discoveryHeading: "发现",
      shareTitle: "分享",
      tagline: "一句",
      description: "故事",
      logoMediaId: null,
    },
    contact: { phone: "1", address: "地址", workingHours: "10:00" },
    team: [],
    channels: [],
    businesses: ["child", "family", "wedding", "portrait", "maternity", "id-photo"].map(
      (id, position) => ({
        id,
        name: id,
        summary: "",
        coverMediaId: null,
        position,
        published: id === "child",
      }),
    ),
    packages: [
      {
        id: "pkg-1",
        businessId: "child",
        name: "基础",
        summary: "说明",
        suitableFor: "一人",
        durationMinutes: 60,
        displayPrice: "",
        priceType: "UNDISCLOSED",
        serviceItems: [],
        tips: [],
        notices: [],
        published: false,
        coverMediaId: null,
        position: 0,
      },
    ],
    featuredBusinessIds: [],
    featuredPackageIds: [],
    featuredWorkIds: [],
    mediaAltTexts: {},
    heroSlides: [],
    ...overrides,
  };
}

function view(revision = 3, value = draft()): CatalogView {
  return { revision, draft: value };
}

function api(partial: Partial<AdminContentApi>): AdminContentApi {
  return partial as AdminContentApi;
}

describe("catalog draft helpers", () => {
  it("keeps at most three featured ids", () => {
    expect(toggleLimitedIds(["a", "b"], "c", 3)).toEqual(["a", "b", "c"]);
    expect(toggleLimitedIds(["a", "b", "c"], "d", 3)).toEqual(["a", "b", "c"]);
    expect(toggleLimitedIds(["a", "b", "c"], "b", 3)).toEqual(["a", "c"]);
  });

  it("keeps hero slides and their pictures when the draft is normalized", () => {
    const value = draft({
      heroSlides: [
        {
          id: "hero-1",
          mediaId: "m1",
          title: "标题",
          subtitle: "",
          actionText: "查看详情",
          target: { type: "ABOUT", id: "about" },
          enabled: true,
          position: 4,
        },
      ],
    });
    normalizeDraft(value);
    expect(value.heroSlides[0]?.position).toBe(0);
    expect(value.heroSlides[0]?.target).toEqual({ type: "ABOUT", id: "about" });
    expect(referencedMediaIds(value)).toContain("m1");
    expect(value.mediaAltTexts.m1).toBe("");
  });

  it("describes the unpublished hint only from this session", () => {
    expect(unpublishedHint(true, true)).toBe("有修改尚未保存");
    expect(unpublishedHint(false, true)).toBe("已保存，尚未发布");
    expect(unpublishedHint(false, false)).toBe("");
  });
});

describe("catalog store", () => {
  it("merges edits from different sections into one save at the loaded revision", async () => {
    const saveCatalog = vi.fn(async (_revision: number, next: CatalogDraftInput) =>
      view(4, JSON.parse(JSON.stringify(next)) as CatalogDraftInput),
    );
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        saveCatalog,
      }),
    );
    await store.load();
    store.state.draft!.brand.name = "新名称";
    store.state.draft!.packages[0]!.name = "新套餐";
    expect(store.dirty).toBe(true);
    expect(store.unpublishedLabel).toBe("有修改尚未保存");
    const saved = await store.save();
    expect(saved).toBe(true);
    expect(saveCatalog).toHaveBeenCalledTimes(1);
    const [revision, payload] = saveCatalog.mock.calls[0] as [number, CatalogDraftInput];
    expect(revision).toBe(3);
    expect(payload.brand.name).toBe("新名称");
    expect(payload.packages[0]?.name).toBe("新套餐");
    expect(payload.heroSlides).toEqual([]);
    store.state.draft!.heroSlides.push({
      id: "hero-kept",
      mediaId: "",
      title: "窗边",
      subtitle: "",
      actionText: "查看详情",
      target: { type: "ABOUT", id: "about" },
      enabled: true,
      position: 0,
    });
    expect(await store.save()).toBe(true);
    const second = saveCatalog.mock.calls[1] as [number, CatalogDraftInput];
    expect(second[1].heroSlides[0]?.id).toBe("hero-kept");
    expect(second[1].heroSlides[0]?.title).toBe("窗边");
    expect(store.state.revision).toBe(4);
    expect(store.dirty).toBe(false);
    expect(store.unpublishedLabel).toBe("已保存，尚未发布");
    await flushPromises();
  });

  it("keeps the editor and asks to reload on a revision conflict", async () => {
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        saveCatalog: vi.fn(async () => {
          throw new AdminApiError(409, "VERSION_CONFLICT", "服务器原文");
        }),
      }),
    );
    await store.load();
    store.state.draft!.brand.name = "冲突前";
    expect(await store.save()).toBe(false);
    expect(store.state.conflict).toBe(true);
    expect(store.state.notice).toBe("内容已在别处更新");
    expect(store.state.draft?.brand.name).toBe("冲突前");
  });

  it("asks to reload without replacing the editor when the precondition expired", async () => {
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        saveCatalog: vi.fn(async () => {
          throw new AdminApiError(428, "PRECONDITION_REQUIRED", "missing");
        }),
      }),
    );
    await store.load();
    store.state.draft!.brand.name = "过期前";
    expect(await store.save()).toBe(false);
    expect(store.state.conflict).toBe(true);
    expect(store.state.notice).toContain("重新加载");
    expect(store.state.draft?.brand.name).toBe("过期前");
  });

  it("keeps the publish key when the same key belongs to another attempt", async () => {
    const keys: string[] = [];
    const publishCatalog = vi.fn(async (_revision: number, key: string) => {
      keys.push(key);
      throw new AdminApiError(409, "IDEMPOTENCY_KEY_REUSE", "reused");
    });
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        previewCatalog: vi.fn(async () => ({ publishable: true, blockingReason: null })),
        publishCatalog,
      }),
    );
    await store.load();
    await store.publish();
    expect(store.state.conflict).toBe(false);
    expect(store.state.unknownAction).toBe("publish");
    expect(store.state.notice).toContain("确认上次是否成功");
    await store.publish();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it("does not treat an unpublished catalog as a revision conflict", async () => {
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        withdrawCatalog: vi.fn(async () => {
          throw new AdminApiError(409, "NOT_PUBLISHED", "not published");
        }),
      }),
    );
    await store.load();
    await store.withdraw();
    expect(store.state.conflict).toBe(false);
    expect(store.state.unknownAction).toBe("");
    expect(store.state.notice).toContain("还没发布");
  });

  it("reuses one publish key after an unknown result", async () => {
    const keys: string[] = [];
    const publishCatalog = vi.fn(async (_revision: number, key: string) => {
      keys.push(key);
      if (keys.length === 1) throw new AdminApiError(0, "NETWORK_ERROR", "连接中断，结果未知。");
      return {
        commandId: "cmd-1",
        releaseId: "rel-1",
        contentVersion: "cv-1",
        revision: 3,
        completedAt: "2026-11-01T00:00:00Z",
        replayed: false,
      };
    });
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        previewCatalog: vi.fn(async () => ({ publishable: true, blockingReason: null })),
        publishCatalog,
      }),
    );
    await store.load();
    await store.publish();
    expect(store.state.unknownAction).toBe("publish");
    expect(store.state.notice).toContain("结果未知");
    await store.publish();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(store.state.unknownAction).toBe("");
    expect(store.unpublishedLabel).toBe("");
  });

  it("does not publish when the preview is blocked", async () => {
    const publishCatalog = vi.fn();
    const store = createCatalogStore(
      api({
        getCatalog: vi.fn(async () => view()),
        previewCatalog: vi.fn(async () => ({ publishable: false, blockingReason: "缺联系方式" })),
        publishCatalog,
      }),
    );
    await store.load();
    await store.publish();
    expect(publishCatalog).not.toHaveBeenCalled();
    expect(store.state.notice).toContain("缺联系方式");
  });
});
