# Research: 轮播、档期、预约运营参数与管理端安全链

- **Query**: 查清目录草稿与 heroItems、档期 slot、Q04 运营参数、管理端安全链、测试体系的现有事实，供 `11-01-admin-config-gaps` 设计使用。
- **Scope**: internal
- **Date**: 2026-11-01

## Findings

### 1. 目录草稿与首页 heroItems

#### 1.1 CatalogDraft 定义与校验

`CatalogDraft` 是纯领域 record，不含发布权威或持久化类型。

```11:21:backend/admin-content/src/main/java/cn/zhinan/admincontent/domain/CatalogDraft.java
public record CatalogDraft(
    Brand brand,
    Contact contact,
    List<TeamMember> team,
    List<Channel> channels,
    List<Business> businesses,
    List<Package> packages,
    List<String> featuredBusinessIds,
    List<String> featuredPackageIds,
    List<String> featuredWorkIds,
    Map<String, String> mediaAltTexts) {
```

紧凑构造器拒绝 `brand`/`contact`/`mediaAltTexts` 为 null，以及任一列表为 null（`TEAM_REQUIRED`、`FEATURED_WORKS_REQUIRED` 等）。没有 `heroSlides`，也没有 Jackson 注解。见 `CatalogDraft.java` 28–40 行。

`empty()`（42–58 行）给出六类固定业务，精选 ID 与 `mediaAltTexts` 均为空。

`validated()`（136–196 行）是草稿结构校验，发布完整性另走 `publishable()`：

| 规则 | 失败 reason | 位置 |
|---|---|---|
| 团队 ≤50、渠道 ≤30、套餐 ≤200 | `CATALOG_COLLECTION_LIMIT` | 139–140 |
| id / position 重复 | `TEAM_ID_DUPLICATE` 等 | 141–149 |
| 业务必须且只能是六个固定 id | `BUSINESS_SET_INCOMPLETE` / `BUSINESS_IDENTITY_INVALID` | 150–162 |
| 已发布套餐的业务也必须已发布 | `PACKAGE_BUSINESS_UNAVAILABLE` | 165–169 |
| `mediaAltTexts` 的键集合必须等于实际引用的 mediaId；alt 非空白且 ≤120 | `MEDIA_ALT_SET_MISMATCH` / `MEDIA_ALT_INVALID` | 171–181 |
| 精选业务/套餐/作品去重；作品 id 符合 `[A-Za-z0-9_-]{1,128}` | `FEATURED_*_DUPLICATE` / `ID_INVALID` | 182–185 |
| 精选业务 ≤3、套餐 ≤3、作品 ≤6 | `HOME_FEATURE_LIMIT` | 186–188 |
| 精选业务/套餐必须已 `published` | `HOME_BUSINESS_UNAVAILABLE` / `HOME_PACKAGE_UNAVAILABLE` | 189–194 |

`publishable()`（199–211 行）在 `validated()` 之后还要求品牌名、英文名、分享标题，至少一种联系方式（电话、地址，或已发布且 `qrStatus=CONFIRMED` 的渠道），以及至少一个已发布业务。

媒体引用由 `mediaReferences()` / `publishedMediaReferences()` 收集：品牌 logo、成员肖像、渠道二维码、业务封面、套餐封面（82–97 行）。`PublicationSnapshot`（69–80、100–118 行）只保留已 `published` 的成员/渠道/业务/套餐，以及三组精选 ID。快照 record **不包含** `mediaAltTexts`。

领域失败类型是 `ContentException(ContentFailure.INVALID_REQUEST, reason)`（234–236 行）。

#### 1.2 JDBC：JSON 列，不是分表

管理库迁移 `V7__admin_catalog_authoring.sql`：

- `admin_catalog_draft`：单行 `singleton_id=1` + `current_revision`（3–9 行）。
- `admin_catalog_draft_revision`：`revision` 主键，`payload_json JSON NOT NULL`，`updated_by`，`updated_at`（11–18 行）。整份草稿是一条 JSON，不是业务/套餐分表。
- `admin_catalog_command`：幂等账本，`operation` 只允许 `PUBLISH`、`WITHDRAW`（20–34 行）。
- `admin_catalog_audit`：一条命令一条审计（36–48 行）。

`JdbcCatalogDraftRepository`：

- 当前稿：先读 `current_revision`，再按 revision 读 `payload_json`（25–46 行）。
- 保存：`SELECT ... FOR UPDATE`，`expectedRevision` 不符则 `VERSION_CONFLICT` / `CATALOG_REVISION_CONFLICT`；通过后 `revision+1` 插入新 JSON 行并更新头指针（80–116 行）。只追加，不更新旧行。

