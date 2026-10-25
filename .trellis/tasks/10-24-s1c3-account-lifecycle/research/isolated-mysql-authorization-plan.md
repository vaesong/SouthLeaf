# C3 隔离 MySQL 授权与验收准备包（草案，不是授权）

**状态：`DRAFT_NOT_AUTHORIZATION`。** 本文只列出未来运行 D-703 `S1C-03`–`S1C-07` 所需的最小授权与可观察结果。它不创建 schema、数据库账号、grant、证书、数据或服务；不得据此连接、迁移、启动/停止既有服务或读取任何凭据。

## 已授权执行记录（2026-10-24）

本草案本身仍不构成授权；随后收到的单独明确用户授权只覆盖本文件所述的可销毁 C3 fixture。j78 已在该边界内成功执行：`AccountLifecycleRealIT` 1 completed、0 failures/errors/skips，V2–V4 已显式迁移，owned fixture 已删除。机器证据为 `docs/开发与演示/证据/stage-two-s1c3-isolated-it.json`。这不改变 C2 `BLOCKED_PUBLIC_REGRESSION`，也不授权 HTTP/admin-web、原服务或生产。

## 1. 隔离不变量

- 环境所有者提供一个明确标为非生产、可销毁的 MySQL 8.4/InnoDB fixture；它与生产、原公开服务、`127.0.0.1:3306/southleaf_public_it`、C2 管理实例不共享进程、datadir、端口、schema、数据库身份、连接池或数据。
- schema 名称由 owner 在授权时指定，demo 目标必须匹配 `southleaf_admin_[a-z0-9]+(?:_[a-z0-9]+)*`；每次测试以唯一 run ID 建立记录前缀。不得 TRUNCATE、关闭 FK、重建既有库或清理他人记录。
- 若使用 TLS，只允许既有私有测试信任材料；不得新建/写入私钥或扩展系统/生产 trust。CLI 必须使用 canonical JDBC URL、有效 TLS 模式、显式 schema，且所有密码只由受控外部凭据源注入，不写仓库、命令行、日志或报告。
- migration、runtime 与 account CLI 必须是三个不同的非 root 直接授权身份。`CURRENT_USER()` 必须等于授权时给出的非秘密 expected user；`CURRENT_ROLE()` 必须为 `NONE`，`@@GLOBAL.mandatory_roles` 必须为空。任何角色、GRANT OPTION、DDL 或跨 schema 能力都是 FAIL，而非“方便的测试设置”。

## 2. 需要用户/环境 owner 明示确认的最小事项

1. fixture owner、环境类别、可销毁 schema 名称/生命周期、独立回环端口与清理责任；确认不使用原服务或生产资源。
2. 可用于该 fixture 的 MySQL 8.4/InnoDB 版本、TLS hostname/CA 类别（不提供秘密）、以及三个 expected identity 的非秘密文字值。
3. migration identity 仅用于显式应用/校验 V2、V3、V4；runtime identity 保持既有 C2 精确矩阵；CLI identity 仅拥有 `USAGE` 加下列十项 direct table grants：
   - `admin_bootstrap_guard`: `SELECT, UPDATE`
   - `admin_account`: `SELECT, INSERT, UPDATE`
   - `admin_account_permission`: `SELECT, INSERT, DELETE`
   - `admin_account_command`: `SELECT, INSERT`
4. 允许在该 fixture 上运行已审阅的 C3 migration、CLI 与测试代码；允许测试结束后由 owner 删除本次 schema/identity/临时信任引用并给出无秘密清理结果。没有该项明确授权时，所有 L3/L4 用例保持 `NOT_RUN`。

## 3. 授权后执行顺序与停止条件

1. **只读预检。** 记录环境类别、隔离 target、MySQL 版本、TLS 成功/失败分类、`DATABASE()`、`CURRENT_USER()`、role 状态与脱敏 grant shape；任何不符立即停止，不尝试修复授权。
2. **迁移。** 只由 migration identity 显式校验并应用 V2–V4；V1–V3 不改写，runtime/CLI 不调用 Flyway、baseline、repair、clean 或 seed。
3. **S1C-03。** 两个独立 CLI 连接以 barrier 竞争 bootstrap；恰一成功，另一稳定拒绝；验证 account、permission、command、guard 同事务，无半初始化。
4. **S1C-04。** 以显式 barrier 覆盖 KDF 后改密/禁用先提交与先签发两种顺序；断言签发/后续认证符合当前 version/enabled。若 C2 所需隔离 login fixture 未授权，本子项标 `BLOCKED`, 不以 mock 代替。
5. **S1C-05。** 同 expected version 的改密/权限替换只一方成功；相同非秘密 command ID/payload 只产生一组事实，不同 payload 拒绝；旧 session 不因版本改变恢复。
6. **S1C-06。** 对命令表/guard/账号权限写入故障做受控失败注入，验证事务回滚；不伪造审计或未知提交成功。无法以隔离 fixture 安全注入的故障拆分为 `BLOCKED`。
7. **S1C-07。** 用三个身份作正反 SQL/grant 检查：runtime 无账号写入；CLI 无 session/auth-event/Flyway/DDL/GRANT/公开 schema 权限；migration 不具有日常 lifecycle 能力。CLI argv、错误输出和日志扫描不得含密码/hash/token/URL/完整 grants。
8. **清理与证据。** 关联逆序清理本 run ID 的记录；owner 删除临时 schema/账号/凭据引用。证据只记录用例 ID、代码哈希、命令退出码、环境类别、结果计数与失败分类，不保存秘密或完整 grants。

## 4. 结论规则

- 任一授权、TLS、身份、role、grant、迁移、并发、清理或证据步骤未运行/失败，C3 L3/L4 不通过；C3 仍不开放 HTTP/admin-web。
- C2 继续是 `BLOCKED_PUBLIC_REGRESSION`，其既定公开回归只能由环境 owner 恢复后按原入口执行。本 C3 fixture 无法替代、更改或绕开该门禁。
