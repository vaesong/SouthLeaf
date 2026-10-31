# 阶段二工程收口设计

## Boundaries

这次只加测试、验证编排和现状文档。不改预约状态机、不改 outbox 投递语义、不新增生产调度器，也不换存储或微信实现。

四个 Failsafe 继续「缺环境即失败」。全仓库验证的修法是把 booking 与 C3 的隔离环境显式送进现有 reactor 入口，而不是给测试加跳过开关。

## Process-interrupt evidence

现有 `aMessageOfADeadWorkerIsRecoveredAfterTheLeaseExpires` 证明同一进程内租约到期后可以再领取。第 7 条还要证明进程边界。

新增 Failsafe：`OutboxProcessRecoveryIT`，放在 `admin-booking` 的 integration 包，复用 `BookingItTarget`、V6 迁移和 `SOUTHLEAF_BOOKING_IT_*`。测试插入一条 `PENDING` 通知后启动两个独立 JVM，classpath 用当前测试进程的 `java.class.path`。

| 进程 | 行为 | 退出后的数据库事实 |
|---|---|---|
| A | 以 `worker-a` 领取该消息，然后 `System.exit(0)`，不调用 `markDelivered` | `LEASED`，owner 为 `worker-a`，attempts 为 1 |
| B | 使用比租约截止更晚的 instant，走现有 `OutboxDispatcher`，投递结果为成功 | `SENT`，lease owner 为空，attempts 为 2 |

「现在」由参数传入，与现有 outbox IT 一样，不依赖墙上时钟，也不 `sleep`。进程 A 正常退出留下的租约行，和进程被杀死留下的租约行是同一份持久化事实；调度器没有租约行以外的检查点。因此不另做 SIGKILL。

子进程只从环境读取既有 `SOUTHLEAF_BOOKING_IT_*`，标准输出只打印固定标记，不打印 URL 或口令。

## Full reactor verification

`verify-admin-isolated.py` 的 `clean_env()` 会丢弃未列入子进程环境的 `SOUTHLEAF_*`。编排命令在调用 `verify --with-reactor` 之前：

1. 用现有 `Start-BookingDatabase.ps1` 启动 booking 隔离实例，读取 `connection.env`。
2. 从 `verify-account-cli-isolated.py` 抽出「只建库、不跑 Maven」的准备步骤，持有 C3 实例并导出 `SOUTHLEAF_C3_IT_*`。
3. 把这两组变量放进 `run_reactor_verification` 传给 `Test-DatabaseIntegration.ps1` 的环境。PowerShell 再把它们原样交给 `mvnw verify`。
4. `Test-DatabaseIntegration.ps1` 在已有 security/public 校验之后，拒绝缺失的 booking/C3 变量，避免静默退回本机凭据库。
5. 将 `run_reactor_verification` 的 600 秒超时调到不少于 45 分钟。booking 套件并入同一次 Maven 后，10 分钟不够。

三个 mysqld 使用各自端口和数据目录：C2 manifest 端口、booking 默认 3307、C3 的 `reserve_port()`。都不使用 3306。验证结束按各脚本既有 ownership 规则关闭并删除本次目录。

C3 测试只在这次 reactor Maven 里跑一次，准备步骤不再调用 `mvnw`。

证据写入 `docs/开发与演示/证据/stage-two-engineering-close.json`：`status`、退出码、四组环境均已注入、输出 SHA-256、实例已停止。失败保留 partial，不把失败运行记成 PASS。

## Packages and migrations

沿用部署方案已有命令：

- `backend` 下 `mvnw -pl public-api -am package` 产出公开 API jar。
- `admin-web` 生产构建后运行 `backend/scripts/build-admin-runtime.py`，静态资源打进 admin-runtime jar。
- 管理端迁移用隔离库的 migrator 账号执行 `--migrate` 再 `--validate`，成功标记为 `ADMIN_SCHEMA_VALIDATION_PASS`。
- 公开端迁移不带 `--seed-demo`。

套餐事实只做合成文件的 `--dry-run`，不导入真实价格，不把 `DATA_MODE` 设成带微信密钥的 production。

## Documents

只改「当前状态」句子，不重写历史节：

- `docs/开发与演示/阶段二G2A退出对照.md`：总判定和第 7 条。测试计数以本次实际运行为准，不沿用旧的 108。
- `docs/开发与演示/阶段二实施跟踪.md`：第 1 节表格，并在文末追加本次收口节。
- `docs/计划/P02_阶段二目标与实施计划.md`：当前位置段落。
- `docs/计划/P03_阶段二分批交付与编码执行计划.md`：文首状态行和第 1 节里「S4–S6 尚未完成」的现状句。
- `docs/开发与演示/阶段二2B部署方案.md`：打包命令旁的「待补」。
- `docs/开发与演示/阶段二2B对接清单.md`：进入条件第 1 条改为 11 条成立；资源清单保持未提供。

## Trade-offs

- 用进程退出而不是 SIGKILL。持久化结果相同，测试不依赖系统信号。对照表要写明这一点，避免以后被理解成漏测。
- 不重跑历史浏览器 L5。本次不改那些产品路径；重跑会把本机微信开发者工具和长时间夹具卷进来，不能增加本次验收信息。
- 不把对象存储或真实通知发送器提前做进部署包。那两项需要外部资源，属于对接清单。

## Rollback

进程测试和证据文件可以单独撤回。若环境转发让既有 `--with-reactor` 在缺少新变量时改变失败方式，恢复 `run_reactor_verification` 的环境表和超时，booking/C3 仍由各自原脚本单独运行。不修改已应用的 V2–V11 脚本。