HTTP 保存时，控制器把校验后的 `CatalogDraft` 再用 Jackson 写成字符串交给 `saveDraft`，不是原样存客户端字节（`AdminContentController.java` 128–137 行）。`CatalogApplication.saveDraft`（43–50 行）先 `draft.validated()`，再限制 JSON 非空且 ≤262144 字节。

#### 1.3 迁移命名与位置

两套 Flyway，互不修改对方已发布脚本。

| 库 | 脚本目录 | 入口 | 历史表 |
|---|---|---|---|
| 管理库 | `backend/admin-migration-cli/src/main/resources/db/admin-migration/` | `AdminDatabaseMigrationMain`（35–44 行）：`classpath:db/admin-migration`，表 `flyway_admin_schema_history`，`cleanDisabled`，无 baseline、无 seed | 同上 |
| 公开库 | `backend/public-api/src/main/resources/db/migration/` | `DatabaseMigrationMain`（34 行）：`classpath:db/migration`。只有显式 `--seed-demo` 才灌演示数据，且 `catalog_release` 非空则拒绝（39–52 行） | Flyway 默认 |

已存在的管理脚本：`V2`–`V11`（`V1` 不在该目录；`V6` 预约、`V7` 目录草稿、`V8` 套餐事实、`V9`/`V10` 权限 CHECK、`V11` 预约偏好）。公开脚本：`V1__public_catalog.sql`、`V2__public_work_overlay.sql`、`V3__public_catalog_content.sql`。

命名是 Flyway 版本化：`V{n}__{snake_name}.sql`。规范（`.trellis/spec/backend/database-guidelines.md` 10–16 行，`directory-structure.md` 37 行）写明已应用的 V1 不可改，新变更用 V2+；运行时不做 migrate/repair/seed。下一次管理脚本应是 `V12__...sql`，下一次公开脚本应是 `V4__...sql`。

包装入口：

- 管理：`backend/scripts/Invoke-AdminDatabaseMigration.ps1`（`Validate` 或 `Migrate`）。
- 公开：`backend/scripts/Invoke-DatabaseMigration.ps1`（可选 `-TestDatabase`、`-SeedDemo`）。

预约集成测试**不走**这条 Flyway 链。`BookingDatabaseIT.applyMigration`（160–186 行）以及 `BookingConcurrencyIT`、`OutboxDatabaseIT`、`OutboxProcessRecoveryIT` 只执行 classpath 上的 `/db/admin-migration/V6__admin_identity_booking.sql`。测试副本在 `backend/admin-booking/src/test/resources/db/admin-migration/V6__admin_identity_booking.sql`。

#### 1.4 发布快照如何生成

`POST /api/admin/v1/content/catalog/publish`（`AdminContentController.java` 173–198 行）：

1. 权限 `CONTENT_PUBLISH`，`If-Match: "c{revision}"`，`Idempotency-Key`，请求体必须是空 JSON 对象。
2. 读当前草稿 JSON，反序列化为 `CatalogDraft`。
3. `CATALOG_JSON.writeValueAsString(currentDraft.publicationSnapshot())` 得到 `snapshot_json`。
4. `CatalogApplication.publish`（66–91 行）要求 `expectedRevision >= 1`，幂等键经 `ContentRules.idempotencyKey`，摘要是 `publish|{revision}`（不含正文）。先查重放；否则 `draft.publishable()`。
5. `JdbcCatalogPublicationRepository.publish`（61–114 行）在一个事务里：`FOR SHARE` 锁定草稿头并核对 revision；`qualifyMedia`（READY 且同时有 `thumbnail` 与 `preview`，324–354 行）；`qualifyWorkReferences`（作品 `PUBLISHED`、授权门 `VALID`、来源匹配、时间窗、公开指针可见且 release 一致，259–295 行）；`FOR UPDATE` 锁定 `active_catalog_publication`；向 `{publicSchema}.public_catalog_release` 插入不可变行（`content_version = "catalog-" + releaseId`，`snapshot_json = payload`）；投影媒体、变体、绑定；epoch+1 切换指针；写 command 与 audit。

撤回（`withdraw`，124–170 行）只把 `visible` 置 0 并增加 epoch，不改已插入的 release 行。

公开表 `V3__public_catalog_content.sql`：

- `public_catalog_release.snapshot_json JSON`（3–13 行）。
- `active_catalog_publication` 单行指针，种子 `(1, NULL, 0, 0)`（15–29 行）。
- `public_catalog_release_binding.owner_type` 只允许 `BRAND`、`TEAM_MEMBER`、`CONTACT_CHANNEL`、`BUSINESS`、`PACKAGE`（59–70 行）。没有首页轮播 owner。

#### 1.5 public-api 如何读发布快照

`PublicQueryService.readHome`（39–87 行）不读草稿。它 `projection()` → `CatalogReadService.load()` → `JdbcPublishedCatalogRepository.readCurrent()`。

