// @ts-nocheck
import type { Plugin } from "vite";

const MARKER = "MOCKFIX-WUTONG";

type Draft = {
  brand: {
    name: string;
    englishName: string;
    discoveryHeading: string;
    shareTitle: string;
    tagline: string;
    description: string;
    logoMediaId: string | null;
  };
  contact: { phone: string; address: string; workingHours: string };
  team: Array<Record<string, unknown>>;
  channels: Array<Record<string, unknown>>;
  businesses: Array<Record<string, unknown>>;
  packages: Array<Record<string, unknown>>;
  featuredBusinessIds: string[];
  featuredPackageIds: string[];
  featuredWorkIds: string[];
  mediaAltTexts: Record<string, string>;
  heroSlides: Array<{
    id: string;
    mediaId: string;
    title: string;
    subtitle: string;
    actionText: string;
    target: { type: string; id: string };
    enabled: boolean;
    position: number;
  }>;
};

function mockMediaId(index: number) {
  return `11111111-1111-4111-8111-${String(index).padStart(12, "0")}`;
}

const MEDIA = Array.from({ length: 20 }, (_, index) => mockMediaId(index + 1));
const MEDIA_COLORS = [
  "#C4A484",
  "#A67C52",
  "#8E5A3C",
  "#D4B483",
  "#B08968",
  "#7A5A3A",
  "#C98A63",
  "#A65A34",
  "#E0C3A4",
  "#9C6644",
  "#6B4F3A",
  "#CDBBA4",
  "#8C6A4A",
  "#BFA58A",
  "#A97E5C",
  "#CFB091",
  "#E9D8C4",
  "#7A4A2E",
  "#C9A27A",
  "#5E554B",
];

function business(id: string, name: string, position: number, published: boolean) {
  return {
    id,
    name,
    summary: `${MARKER} ${name}简介`,
    coverMediaId: MEDIA[position],
    position,
    published,
  };
}

function pkg(
  id: string,
  businessId: string,
  name: string,
  priceType: string,
  displayPrice: string,
  position: number,
) {
  return {
    id,
    businessId,
    name,
    summary: "模拟套餐说明",
    suitableFor: "虚构来访者",
    durationMinutes: 90,
    displayPrice,
    priceType,
    serviceItems: ["虚构精修"],
    tips: ["虚构提示"],
    notices: ["虚构须知"],
    published: true,
    coverMediaId: MEDIA[position % 6],
    position,
  };
}

