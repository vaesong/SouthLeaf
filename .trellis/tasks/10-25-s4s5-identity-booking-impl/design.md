# S4/S5 设计：客户身份、档期与预约确认成单

## 1. 主链与信任边界

`customer` 是客户主体与身份关联的唯一入口；`reservation` 是档期占用的唯一事实所有者；`order` 拥有确认时的不可变商业快照。三者互不代写对方表。管理会话由 `admin-security` 解析为不可伪造的 actor context，业务用例不读 session、不信客户端传入的 customerId。

管理端 capability 复用既有 `AdminPermission` 词汇，不新增角色：`RESERVATION_READ` / `RESERVATION_CONFIRM` / `RESERVATION_CANCEL` / `RESERVATION_RESCHEDULE` / `ORDER_READ`。Q06 未确认前不把这些 capability 映射到任何真实后台角色。

公开浏览档期无需登录；创建预约与查看本人订单需客户会话。客户身份解析、关系校验与本人访问判定在本批次只提供**领域与应用层能力**，客户会话适配器与公开 HTTP 属后续切片。

## 2. 状态机与不变量

### 2.1 Reservation

| 状态 | 允许转移 | 不变量 |
|---|---|---|
| `PENDING_CONFIRMATION` | `CONFIRMED` / `REJECTED` / `EXPIRED` / `CANCELLED` | **不持有任何当前 occupancy**；不产生订单 |
| `CONFIRMED` | `CANCELLED`（改期保持 `CONFIRMED`） | 有且仅有一个当前 occupancy；有且仅有一个原始订单 |
| `REJECTED` / `EXPIRED` / `CANCELLED` | 无 | 终态；再预约创建新申请 |

改期不引入 `RESCHEDULED` 终态（避免含义模糊），而是追加 assignment history 并保持 `CONFIRMED`。

### 2.2 Slot 与 Occupancy

`service_slot` 是**已物化**的可预约时段，含内部 ID、开始/结束 UTC instant、业务时区标识、可售状态、容量（默认 1）、规则版本与 revision。后台确认只能占用**已存在**的可售行，不得锁一条尚不存在的占位记录。

`slot_occupancy` 以 `slot_id` 为主键/唯一键，承载"当前占用"这一唯一事实；历史变更进入 `slot_assignment_history`。释放与建立占用必须在同一事务。

### 2.3 Order

`order.lifecycle_status ∈ {ACTIVE, CANCELLED}`；`fulfillment_status ∈ {UNSCHEDULED, SCHEDULED, NEEDS_ATTENTION}`。**不创建** `payment_status` / `deposit_status` / `refund_status` / `delivery_status`。

`order_snapshot` 不可变，捕获确认当时的套餐版本、展示名、币种与金额、服务内容摘要、确认 slot 与 UTC 起止、时区与显示偏移、schedule revision、下单主体与最小联系人快照、创建来源与确认操作者。已确认快照永不 UPDATE/DELETE；纠正通过 `order_revision` 追加（`UNIQUE(order_id, revision_no)`）。

### 2.4 Customer

`customer`（内部不可猜测 ID）+ `external_identity`（provider + app scope + subject fingerprint 唯一）+ `contact_point`（可重复、可变更、不可作唯一键）+ `customer_relationship`（角色、用途、验证状态、有效期、可撤销）+ `customer_link_request`（待核验关联申请，批准前**无任何权限效果**）。

关系角色用类型枚举 `BOOKER` / `CONTACT` / `SUBJECT` / `PARTICIPANT` / `GUARDIAN`。`GUARDIAN` 仅为建模预留，**不隐含任何适用年龄或法律同意结论**。

## 3. 事务与锁顺序

全局固定锁序，所有命令遵守，不得逆序：

```
idempotency_command → reservation(ID 排序) → service_slot(ID 排序)
→ slot_occupancy(唯一约束) → order(ID 排序) → audit / notification_outbox
```

`ConfirmReservation` 在**单一 InnoDB 事务**内：

1. 取得/写入命令幂等记录（actor + operation + client key 作用域，持久化请求摘要）。
2. 锁 reservation 行；断言状态为 `PENDING_CONFIRMATION` 且未过期。
3. 锁目标 slot 行；断言 `status = OPEN` 且容量未被占用。
4. 插入 `slot_occupancy`（`slot_id` 唯一约束是最后一道并发保护）。
5. 条件更新 reservation → `CONFIRMED`，记录确认时间、slot 与 schedule revision。
6. 插入唯一订单（`UNIQUE(source_reservation_id)`）与不可变快照。
7. 追加审计与通知 outbox。

