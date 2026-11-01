# 轮播、档期、预约规则：设计草案

供主会话审查。事实依据见 `research/findings.md`。文中路径、校验和错误码沿用现有模块；产品规则没有被代码或 P02 钉死的地方写在文末「待决问题」，不在正文里选边。

建议实现顺序见 `implement.md`：轮播 → 单个档期 → 预约规则 → 按周批量。按周批量要引用当前规则版本，所以不能排在规则读写之前。

## 1. 首页轮播 `heroSlides`

### 1.1 数据流

```
管理端 PUT 整份草稿
  → CatalogDraft.heroSlides（结构校验）
  → admin_catalog_draft_revision.payload_json
  → 预览 / 发布再做目标与媒体资格
  → PublicationSnapshot.heroSlides
  → public_catalog_release.snapshot_json
  → 同时把启用项的图片写入 public_catalog_release_binding
  → readHome 投影为现有 HeroItem
  → frontend/api/public.js mapHome
  → pages/index openHero
```

保存、预览、发布、撤回继续走现有目录命令，不新增目录 URL。撤回仍只把公开指针 `visible` 置 0。

### 1.2 数据库迁移

管理库草稿仍是 JSON 列，**不为轮播建分表**。

公开库新增 `V4__public_catalog_hero_binding.sql`（不改 `V3`）：

- 放宽 `public_catalog_release_binding` 的 `owner_type` CHECK，在现有五类之外加入 `HERO`。
- 做法与 `V10` 替换权限 CHECK 相同：`DROP CHECK` 后按完整允许列表重建。绑定主键仍是 `(release_id, owner_type, owner_id, position)`。
- `owner_id` 使用该张幻灯片的 id（见 1.3）。`position` 使用幻灯片顺序。

已发布的旧 release 不回写。没有 `heroSlides` 键的快照继续被 `JdbcPublishedCatalogRepository` 当成空列表。

### 1.3 领域模型

在 `CatalogDraft` 增加最后的列表分量之前，紧凑构造器对 **缺失的 JSON 列表** 必须收成空列表，不能走现有 `copy(..., REQUIRED)`。否则已保存的修订在 `readCatalog` 时会变成 `STORAGE_UNAVAILABLE`。`PublicationSnapshot` 同样带上该列表（只含需要公开的项，见下）。

草稿元素（任务给定的字段，加上小程序合同迫使要有的 id）：

| 字段 | 草稿 | 说明 |
|---|---|---|
| `id` | 必填，`[A-Za-z0-9_-]{1,128}`，列表内唯一 | 任务字段表没有它。公开 `HeroItem.id` 和 `wx:key`、`mapHome` 的 `unique()` 都靠它。不使用目标 id，避免两张幻灯片指向同一作品时整页被判无效 |
| `mediaId` | 必填，同一 id 规则 | 进入现有 `mediaReferences()`，因而必须出现在 `mediaAltTexts`，描述非空白且 ≤120。这就是「图片需有描述」 |
| `title` | 字符串，≤255，允许空白与否见待决 1 | |
| `subtitle` | 可选。存储用空串，不用 null | `mapHome` 的 `text()` 拒绝 null |
| `actionText` | 字符串，≤64 | 公开字段名仍是 `action`。页面会再加 ` →`，文案本身不要带箭头 |
| `target.type` | `WORK` \| `PACKAGE` \| `BUSINESS` \| `ABOUT` | 公开 DTO 用小写 `work` / `package` / `business` / `about`，与现有 `target === "package"` 一致 |
| `target.id` | 除待决 2 外，符合现有 id 规则 | `ABOUT` 若没有业务 id，公开 `targetId` 仍不能是空串 |
| `enabled` | boolean | |
| `position` | `≥ 0`，列表内唯一 | 公开输出按 position 升序，再按 id |

`validated()` 增加：

- 列表最多 5 张。计数是否包含 `enabled=false` 见待决 3。
- 上述形状、去重、媒体描述集合仍必须与 `mediaAltTexts` 完全一致（沿用 `MEDIA_ALT_SET_MISMATCH`）。

`publishable()` / `preview()` 在结构通过后追加（只针对待决 3 认定的「要公开」的项）：

