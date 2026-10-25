# M3 实施与验收记录（S3/M3 2A 合成验收通过；阶段二整体未完成）

## 已实现

- **全目录草稿与规则**：`CatalogDraft` 覆盖品牌、联系资料、成员、渠道、六类固定业务、套餐/服务项/显式价格类型，以及首页精选业务/套餐/作品 ID。草稿版本单调递增；输入严格校验、业务/套餐引用校验、媒体替代文字校验；首页作品 ID 在预览/发布时复核 S2 的 PUBLISHED 状态、有效授权窗口/来源和当前 active pointer，不创建第二套授权事实。
- **增量 schema**：admin V7 与 public V3，旧 admin/public migration 不改。Admin V7 记录当前草稿指针及不可变历史修订；public V3 记录完整公开安全快照、复制后的 READY media variant 元数据、active pointer/epoch。
- **应用与持久化**：草稿 If-Match 保存、媒体/作品资格检查、发布/撤回、幂等重放/冲突、审计与同一 InnoDB 事务切换 public pointer。publication writer 对草稿和资格事实使用只读 locking reads，不额外放大 UPDATE grants。
- **安全公开投影**：公开快照只收录 published 项，不把未发布草稿、mediaAltTexts 或 object key 写入 public release JSON；public reader 将 V3 catalog 与当前可见 S2 work overlay 合并。首页可按 V3 精选作品排序并继续按权威可见性策略过滤撤回/过期作品。首次 V3 前沿用 V1/V2，V3 撤回后不回退到旧目录。
- **HTTP 与认证**：catalog 的读取/保存/预览/历史查询/发布/撤回路由加入已认证 HTTPS 安全 matcher；复用现有 session、capability、CSRF、严格 JSON 与 `If-Match`/幂等键策略。
- **版本历史**：`GET /api/admin/v1/content/catalog/versions` 列出只读元数据，`GET .../versions/{revision}` 读取快照；后台可载入旧快照，但仍以当前服务器 revision 保存，追加新版本，不覆盖旧版本。
- **Admin UI/API**：目录运营页可维护品牌、联系、成员、渠道、六类业务、套餐/服务项、首页精选业务/套餐/作品；包含草稿版本历史载入、保存、预览、发布、撤回和失败状态。已将 Logo、成员头像、渠道二维码、业务/套餐封面从手填 ID 改为 READY media 选择器，带状态/尺寸、刷新、引用资源缩略图及替代文字。

## S3/M3 逐项验收追踪（P02 §6 / P03 §3、§12.2 / PRD / design）

| M3 PRD 验收标准 | 实现与可复核证据 | 状态 |
|---|---|---|
| 1. Admin 页面可维护所有约定内容；错误、忙碌、空态、冲突、权限不足真实呈现 | `CatalogView.vue` 结构化维护品牌/联系、团队、渠道、六业务、套餐/服务、首页精选和媒体；ops31 browser checks 覆盖新增/删除/排序/字段保存、READY media picker、保存 busy/loading 和已发布 CONFIRMED 渠道 READY QR、空套餐/空精选、媒体/作品列表错误态、只读权限、陈旧编辑冲突、历史/预览/发布/撤回。`m3CatalogBrowserChecks=55` | PASS（2A 合成资料） |
| 2. 草稿不影响公开端；校验合格后单版本原子发布 | `m3-public-release-excludes-unpublished-draft-content`、`m3-publish-mid-transaction-failure-rolls-back`、`m3-publish-same-key-recovers-after-rollback`；ops31 同时验证 public V3 和实际 UI publish | PASS |
| 3. 内容权威事实唯一；作品授权仍归 S2 | M3 `CatalogDraft` 只保存 `featuredWorkIds`，不包含授权状态/证据字段；`CatalogApplication.preview/publish` 和 publication repository 在预览/发布时重新校验 S2 工作资格。ops31 API checks `m3-featured-work-uses-s2-publication-authority`、`m3-preview-blocks-unpublished-featured-work`；浏览器从 Works 列表选择精选作品。历史载入只替换本地编辑草稿。ops28 新增历史载入断言：载入 revision 3 后 `stored.revision` 与提示的 current server revision 保持原值；ops30 以真实延迟网络响应断言保存按钮 busy/loading 且阻止重复提交；ops31 另验证已发布 CONFIRMED channel 关联 READY QR。保存仍依当前 ETag 追加版本。S2 Works 编辑/发布/撤回仍由 S2 页面与服务承接，M3 不复制工作版本或授权操作 | PASS；职责边界已明确 |
| 4. Public API/小程序读取当前版本；撤回不回退旧版本 | `m3-public-v3-catalog-current`、`ui-publish-visible-in-public-api`、`miniprogram-chain-after-ui-publish`、`m3-withdraw-does-not-fallback-to-v1`、`v1-native-contract-unchanged-with-epoch`；public V1/V2兼容回归 | PASS |
| 5. 双 schema MySQL、最小 grants、回滚、重放/冲突、并发、清理均有证据 | ops31 owned MySQL 8.4/InnoDB + TLS `VERIFY_IDENTITY`；admin V7/public V3；17 项 grant negative；M3 中段事务 trigger rollback/同键恢复；审计一次；并发同 revision 保存 `[200, 409]`；fixture cleanup verified | PASS |
| 6. 证据仅来自 owned disposable fixture + synthetic content，并记录限制 | ops31: `realOrProductionDataUsed=false`、`c2FixtureUsed=false`、`secretsRecorded=false`；owned cleanup verified；限制记录 `TEST_SYNTHETIC` 不是 A15 正式 authority | PASS（仅 2A 本地验收） |

