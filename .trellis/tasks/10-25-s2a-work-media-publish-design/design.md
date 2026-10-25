# S2-A 设计：首个作品媒体发布与撤回

## 1. 主链与信任边界

`admin-web` 仅通过已有同源 HTTPS 管理 session 调用 `/api/admin/v1`；S1-D Cookie、Origin、CSRF 与逐请求 capability 检查不变。管理 Controller 调用 application ports，不持有 `DataSource`/任意 SQL。媒体对象一直处于私有根，公开访问只能通过既有 public-api media gateway；不提供对象直链/客户端 key/远程 URL。

数据 owner：媒体和作品草稿归 admin schema V5 业务表；授权事实/证据属于 A15 权威 owner；S2 只读当前最小 authorization gate；发布命令/审计由 publication port 拥有；public V2 保存白名单投影；public reader 只读 public V1+V2。admin business writer、publication writer、security writer、public reader、migration 身份分离。

## 2. 技术模型

按 ADR-005：S2 采用新增 public V2 按作品版本化 overlay，现有 V1 保持不变；public-api 合并 V1 release 与 V2 active works 至既有 `CatalogSnapshot`/DTO，并以 `(V1 release, V2 projection epoch)` 绑定游标。该选择只覆盖 S2 首个作品；S3 业务字段/发布总模型需要届时复审。

媒体对象不是 MySQL 事务参与者：先写私有对象，再写 READY metadata；数据库失败只允许私有孤儿，不允许任何 URL fallback。发布/撤回仅在同一 MySQL 实例 InnoDB admin/public schema 上以 publication writer 单连接本地事务完成，不用 XA/异步双写。

## 3. 版本化对象与状态

- `MediaAsset`: `RECEIVING → PROCESSING → READY`，以及 `REJECTED / FAILED_RETRYABLE / WITHDRAWN`；只允许 JPEG/PNG，经签名校验/完整解码/重编码清元数据；S2 测试 profile 有界 4 MiB、8192 边长、24 MP；仅 `thumbnail`/`preview` 可绑定。
- `WorkCase`: 服务端 opaque ID、当前草稿 revision、workflow 状态；修订为不可变版本。草稿保存不写 public projection。
- `WorkMediaBinding`: revision 到 READY media 的 cover/position/purpose 关系；不可变，编辑即新 revision。
- `AuthorizationGate`: A15 owner 的最小、只读判定投影；时间按 UTC DATETIME(6)，每次读取按 `valid_from <= now < valid_until` 检查；S2 synthetic fixture 只能在隔离 profile 标记 `TEST_SYNTHETIC`。
- `PublicationCommand`: `(actor, operation, work, idempotencyKey)` 唯一作用域、canonical request digest、结果与修订；同键异 payload 冲突；响应丢失按键复查。

详细表与逐表 grants 见 D-507 §12；endpoint/error DTO 见 D-403 §8；状态及业务校验见 D-302 §12；对象准入见 D-205 §13。

## 4. 发布与撤回事务

统一锁顺序：command key → work case → target version → media IDs 字典序 → current authorization gate（共享锁）→ active pointer → projection epoch → audit。发布在单个本地事务内复核 If-Match、媒体 READY/绑定/封面、当前授权 authority revision/scope/purpose/channel/UTC window，写不可变 public projection、更新 pointer/epoch、命令与审计。业务撤回关闭 pointer、递增 epoch、保留对象/历史；A15 法律撤回必须在同一授权/发布边界原子失效，不能先提交 gate 再异步补投影。

已提交撤回后新 GET/HEAD 必须拒绝；媒体对象缓冲前后重读 access stamp；已发送/已下载字节不可追回。自然到期不依赖定时任务；未知/异常 fail-closed。缓存/CDN 不可绕过门禁，JSON/媒体 no-store。

## 5. 管理 API/UI 与公开兼容

D-403 §8 定义 upload/status/private preview, work draft/preview, publish/withdraw, command lookup。所有写操作有 Idempotency-Key；修订敏感操作有强 If-Match。UI 不含授权建立/审批/补证/法律撤回，不显示证据或对象 key。D-607 §1–3 描述操作反馈；S4–S6 仅保留后续范围提示。

公开端沿用现有 works list/detail DTO 和同源 media content route；public V2 overlay 必须显式 `WORK_OVERLAY_V2` 授权来源，无权威行或故障不回退 V1。旧游标在任一 release/epoch 变化后失效。V1 公开表和旧作品授权路径不改。

## 6. 风险和 2A 验证门

此设计还不是已实施系统。S2-B 必须在明确授权的专用双 schema MySQL/InnoDB 与独立 grants 下，证明实际 migration、`SHOW GRANTS`、`CURRENT_USER`、事务回滚、锁竞争、过期 fail-closed、未知提交、public DTO/cursor、媒体 GET/HEAD 撤回和小程序真链路；仅页面或 mock 不够。所有输入为合成资料，测试身份经现行受控 C3 路径创建，清理只限本次拥有 fixture。