function emptyDraft(): Draft {
  const businesses = [
    business("child", "虚构儿童", 0, true),
    business("family", "虚构家庭", 1, true),
    business("wedding", "虚构仪式", 2, true),
    business("portrait", "虚构肖像", 3, true),
    business("maternity", "虚构孕期", 4, false),
    business("id-photo", "虚构证件", 5, false),
  ];
  return {
    brand: {
      name: "枝南影像",
      englishName: "Zhinan Studio",
      discoveryHeading: "虚构发现标题",
      shareTitle: "虚构分享标题",
      tagline: "虚构的一句话",
      description: `${MARKER} 这是本地模拟文案，不是真实工作室。`,
      logoMediaId: MEDIA[0],
    },
    contact: {
      phone: "010-0000-0000",
      address: "虚构街 1 号",
      workingHours: "模拟时段 10:00-18:00",
    },
    team: [
      {
        id: "member-mock",
        name: "陈予安",
        role: "虚构摄影师",
        bio: "只存在于本地模拟数据。",
        specialties: ["虚构光线"],
        portraitMediaId: MEDIA[6],
        position: 0,
        published: true,
      },
      {
        id: "member-mock-2",
        name: "周晚宁",
        role: "虚构造型",
        bio: "只存在于本地模拟数据。",
        specialties: ["虚构色彩"],
        portraitMediaId: MEDIA[7],
        position: 1,
        published: true,
      },
      {
        id: "member-mock-3",
        name: "许清和",
        role: "虚构助理",
        bio: "这位成员在小程序上隐藏。",
        specialties: ["虚构统筹"],
        portraitMediaId: MEDIA[8],
        position: 2,
        published: false,
      },
    ],
    channels: [
      {
        id: "channel-mock",
        platform: "wechat",
        label: "虚构微信",
        account: "mock-wutong",
        qrMediaId: MEDIA[9],
        qrStatus: "CONFIRMED",
        position: 0,
        published: true,
      },
      {
        id: "channel-mock-2",
        platform: "xiaohongshu",
        label: "虚构小红书",
        account: "mock-note",
        qrMediaId: MEDIA[10],
        qrStatus: "PLACEHOLDER",
        position: 1,
        published: true,
      },
      {
        id: "channel-mock-3",
        platform: "douyin",
        label: "虚构短视频",
        account: "mock-clip",
        qrMediaId: null,
        qrStatus: "UNCONFIGURED",
        position: 2,
        published: false,
      },
    ],
    businesses,
    packages: [
      pkg("pkg-child-1", "child", "虚构窗边半日", "FIXED", "1280", 0),
      pkg("pkg-child-2", "child", "虚构户外小时", "FROM", "699", 1),
      pkg(
        "pkg-child-3",
        "child",
        "虚构超长名称的亲子记录套餐用于检查价格行是否换行",
        "INQUIRY",
        "",
        2,
      ),
      pkg("pkg-family-1", "family", "虚构全家福", "UNDISCLOSED", "", 3),
      pkg("pkg-family-2", "family", "虚构室内双小时", "FIXED", "880", 4),
      pkg("pkg-wedding-1", "wedding", "虚构仪式记录", "FROM", "2680", 5),
      pkg("pkg-portrait-1", "portrait", "虚构肖像", "INQUIRY", "", 1),
      pkg("pkg-maternity-1", "maternity", "虚构孕期", "FIXED", "560", 2),
      pkg("pkg-id-1", "id-photo", "虚构证件", "FROM", "99", 3),
    ],
    featuredBusinessIds: ["child", "family", "wedding"],
    featuredPackageIds: ["pkg-child-1", "pkg-family-2"],
    featuredWorkIds: [
      "aaaaaaaa-aaaa-4aaa-8aaa-000000000001",
      "aaaaaaaa-aaaa-4aaa-8aaa-000000000002",
      "aaaaaaaa-aaaa-4aaa-8aaa-000000000003",
    ],
    heroSlides: [
      {
        id: "hero-window",
        mediaId: MEDIA[1],
        title: "虚构窗边",
        subtitle: "本地样例",
        actionText: "查看详情",
        target: { type: "WORK", id: "aaaaaaaa-aaaa-4aaa-8aaa-000000000001" },
        enabled: true,
        position: 0,
      },
      {
        id: "hero-family",
        mediaId: MEDIA[2],
        title: "虚构全家",
        subtitle: "",
        actionText: "看套餐",
        target: { type: "PACKAGE", id: "pkg-family-1" },
        enabled: true,
        position: 1,
      },
      {
        id: "hero-about",
        mediaId: MEDIA[3],
        title: "来坐坐",
        subtitle: "关于工作室",
        actionText: "了解我们",
        target: { type: "ABOUT", id: "about" },
        enabled: false,
        position: 2,
      },
    ],
    mediaAltTexts: {
      [MEDIA[0]]: "虚构封面",
      [MEDIA[1]]: "虚构封面二",
      [MEDIA[2]]: "虚构封面三",
      [MEDIA[3]]: "虚构封面四",
      [MEDIA[4]]: "",
      [MEDIA[5]]: "",
      [MEDIA[6]]: "虚构肖像",
      [MEDIA[7]]: "虚构肖像二",
      [MEDIA[8]]: "虚构肖像三",
      [MEDIA[9]]: "虚构二维码",
      [MEDIA[10]]: "虚构二维码二",
    },
  };
}

function envelope(data: unknown) {
  return JSON.stringify({ code: "OK", requestId: "mock-request", data });
}