`mergeCatalog`（约 99–279 行）读 `active_catalog_publication` 中 `visible=1` 的 release。没有可见指针时，品牌/业务/套餐/精选列表为空，作品仍来自下层快照（103–117 行）。有指针时把 `snapshot_json` 读成 `Map`，按键取出 brand、contact、businesses、packages、三组精选 ID（252–278 行）。`strings()`（294–297 行）在值不是 `List` 时返回空列表，因此旧快照缺某个列表键不会抛异常。解析器不认识 `heroSlides`。

`readHome` 的轮播算法（49–62 行）：

1. `visibleWorks(null, "recommended")`。
2. `featuredWorks` 为空则用全部可见作品，否则按精选 ID 过滤且保持精选顺序。
3. 这些作品再 `limit(3)`。
4. 每张 `HeroItem`：`id=作品 id`，`title=作品标题`，`subtitle` 在 `dataMode=demo` 时为 `DEMO / 展示样例`，否则 `公开作品`，`action=浏览作品`，`media=封面`，`target=work`，`targetId=作品 id`。

`PublicDtos.HeroItem`（64–71 行）字段：`id`、`title`、`subtitle`、`action`、`media`、`target`、`targetId`。`Home`（136–142 行）含 `heroItems`。

`CatalogProjection.media`（90–110 行）只返回 `bound()` 为真的绑定。`bound()`（51–87 行）的 `default` 是 `false`。未知 `owner_type` 的图片不会进公开 DTO，公开媒体读取也会空。

`PublicQueryServiceTest.homePreservesExplicitCurationWithoutDuplicatePackages`（37–41 行）断言当前夹具首页 `heroItems` 大小为 2。

#### 1.6 小程序消费

`frontend/api/public.js` `mapHome`（187–207 行）要求 `heroItems` 是数组。每张：

| 字段 | 校验 |
|---|---|
| `id` | `id()`：`/^[A-Za-z0-9_-]{1,128}$/`（12–15 行） |
| `title`、`subtitle`、`action`、`target` | `text()`：必须是 string，允许空串，不允许 null/非字符串（11 行） |
| `targetId` | 再次 `id()`，空串或 null 会使整份首页抛 `PUBLIC_RESPONSE_INVALID` |
| `media` | `mapMedia`：id、preview/thumbnail URL、alt、宽高、`sortOrder`（23–41 行）。不能为 null |

`unique()`（17–18 行）要求每张 `id` 互异，重复则整页无效。

`frontend/presentation/views.js` `homeView`（12–17 行）展开 home，不改写 `heroItems`。

`frontend/pages/index/index.wxml`（30–68 行）：有 `heroItems.length` 才渲染 swiper；否则空面板「暂时没有可展示的首页内容」。展示 `item.media.src`、`item.subtitle`、`item.title`、`item.action`，按钮文案后页面自己加 ` →`。`wx:key="id"`。

`frontend/pages/index/index.js` `openHero`（119–124 行）：

- `target === "package"` 且有 `targetId` → `/pages/package/pkgdetail?packageId=`
- 否则只要有 `targetId` → `/pages/works/workdetail?workId=`（**不看 target**）
- 否则 `switchTab` 到作品列表

页面上已有、但轮播点击未使用的跳转：

- `goServices(id)`（142–146 行）：把 `globalData.serviceBusinessId` 设为 id，再 `switchTab` 到 `/pages/package/package`。`package.js` 60–65 行读取后清空。
- `goAbout()`（148–151 行）：`switchTab` 到 `/pages/about/about`。

`frontend/app.json` 3–11 行没有业务详情页。业务、套餐列表、关于我们都是 tab。

文档：`docs/设计笔记集/06_客户端/小程序/02_状态反馈与接口消费.md` 145 行写明轮播数量跟 `heroItems` 走，页面不写死三张或五张。`docs/设计笔记集/04_接口与外部契约/01_公开查询接口.md` 80 行只列出 `heroItems: List<PublicHeroItemDTO>`，没有字段表。

管理端草稿类型 `admin-web/src/content/api.ts` `CatalogDraftInput`（61–119 行）字段与 Java record 一一对应，没有轮播。保存是整份 `PUT /content/catalog`。

### 2. 档期

#### 2.1 表结构

`V6__admin_identity_booking.sql` 112–133 行。注释写明档期是物化行，不是临时计算。

`booking_service_slot`：

