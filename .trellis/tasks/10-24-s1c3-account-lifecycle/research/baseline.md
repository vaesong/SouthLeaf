# C3 本地基线调研（2026-10-24）

- 当前 reactor 有七模块；`admin-credential-crypto` 已实现 `PasswordHasher`/Argon2id，`admin-security-core` 是 JDK-only，`admin-runtime` 是非 Web Spring Boot 应用。
- V2 创建 `admin_account`、`admin_account_permission`、`admin_session`、`admin_security_audit`；后者的 action/target session 约束不适合账号命令。V3 是 runtime-only `admin_auth_event`。
- runtime 的 exact grants 为 account/permission SELECT、session SELECT/INSERT/UPDATE、security audit INSERT、auth event INSERT。C3 必须另建 policy，不能扩 runtime。
- `AdminAccount` 已有 1–64 ASCII 兼容校验；新建账号规则 3–64 属 CLI policy。`PasswordInput` 是登录/hash 资源上限（1–128 code points、UTF-8≤512），创建策略须在其上增加最小 15 和拒绝词表。
- C2 完整公开回归仍受 `127.0.0.1:3306/southleaf_public_it` 不可达阻断；本 C3 仅并行代码/静态验证，不把 C2 标为完成，也不操作原服务。