任一步失败整体回滚。唯一约束冲突或死锁时回滚并**重读赢家状态**，向调用方返回 `SLOT_UNAVAILABLE` / `VERSION_CONFLICT`，不得吞掉冲突后错误确认。

排他性由数据库唯一约束保证。列表可用性只是提示；按钮禁用、缓存、定时刷新或应用层先查后写都不满足排他性。

## 4. 幂等与未知提交

命令幂等键作用域为 `actor + operation + clientKey`，持久化 `request_sha256` 与结果引用。相同键 + 相同摘要 → 返回同一结果并标记 `replayed`；相同键 + 不同摘要 → `IDEMPOTENCY_KEY_REUSE`。DB commit 结果未知时**按命令 ID 复查当前事实**，不得用新键重建订单。

## 5. 时间与时区

所有时间点存 UTC instant（DATETIME(6)，微秒精度）。业务时区以 IANA `ZoneId` 标识随记录保存，客户端只按服务端返回的 display offset 渲染。**禁止依赖服务器默认时区**。具体时区、营业日、服务时长、缓冲、跨午夜、节假日、提前量、容量规则属 Q04，由调用方以配置提供，代码不猜测。

## 6. 失效、取消与改期

- **失效**：条件更新 `status = PENDING_CONFIRMATION AND expires_at <= db_now` → `EXPIRED`，每个申请最多一次终态更新。与确认竞态时二者对同一行使用相同锁/版本条件，恰有一个胜出；过期后确认必须拒绝。
- **取消**：同一事务内解除 occupancy、更新 reservation 与 order 状态、追加 order revision、审计与 outbox。重复命令幂等。阶段二无退款语义，取消只表示服务约定已取消。
- **改期**：按全局锁序同时锁旧档与新档（ID 排序），先确保新档可占，再原子建立新 occupancy、更新预约与订单安排修订，最后释放旧档，全在同一事务。失败时**旧预约/订单时间完全保留**。新档冲突返回明确冲突，**不自动另选时段**。

## 7. 数据所有权与最小化

客户档案、身份关联、联系信息、预约与订单均为私有管理数据，存 admin canonical 模型，**不进入公开 projection**。订单/预约只持有完成约定服务所需的最小联系快照；更新当前联系方式不改变历史交易快照。列表默认掩码联系值。审计与日志不记 token、完整联系值、外部 subject 或证据。

授权范围与 A15"公开发布授权"是不同事实：建立客户关系或预约成功**不推导**任何公开影像授权。

## 8. 错误契约（稳定类别，不含秘密）

`INVALID_REQUEST` / `NOT_FOUND` / `FORBIDDEN` / `VERSION_CONFLICT` / `IDEMPOTENCY_CONFLICT` / `SLOT_UNAVAILABLE` / `SLOT_CLOSED` / `RESERVATION_NOT_PENDING` / `RESERVATION_EXPIRED` / `RESERVATION_ALREADY_CANCELLED` / `ORDER_ALREADY_CANCELLED` / `RELATIONSHIP_REQUIRED` / `LINK_NOT_APPROVED` / `STORAGE_UNAVAILABLE` / `CONFLICT`。

对外错误统一使用"不存在或不可用"语义，避免资源存在性枚举。

## 9. 与既有切片的关系

本模块**不依赖也不修改** S2 内容切片（`admin-content`）的表、端点与模块边界；工作包/套餐事实以**外部引用**（`package_version_ref` 字符串）接入，不跨模块直连 `admin_work_*` 表，避免两个 AI 并行改动互相污染。若 S3 后续引入真实套餐模型，只需替换该引用的事实来源。

## 10. 验证门

单元测试覆盖：状态机转移、幂等重放与同键异载荷、快照不可变性、修订连续性、pending 不占档、过期与确认竞态、条件更新一次性、关系未批准时无权限效果、越权访问拒绝、失败回滚语义。

真实 MySQL/InnoDB 的事务、唯一约束、锁序与 grants 证据属后续隔离验收（D-703 §7–§8 RES-01..06 / REV-01..02），不以 H2 或 mock 冒充。