| 列 | 约束 |
|---|---|
| `id CHAR(36)` | 主键 |
| `start_at` / `end_at DATETIME(6)` | `end_at > start_at` |
| `business_zone VARCHAR(64)` | 非空。领域层要求 IANA 时区，拒绝 `+08:00` 这种偏移（`BookingRules.zone` 135–140 行） |
| `display_offset VARCHAR(16)` | 非空，与时区分开存 |
| `status VARCHAR(16)` | 只能 `OPEN` 或 `CLOSED` |
| `capacity INT` | 默认 1，CHECK `capacity = 1` |
| `rule_version VARCHAR(64)` | 非空字符串，**不是**外键 |
| `revision INT` | 默认 1 |
| `created_at` / `updated_at` | |

索引：`(status, start_at, id)`。没有时间重叠的唯一约束。

`booking_slot_occupancy`（162–175 行）：`slot_id` 主键，所以同一档期最多一行占用；`reservation_id` 唯一。注释写明这是并发护栏，待确认申请不写这张表。

`booking_reservation.status`（155–156 行）：`PENDING_CONFIRMATION`、`CONFIRMED`、`REJECTED`、`EXPIRED`、`CANCELLED`。`slot_id` 可空。公开侧插入申请时 `slot_id` 写 NULL，偏好写入 `booking_request_preference`（`JdbcPublicBookingRepository.java` 194–208 行）。

迁移脚本和 `admin-migration-cli` 中都没有 `INSERT INTO booking_service_slot`。没有档期种子，也没有档期 CLI。

#### 2.2 谁创建、状态机、与占用的关系

创建只存在于 `BookingApplication.createSlot`（363–385 行）和 `JdbcScheduleRepository.createSlot`（63–89 行）。插入即 `status='OPEN'`、`revision=1`。调用方传入 `actorId`、起止、`businessZone`、`displayOffset`、`ruleVersion`、`capacity`。领域校验：

- 时长 5–1440 分钟（`BookingRules` 23–24、161–167 行），否则 `SLOT_RANGE_INVALID` / `SLOT_DURATION_INVALID`。
- `capacity` 必须为 1，否则 `CAPACITY_NOT_SUPPORTED`（170–174 行）。
- `ruleVersion` 只校验形态 `[A-Za-z0-9._:-]{1,64}`（60–63 行），不查配置表。

`BookingRules.MAX_SLOTS_PER_DAY = 24`（25 行）没有其他引用，创建路径不强制每日上限。

全仓库 Java 里，`createSlot` 的生产调用只有 `BookingApplication` → JDBC。`AdminBookingController` 没有开放档期的映射。测试里会调用（`BookingDatabaseIT`、`BookingConcurrencyIT`）。

关闭：`BookingApplication.closeSlot`（387–396 行）→ SQL（`JdbcScheduleRepository.java` 93–99 行）：

```sql
UPDATE booking_service_slot
SET status = 'CLOSED', revision = revision + 1, updated_at = ?
WHERE id = ? AND status = 'OPEN'
```

更新行数为 0 则 `NOT_FOUND` / `SLOT_NOT_FOUND`。**不读** `booking_slot_occupancy`，不删除行。

`BookingConcurrencyIT.closingASlotAfterConfirmationKeepsTheOrderIntact`（752–775 行）锁定的行为是：确认之后再 `closeSlot`，订单和占用都还在，预约仍是 `CONFIRMED`。同文件 681–749 行说明确认路径用 `SELECT ... FOR UPDATE` 锁档期行；未提交的关闭会让确认等到锁超时，提交关闭后再确认得到 `SLOT_CLOSED`。

确认占档（`BookingApplication.java` 518–537 行）：`findSlotForUpdate` → 读占用 → `assertSlotUsable`（档期存在、`OPEN`、占用为空或就是本预约，250–258 行）→ `occupy`。主键冲突变成 `SLOT_UNAVAILABLE`。

公开可约列表（`JdbcPublicBookingRepository.java` 130–154 行）只含 `status='OPEN'` 且没有占用行的档期。待确认申请不占这张表，因此关闭「未被占用」若只看 `booking_slot_occupancy`，不会把仍指向该档期的待确认偏好算作占用。

状态只有两态：`OPEN` → `CLOSED`。没有删除，没有从 `CLOSED` 回到 `OPEN` 的代码。

#### 2.3 现有只读接口

`GET /api/admin/v1/bookings/slots?from&to`（`AdminBookingController.java` 91–107 行）。

- 权限：`RESERVATION_READ`（控制器内再次检查，不是只靠 Spring Security）。
- `from` 必须早于 `to`，否则 400 `TIME_RANGE_INVALID`。
- 最多 100 条。数据来自 `BookingApplication.openSlots` → `listOpenSlots`（`JdbcScheduleRepository.java` 48–59 行）：`OPEN`、`start_at` 落在 `[from, to)`、且不存在占用行。
- 响应 `SlotView`（422–433 行）：`id`、`startAt`、`endAt`、`businessZone`、`displayOffset`、`status`、`capacity`、`ruleVersion`、`revision`。时间是 `Instant.toString()`。

