# S1-C3 实施计划

## 顺序

1. 在 core 新增生命周期命令值对象、密码创建策略、非秘密结果模型、`AccountLifecycleStore` 端口和纯应用服务；保持 core 仅 JDK。
2. 在 credential crypto 复用 Argon2id `PasswordHasher`，不新增 KDF 算法；给密码策略和秘密数组清理补单元测试。
3. 新建 `admin-account-cli` 模块：严格命令解析、非回显秘密读取、独立配置/URL/grant 策略、verified pool、JDBC adapter 与显式 main。不得改 runtime 账号写权限。
4. 新增 V4 SQL 源码和静态 shape/迁移顺序测试；不对任何数据库执行它。
5. 更新 reactor POM、模块边界检查与产物负向检查；public/runtime/migration 产物均不得带 CLI。
6. 运行纯单元、模块构建、Spotless、架构/嵌套 artifact、CLI 进程负向、markdown/差异/诊断检查。仅在获得单独 C3 隔离库授权后运行真实 MySQL concurrency/grant IT。

## 测试矩阵

- account/password/UUID/expected-version/permissions 输入边界；UTF-8 和 surrogate 拒绝；不 trim/normalize。
- bootstrap 一次性、初始维护权限、同 command ID 重放、同 ID 不同非秘密 payload、密码命令重复只能查询结果。
- 禁用/替换权限保持至少一个 enabled maintenance account；版本增加与历史会话拒绝由 C2 Store 竞争 IT 在获授权后合并验证。
- command table/guard SQL 不含秘密字段，V1–V3 sha/hash 不变；migration main 仍不自动运行。
- CLI 不接收 password argv/env，console 缺失拒绝；错误、help、toString、日志、异常不含输入密码/credential hash/URL。
- CLI grants 精确通过/缺项/额外项/role/grant-option/root/非 TLS/身份/schema 不匹配全拒绝；真实身份验证标 NOT_RUN 直到授权。

## 操作边界

本任务可以写源码、SQL 迁移文本和自动测试，但不创建/修改真实 schema、用户、grant、凭据或服务。C2 的既定公开 fixture、原 3306 守卫、生产和提交推送不触碰。C2 恢复后仍需补其独立完整回归；C3 完成不开放 HTTP/admin-web。

## 已落地的首个源码增量（2026-10-24）

- core：`AccountLifecycleStore`、生命周期命令/结果/拒绝类型、15–128 code point 的创建/重置策略、最小版本化本地弱密码拒绝表、秘密命令重放只允许结果查询、非秘密命令 canonical SHA-256 指纹。
- policy/migration：`AdminAccountCliGrantPolicy` 的十项精确 table grants 与 `V4__admin_account_lifecycle.sql`（command + singleton guard）；未执行 migration。
- 自动验证：core 65 测试，db-policy 71 测试，migration-cli 15 测试均通过，0 failure/error/skip。最初的格式失败、core 空权限 NPE、迁移测试错误断言已修正后重跑；它们不计为首次通过。
- 2026-10-24 第二个源码增量：已新增独立 `admin-account-cli` artifact、严格 allowlist argv parser、console-only 双次秘密读取、独立环境变量/TLS URL/`CURRENT_USER()`/精确 grant verifier、verified Hikari pool、显式 `AccountCliMain`、守卫优先锁序的 JDBC lifecycle store，以及模块边界和独立 Java 进程负向测试。
- 该增量仅编译、单元、产物/依赖静态检查；它从未以有效数据库配置运行，未连接、创建、迁移或修改任何数据库、账号或 grant。仍待：另行授权的隔离 MySQL S1C-03..07、真实身份/TLS/grant/事务/竞争验证及人工运维恢复 SOP 审查。
