# S1-C3 受控账号生命周期 CLI

## 目标

交付独立、非 Web 的 `admin-account-cli`：在**单独数据库身份**下完成一次性初始化、改密、禁用、权限替换和只读命令结果查询。它与 C2 runtime、迁移 CLI、公开 API 分产物/分凭据/分连接池；绝不提供默认管理员、公开注册、HTTP 写入口或密码参数。

C2 完整公开回归仍为 `BLOCKED_PUBLIC_REGRESSION`，但这不阻断 C3 的隔离代码、迁移源码、静态/单元/架构测试。真实 C3 数据库迁移、账号/grant 准备和 IT 另按最小影响范围授权；不得启停原公开服务或连接生产。

## 需求

1. 新增普通 Java jar `admin-account-cli`；不依赖 Spring Boot/admin-runtime/public-api，不开启网络 listener，不自动迁移、seed、repair、clean 或授撤权。
2. 支持 `bootstrap`、`change-password`、`disable-account`、`replace-permissions`、`command-result` 五个明确子命令；每个写命令都要求可信 UUID command ID，后三项要求 expected security version。命令不接受 `--password`、环境变量密码、stdin 明文回显、配置密码或脚本常量。
3. 仅交互式非回显密码通道可提供 bootstrap/change-password 密码；无可用安全终端/秘密通道必须失败。创建/重置密码强制 15–128 Unicode code point、UTF-8 ≤512 bytes、原值处理，不 trim/归一化/大小写转换；拒绝最小版本化本地常见密码集合，集合无法加载时 fail closed。
4. 密码先经现有 `PasswordHasher`（Argon2id）生成版本化 hash，再在短事务内执行账号事实。调用方/服务必须复制并清零所拥有的 `char[]`；原密码、hash、token、JDBC URL、完整 grant 或异常正文不得输出。
5. 每个写命令在单事务内按 `admin_bootstrap_guard → target admin_account` 的锁序执行，校验版本、修改账号/权限、写幂等命令事实，并更新 guard（仅 bootstrap）。改密、禁用和权限变更必须递增 securityVersion；不会重新激活或复用旧会话。
6. bootstrap 只能成功一次；初始账号必须 enabled 且显式带 `STAFF_ACCESS_MANAGE`。disable/replace-permissions 不得让 enabled 且具 `STAFF_ACCESS_MANAGE` 的维护账号数降到零。没有 hard delete/enable/self-service reset/MFA/角色推断。
7. 密码命令不可通过重复提交密码重试：撞到既有 command ID 时必须要求 `command-result` 查询已有非秘密结果，且不得保存低熵密码摘要或可离线猜测指纹。禁用/权限替换仅保存规范化非秘密 payload SHA-256，用以区分同 ID 的不同行为。
8. 新增后续 Flyway 迁移（不改 V1–V3），包含 `admin_account_command`、`admin_bootstrap_guard` 的约束、索引和固定 guard 行。SQL 与专用 CLI exact-grants 同步设计；runtime 的四表+auth event 权限不得扩大。
9. CLI 只能在显式 `SOUTHLEAF_ACCOUNT_CLI_*` 配置、专属 expected user、TLS 和精确 grants 均通过后建立连接。运行/迁移/CLI 身份、DataSource 和环境变量命名空间不得共享；root、角色、GRANT OPTION、schema/global 通配及未知 grant 格式 fail closed。

## 非目标

- HTTP、Cookie、CSRF、管理页面/admin-web、浏览器 E2E。
- 原公开测试服务、生产库/身份/证书、真实人员/角色映射或 Q06 的经营决定。
- 创建真实测试 schema/账号、执行 migration/GRANT/REVOKE、读取现有凭据库、启动/停止数据库服务。
- 密码找回、启用账号、硬删、多因素认证、线上无感 rehash、客户身份和任何默认管理员。

## 验收标准

- [ ] Reactor 含新 jar，依赖图保持无环；public-api、admin-runtime 和 migration-cli 均不携带 CLI 类/资源，CLI 不携带 Boot/Web/runtime/public 类。
- [ ] 所有命令/参数/配置拒绝路径经单元和进程级负向测试；argv/环境/日志/异常/帮助无秘密泄漏；CLI 无安全终端时拒绝密码命令。
- [ ] 纯核心测试覆盖 ID/UUID/版本/Unicode/字节边界、未归一化密码、拒绝集合、command-result、命令 ID 重放、最后维护账号与 securityVersion 规则。
- [ ] V4 迁移文本与静态 shape/约束测试证明 V1–V3 未改、guard 初始行存在、命令表不含秘密字段，且授权矩阵与每条 SQL 一致。
- [ ] 隔离 MySQL 获单独授权后，执行 D-703 S1C-03..07：bootstrap 竞争、重放/冲突、改密/禁用与登录竞争、审计/命令事务回滚、runtime/CLI/migrator grants 反例；未执行前标 NOT_RUN，不以 mock 代替。
- [ ] C2 仍保持 BLOCKED_PUBLIC_REGRESSION 直到既定公开 fixture 恢复并完成完整 reactor/公开/原生回归；C3 通过不等于 S1 或 Web 开放。

## 事实来源

- [P03](../../../docs/计划/P03_阶段二分批交付与编码执行计划.md) §14
- [D-201](../../../docs/设计笔记集/02_通用后端/01_认证与会话设计.md) §8–14
- [D-506](../../../docs/设计笔记集/05_数据与存储/06_后台管理运行隔离与Writer最小权限设计.md)
- [D-703](../../../docs/设计笔记集/07_测试与验证/03_跨模块并发幂等测试.md)