- `WORK`：沿用 `qualifyWorkReferences`（已发布、授权窗有效、公开指针一致）。失败 reason 保持 `WORK_NOT_PUBLISHABLE`。
- `PACKAGE`：草稿内存在、`published=true`，且所属业务已发布。否则新 reason `HERO_PACKAGE_UNAVAILABLE`。
- `BUSINESS`：六个固定 id 之一且 `published=true`。否则 `HERO_BUSINESS_UNAVAILABLE`。
- `ABOUT`：无第二套内容 id 可核对。是否还要检查关于页最低限度（品牌名等）见待决 2。
- 图片：沿用 `qualifyMedia`（`READY`，且 thumbnail 与 preview 都在）。失败仍是 `MEDIA_NOT_READY`。
- 绑定 owner 使用 `HERO` + 幻灯片 id。`CatalogProjection.bound()` 必须认识 `HERO`，否则 `media()` 返回空，公开首页要么丢图，要么因为 `mapMedia` 拒绝 null 而整页失败。

`heroSlides` 为空，或公开时没有任何可展示项：`readHome` **保持现在的算法**（可见精选作品，缺精选则全部可见作品，最多 3 张，副标题和「浏览作品」写死，`target=work`）。不要在小程序里再写一套回退。

有可展示项时不再混入这 3 张回退。某一张在阅读时目标已不可见（作品授权随后失效、套餐已不在快照中）：是跳过该张，还是让首页失败，见待决 4。跳过之后若一张不剩，再走上面的回退。

### 1.4 管理端接口

不新开 URL。

| 动作 | 现有接口 | 权限 | 并发 |
|---|---|---|---|
| 保存 | `PUT /api/admin/v1/content/catalog` | `CONTENT_EDIT` | `If-Match: "c{revision}"`。保存不用 Idempotency-Key |
| 预览 | `GET /api/admin/v1/content/catalog/preview` | `CONTENT_READ` | 读当前修订 |
| 发布 | `POST /api/admin/v1/content/catalog/publish` | `CONTENT_PUBLISH` | `If-Match` + `Idempotency-Key`，体为 `{}` |
| 撤回 | `POST /api/admin/v1/content/catalog/withdraw` | `CONTENT_PUBLISH` | 同上 |

错误码沿用内容控制器：结构问题 400 `INVALID_REQUEST`，reason 放在现有 reason 字段；缺 If-Match 428 `PRECONDITION_REQUIRED`；修订冲突 409 `VERSION_CONFLICT`；发布资格 422 `PUBLICATION_BLOCKED`；媒体未就绪 422 `MEDIA_NOT_READY`。

安全链：路径已在 `contentRoutes`。仍受 CSRF、`AdminOriginFilter`（PUT ≤ 16 KiB）约束。轮播五张的文本增量很小；若整份草稿已经顶着 16 KiB，过滤器会先于控制器的 256 KiB 上限返回 413 `PAYLOAD_TOO_LARGE`。本设计不放宽该上限。

`admin-web` 的 `CatalogDraftInput` 必须带上 `heroSlides`。保存是整份替换。旧页面若省略该字段，服务端会把它收成空列表并在下次发布时回到作品回退。管理界面是否在本任务里做，见待决 5。

### 1.5 public-api 与小程序

公开 JSON **不新增** `heroSlides`。继续输出 `HeroItem`：

| 公开字段 | 来源 |
|---|---|
| `id` | 幻灯片 id |
| `title` | `title` |
| `subtitle` | `subtitle`，缺省 `""` |
| `action` | `actionText` |
| `media` | `CatalogProjection.media` 的现有 Media 形状 |
| `target` | 小写类型 |
| `targetId` | `target.id`；`ABOUT` 的取值见待决 2 |

`frontend/api/public.js`：若 `ABOUT` 的 `targetId` 使用一个符合 `id()` 的固定值，解析器可以不改。若允许空 id，必须改 `mapHome`，否则整页 `PUBLIC_RESPONSE_INVALID`。

`frontend/pages/index/index.js` `openHero` 必须按 `target` 分支，不能再把「有 targetId」一律送去作品详情：

| target | 现有去向 |
|---|---|
| `work` | `openWork` |
| `package` | `openPackage` |
| `business` | `goServices(targetId)`（服务 tab，没有业务详情页） |
| `about` | `goAbout()` |

