# 阶段 3 交付：授权闭环、档期开放与关闭

没有连接 3306，没有提交 git。提前期和最远日期按绝对时刻比较：最早是「现在 + 提前小时」，最晚是「现在 + 最远天数」且该时刻本身可以开放。这与公开入口 `withinRule` 一致，不是按北京时间的日历日截断。

## A. 授权与部署

启动核对仍然是封闭清单。预约写账号对两张规则表必须是与其他预约表相同的全部权限；公开预约账号只能 `SELECT`。缺一条或多一条都会拒绝启动。

本阶段改过的授权 / 能力出处：

| 出处 | 改动 |
|---|---|
| `AdminBookingGrantPolicy.OWNED_TABLES` | 阶段 2 已加入两张规则表。本阶段没有再改 Java 清单 |
| `PublicBookingGrantPolicy.GRANTS` | 阶段 2 已加入两张表的 `SELECT`。本阶段没有再改 |
| `backend/scripts/verify-s6-booking-runtime-isolated.py` | `BOOKING_TABLES` 补上两张表，否则写账号授权和 `OWNED_TABLES` 对不上。测试管理员补 `BOOKING_RULES_WRITE`、`SLOT_WRITE` |
| `backend/scripts/verify-booking-miniapp-isolated.py` | 公开预约账号补两张表的 `SELECT`。在启动公开预约前写入一版默认规则（24 / 30 / 48 / 120），否则原有查档和建申请会得到 `BOOKING_RULES_UNCONFIGURED` |
| `docs/开发与演示/阶段二2B部署方案.md` | 写明 V12、V13、公开 V4，以及迁移后的 GRANT 和账号 CLI |
| `docs/开发与演示/阶段二2B对接清单.md` | 第 6 行改为规则必须由后台保存后才生效 |
| `docs/开发与演示/阶段二实施跟踪.md` | 公开写账号的现行授权说明补上两张规则表。历史「V2–V11 已完成」那一行没有改写成已经跑过 V13 |
| `docs/设计笔记集/04_接口与外部契约/03_后台管理接口.md` | 档期写接口改为 `SLOT_WRITE` 和现有路径 |
| `docs/设计笔记集/02_通用后端/02_权限与资源访问设计.md` | 词表补上两个新能力 |

查过但没有改、也不该改的地方：

- `verify-admin-isolated.py` 只给登录探针 `CONTENT_READ`，不启动预约写账号。
- `verify-s1d-admin-web-isolated.py` 只引导 `STAFF_ACCESS_MANAGE` / `AUDIT_READ`，并核对这个较小集合。迁移身份是 schema 级，不是预约运行身份。
- `verify-s2b-work-publication-isolated.py` 只有内容能力。
- `verify-account-cli-isolated.py` 只给 CLI 身份账号表权限，不发业务能力。
- `Start-BookingDatabase.ps1` 对隔离库做 schema 级 `ALL`，不启动授权核对。预约集成测试仍只执行 V6 副本，不会建规则表。
- S6 现有用例是后台确认预先插入的档期，不走公开建申请，也不调用新的开放接口，所以没有在 S6 里再插一版规则。

`replace-permissions` 会替换整组能力。交付命令必须把仍要保留的能力一起列出。迁移不插入管理员、不插入权限行。

## B. 档期