安全匹配器只登记了这个 GET，没有 POST 档期（`AdminWebSecurityConfiguration.java` 107–123 行）。

### 3. 预约运营参数（Q04）

#### 3.1 已确认的文档口径

`docs/计划/P02_阶段二目标与实施计划.md` 201–208 行（2026-10-25）是生效口径，优先于文中更早的「待确认」：

- Q04：营业时区、营业日、服务时长、缓冲、跨午夜、节假日、开放提前期与最远日期、取消与改期条件、容量，都由后台配置；持久化在管理库并带版本号；生成和校验引用当前版本；未配置则拒绝，不猜默认值。
- 同表 Q02（207 行）：阶段二申请仍按创建后 48 小时有效；确认必须人工复核；申请不占硬档。

`docs/开发与演示/阶段二实施跟踪.md` 374 行、`docs/开发与演示/阶段二2B对接清单.md` 37 行复述同一句。`docs/设计笔记集/06_客户端/管理后台/02_内容预约订单运营.md` 42 行仍写「Q04 未确认、不自动生成时段」；按 P02 第 12 节，该历史表述不再是否决口径。

#### 3.2 代码里实际配置在哪里

**没有**运营参数表，**没有**带版本号的规则行，**没有**后台读写接口。

现存的相关值：

| 值 | 位置 | 是否版本化 |
|---|---|---|
| `SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS` | 环境变量，1–720 小时（`PublicBookingProperties.java` 30、133–134 行） | 否。进程配置。缺任一必填键且 `MODE` 不是缺省关闭，启动直接 `CONFIGURATION` 拒绝（88–99、191–193 行） |
| `SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE` | 同上，必须是 `ZoneId` 能解析的时区（135–139 行） | 否 |
| `SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS` | 同上，可约套餐清单 | 否 |
| `BookingRules.PENDING_LIFETIME = 48 小时` | 常量（20–21、196–207 行） | 否。仅当 `createReservation` 的 `lifetime == null` 时使用 |
| 档期上的 `rule_version` | 调用 `createSlot` 的人传入的字符串 | 不是配置版本外键 |
| `capacity = 1` | 表 CHECK + `BookingRules.capacity` | 其他容量直接拒绝 |

公开申请过期时间是 `now.plus(properties.requestLifetime())`（`PublicBookingService.java` 151 行），用的是环境变量小时数，不是 `PENDING_LIFETIME`。

`BookingRules` 类注释（14–18 行）写「经营数字由调用方提供，本类不读配置」。与 P02「未配置则拒绝」相比：调用方今天可以传入任意合法 `ruleVersion` 和 `lifetime`，领域层不会因为缺少规则版本而拒绝。

管理端预约进程配置 `AdminBookingProperties` 只有模式、数据库账号和 `SYNTHETIC`/`PRODUCTION`，没有经营参数。

#### 3.3 未配置时如何拒绝

分两层，都**不是**「规则版本缺失」：

1. 环境变量一个都没给：`PublicBookingProperties.load` 返回 `DISABLED`（91–94 行）。`PublicBookingService.ensureEnabled`（253–256 行）对查档、套餐、建申请都抛 `SERVICE_UNAVAILABLE` / `DISABLED`。
2. 给了 `SOUTHLEAF_PUBLIC_BOOKING_MODE` 但集合不完整或自相矛盾：进程启动失败，不会降级成默认时区或默认套餐（10–13、191–193 行）。
3. 模式已启用时，建申请还要求套餐 ref 在环境变量清单内（`PACKAGE_REF_UNKNOWN`，122–123 行）、档期行 `OPEN` 且无占用（140–142 行）、该客户没有未过期的待确认申请（133–138 行）。这些都不是 Q04 参数表。

公开查询窗口 `PublicBookingRules.window`（56–67 行）用营业时区解释 `from`/`to` 两个本地日期，跨度最多 31 天（`MAX_WINDOW`，20 行），起点不得早于现在减 1 天。这里没有「最早可约提前量」或「最远可约日期」配置项。

### 4. 管理端安全链

#### 4.1 路由权限矩阵

`AdminWebSecurityConfiguration`（48–96 行）只匹配 `/api/admin/v1/**`。CSRF 开启，CORS 关，会话 `STATELESS`，表单登录关闭。`authorizeHttpRequests`：