业务是否必须有独立详情页，见待决 6。在那之前，`business` 只接现有 `goServices`。

### 1.6 测试

- `CatalogDraftTest`：空列表、第 6 张、重复 position、媒体描述缺失、未发布目标、`publishable` 只把待公开项放进快照。
- `PublicQueryServiceTest`：无 `heroSlides` 时 hero 仍是现在的最多 3 张作品；有启用项时条数、顺序、小写 target、不再混入回退；全部不可展示时回到回退。
- 公开 JDBC：`HERO` 绑定能被 `media()` 读到；旧快照缺键仍可解析。需要时补 `DatabasePublicApiIT` 的迁移夹具，不碰 3306。
- 小程序：`mapHome` 对现有作品轮播仍通过；`openHero` 四种 target 的 URL。`native-pages.test.mjs` 的空列表夹具保持可渲染。

### 1.7 风险与回滚

- 风险：旧草稿反序列化、绑定 CHECK、`bound()` 默认 false、`openHero` 误开作品、`targetId` 空串弄坏整页、管理端整份保存把轮播写成空。
- 回滚：停止发布新快照并撤回当前目录指针，公开读回到无 `heroSlides` 的上一份 release 或不可见指针，`readHome` 自然走作品回退。已写入的 release 行不删除。`V4` CHECK 若需撤回，用再下一版迁移把允许列表改回五类之前，必须先确认没有 `HERO` 行。

## 2. 档期开放与关闭

### 2.1 现状约束（方案必须守住）

- 行已经存在：`booking_service_slot`、`booking_slot_occupancy`。不改 `V6`。
- 容量 CHECK 与 `BookingRules.capacity` 只接受 1。本设计不开放其他容量。
- 状态只有 `OPEN` 和 `CLOSED`。关闭是更新状态，不是 `DELETE`。
- 现有 `BookingApplication.closeSlot` 不看占用，并且 `BookingConcurrencyIT` 断言：确认之后关闭，订单和占用仍在。**新 HTTP 不调用这个方法。** 是否收紧领域方法见待决 7。
- 待确认申请不写占用表，只写 `booking_request_preference`。
- 没有重叠唯一索引。`MAX_SLOTS_PER_DAY` 未被调用。
- 新路径必须写入 `AdminWebSecurityConfiguration.bookingRoutes`，并走 `AdminBookingController.handle`。POST 正文 ≤ 4 KiB，要 CSRF 和 Origin。

### 2.2 数据库迁移

单个开放/关闭可以不改表：`rule_version` 已是 `VARCHAR(64)`，`booking_command.operation` 没有枚举 CHECK。

按周批量与「关闭前确认无占用」都不需要新列。若待决 8 要求拒绝时间重叠，用新的 `V12`（或当时的下一号）加约束或由事务内查询实现；不要改 `V6`。预约集成测试只执行测试资源里的 V6 副本，新脚本必须被 `BookingDatabaseIT` / `BookingConcurrencyIT` 的 `applyMigration` 显式执行，否则 IT 看不到新约束。

### 2.3 单个开放

`POST /api/admin/v1/bookings/slots`

- 权限：见待决 9。实现前不要临时复用 `RESERVATION_CONFIRM`，除非待决结论就是复用。
- 头：`Idempotency-Key`（`[A-Za-z0-9_-]{16,64}`）。创建没有可对比的修订，不用 If-Match。
- 体：`startAt`、`endAt`（ISO-8601 instant）。时区、显示偏移、`rule_version` 是否由客户端传入，见待决 10。在待决落地前，接口可以先拒绝「没有当前运营参数版本」的调用（422，reason `RULES_NOT_CONFIGURED`），这样不会发明默认营业时区。
- 成功 200，体为现有 `SlotView`，`status=OPEN`，`capacity=1`，`revision=1`。
- 领域仍用 `BookingRules.slotStart` 与 `capacity(1)`。
- 幂等：`booking_command` 新 operation，建议 `OPEN_SLOT`（远小于 48 字符）。同一演员、同一键、同一摘要重放第一次的 slot id；摘要不同则 409 `IDEMPOTENCY_KEY_REUSE`。
- 错误：400 `SLOT_RANGE_INVALID` / `SLOT_DURATION_INVALID` / `ZONE_INVALID` / `IDEMPOTENCY_KEY_INVALID`；409 幂等冲突；503 存储失败。映射沿用 `bookingFailure`。

