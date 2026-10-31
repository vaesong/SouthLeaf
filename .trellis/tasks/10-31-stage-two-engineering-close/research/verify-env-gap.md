# 全仓库验证为什么现在跑不通

记录日期：2026-10-31。这是规划依据，不是验收证据。

`mvnw verify` 会执行四个模块的 Failsafe，类初始化即读取环境变量，缺任何一个就抛 `IllegalStateException`，没有跳过分支：

| 模块 | 必填环境 |
|---|---|
| `public-api` `DatabasePublicApiIT` | `SOUTHLEAF_TEST_DB_URL` / `USERNAME` / `PASSWORD`，以及运行时 `SOUTHLEAF_DB_*`；owned 路径还要 `SOUTHLEAF_PUBLIC_IT_ISOLATED=owned` |
| `admin-security-jdbc` | `SOUTHLEAF_SECURITY_IT_URL` / `USERNAME` / `PASSWORD` / `EXPECTED_USER` / `UUID` / `TRUSTSTORE` |
| `admin-booking` `BookingDatabaseIT`、`BookingConcurrencyIT`、`OutboxDatabaseIT` | `SOUTHLEAF_BOOKING_IT_URL` / `USERNAME` / `PASSWORD` / `INSTANCE_ID` |
| `admin-account-cli` `AccountLifecycleRealIT` | `SOUTHLEAF_C3_IT_MODE` / `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_SCHEMA` / `DB_EXPECTED_USER` |

现有入口只覆盖前两组：

- `backend/scripts/Test-DatabaseIntegration.ps1` 注入 public 与 security，然后调用整个 reactor 的 `mvnw verify`。
- `backend/scripts/verify-admin-isolated.py` 的 `run_reactor_verification` 用 `clean_env()` 丢掉父进程里所有 `SOUTHLEAF_*`，只把 security 和 public fixture 放进子进程。超时 600 秒。
- `backend/scripts/Test-BookingDatabaseIntegration.ps1` 自建隔离 MySQL（默认 3307，schema `southleaf_booking_it`），只跑 `admin-booking`。
- `backend/scripts/verify-account-cli-isolated.py` 自建 C3 隔离实例，只跑账号 CLI。

因此现在的 `--with-reactor` 在加载 booking / C3 测试类时会失败。质量规范要求真实 MySQL、禁止 H2 或 skip 代替，全仓库验证必须把这四组隔离环境同时交给同一次 `mvnw verify`。

G2A 第 7 条的进程缺口：`OutboxDatabaseIT.aMessageOfADeadWorkerIsRecoveredAfterTheLeaseExpires` 在同一个 JVM 里先 `lease` 再换一个更晚的 instant 再 `lease`。封档瞬间确认已经由 `BookingConcurrencyIT.aConfirmationThatWaitsOnTheSlotRowNeverBooksIt` 覆盖（提交 `0e11811`），对照表还没改。