| 方法 | 路径 | Spring 层 | 控制器内权限 |
|---|---|---|---|
| GET | `/auth/csrf`、`/auth/session` | `permitAll` | — |
| POST | `/auth/login`、`/auth/logout` | `permitAll` | — |
| 内容路由 | 见下 | 仅当 `runtime.content().enabled()`，否则匹配器恒假；匹配到则 `permitAll` | 每个方法自己 `handle(..., AdminPermission)` |
| 发件箱 | GET outbox、POST recover | `outbox().enabled()` 同样处理 | `OUTBOX_READ` / `OUTBOX_RECOVER` |
| 预约 | 见下 | `booking().enabled()` 同样处理 | 见下 |
| 其他 | `anyRequest().denyAll()` | 403 `ACCESS_DENIED`（CSRF 失败则 `CSRF_REJECTED`，69–75 行） | — |

预约匹配器（107–123 行）只有：

- GET `.../reservations/pending`、`.../reservations/{id}`、`.../slots`、`.../orders/{id}`
- POST `.../cancel|reschedule|confirm|reject`

内容匹配器（125–148 行）含媒体、作品和：

- GET/PUT `/content/catalog`
- GET preview、versions、versions/{revision}、commands
- POST publish、withdraw
- GET `/commands/{commandId}`

控制器才是权限边界。预约 `handle`（261–277 行）和内容 `handle`（约 430–448 行）都是：无会话 401 `AUTHENTICATION_REQUIRED`；会话存储失败 503 `AUTHORIZATION_UNAVAILABLE`；`principal.permissions()` 不含所需枚举则 403 `CAPABILITY_REQUIRED`。

预约写操作权限（`AdminBookingController.java`）：取消 `RESERVATION_CANCEL`，改期 `RESERVATION_RESCHEDULE`，确认 `RESERVATION_CONFIRM`，拒绝 `RESERVATION_REJECT`。读档期是 `RESERVATION_READ`。

内容：读 `CONTENT_READ`，保存草稿 `CONTENT_EDIT`，发布/撤回 `CONTENT_PUBLISH`。

#### 4.2 权限常量与库约束

枚举：`backend/admin-security-core/src/main/java/cn/zhinan/adminsecurity/domain/AdminPermission.java` 4–19 行。当前 16 个：`CONTENT_READ/EDIT/PUBLISH`、`PUBLIC_AUTHORIZATION_REVIEW/REVOKE`、`RESERVATION_READ/CONFIRM/REJECT/CANCEL/RESCHEDULE`、`ORDER_READ`、`OUTBOX_READ/RECOVER`、`AUDIT_READ`、`STAFF_ACCESS_MANAGE`。没有档期或经营参数专用权限。

`admin_account_permission.permission_code` 的 CHECK 必须与枚举一致，否则插入失败。`V9` 曾漏 `RESERVATION_REJECT`；`V10__admin_reservation_reject.sql` 用 `DROP CHECK` + 新建 `ck_admin_account_permission_code` 补齐（7–20 行）。新增枚举值必须再来一次同样的替换，不能改旧脚本。

P02 Q06（210 行）：不建多角色；能力仍逐请求校验；阶段二把已实现能力授给同一个管理员；禁止默认管理员和默认口令，账号经 CLI 初始化。

#### 4.3 CSRF、Origin、If-Match、Idempotency-Key

CSRF（`AdminWebSecurityConfiguration.java` 29–49 行）：`CookieCsrfTokenRepository`，cookie 名来自 `AdminAuthController.CSRF_COOKIE`，请求头 `X-CSRF-TOKEN`，cookie `Secure`、`SameSite=Strict`、`Path=/`、`HttpOnly=false`。覆盖 `/api/admin/v1/**` 的非安全方法。失败在登录/登出路径或 `CsrfException` 时返回 `CSRF_REJECTED`，其他拒绝是 `ACCESS_DENIED`。

`AdminOriginFilter` 在 CSRF 之前（50 行；过滤器 27–47 行）。非 GET/HEAD：

- `Origin` 不在策略内，或连接不是 HTTPS：403 `ORIGIN_REJECTED`。
- PUT：必须是 `application/json`（charset 若有则只能 utf-8），无压缩，**正文 ≤ 16 KiB**（19、71–84 行），否则 413 `PAYLOAD_TOO_LARGE`。
- POST：同样必须是 JSON，**正文 ≤ 4 KiB**（86–102 行）。上传路径除外。

内容控制器自己的目录正文上限是 262144（`AdminContentController.java` 54 行），比过滤器的 16 KiB 宽。请求先过过滤器。

If-Match：

- 目录：`catalogIfMatch`（551–562 行）要求 `"c{revision}"`，`c0` 合法。缺失 → `PRECONDITION_REQUIRED` → HTTP 428。格式不对 → 400 `INVALID_REQUEST` / `IF_MATCH_INVALID`。发布要求 revision ≥ 1。
- 预约写：`If-Match` 模式 `"r{version}"`（50、319–325 行）。缺失或格式不对 → 428 `IF_MATCH_REQUIRED`。档期只读接口不用它。

Idempotency-Key：