事务：插入档期、审计 `SLOT_CREATED`、命令行同事务。不写占用。

### 2.4 按周批量

`POST /api/admin/v1/bookings/slots/weeks`

排在运营参数读写之后。没有当前规则版本时整批拒绝，reason `RULES_NOT_CONFIGURED`，不插入任何行。

一周的起算日、营业日、每日起止、时长、缓冲、跨午夜、节假日日历都来自那一版参数。这些字段的形状见第 3 节和待决 11。本接口只接收「使用哪一周」的定位（本地日期），不接收一套临时营业时间，避免调用方绕过版本。

整批一个事务：要么全部插入，要么全部不插入。每行 `rule_version` 写成该版本号。幂等 operation 建议 `OPEN_SLOT_WEEK`，重放返回同一批 id。与已有档期重叠时的行为见待决 8。

### 2.5 关闭未占用档期

`POST /api/admin/v1/bookings/slots/{slotId}/close`

- 权限见待决 9。
- 头：`If-Match: "r{revision}"`（与预约写操作同一形状），以及 `Idempotency-Key`。
- 体：`{}`。
- 事务顺序：
  1. 幂等重放检查。
  2. `SELECT ... FOR UPDATE` 锁定档期行。
  3. revision 不符 → 409 `VERSION_CONFLICT`。
  4. 不存在 → 404 `RESOURCE_UNAVAILABLE`。
  5. 已是 `CLOSED`：若本次是同一幂等重放则返回当时结果；否则见待决 12。
  6. 存在 `booking_slot_occupancy` → 409，建议 reason `SLOT_OCCUPIED`（`SAFE_CODE` 允许这个形状）。不更新状态，不删除占用，不碰订单。
  7. 待确认偏好是否也算「占用」见待决 13。
  8. 通过后 `status=CLOSED`，`revision+1`，审计 `SLOT_CLOSED`。
- 成功体：更新后的 `SlotView`。

这个路径不调用现有 `closeSlot`。确认预约仍用 `findSlotForUpdate`；关闭与确认抢同一行锁，失败方看到 `SLOT_CLOSED` 或 `SLOT_OCCUPIED`，不会双写占用。

`GET /api/admin/v1/bookings/slots` 保持不变：只列出未占用的 `OPEN`。关闭后的行从该列表消失。本设计不增加「列出已关闭档期」的接口。

### 2.6 测试

- 领域/应用：时长、容量 2、无规则版本、关闭有占用行、关闭与确认的锁顺序。
- `BookingConcurrencyIT` 现有「确认后 closeSlot 仍保留订单」继续指向**旧方法**。新方法另测：占用存在时行数与状态不变。
- `AdminBookingControllerTest`：未登记权限 403、缺 If-Match 428、缺幂等键 400、Origin/CSRF 若该测试组已覆盖则补一条 POST。
- `BookingDatabaseIT`：迁移后的关闭条件更新；隔离库，端口不是 3306。

### 2.7 风险与回滚

- 风险：新接口误调用旧 `closeSlot`，会关掉已确认档期。批量在规则未定时猜出一周的时刻。IT 不跑新 SQL。
- 回滚：停止暴露 POST 即可。已 `OPEN` 的行留在表里，公开列表仍按 `OPEN` 且无占用展示。已 `CLOSED` 的行没有重新打开的路径；若要恢复，需另一次明确的产品决定，而不是回滚脚本把状态改回去。

## 3. 预约运营参数

### 3.1 与现有拒绝行为的关系

今天「未配置」指公开预约进程环境不完整：服务 `DISABLED` 或启动失败。它不是规则版本缺失。

新的版本表接上之后，环境开关仍然先生效：模式未启用时，公开接口继续 `SERVICE_UNAVAILABLE` / `DISABLED`，不改为假装有一套规则。模式已启用但管理库没有当前规则版本时，建申请和按周开放拒绝，reason `RULES_NOT_CONFIGURED`。不回落到 `PENDING_LIFETIME` 或环境变量里的小时数。这是 P02 Q04 的「未配置则拒绝」。Q02 写死的 48 小时是否仍优先，见待决 14。

### 3.2 数据库迁移

