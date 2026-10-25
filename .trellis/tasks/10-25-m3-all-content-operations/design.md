# M3 设计 — 全部约定内容运营

## 权威边界

- P02/P03 定义 M3 范围；D301/D302/D303 为内容规则来源；D403/D607 管接口与后台体验；D507/ADR-005 要求 V1 不可变、V2 仅承载单作品 overlay。
- Admin `admin-content` 保持内容领域 owner。业务草稿数据和操作审计在 admin schema；公开安全快照只由独立 publication adapter 写入 public schema。
- M3 不修改 public V1、public V2、admin V5 (S2) 或 admin V6 (booking)。追加 admin V7 与 public V3；显式迁移身份，不自动 DDL。
- S2 per-work overlay 继续提供作品资格与授权事实；目录发布只引用当前可发布作品/媒体，不写授权事实、不复制授权证据。

## 数据/版本策略

- 管理端使用一个 typed `CatalogDraft` 快照版本承载 brand/contact、team/channels、six businesses、packages/service lines、home featured references 和工作版本引用。快照根具有单调 revision；更新要求 `If-Match`，整包校验，禁止部分写入后回成功。
- Admin V7 保存不可变草稿修订、当前修订指针、命令幂等账本与审计；仅允许 `SELECT/INSERT/UPDATE` 所需表级权限，不授予 DDL/跨域权限。
- 发布生成不可变 public V3 full-catalog release，并原子切换 singleton active pointer/epoch；public V1/V2 是 fallback 仅限 M3 overlay 从未发布的时期。V3 一旦生效，withdraw 必须 fail-closed，不回退 V1 的旧品牌/套餐。
- public V3 投影保持与既有 `CatalogSnapshot` 兼容：brand/contact/team/channels/business/packages/home 重叠字段以当前 V3 为准；既有 V1/V2 work 集合按稳定 ID 合并，跨版本 ID 冲突拒绝发布；media asset/variant 经已 READY private assets 显式复制元数据，客户端绝不见 object key。
- 旧版本只读保留；下架使用新 snapshot 排除资源/引用或显式不可见状态，不物理删除历史。作品资格、套餐价格和业务状态不可由 UI 自行推断。

## 校验与并发

- 输入使用严格 DTO/JSON、字段白名单、长度/数量约束，不接受客户端 `published/authorized/objectKey` 作为事实。
- 六类业务 ID/语义固定可表达；显示字段由维护操作填写。无套餐为空列表。套餐 `priceType` 与金额/文案严格校验；仅测试配置可填写 synthetic values，不迁移旧原型数值。
- brand/contact 必需字段、首页引用、封面 READY 状态、package→business 关系、package/work/media 引用与公开资格在预览和发布时重新检查。
- Revision conflict=409；命令使用 actor + operation + scope + key + canonical digest；同键同摘要返回既有结果，同键异摘要冲突；未知提交只按命令查询。
- Publication writer 单连接单 InnoDB 事务跨 admin/public schema；锁序需冻结于实现文档和测试，失败全部回滚。公网 Reader 仍只读；不做 XA/跨实例双写。

## 接口/页面

- `GET /api/admin/v1/content/catalog` 读取当前可编辑快照和 ETag；`PUT` 携带 `If-Match` 保存候选；`GET .../preview` 返回仅安全字段的校验结果；`POST .../publish` 用幂等键与预期 revision 发布；资源下架通过同一领域服务显式动作完成。
- 运营页面在已认证 admin-web 中提供结构化 tabs/sections：品牌与联系、成员与渠道、六类业务、套餐/服务项、作品、首页精选、预览/发布。按钮显示权限，但服务器始终强制权限。
- 公开读 DTO 和 mini-program routes 不增加非兼容字段；无需真实内容即可用合成 profile 验收。

## 主要测试

- 领域：六种业务身份、空套餐合法、四种 priceType、字段/引用/媒体校验、历史只读。
- JDBC/MySQL：迁移、完整快照读写、If-Match 竞争、发布原子化、各步骤故障回滚、命令重放/冲突、pointer/epoch、权限正负。
- HTTP/UI：CSRF/session/capability、编辑全内容、预览拒绝/接受、保存冲突、发布后公开小程序当前版本、下架后无旧值回退。
- Compatibility: V1 原始数据/DTO 不变；V2 work overlay 与 M3 V3 catalog overlay 同时读取正确；已有原生合约测试通过。