- 内容：`ContentRules.idempotencyKey`（`ContentRules.java` 27、35–37 行），`[A-Za-z0-9_-]{16,64}`。目录保存不用它；发布和撤回用。摘要是 `publish|{revision}` 或 `withdraw|{revision}`，重放键相同但摘要不同 → 409 `IDEMPOTENCY_KEY_REUSE`。命令表 operation CHECK 只有这两种，保存草稿不能塞进这张表，除非新迁移放宽 CHECK。
- 预约：控制器把头传给应用层，规则相同（16–64）。公开小程序是另一套：`[A-Za-z0-9._:-]{8,64}`（`PublicBookingRules.java` 36–38 行）。
- `booking_command.operation` 是 `VARCHAR(48)`，没有 operation 枚举 CHECK（`V6` 259–268 行）。新操作名只要不超过 48 字符就可以插入。

新写接口要接上的位置：

1. `AdminWebSecurityConfiguration` 里对应的 `bookingRoutes` 或 `contentRoutes`（或新的匹配器）。不登记会被 `denyAll`。
2. 控制器 `handle(request, AdminPermission.XXX, ...)`。
3. 若新增权限：`AdminPermission` 与下一次权限 CHECK 迁移（`V12` 若尚无别的 V12）必须同一词汇。
4. 写请求自动经过 `AdminOriginFilter` 和 CSRF；PUT/POST 体积上限是过滤器上的 16 KiB / 4 KiB。
5. 乐观锁沿用现有 ETag 形状；创建类命令沿用 `Idempotency-Key` + `booking_command` 或目录命令表。目录命令表不改 CHECK 就不能记录新 operation。

### 5. 测试体系

相关测试（不是完整清单）：

| 测试 | 覆盖 |
|---|---|
| `backend/admin-content/src/test/java/cn/zhinan/admincontent/domain/CatalogDraftTest.java` | 草稿结构与发布快照过滤 |
| `backend/public-api/src/test/java/cn/zhinan/uniapp/publicquery/application/PublicQueryServiceTest.java` | `readHome` 精选与 hero 数量 |
| `backend/public-api/src/test/java/cn/zhinan/uniapp/integration/DatabasePublicApiIT.java` | 公开库集成；需要 `SOUTHLEAF_TEST_DB_URL` 等 |
| `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/domain/BookingRulesTest.java` | 含容量只能为 1 |
| `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/application/BookingApplicationTest.java` | 含内存版 `createSlot` / `closeSlot` |
| `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/integration/BookingDatabaseIT.java` | 真实 MySQL 上的 V6 副本 |
| `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/integration/BookingConcurrencyIT.java` | 占档竞态；关闭已确认档期仍保留订单 |
| `backend/admin-runtime/src/test/java/cn/zhinan/adminruntime/AdminBookingControllerTest.java` | 预约 HTTP，含 `If-Match` 与 `Idempotency-Key` |
| `backend/admin-runtime/src/test/java/cn/zhinan/adminruntime/AdminOriginFilterTest.java` | Origin / 体积 |
| `backend/public-api/src/test/java/cn/zhinan/uniapp/booking/PublicBookingServiceTest.java`、`PublicBookingControllerTest.java` | 公开预约环境变量夹具 |
| `frontend/tests/native-pages.test.mjs` | 首页夹具 `heroItems: []`，没有跳转目标断言 |

`admin-runtime` 测试目录里没有目录 `PUT/publish` 的控制器测试。`admin-content` 没有目录 JDBC 集成测试类。

运行：

- 单元层：各模块 `mvnw.cmd test`。质量规范写明这不是数据库验收（`.trellis/spec/backend/quality-guidelines.md` 15 行）。
- 预约库：`backend/scripts/Test-BookingDatabaseIntegration.ps1`。它起隔离实例（默认端口 3307，拒绝 3306），再 `mvnw.cmd -pl admin-booking verify`（含 Failsafe `*IT`），然后 `check-booking-sql-columns.py` 对照 V6 与 V8。
- 全反应器：`backend/scripts/Test-DatabaseIntegration.ps1`。先要求隔离 IT 环境变量，URL 端口不得为 3306，再 `mvnw.cmd verify -DskipITs=false -Pc3-isolated-it`。
- `admin-booking/pom.xml` 与 `public-api/pom.xml` 的 Failsafe 绑定 `integration-test` + `verify`，所以 `*IT` 在 `verify` 而不是 `test`。

规范还要求：不使用 H2；不在运行时迁移；隔离测试不得碰现有 3306 服务。

### Files Found