### Design 主要测试追踪

- **领域**：六固定业务 ID/空套餐/显式四类价格/字段引用与媒体规则 → `CatalogDraftTest`、M3 API checks、`catalog-ui-preserves-six-stable-business-identities`、`catalog-ui-keeps-undisclosed-price-distinct` 等。
- **MySQL/JDBC**：迁移/完整快照/If-Match/发布原子性/失败回滚/命令重放与冲突/pointer-epoch/权限 → ops31 的 114 check 清单及 26 个 M3 API/DB checks；cleanup verified。
- **HTTP/UI**：session/capability、全内容编辑、预览拒绝/接受、保存冲突、publish/read/withdraw → ops31 的 55 个 M3 browser checks 与 browser/API publication flow。
- **兼容性**：V1/V2 与原生小程序合同 → `v1-native-contract-unchanged-with-epoch`、`miniprogram-chain-after-ui-publish`、public V3 实际 API 读取。

## 最新验证证据

- **ops31**：`status=PASS`，114 checks，`failedStage=null`，26 个 M3 API/DB checks，55 个 M3 browser checks，cleanup verified；run ID `07ad61e8`。证据：`docs/开发与演示/证据/stage-two-s2b-isolated-l5-m3-ui-ops31.json`。
- ops28 后端验收通过前端 history guard 加强：真实浏览器确认载入旧 catalog revision 不回退当前写入版本；随后仍以当前 ETag 保存追加修订。S2 工作资格继续由服务端实时复核。
- 最新前端复验：`npm run test -- --run`（9 文件/36 tests）、`npm run typecheck`、`npm run build`、`node --check e2e/m3-catalog.mjs` 全部 exit 0；CatalogView 和 E2E 编辑器诊断为 0 errors/0 warnings。
- 之前 ops18/19 等失败证据保留，不覆盖；ops29 的 deferred-route busy-state 尝试以 `M3_CATALOG_BROWSER_FAILED_full_UNKNOWN` 失败且 cleanup verified，根因未由该证据确立。将 busy-state E2E 改为有限 750ms route delay + 明确 loading/disabled assertion 后，ops30 完整通过；不把 ops29 计为通过。
- **限制**：只使用 owned disposable synthetic fixture；`TEST_SYNTHETIC` 不是 A15 正式 authority；无真实/生产资料、生产通知、C2 fixture 或原 `127.0.0.1:3306` 服务；占位 QR 是合成素材。不是 2B/G2B/G3/正式开放证据。

## S3/M3 结论与后续阶段边界

**S3/M3 的 2A synthetic acceptance 已通过，M3 任务范围完成。** “作品运营”边界按 P02/P03 解决为：S2 继续拥有单作品创建/编辑/授权/发布/撤回；S3/M3 负责品牌目录与首页编排，并以 S2 Works 列表作引用选择，发布前重新向 S2 权威验证。历史目录快照只包含作品 ID，不携带或恢复授权事实；载入历史不改当前 revision，发布资格始终重新读取当前状态。无需另造 M3 work-version 或授权数据库。

这不等于整体 G2A/2A 已通过：P03 的 S4–S6 仍需按其自身页面、接口和退出项完成；Q02/Q04/Q05 的经营决策仍按 P02 管理。2B 的真实资料/A15正式授权/正式平台/真机 UAT/备份恢复/生产开放另验收。未触碰原服务、未创建默认管理员、未 commit/push。