管理库下一号脚本，例如 `V12__admin_booking_operating_rule.sql`（若档期重叠也要改表，合成同一脚本或顺延版本号，不要两个人写两个 V12）。

建议形状（与目录草稿同一模式：追加版本，不更新旧行）：

- `admin_booking_operating_rule`：`singleton_id=1`，`current_version INT NOT NULL`（0 表示从未配置）。
- `admin_booking_operating_rule_version`：`version` 主键，`payload_json JSON`，`updated_by`，`updated_at`。
- 不设默认行。没有头行即「未配置」。

`payload_json` 的字段集合见待决 11。P02 列出的候选是：营业时区、营业日、服务时长、缓冲、跨午夜、节假日、开放提前期、最远日期、取消条件、改期条件、容量。容量在表 CHECK 和领域里固定为 1，本设计的 payload **不接受**其他容量；若产品要改容量，那是另一项迁移，不放进这次。

档期上的 `rule_version` 继续是字符串，写入 `"operating-{version}"` 这类引用。不把档期表改成外键，避免旧测试行 `rule-v1` 插不进去。

预约集成测试的 `applyMigration` 要执行这份新脚本。

### 3.3 领域校验

纯 Java，不读环境变量。调用方把已解析的当前版本传进来。

- 没有任何当前版本：`RULES_NOT_CONFIGURED`。
- 时区必须能过现有 `BookingRules.zone`（IANA，拒绝纯偏移）。
- 时长仍须落在 5–1440 分钟，并且容量只能是 1。
- 提前期、最远日期、缓冲、营业日若出现负数或空窗，400，reason 用 `RULE_FIELD_INVALID` 加上稳定后缀。具体上下界见待决 11，未决定前不要抄环境变量的 1–720 小时当成产品默认。

生效（建议，待决 15 确认前不要写成已定规则）：

- 新版本只影响之后的档期生成和新申请的 `expires_at`。
- 已写在 `booking_reservation.expires_at` 上的时刻不回写。
- 已写在档期行上的 `rule_version` 不回写。
- 取消/改期条件若成为可配置项，执行那些命令时读取**当前**版本还是预约创建时的版本，由待决 15 决定。

### 3.4 管理端接口

`GET /api/admin/v1/bookings/operating-rules/current`

- 权限见待决 9。
- 未配置：200，`version=0`，`configured=false`，`rules=null`。不要返回猜出来的时区或 48 小时。
- 已配置：200，`version`、`configured=true`、`rules`、`ETag: "o{version}"`。

`PUT /api/admin/v1/bookings/operating-rules`

- 权限见待决 9。
- `If-Match: "o{version}"`，`o0` 表示第一次写入。冲突 409 `VERSION_CONFLICT`。
- 正文是规则对象，不是补丁。成功后 version+1，旧 JSON 行保留。
- 不用 Idempotency-Key 也可以，因为 If-Match 与目录保存相同；若加上，摘要必须包含正文哈希，避免同一键覆盖不同规则。目录那套 `publish|{revision}` 摘要**不够**，因为它不含正文。
- PUT 仍受 16 KiB 限制。

公开 `POST` 建申请在 `ensureEnabled()` 之后增加：无当前版本则拒绝，不创建 `booking_reservation`。查可用档期可以继续只返回已物化且未占用的 `OPEN` 行；「提前量 / 最远日期」是否再滤这张列表，见待决 16。

环境变量 `SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS` 与 `SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE` 在规则版本存在时不再决定过期时间和批量时区。它们仍是进程启动合同的一部分，本设计不删除这两个键，避免启动检查突然变松。

### 3.5 public-api 与小程序

公开 DTO 不暴露整份运营参数。小程序继续消费档期列表和申请上的 `expiresAt`。无规则版本时的错误码要进公开预约的稳定码，不能把内部 reason 原样当文案。具体客户提示见待决 17。

### 3.6 测试

- 无版本：批量开放与公开建申请拒绝，且预约行数不变；GET current 为 `configured=false`。
- 版本递增：旧 payload 行仍可按 version 读到；If-Match 落后 409。
- 新申请的 `expires_at` 等于创建时刻加上**该版本**的待确认时长，而不是常量 48 小时或环境变量。待决 14 若维持 48 小时，这条断言改成恒为 48 小时，并且 payload 里不再有该字段。
- 改版本不更新已有 `expires_at` 和已有档期 `rule_version`（若待决 15 维持不追溯）。
- `PublicBookingProperties` 启动测试保持：残缺环境仍然拒绝启动。
- 权限 CHECK 迁移：枚举里新增的码可以插入，未列入的码仍被拒绝。不插入默认管理员。