export function mockAdminApi(): Plugin {
  const draft = emptyDraft();
  let revision = 3;
  const versions = [{ revision: 3, updatedBy: "mock-operator", updatedAt: "2026-11-01T01:00:00Z" }];
  const commands = new Map<string, unknown>();
  const folders: { folderId: string; parentId: string | null; name: string }[] = [
    { folderId: "folder-wechat", parentId: null, name: "微信客片" },
    { folderId: "folder-qr", parentId: null, name: "二维码" },
    { folderId: "folder-wechat-nov", parentId: "folder-wechat", name: "11月" },
  ];
  const media = MEDIA.map((mediaId, index) => ({
    mediaId,
    status: index === 18 ? "PROCESSING" : "READY",
    sourceFormat: "image/png",
    sourceWidth: 800,
    sourceHeight: 600,
    createdAt: "2026-11-01T01:00:00Z",
    sourceFileName: `mock-${index + 1}.png`,
    folderId: index < 4 ? "folder-wechat" : index < 7 ? "folder-qr" : null,
    variants: [],
  }));
  const workPlan = [
    ["虚构窗边", "PUBLISHED", "child", 2, 2],
    ["虚构走廊", "PUBLISHED", "family", 3, 2],
    ["虚构仪式", "PUBLISHED", "wedding", 1, 1],
    ["虚构肖像", "WITHDRAWN", "portrait", 2, 1],
    ["虚构孕期", "WITHDRAWN", "maternity", 1, 1],
    ["虚构证件", "NEVER_PUBLISHED", "id-photo", 1, null],
    ["虚构午后", "NEVER_PUBLISHED", "child", 1, null],
    ["虚构庭院", "NEVER_PUBLISHED", "family", 1, null],
  ] as const;
  const works = workPlan.map((plan, index) => {
    const mediaId = MEDIA[11 + index];
    return {
      workId: `aaaaaaaa-aaaa-4aaa-8aaa-${String(index + 1).padStart(12, "0")}`,
      version: plan[3],
      updatedAt: "2026-11-01T01:00:00Z",
      publicationState: plan[1],
      publishedVersion: plan[4],
      title: plan[0],
      summary: "模拟作品介绍",
      subjectScopeRef: index % 2 === 0 ? "scope.mock" : "",
      businessIds: [plan[2]],
      media: [{ mediaId, altText: "虚构画面" }],
      coverMediaId: mediaId,
      authorizationRecorded: false,
    };
  });
  let workSerial = 1;
  let operatingRule = {
    version: 1,
    rules: {
      minAdvanceHours: 24,
      maxAdvanceDays: 30,
      pendingExpiryHours: 48,
      slotDurationMinutes: 120,
    },
  };

  function buildWeekSlots() {
    const shifted = new Date(Date.now() + 8 * 60 * 60 * 1000);
    const weekday = (shifted.getUTCDay() + 6) % 7;
    const monday =
      Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()) -
      weekday * 86400000 -
      8 * 60 * 60 * 1000;
    const slots = [];
    let serial = 1;
    for (let day = 0; day < 7; day += 1) {
      for (const hour of [10, 14]) {
        const start = monday + day * 86400000 + hour * 3600000;
        slots.push({
          id: `3c9a6900-0000-4000-8000-${String(serial).padStart(12, "0")}`,
          startAt: new Date(start).toISOString(),
          endAt: new Date(start + 2 * 3600000).toISOString(),
          businessZone: "Asia/Shanghai",
          displayOffset: "+08:00",
          status: "OPEN",
          capacity: 1,
          ruleVersion: "mock-1",
          revision: 1,
          availability: serial === 1 ? "PENDING" : serial === 2 ? "OCCUPIED" : "OPEN",
        });
        serial += 1;
      }
    }
    return slots;
  }

  const mutableSlots = buildWeekSlots();

  function weekSlots() {
    return mutableSlots.filter((slot) => slot.availability !== "CLOSED");
  }

  function pendingReservations() {
    const slots = weekSlots();
    return [
      {
        id: "3c9a6900-0000-4000-8000-000000000001",
        status: "PENDING_CONFIRMATION",
        source: "MINI_PROGRAM",
        expiresAt: new Date(Date.now() + 4 * 3600000).toISOString(),
        createdAt: "2026-11-01T01:00:00Z",
        version: 1,
        slotId: slots[0]?.id ?? null,
      },
      {
        id: "3c9a6900-0000-4000-8000-000000000002",
        status: "PENDING_CONFIRMATION",
        source: "CUSTOMER_ONLINE",
        expiresAt: new Date(Date.now() + 20 * 3600000).toISOString(),
        createdAt: "2026-11-01T01:00:00Z",
        version: 1,
        slotId: null,
      },
      {
        id: "3c9a6900-0000-4000-8000-000000000003",
        status: "PENDING_CONFIRMATION",
        source: "STAFF_OFFLINE",
        expiresAt: new Date(Date.now() + 30 * 3600000).toISOString(),
        createdAt: "2026-11-01T01:00:00Z",
        version: 1,
        slotId: slots[3]?.id ?? null,
      },
    ];
  }

  function readBody(req: { on: (event: string, cb: (value?: Uint8Array) => void) => void }) {
    return new Promise<string>((resolve) => {
      const chunks: Uint8Array[] = [];
      req.on("data", (chunk) => {
        if (chunk) chunks.push(chunk);
      });
      req.on("end", () => {
        const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
        const out = new Uint8Array(total);
        let offset = 0;
        for (const chunk of chunks) {
          out.set(chunk, offset);
          offset += chunk.length;
        }
        resolve(new TextDecoder().decode(out));
      });
    });
  }

  function send(
    res: {
      statusCode: number;
      setHeader: (name: string, value: string) => void;
      end: (body?: string) => void;
    },
    status: number,
    body: string,
    headers: Record<string, string> = {},
  ) {
    res.statusCode = status;
    res.setHeader("content-type", "application/json; charset=utf-8");
    for (const [key, value] of Object.entries(headers)) res.setHeader(key, value);
    res.end(body);
  }

  return {
    name: "southleaf-admin-mock",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = new URL(req.url ?? "/", "http://mock.local");
        const path = url.pathname;
        if (!path.startsWith("/api/admin/v1")) return next();
        const method = req.method ?? "GET";

        if (path.endsWith("/thumbnail/content") || path.endsWith("/preview/content")) {
          const found = MEDIA.findIndex((id) => path.includes(id));
          const fill = MEDIA_COLORS[found] ?? "#C4A484";
          const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="320"><rect width="100%" height="100%" fill="${fill}"/></svg>`;
          res.statusCode = 200;
          res.setHeader("content-type", "image/svg+xml");
          res.end(svg);
          return;
        }

        if (path === "/api/admin/v1/auth/csrf" && method === "GET") {
          send(res, 200, envelope({ csrfToken: "mock-csrf-token", headerName: "X-CSRF-TOKEN" }));
          return;
        }
        if (path === "/api/admin/v1/auth/session" && method === "GET") {
          send(
            res,
            200,
            envelope({
              accountId: "mock.operator",
              permissions: [
                "CONTENT_READ",
                "CONTENT_EDIT",
                "CONTENT_PUBLISH",
                "RESERVATION_READ",
                "RESERVATION_CONFIRM",
                "RESERVATION_REJECT",
                "RESERVATION_CANCEL",
                "RESERVATION_RESCHEDULE",
                "ORDER_READ",
                "OUTBOX_READ",
                "OUTBOX_RECOVER",
                "BOOKING_RULES_WRITE",
                "SLOT_WRITE",
              ],
              expiresAt: "2026-11-02T10:30:00Z",
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/auth/login" && method === "POST") {
          send(
            res,
            200,
            envelope({
              accountId: "mock.operator",
              permissions: [
                "CONTENT_READ",
                "CONTENT_EDIT",
                "CONTENT_PUBLISH",
                "RESERVATION_READ",
                "BOOKING_RULES_WRITE",
                "SLOT_WRITE",
              ],
              expiresAt: "2026-11-02T10:30:00Z",
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/auth/logout" && method === "POST") {
          res.statusCode = 204;
          res.end();
          return;
        }
        if (path === "/api/admin/v1/content/catalog" && method === "GET") {
          send(res, 200, envelope({ revision, draft }), { ETag: `"c${revision}"` });
          return;
        }
        if (path === "/api/admin/v1/content/catalog" && method === "PUT") {
          const ifMatch = req.headers["if-match"];
          if (ifMatch !== `"c${revision}"`) {
            send(
              res,
              409,
              JSON.stringify({
                code: "VERSION_CONFLICT",
                requestId: "mock",
                message: "内容已在别处更新",
              }),
            );
            return;
          }
          const body = JSON.parse(await readBody(req)) as Draft;
          Object.assign(draft, body);
          revision += 1;
          versions.unshift({
            revision,
            updatedBy: "mock-operator",
            updatedAt: new Date().toISOString(),
          });
          send(res, 200, envelope({ revision, draft }), { ETag: `"c${revision}"` });
          return;
        }
        if (path === "/api/admin/v1/content/catalog/versions" && method === "GET") {
          send(res, 200, envelope(versions));
          return;
        }
        const versionMatch = path.match(/^\/api\/admin\/v1\/content\/catalog\/versions\/(\d+)$/);
        if (versionMatch && method === "GET") {
          send(res, 200, envelope({ revision: Number(versionMatch[1]), draft }));
          return;
        }
        if (path === "/api/admin/v1/content/catalog/preview" && method === "GET") {
          const publishable = draft.brand.name.trim().length > 0;
          send(
            res,
            200,
            envelope({ publishable, blockingReason: publishable ? null : "缺品牌名称" }),
          );
          return;
        }
        if (path === "/api/admin/v1/content/catalog/publish" && method === "POST") {
          const key = String(req.headers["idempotency-key"] ?? "");
          const cached = commands.get(key);
          if (cached) {
            send(res, 200, envelope(cached));
            return;
          }
          const result = {
            commandId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            releaseId: "mock-release",
            contentVersion: `mock-${revision}`,
            revision,
            completedAt: new Date().toISOString(),
            replayed: false,
          };
          commands.set(key, result);
          send(res, 200, envelope(result));
          return;
        }
        if (path === "/api/admin/v1/content/catalog/withdraw" && method === "POST") {
          send(res, 200, envelope({ status: "WITHDRAWN", revision }));
          return;
        }
        if (path === "/api/admin/v1/content/catalog/commands" && method === "GET") {
          const key = String(req.headers["idempotency-key"] ?? "");
          const found = commands.get(key);
          if (!found) {
            send(res, 404, JSON.stringify({ code: "NOT_FOUND", requestId: "mock" }));
            return;
          }
          send(res, 200, envelope(found));
          return;
        }
        if (path === "/api/admin/v1/media/folders" && method === "GET") {
          send(res, 200, envelope(folders));
          return;
        }
        const moveMatch = path.match(/^\/api\/admin\/v1\/media\/([^/]+)\/folder$/);
        if (moveMatch && method === "PUT") {
          const found = media.find((item) => item.mediaId === decodeURIComponent(moveMatch[1]));
          if (!found) {
            send(res, 404, JSON.stringify({ code: "RESOURCE_UNAVAILABLE", requestId: "mock" }));
            return;
          }
          const body = JSON.parse(await readBody(req)) as { folderId?: string | null };
          found.folderId = body.folderId ?? null;
          send(res, 200, envelope({ moved: true }));
          return;
        }
        if (path === "/api/admin/v1/media" && method === "GET") {
          const q = url.searchParams.get("q")?.trim().toLowerCase() ?? "";
          const folderId = url.searchParams.get("folderId");
          const rows = q
            ? media
                .filter((item) => (item.sourceFileName ?? "").toLowerCase().includes(q))
                .slice(0, 100)
            : media
                .filter((item) => (item.folderId ?? null) === (folderId || null))
                .slice(0, 500);
          send(res, 200, envelope(rows));
          return;
        }
        const mediaItem = path.match(/^\/api\/admin\/v1\/media\/([^/]+)$/);
        if (mediaItem && method === "GET") {
          const found = media.find((item) => item.mediaId === decodeURIComponent(mediaItem[1]));
          if (!found) {
            send(res, 404, JSON.stringify({ code: "RESOURCE_UNAVAILABLE", requestId: "mock" }));
            return;
          }
          send(res, 200, envelope(found));
          return;
        }
        if (path === "/api/admin/v1/media/uploads" && method === "POST") {
          const created = {
            mediaId: `44444444-4444-4444-8444-${String(media.length).padStart(12, "0")}`,
            status: "READY",
            sourceFormat: "image/png",
            sourceWidth: 640,
            sourceHeight: 480,
            createdAt: new Date().toISOString(),
            sourceFileName: null,
            folderId: url.searchParams.get("folderId"),
            variants: [],
          };
          media.unshift(created);
          send(res, 200, envelope({ media: created, replayed: false }));
          return;
        }
        if (path === "/api/admin/v1/works" && method === "GET") {
          send(res, 200, envelope(works));
          return;
        }
        if (path === "/api/admin/v1/works" && method === "POST") {
          workSerial += 1;
          const created = {
            workId: `cccccccc-cccc-4ccc-8ccc-${String(workSerial).padStart(12, "0")}`,
            version: 1,
            updatedAt: new Date().toISOString(),
            publicationState: "NEVER_PUBLISHED",
            publishedVersion: null,
            title: "",
            summary: "",
            subjectScopeRef: "",
            businessIds: [],
            media: [],
            coverMediaId: null,
            authorizationRecorded: false,
          };
          works.unshift(created);
          send(res, 200, envelope({ work: created, replayed: false }));
          return;
        }
        const workMatch = path.match(/^\/api\/admin\/v1\/works\/([^/]+)(\/.*)?$/);
        if (workMatch && method === "GET" && !workMatch[2]) {
          const found = works.find((item) => item.workId === decodeURIComponent(workMatch[1]));
          send(
            res,
            found ? 200 : 404,
            found ? envelope(found) : JSON.stringify({ code: "NOT_FOUND", requestId: "mock" }),
          );
          return;
        }
        if (workMatch && method === "PUT") {
          const found = works.find((item) => item.workId === decodeURIComponent(workMatch[1]));
          if (!found) {
            send(res, 404, JSON.stringify({ code: "NOT_FOUND", requestId: "mock" }));
            return;
          }
          const body = JSON.parse(await readBody(req)) as Partial<(typeof works)[number]>;
          Object.assign(found, body, {
            version: found.version + 1,
            updatedAt: new Date().toISOString(),
          });
          send(res, 200, envelope(found), { ETag: `"v${found.version}"` });
          return;
        }
        if (workMatch?.[2] === "/preview" && method === "GET") {
          const found = works.find((item) => item.workId === decodeURIComponent(workMatch[1]));
          send(
            res,
            200,
            envelope({
              work: found,
              publishable: Boolean(found?.title && found.coverMediaId),
              blockingReason: found?.title ? null : "缺标题",
              authorizationState: "ACCEPTED",
              acceptedProvenance: "MOCK",
            }),
          );
          return;
        }
        if (workMatch && method === "DELETE") {
          const index = works.findIndex((item) => item.workId === decodeURIComponent(workMatch[1]));
          if (index < 0) {
            send(res, 404, JSON.stringify({ code: "NOT_FOUND", requestId: "mock" }));
            return;
          }
          works.splice(index, 1);
          send(res, 200, envelope({ deleted: true }));
          return;
        }
        if (workMatch?.[2] === "/authorization" && method === "POST") {
          const found = works.find((item) => item.workId === decodeURIComponent(workMatch[1]));
          if (!found || !found.subjectScopeRef || found.media.length === 0) {
            send(
              res,
              422,
              JSON.stringify({
                code: "PUBLICATION_BLOCKED",
                reason: "AUTHORIZATION_FIELDS",
                requestId: "mock",
              }),
            );
            return;
          }
          found.authorizationRecorded = true;
          send(res, 200, envelope({ recorded: true }));
          return;
        }
        if (
          (workMatch?.[2] === "/publish" || workMatch?.[2] === "/withdraw") &&
          method === "POST"
        ) {
          const found = works.find((item) => item.workId === decodeURIComponent(workMatch[1]));
          if (found && workMatch[2] === "/publish") found.publicationState = "PUBLISHED";
          if (found && workMatch[2] === "/withdraw") found.publicationState = "WITHDRAWN";
          send(
            res,
            200,
            envelope({
              commandId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              operation: workMatch[2] === "/publish" ? "PUBLISH" : "WITHDRAW",
              status: "COMPLETED",
              workId: found?.workId,
              targetVersion: found?.version ?? 1,
              completedAt: new Date().toISOString(),
              replayed: false,
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/reservations/pending" && method === "GET") {
          send(res, 200, envelope(pendingReservations()));
          return;
        }
        const reservationMatch = path.match(
          /^\/api\/admin\/v1\/bookings\/reservations\/([0-9a-f-]+)$/i,
        );
        if (reservationMatch && method === "GET") {
          const found = pendingReservations().find((item) => item.id === reservationMatch[1]);
          if (!found) {
            send(
              res,
              404,
              JSON.stringify({ code: "NOT_FOUND", requestId: "mock", message: "没有这单预约" }),
            );
            return;
          }
          const orderId =
            found.id === "3c9a6900-0000-4000-8000-000000000001"
              ? "3c9a6900-0000-4000-8000-000000000010"
              : null;
          send(res, 200, envelope({ reservation: found, orderId, assignments: [] }), {
            ETag: `"r${found.version}"`,
          });
          return;
        }
        const orderMatch = path.match(/^\/api\/admin\/v1\/bookings\/orders\/([0-9a-f-]+)$/i);
        if (orderMatch && method === "GET") {
          const slots = weekSlots();
          send(
            res,
            200,
            envelope({
              id: orderMatch[1],
              reservationId: "3c9a6900-0000-4000-8000-000000000001",
              lifecycle: "ACTIVE",
              fulfilment: "SCHEDULED",
              currentRevision: 1,
              createdAt: "2026-11-01T01:00:00Z",
              reservationStatus: "PENDING_CONFIRMATION",
              snapshot: {
                packageVersionRef: "pkg-child-1",
                packageDisplayName: "虚构窗边半日",
                currencyCode: "CNY",
                amountMinorUnits: 128000,
                priceVersion: "p1",
                slotId: slots[0]?.id ?? "3c9a6900-0000-4000-8000-0000000000bb",
                startAt: slots[0]?.startAt ?? new Date().toISOString(),
                endAt: slots[0]?.endAt ?? new Date().toISOString(),
                businessZone: "Asia/Shanghai",
                displayOffset: "+08:00",
                scheduleRevision: 1,
                source: "MINI_PROGRAM",
                capturedAt: "2026-11-01T01:00:00Z",
              },
              revisions: [],
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/operating-rules/current" && method === "GET") {
          send(
            res,
            200,
            envelope(
              operatingRule.version === 0
                ? { version: 0, configured: false, rules: null }
                : { version: operatingRule.version, configured: true, rules: operatingRule.rules },
            ),
            { ETag: `"o${operatingRule.version}"` },
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/operating-rules/versions" && method === "GET") {
          send(
            res,
            200,
            envelope(
              operatingRule.version === 0
                ? []
                : [
                    {
                      version: operatingRule.version,
                      rules: operatingRule.rules,
                      updatedAt: "2026-11-01T01:00:00Z",
                    },
                  ],
            ),
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/operating-rules" && method === "PUT") {
          const ifMatch = req.headers["if-match"];
          if (ifMatch !== `"o${operatingRule.version}"`) {
            send(res, 409, JSON.stringify({ code: "VERSION_CONFLICT", requestId: "mock" }));
            return;
          }
          const body = JSON.parse(await readBody(req));
          operatingRule = {
            version: operatingRule.version + 1,
            rules: {
              minAdvanceHours: body.minAdvanceHours,
              maxAdvanceDays: body.maxAdvanceDays,
              pendingExpiryHours: body.pendingExpiryHours,
              slotDurationMinutes: body.slotDurationMinutes,
            },
          };
          send(
            res,
            200,
            envelope({ version: operatingRule.version, replayed: false, ...operatingRule.rules }),
            { ETag: `"o${operatingRule.version}"` },
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/slots/board" && method === "GET") {
          const from = Date.parse(url.searchParams.get("from") ?? "");
          const to = Date.parse(url.searchParams.get("to") ?? "");
          const slots = weekSlots()
            .filter((slot) => {
              const at = Date.parse(slot.startAt);
              if (Number.isNaN(from) || Number.isNaN(to)) return true;
              return at >= from && at < to;
            })
            .map((slot) => ({
              id: slot.id,
              startAt: slot.startAt,
              endAt: slot.endAt,
              availability: slot.availability,
              revision: slot.revision,
            }));
          send(res, 200, envelope(slots));
          return;
        }
        if (path === "/api/admin/v1/bookings/slots" && method === "POST") {
          if (operatingRule.version < 1) {
            send(res, 422, JSON.stringify({ code: "RULES_NOT_CONFIGURED", requestId: "mock" }));
            return;
          }
          const body = JSON.parse(await readBody(req));
          const start = new Date(`${body.date}T${body.startTime}:00+08:00`);
          const created = {
            id: `3c9a6900-0000-4000-8000-${String(mutableSlots.length + 20).padStart(12, "0")}`,
            startAt: start.toISOString(),
            endAt: new Date(start.getTime() + 2 * 3600000).toISOString(),
            businessZone: "Asia/Shanghai",
            displayOffset: "+08:00",
            status: "OPEN",
            capacity: 1,
            ruleVersion: "mock-1",
            revision: 1,
            availability: "OPEN",
          };
          mutableSlots.push(created);
          send(
            res,
            200,
            envelope({
              id: created.id,
              startAt: created.startAt,
              endAt: created.endAt,
              revision: 1,
              replayed: false,
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/slots/weeks" && method === "POST") {
          if (operatingRule.version < 1) {
            send(res, 422, JSON.stringify({ code: "RULES_NOT_CONFIGURED", requestId: "mock" }));
            return;
          }
          const body = JSON.parse(await readBody(req));
          const items = [];
          for (const date of body.dates ?? []) {
            for (const startTime of body.startTimes ?? []) {
              const start = new Date(`${date}T${startTime}:00+08:00`);
              const created = {
                id: `3c9a6900-0000-4000-8000-${String(mutableSlots.length + 40).padStart(12, "0")}`,
                startAt: start.toISOString(),
                endAt: new Date(start.getTime() + 2 * 3600000).toISOString(),
                revision: 1,
                replayed: false,
              };
              mutableSlots.push({
                ...created,
                businessZone: "Asia/Shanghai",
                displayOffset: "+08:00",
                status: "OPEN",
                capacity: 1,
                ruleVersion: "mock-1",
                availability: "OPEN",
              });
              items.push({ date, startTime, ok: true, reason: null, slot: created });
            }
          }
          send(res, 200, envelope(items));
          return;
        }
        const closeMatch = path.match(/^\/api\/admin\/v1\/bookings\/slots\/([0-9a-f-]+)\/close$/i);
        if (closeMatch && method === "POST") {
          const found = mutableSlots.find((slot) => slot.id === closeMatch[1]);
          if (!found) {
            send(res, 404, JSON.stringify({ code: "RESOURCE_UNAVAILABLE", requestId: "mock" }));
            return;
          }
          if (found.availability === "OCCUPIED") {
            send(res, 409, JSON.stringify({ code: "SLOT_OCCUPIED", requestId: "mock" }));
            return;
          }
          if (found.availability === "PENDING") {
            send(res, 409, JSON.stringify({ code: "SLOT_PENDING_REQUEST", requestId: "mock" }));
            return;
          }
          const alreadyClosed = found.availability === "CLOSED";
          if (!alreadyClosed) {
            found.availability = "CLOSED";
            found.status = "CLOSED";
            found.revision += 1;
          }
          send(
            res,
            200,
            envelope({
              id: found.id,
              startAt: found.startAt,
              endAt: found.endAt,
              revision: found.revision,
              replayed: alreadyClosed,
            }),
          );
          return;
        }
        if (path === "/api/admin/v1/bookings/slots" && method === "GET") {
          const from = Date.parse(url.searchParams.get("from") ?? "");
          const to = Date.parse(url.searchParams.get("to") ?? "");
          const slots = weekSlots().filter((slot) => {
            const at = Date.parse(slot.startAt);
            if (Number.isNaN(from) || Number.isNaN(to)) return true;
            return at >= from && at < to && slot.availability === "OPEN";
          });
          send(res, 200, envelope(slots));
          return;
        }
        if (path === "/api/admin/v1/notifications/outbox" && method === "GET") {
          send(
            res,
            200,
            envelope({
              deliveryMode: "TEST_SINK",
              externalDeliveryEnabled: false,
              pendingCount: 1,
              failures: [
                {
                  id: "3c9a6900-0000-4000-8000-0000000000cc",
                  intent: "RESERVATION_CONFIRMED",
                  status: "FAILED",
                  attempts: 2,
                  lastError: "PROVIDER_TIMEOUT",
                  dueAt: "2026-11-01T02:00:00Z",
                },
              ],
            }),
          );
          return;
        }
        if (path.includes("/recover") && method === "POST") {
          send(
            res,
            200,
            envelope({
              commandId: "3c9a6900-0000-4000-8000-0000000000dd",
              messageId: "3c9a6900-0000-4000-8000-0000000000cc",
              status: "QUEUED",
              replayed: false,
            }),
          );
          return;
        }
        send(
          res,
          404,
          JSON.stringify({ code: "NOT_FOUND", requestId: "mock", message: "模拟接口未实现" }),
        );
      });
    },
  };
}
