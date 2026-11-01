# 实施步骤

顺序：轮播 → 单个档期 → 预约规则 → 按周批量。每阶段单独可验证。不改 `V6`/`V7`/`V3`，不连 3306，不提交 git。产品待决见 `design.md` 文末；某一步被待决挡住时停在该步，不要用默认值填上。

验证命令在 `backend` 目录执行。PowerShell 不使用 `&&`。数据库验收用 `verify`，不要用 `test` 代替 `*IT`。

## 阶段 1：轮播

目标：草稿可以保存 `heroSlides`，发布进公开快照，首页在有配置时输出现有 `HeroItem`，没有配置时与现在的 `readHome` 一致。

1. 公开库增加 `backend/public-api/src/main/resources/db/migration/V4__public_catalog_hero_binding.sql`，按 `design.md` 1.2 替换绑定 `owner_type` CHECK。不要改 `V3`。
2. `CatalogDraft` 增加幻灯片 record 与空列表兼容（旧 JSON 缺字段仍能读）。`validated()` 做条数、id、position、目标和媒体描述。`publicationSnapshot()`、`mediaReferences()` 带上待公开的项，owner type 为 `HERO`。
3. `CatalogApplication.preview/publish` 把幻灯片的作品 id 并入现有 `validateWorkReferences`，套餐/业务在领域层核对，图片仍走 `validateMedia`。
4. `JdbcPublishedCatalogRepository` 解析可选 `heroSlides`；缺键等于空。`CatalogSnapshot` 增加该列表，所有现有构造保持空列表。
5. `CatalogProjection.bound()` 接受 `HERO`。`PublicQueryService.readHome`：有可展示项则按 position 投影为 `HeroItem`（小写 target，subtitle 用空串）；否则保持现有最多 3 张作品逻辑。
6. `admin-web/src/content/api.ts` 的 `CatalogDraftInput` 增加同名字段，避免整份 PUT 把列表写成空。编辑页面是否本阶段做，先看待决 5。
7. `frontend/api/public.js` 只在待决 2 要求放宽 `targetId` 时修改。`frontend/pages/index/index.js` 的 `openHero` 按 `work` / `package` / `business` / `about` 分支；`business` 调用现有 `goServices`，`about` 调用现有 `goAbout`。
8. 补 `design.md` 1.6 的测试。

本阶段验证：

```powershell
.\mvnw.cmd -pl admin-content,public-api test
```

小程序解析与 `openHero` 走仓库里现有的 frontend 测试。有隔离公开库时再跑 `DatabasePublicApiIT`（`Test-DatabaseIntegration.ps1` 或该 IT 所需的环境变量）。不要为了这一步启动 3306 上的原服务。

手工对照：空 `heroSlides` 的夹具，`heroItems` 仍是作品回退；五张启用项的顺序、`target` 小写、`media` 非空；第六张和缺描述在 `validated()` 失败。

## 阶段 2：单个档期

目标：后台可以开放一条档期、关闭一条没有占用行的档期。不实现按周批量。

1. 待决 9、10 有结论后再写权限和请求体。没有结论时，本阶段只准备领域方法，不挂 HTTP。
2. 在 `admin-booking` 增加「关闭未占用」用例：`FOR UPDATE`、核对 revision、有占用则拒绝且不更新。不要改 `BookingApplication.closeSlot` 的现有语义。
3. 单个开放复用 `createSlot` 的校验与插入。幂等写入 `booking_command`，operation `OPEN_SLOT`。关闭使用 `CLOSE_UNOCCUPIED_SLOT`。
4. `AdminWebSecurityConfiguration.bookingRoutes` 登记两条 POST。`AdminBookingController` 用 `handle` 检查待决 9 的权限，解析 `Idempotency-Key` 和关闭用的 `If-Match: "r{revision}"`。
5. 若新增 `AdminPermission`，同一阶段加上权限 CHECK 迁移（当时的下一号，不要占用已经留给规则表的版本号时互相覆盖）。不插入账号，不改 `V9`/`V10`。
6. 补控制器测试、应用测试，以及占用存在时状态不变的并发测试。旧的 `closingASlotAfterConfirmationKeepsTheOrderIntact` 仍调用旧 `closeSlot`。

本阶段验证：

```powershell
.\mvnw.cmd -pl admin-booking,admin-runtime,admin-security-core test
```

有隔离预约库时：

```powershell
.\backend\scripts\Test-BookingDatabaseIntegration.ps1
```

确认：POST 未进匹配器时不会 404 成匿名成功；有占用时 revision 不变；无占用时状态变为 `CLOSED` 且行仍在；`GET /slots` 不再返回它。

## 阶段 3：预约规则

目标：管理端可读当前版本、可追加新版本；没有当前版本时，公开建申请和（下一阶段的）按周开放拒绝。

1. 待决 11、14、15 有字段和追溯结论后再写 payload 校验。容量保持 1，不接受其他值。
2. 新增管理库迁移（`V12__admin_booking_operating_rule.sql` 或当时的下一号）：头指针 + 追加 JSON 版本，不插入默认经营值。
3. `BookingDatabaseIT`、`BookingConcurrencyIT`、`OutboxDatabaseIT`、`OutboxProcessRecoveryIT` 的 `applyMigration` 在 V6 之后执行这份脚本。测试资源若需要副本，从迁移模块复制，不改 V6 副本的表定义。
4. 领域 record 与 JDBC 仓库：`FOR UPDATE` 头行，If-Match 不符则冲突，成功则 `version+1`。未配置是「没有头行或 version=0」，GET 返回 `configured=false`。
5. `bookingRoutes` 增加 GET 与 PUT。PUT 走 Origin 的 16 KiB 限制。
6. `PublicBookingService.createRequest` 在 `ensureEnabled()` 之后要求当前版本。没有版本则拒绝且不插入预约。不要删除 `SOUTHLEAF_PUBLIC_BOOKING_*` 启动检查，不要在缺失时使用 `PENDING_LIFETIME`。
7. 新申请的过期时刻按待决 14 的结论计算，并在测试里锁住。已有行的 `expires_at` 不因新版本更新，除非待决 15 要求追溯。

本阶段验证：

```powershell
.\mvnw.cmd -pl admin-booking,admin-runtime,public-api test
```

隔离库再跑 `Test-BookingDatabaseIntegration.ps1`。公开预约单测确认：残缺环境仍然启动失败；模式开启但无规则版本时建申请被拒绝。

## 阶段 4：按周批量

目标：一条命令按当前规则版本生成一周档期。规则未配置时零行写入。

1. 仅在阶段 3 的当前版本可读之后开始。
2. `POST /api/admin/v1/bookings/slots/weeks` 登记进 `bookingRoutes`。一个事务插入全部行，`rule_version` 写成该版本引用。幂等 operation `OPEN_SLOT_WEEK`。
3. 一周边界、营业日、时长、缓冲、节假日只读 payload。重叠策略按待决 8。
4. 测试：无版本时表行数不变；有版本时行数与时刻符合该 payload；重放不加倍；中途校验失败整批回滚。

验证命令与阶段 2 相同，并加一条公开查档：新行在 `OPEN` 且无占用时出现在客户档期列表。

## 完成前

- 三阶段（加批量则四阶段）的单测都通过后再考虑隔离 `verify`。
- `python backend/scripts/check-module-boundaries.py` 在改了模块依赖时跑一次。
- 不把 `Test-DatabaseIntegration.ps1` 指向 3306。
- 权限若有新增，用现有账号 CLI 的授予路径写进交付说明，不在迁移里插入管理员。