- 新迁移 `V13__admin_slot_write_permission.sql` 只替换权限 CHECK，加入 `SLOT_WRITE`，并保留 `BOOKING_RULES_WRITE`。没有改 V12。
- `POST /api/admin/v1/bookings/slots`：日期 + 开始时间。时长、版本来自当前规则。时区 `Asia/Shanghai`，显示偏移 `+08:00`，`rule_version` 为 `operating-{version}`。没有规则时 422，提示「请先设置预约规则」。
- `POST /api/admin/v1/bookings/slots/weeks`：若干天 × 若干开始时间。逐个返回。失败的格子不撤销已经开放的格子。
- `POST /api/admin/v1/bookings/slots/{slotId}/close`：占用或待确认申请则拒绝且不改行。已经 `CLOSED` 时返回 200 和当前状态。开放中的档期仍要 `If-Match: "r{revision}"`。
- 同一北京时间日内，与仍开放或已占用的档期时间重叠则拒绝。当天 `OPEN` 达到 `BookingRules.MAX_SLOTS_PER_DAY`（24）则拒绝。提前期和最远日期用上面的绝对时刻。
- 写接口都要 CSRF 和 `Idempotency-Key`。新路径在 `bookingRoutes` 里。权限是 `SLOT_WRITE`。周视图读取仍是 `RESERVATION_READ`。
- 没有调用、也没有修改 `BookingApplication.closeSlot`。新的关闭走单独的 `closeIfOpen`。
- 原 `GET /slots` 仍只返回未占用的开放档期。周视图改读 `GET /slots/board`，才能看见已占用和待确认。
- 档期安排页恢复「开放新档期」。抽屉里分「单个 / 按周批量」，可选日期和开始时间，并预览将生成的时段。未占用的开放格子可以关闭；已占用、待确认只显示状态。已去掉「档期需要联系技术人员」和「暂时还不能在这里操作」。
- 客户端在 `admin-web/src/bookings/slotsApi.ts`，没有改 `api.ts` 的传输。

## 迁移

- `V12__admin_booking_operating_rule.sql`（阶段 2，规则表与 `BOOKING_RULES_WRITE`）
- `V13__admin_slot_write_permission.sql`（本阶段，`SLOT_WRITE`）

## 验证

| 命令 | 结果 |
|---|---|
| `backend` 下 `.\mvnw.cmd -pl admin-booking,admin-runtime,admin-security-core -am test` | 通过。`admin-booking` 85（含 `SlotOpeningServiceTest` 6），`admin-runtime` 114（`AdminBookingControllerTest` 8），`admin-security-core` 65 |
| `frontend` 下 `npm run verify` | 通过，79 |
| `admin-web` 下 `npm run typecheck`、`npm test`、`npm run build`、`npm run format:check` | 均通过，测试 62。构建产物里没有 `MOCKFIX` |
| `python -m py_compile` 两个隔离脚本，以及 `python backend/scripts/test-verify-admin-isolated.py` | 通过，29 个无数据库测试 |

## 未运行

- `Test-DatabaseIntegration.ps1 -ValidateOnly` 在读取环境变量时就拒绝：缺少 `SOUTHLEAF_SECURITY_IT_URL`。没有连接数据库。
- `verify-s6-booking-runtime-isolated.py` 和 `verify-booking-miniapp-isolated.py` 没有 `--validate-only`。只做了语法编译，没有启动隔离 MySQL。
- 没有跑 `Test-BookingDatabaseIntegration.ps1`、`Verify-StageTwoGate.ps1`，也没有把任何脚本指到 3306。
- 预约 `*IT` 仍只应用测试资源里的 V6，看不到 V12/V13。隔离门禁要等完整迁移后的实例。
- 没有在浏览器里点开档期抽屉。

## 不确定之处

- 按周批量不是一个大事务。每格用由总幂等键派生的 `OPEN_SLOT` 命令。父命令 `OPEN_SLOT_WEEK` 的 `result_ref` 只存成功数量，因为该列只有 64 字符，放不下一组档期 id。重放会回到已成功的格子，失败的格子会再试一次；若当时造成重叠的档期后来被关掉，重放可能补开。
- 重叠检查会锁住「开始时刻落在当天」的已有行。两笔同时插入、彼此还都不存在的开放，仍可能都通过。没有为这个补唯一约束。
- 档期已经是 `CLOSED` 时，即使 `If-Match` 还是关闭前的修订号，也返回 200。仍开放的档期修订号不对则是 409。
- 单个开放没有调用 `BookingApplication.createSlot`。那个方法自己开事务，也不检查重叠、每日上限和预约窗口。HTTP 使用同一套 `BookingRules` 校验和 `ScheduleRepository.createSlot`。