### 3.7 风险与回滚

- 风险：把「环境未启用」和「规则未配置」收成同一种拒绝，调用方无法区分。把 48 小时常量悄悄换成环境变量，或相反。新权限没有经 CLI 授予时，唯一管理员也会 403。
- 回滚：停止 PUT。已存在的版本行保留。若要恢复「没有规则表也能预约」，必须显式改回公开建申请的分支；不提供自动删表。头指针可以在以后的迁移里忽略，但不能靠 `DELETE` 生产数据来回滚。

## 4. 三项共用的接入清单

| 接入点 | 轮播 | 档期 | 规则 |
|---|---|---|---|
| 迁移 | 公开 `V4` 绑定 CHECK | 仅当要加重叠等约束 | 管理库规则版本表；权限 CHECK 若新增枚举 |
| 领域 | `CatalogDraft` | 新的关闭未占用用例，不改旧 `closeSlot` | 新规则 record，不读环境 |
| Security 匹配器 | 已覆盖 | `bookingRoutes` 增加 POST | `bookingRoutes` 增加 GET/PUT |
| 控制器 `handle` | 现有内容权限 | 待决 9 | 待决 9 |
| Origin / CSRF | 已覆盖 | POST ≤ 4 KiB | PUT ≤ 16 KiB |
| If-Match | `"c{revision}"` | 关闭用 `"r{revision}"` | `"o{version}"` |
| Idempotency-Key | 仅发布/撤回 | 开放与关闭 | 见 3.4 |
| 公开读模型 | `HeroItem` 形状不变 | 无新公开字段 | 无新公开字段 |
| 小程序 | `openHero` 与可能的 `targetId` | 无，除非待决 16 改变可约列表 | 错误码分支 |

## 待决问题

1. **幻灯片标题、按钮文案是否允许空白？** 现有公开 `text()` 允许空串。空按钮在页面上会变成单独的 `→`。
2. **`ABOUT` 的 `target.id` 用什么？** `mapHome` 的 `id()` 拒绝空串。可选方向：固定哨兵（例如 `about`），或放宽小程序校验。关于页是否还要在发布时检查品牌字段，也未定。
3. **`enabled=false` 是否计入最多 5 张？发布时是否校验其目标和图片？** 任务只说最多 5 张，并带显示开关。
4. **阅读首页时，目标已经不可见（作品授权失效、套餐不在快照中）怎么办？** 跳过该张，或让整个 `readHome` 失败。跳过到零张之后是否一定回到现在的作品回退，需要一并决定。
5. **管理端编辑界面是否属于本任务？** 接口可以先独立存在。但 `CatalogDraftInput` 若不带 `heroSlides`，现有内容工作室的整份保存会把轮播写成空列表。
6. **业务目标的页面是什么？** 小程序没有业务详情页。现有能力是切到服务 tab 并带上 `serviceBusinessId`。
7. **是否收紧现有 `closeSlot`？** 新 HTTP 会拒绝已占用档期。旧方法以及 `BookingConcurrencyIT` 允许关闭已确认档期并保留订单。两套语义可以并存，也可以改成一处。
8. **时间重叠和每日 24 档是否拒绝？** 表上没有重叠约束；`MAX_SLOTS_PER_DAY` 没有被调用。
9. **开放、关闭、读规则、写规则各用哪个权限？** 现有枚举没有这些码。新增码必须改 CHECK，并且由 CLI 授予现有管理员，不能靠默认账号。也可以复用现有 `RESERVATION_*` / `CONTENT_*`，但复用会把「确认预约」和「改营业时间」绑在一起。
10. **单个开放时，时区、显示偏移和规则版本由谁提供？** 现有 `createSlot` 全部由调用方传入。若单个开放发生在规则表之前，它要么暂时拒绝，要么接受调用方传入的 IANA 时区和一段 `rule_version` 文本。
11. **运营参数的字段全集、单位和上下界是什么？** P02 给了清单，PRD 只点名「提前多久可约」和「待确认多久过期」。节假日没有日历存储。容量目前不能不是 1。
12. **重复关闭一条已经 `CLOSED` 的档期，幂等重放之外返回什么？**
13. **待确认申请的档期偏好算不算占用？** 占用表里没有这行。关闭后，该申请仍指着一个已关闭档期，确认时会得到 `SLOT_CLOSED`。
14. **待确认时长听 Q02 还是 Q04？** Q02 写申请仍按 48 小时。Q04 和本任务 PRD 把「待确认多久过期」写成可配置，未配置则拒绝。公开入口今天用环境变量小时数，管理端 null lifetime 用 48 小时常量。
15. **新版本是否追溯？** 已生成档期、未过期申请的 `expires_at`、此后的取消和改期，各读哪一版。
16. **提前期和最远日期约束哪一次请求？** 只约束后台按周生成，还是公开查档和提交申请也要拒绝窗外的档期。
17. **规则未配置时，小程序展示哪一个稳定错误码和哪句客户文案？** 不能复用内部 reason 当文案。