| File Path | Description |
|---|---|
| `backend/admin-content/src/main/java/cn/zhinan/admincontent/domain/CatalogDraft.java` | 草稿 record、校验、发布快照 |
| `backend/admin-content/src/main/java/cn/zhinan/admincontent/adapter/jdbc/JdbcCatalogDraftRepository.java` | 草稿 JSON 追加与乐观锁 |
| `backend/admin-content/src/main/java/cn/zhinan/admincontent/application/CatalogApplication.java` | 保存、预览、发布、撤回 |
| `backend/admin-content/src/main/java/cn/zhinan/admincontent/adapter/jdbc/JdbcCatalogPublicationRepository.java` | 发布事务、媒体与作品资格、公开指针 |
| `backend/admin-migration-cli/src/main/resources/db/admin-migration/V7__admin_catalog_authoring.sql` | 草稿 JSON 表 |
| `backend/public-api/src/main/resources/db/migration/V3__public_catalog_content.sql` | 公开快照与绑定 CHECK |
| `backend/public-api/src/main/java/cn/zhinan/uniapp/publicquery/application/PublicQueryService.java` | `readHome` 回退轮播 |
| `backend/public-api/src/main/java/cn/zhinan/uniapp/publicquery/application/CatalogProjection.java` | 媒体绑定资格 |
| `backend/public-api/src/main/java/cn/zhinan/uniapp/dto/PublicDtos.java` | `HeroItem` |
| `frontend/api/public.js` | `mapHome` 字段校验 |
| `frontend/pages/index/index.js` | `openHero` |
| `backend/admin-migration-cli/src/main/resources/db/admin-migration/V6__admin_identity_booking.sql` | 档期与占用 |
| `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/application/BookingApplication.java` | `createSlot` / `closeSlot` / 确认锁 |
| `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/domain/BookingRules.java` | 时长、容量、48 小时常量 |
| `backend/admin-runtime/src/main/java/cn/zhinan/adminruntime/AdminBookingController.java` | 只读 slots |
| `backend/public-api/src/main/java/cn/zhinan/uniapp/booking/config/PublicBookingProperties.java` | 预约环境变量 |
| `backend/admin-runtime/src/main/java/cn/zhinan/adminruntime/AdminWebSecurityConfiguration.java` | 路由匹配与 CSRF |
| `backend/admin-runtime/src/main/java/cn/zhinan/adminruntime/AdminOriginFilter.java` | Origin 与正文上限 |
| `backend/admin-security-core/src/main/java/cn/zhinan/adminsecurity/domain/AdminPermission.java` | 权限枚举 |
| `docs/计划/P02_阶段二目标与实施计划.md` | Q02/Q04 生效口径 |

### Code Patterns

- 目录草稿是单例头指针 + 追加 JSON 修订。发布把 `PublicationSnapshot` 写入公开库不可变 release，再切换 `active_catalog_publication`。
- 首页轮播不在快照里。`readHome` 用最多 3 个可见精选作品现场生成，目标恒为 `work`。
- 档期是物化行，状态只有 `OPEN`/`CLOSED`。占用是另一张表的主键。待确认申请不占档。现有 `closeSlot` 不看占用。
- 管理端 Spring Security 对业务路由 `permitAll`，真正的登录和权限在控制器 `handle`。新路径必须同时进入匹配器和 `handle`。
- 经营参数的文档要求版本化；代码里只有环境变量、48 小时常量，以及档期上的自由 `rule_version` 字符串。

### External References

无。本次只读仓库与项目文档。

### Related Specs

- `.trellis/spec/backend/database-guidelines.md` — 迁移与公开库只读账号；已应用脚本不可改。
- `.trellis/spec/backend/directory-structure.md` — 公开查询分层；`mvnw test` 与 `Test-DatabaseIntegration.ps1` 的分工。
- `.trellis/spec/backend/error-handling.md` — 公开错误不泄漏内部信息；存储失败不是空目录。
- `.trellis/spec/backend/quality-guidelines.md` — 领域层不依赖 Spring/JDBC；数据库验收走 `verify`。
- `.trellis/spec/guides/cross-layer-thinking-guide.md` — 草稿 JSON、公开 DTO、小程序 `mapHome` 是三套形状，必须分别写明。

## Caveats / Not Found

- 未找到 `heroSlides`、运营参数表、档期种子、档期 CLI、档期开放/关闭 HTTP。
- 未连数据库，未核对 3306 上实际已应用的 Flyway 版本。下一次脚本号以仓库文件最大版本为准：管理库 `V11`，公开库 `V3`。
- `MAX_SLOTS_PER_DAY` 只有常量定义，没有调用点。
- 目录 HTTP 没有对应的 `AdminContentController` 测试。
- P02 的 Q02（申请固定 48 小时）和 Q04（过期时间可配置）同时有效。代码两边都有痕迹：公开入口用环境变量小时数，管理端 `lifetime == null` 时用 48 小时常量。哪一个约束新接口，文档没有再裁定。
