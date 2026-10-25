# 实施记录：S4/S5 客户身份与档期预约确认成单

## 交付范围（本任务实际完成）

后端能力切片：领域 + 端口 + 应用层 + JDBC 适配器 + 迁移 + grants + 单元测试 + 文档。

| 产出 | 路径 |
|---|---|
| 领域模型与状态机 | `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/domain/` |
| 端口契约 | `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/port/BookingPorts.java` |
| 应用层编排 | `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/application/BookingApplication.java` |
| JDBC 适配器 | `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/adapter/jdbc/` |
| 精确 grants | `backend/admin-booking/src/main/java/cn/zhinan/adminbooking/policy/AdminBookingGrantPolicy.java` |
| 迁移 | `backend/admin-migration-cli/src/main/resources/db/admin-migration/V6__admin_identity_booking.sql` |
| 单元测试 | `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/` |

## 实现要点

1. **域层零依赖**：`domain/` 与 `port/` 不引入 Spring、Web、JDBC 或文件系统类型；`BookingRules` 是纯函数，容量/时长/时区等经营数值由调用方传入。
2. **失败类别稳定**：`BookingFailure` 枚举 + `BookingException(category, reason)`，不携带 SQL、路径、联系值或外部 subject；HTTP 映射留给运行时适配器。
3. **锁顺序统一**：`idempotency → reservation → slot → occupancy → order → audit/outbox`，应用层所有命令按此序取用端口。
4. **排他占档靠唯一约束**：`booking_slot_occupancy` 以 `slot_id` 为主键，重复写入触发唯一冲突 → `SLOT_OCCUPANCY_CONFLICT` → `SLOT_UNAVAILABLE`；不做"先查后写"占位。
5. **确认即成单**：`confirmReservation` 在单一事务内完成 occupancy → reservation 状态迁移 → 唯一订单 → 不可变快照 → 审计 → outbox → 幂等记录，任一步失败整体回滚。
6. **pending 不占硬档**：创建申请只写 `booking_reservation`，不写 occupancy，不产生订单。
7. **改期原子**：`rescheduleReservation` 在同一事务内原子移动 occupancy 主键并追加修订；目标冲突或后续步骤失败会回滚，原档与预约时间完全保留；冲突不自动另选时段。
8. **取消**：`cancelReservation` 同事务释放 occupancy、迁移预约与订单状态、追加一条订单修订；阶段二无退款语义。
9. **快照不可变**：`booking_order_snapshot` 无 `updated_at`/`version`，适配器不提供更新路径；纠正只能追加 `booking_order_revision`（`UNIQUE(order_id, revision_no)`）。
10. **幂等**：`actor + operation + clientKey` 作用域，`booking_command` 唯一键兜底；同键异载荷 → `IDEMPOTENCY_CONFLICT`；重放返回 `replayed=true` 且不产生第二个订单或占用。
11. **过期竞争**：`markExpired` 为条件更新（状态 + `expires_at <= db_now`），与确认竞态时恰有一个终态转移胜出。
12. **显式无支付**：`booking_order` 只有 `lifecycle_status` 与 `fulfilment_status`，全库无 payment/deposit/refund/delivery 列。

## 未包含（后续切片，不得计入本任务验收）

- 后台 HTTP 控制器与 Spring 装配（`admin-runtime` 接线）
- admin-web 的预约/订单页面
- 真实 provider/生产最小 grants 与 HTTP 运行时端到端验收（隔离 MySQL 事务、唯一约束、改期/取消、并发和 outbox 的机器证据已完成，见最终验证状态与 D-703 §8.4）
- 客户会话适配器与小程序自助预约
- 套餐事实来源（当前以 `package_version_ref` 外部引用接入）

## 最终验证状态（2026-10-25；取代下方旧 51/11 与 MariaDB-only 执行统计）

### 用户机器隔离 MySQL 8.4.11 实跑

命令入口：

`powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-BookingDatabaseIntegration.ps1`

- 目标：MySQL Community **8.4.11 / InnoDB**，loopback 127.0.0.1:3307，固定专用 schema `southleaf_booking_it`、独立随机实例 marker 和 schema 内测试身份；ownership guard 成功后才允许 destructive IT。未连接 3306/C2/生产。
- 结果：`admin-booking` Spotless + Surefire **73/73**（JdbcConnectionProviderTest 5、BookingApplicationTest 27、OutboxDispatcherTest 10、BookingRulesTest 22、AdminBookingGrantPolicyTest 9）；Failsafe **28/28**（BookingDatabaseIT 16、BookingConcurrencyIT 3、OutboxDatabaseIT 9）；0 failures、0 errors、0 skips；最终 Maven `BUILD SUCCESS` / exit 0。
- 同一 runner 随后 `python scripts/check-module-boundaries.py` PASS（10 modules、2 个负向自测），`check-booking-sql-columns.py` 输出 `BOOKING_SQL_COLUMNS_MATCH_SCHEMA`；PowerShell launcher/runner 语法分析通过。
- 最终清理输出 `BOOKING_DB_STOPPED_AND_OWNED_DATADIR_REMOVED`，隔离 mysqld、connection.env 与 owned datadir 均已移除。之前失败的 launcher/path/URL 尝试不计 PASS，只有最终 exit 0 运行计入。

### 本次测试识别并修复的缺陷

MySQL 8.4.11 首次并发同键确认测试发现：第二个事务可能在赢家的命令账本提交前读到空值，随后因 occupancy 唯一约束冲突误报 `SLOT_UNAVAILABLE`。现在在该事务完整回滚后重新读取已提交 command ledger：同键同摘要返回赢家的同一订单；同键异摘要仍拒绝为 `IDEMPOTENCY_CONFLICT`；账本不存在则维持真正时段冲突。修复后上述 101 项全部通过。

将 production JDBC outbox writer 接入真实 DB assertions 时，MySQL strict mode 又暴露 `JdbcBookingAudit.enqueue` 漏写非空 `updated_at`（error 1364）。适配器已补齐创建/更新时间，集成测试断言确认成功时审计/outbox 各一，并断言强制回滚后占档、审计和 outbox 均为零；最终 101/101 包含此修复。

此前 MariaDB 11.8.6 沙箱的 101/101 是单独辅助结果，不代替此次 MySQL 8.4.11 机器证据。

### 旧 51/11 基线及剩余边界

旧文档中的 51 个 unit + 11 个数据库 IT 是较早阶段的历史统计，本节 73+28 的最终结果 supersede 它。全 reactor `mvnw.cmd verify` 未运行；本轮仅验收 `admin-booking` 模块，未设置 `SOUTHLEAF_TEST_DB_URL`，不得声称全 reactor verify 通过。

数据库 IT 证明隔离 schema 中 JDBC/约束/并发/outbox 行为；测试身份在该专用 schema 拥有重建测试表所需权限，**不是**生产最小 grants 证据。`admin-runtime` HTTP/Spring 接线、管理 session、admin-web、小程序入口、真实 provider 通知、调度/人工死信恢复、KMS 与实际角色授权未交付。Q02/Q04/Q05/Q06 继续待决；不改支付/24 小时 deposit baseline。无真实客户、支付或通知数据。