## 决策（主会话审定，覆盖上方待决问题）

1. 主标题必填（1–16 字）；副标题选填；按钮文字必填（1–8 字），后台默认填“查看详情”。
2. `ABOUT` 的 `target.id` 固定为 `about`；小程序跳“关于”页。发布时不额外检查品牌字段。
3. 隐藏（`enabled=false`）的幻灯片计入 5 张上限；发布校验只检查启用的幻灯片（目标存在且可见、图片有描述）。
4. 公开读取时目标已不可见的幻灯片直接跳过，不让首页失败；跳过后为 0 张则回到现有“精选作品前 3 个”回退。
5. 后台编辑界面属于本任务：在“首页布置”把只读轮播卡换成可编辑轮播（对照设计稿 `docs/设计笔记集/后台界面设计稿-v2.html` 的轮播区，复用已有组件）。后端读取旧草稿 JSON 时缺失 `heroSlides` 视为空列表（向后兼容，不得拒绝旧修订）；admin-web 的 catalogStore 必须原样回传该字段。
6. 业务目标沿用现有能力：切到服务 tab 并带业务 id。小程序 `openHero` 必须按 `target.type` 显式分支，未知类型不跳转（修复风险 1）。
7. 不修改现有内部 `closeSlot` 及其测试；新增的后台关闭接口单独实现：已占用或存在待确认申请引用的档期拒绝关闭。
8. 开放档期时拒绝与同一天已开放/已占用档期时间重叠的档期；每天最多 24 个（调用现有常量）。
9. 新增权限 `SLOT_WRITE`（开放/关闭档期）与 `BOOKING_RULES_WRITE`（修改规则）；读取沿用 `RESERVATION_READ`。通过迁移修改 CHECK 约束，并通过迁移/CLI 授予现有管理员。
10. 业务时区固定 `Asia/Shanghai`，显示偏移 `+08:00`；规则版本取当前生效版本。尚无规则时拒绝开放档期，错误提示“请先设置预约规则”。
11. 运营参数字段与范围：最少提前预约小时数（0–168，默认 24）、最远可预约天数（1–180，默认 30）、待确认过期小时数（1–168，默认 48）、单档时长分钟数（30–480，默认 120）。容量固定为 1；本期不做节假日。（默认值在后台表单中预填，需管理员保存后才生效。）
12. 重复关闭已是 `CLOSED` 的档期：返回 200 与当前状态（幂等）。
13. 待确认申请引用的档期视为不可关闭（见第 7 条）。
14. 待确认过期时长以 Q04 规则为准（默认 48 小时与 Q02 一致）；规则生效后公开入口不再读环境变量小时数，未配置规则时保留现有拒绝行为。
15. 新规则版本不追溯：只影响此后新建的档期与新提交的申请。
16. 提前期与最远日期同时约束：后台开放档期、公开查询档期、公开提交申请。
17. 未配置规则时公开接口返回稳定错误码 `BOOKING_RULES_UNCONFIGURED`，小程序文案“暂未开放预约，请稍后再来或直接联系我们”。

实施顺序调整为：轮播 → 预约规则 → 单个档期 → 按周批量（档期开放依赖规则版本）。
