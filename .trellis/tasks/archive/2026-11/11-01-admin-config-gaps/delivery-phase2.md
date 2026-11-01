# 阶段 2 交付：预约规则

只做了预约运营参数。档期开放、关闭和按周批量没有做。没有连接 3306，没有提交 git。

## 行为

- 管理库新增规则头指针和追加版本表。没有默认行。未保存过就是未配置。
- 新权限 `BOOKING_RULES_WRITE`。读取用现有 `RESERVATION_READ`。
- 保存校验：提前小时 0–168、最远天数 1–180、待确认过期小时 1–168、单档时长 30–480 分钟。容量固定为 1，不写入规则正文。
- `GET /api/admin/v1/bookings/operating-rules/current` 与 `GET .../versions` 读当前和历史。`PUT .../operating-rules` 追加版本，要求 CSRF、`If-Match: "o{version}"`（首次为 `o0`）和 `Idempotency-Key`。摘要按规则正文计算，同一键不能改成另一套数字。
- 规则存在时，公开查档和提交申请用规则里的提前期、最远日期和待确认过期小时。环境变量小时数仍是启动必填项，但不再决定新申请的过期时间。已有申请和档期不回写。
- 预约进程未启用时仍是 `BOOKING_SERVICE_UNAVAILABLE`。进程已启用但没有规则版本时是 `BOOKING_RULES_UNCONFIGURED`，小程序文案为「暂未开放预约，请稍后再来或直接联系我们」。
- 档期安排页增加「预约规则」卡片。未配置时表单预填 24 / 30 / 48 / 120。保存后提示「已生效，只影响之后的新档期与新预约」。原来的说明只保留档期还不能在页面上开放或关闭。

## 迁移

`backend/admin-migration-cli/src/main/resources/db/admin-migration/V12__admin_booking_operating_rule.sql`

## 权限授予

与现有能力相同，分两步，迁移不插入账号、不插入权限行：

1. `V12` 替换 `ck_admin_account_permission_code`，允许 `BOOKING_RULES_WRITE`。
2. 用现有账号 CLI 的 `replace-permissions`（或首次 `bootstrap`）把该码授给现有管理员，例如 `--permission BOOKING_RULES_WRITE`。CLI 按 `AdminPermission` 枚举解析，没有单独的默认管理员。

在完成第 2 步之前，管理员保存规则会得到 403。

预约写账号还需要对 `admin_booking_operating_rule` 与 `admin_booking_operating_rule_version` 持有与其他预约表相同的完整权限；公开预约账号需要对这两张表有 `SELECT`。启动时的授权核对会按这个清单拒绝缺权限的进程。

## 验证

| 命令 | 结果 |
|---|---|
| `backend` 下 `.\mvnw.cmd -pl public-api test`（含在 `-am` 的前一次运行里） | 通过，70 个测试 |
| `backend` 下 `.\mvnw.cmd -pl admin-booking,admin-runtime -am test` | 通过。`admin-booking` 79 个，`admin-runtime` 113 个，`admin-security-core` 一并通过 |
| `frontend` 下 `npm run verify` | 通过，79 个测试 |
| `admin-web` 下 `npm run typecheck`、`npm test`、`npm run build`、`npm run format:check` | 均通过，测试 58 个 |

## 未运行

- 隔离 MySQL 与 `Verify-StageTwoGate.ps1` 按任务放到全部阶段之后。`V12` 还没有在数据库上执行。
- 没有在浏览器里点保存规则。管理端单测和构建覆盖了接口客户端与页面类型。

## 不确定

- 提前期和最远日期按绝对时刻比较（`现在 + 小时`、`现在 + 天数`），不是按上海日历日的零点切开。公开查档的起止日期仍用环境变量里的营业时区解释。
- 单档时长已经保存，开放档期的下一阶段才会用来生成时段。
