# Actual isolated Writer privilege / lock contract conflict

2026-10-18：用户已明确授权独立本机测试实例。j493/j494 确认可用 MySQL 8.4.11 / Git OpenSSL 3.5.6 / JDK 21，未安装软件。新实例仅 loopback，独立私有数据目录，凭据为当前用户 DPAPI 加密，ACL 仅当前用户/SYSTEM；原服务未改动。

j497 首次准备因 Windows MySQL monitor 父/子 PID 不一致触发严格监听检查；j499 正常关闭此新实例，j501 确认测试端口无监听，仅原 MySQL 进程。j500 查证内置 --no-monitor，脚本已使用该参数保持单进程归属，未放宽监听要求。j502 明确完成两个仅属于新实例的管理 schema 原 CLI 迁移/校验，使用 VERIFY_IDENTITY 和专属测试 CA；新实例正常关闭。

j503 真实验证在 actual restricted Store 操作处失败；此前完整脚本未产出 PASS，因此不能算整体验收。j504 脱敏栈定位 createIfAccountCurrent 的账号行锁查询。j505 同一受限 Writer / 同一真实 MySQL / 同一 SELECT 查询只换锁子句：

| Query lock | Result |
|---|---|
| FOR UPDATE | exit 1, MySQL 1142 / SQLSTATE 42000, SELECT with locking clause command denied |
| FOR SHARE | exit 0 |

D-506 明确 admin_account 仅 SELECT，不允许 runtime 更新账号；现有 JdbcAdminSessionStore 为该行使用 FOR UPDATE，故真实权限矩阵与 SQL 不兼容。既有共库事务 IT 的较高权限掩盖了问题；本轮隔离验证发现，不得通过扩权或 mock 抹平。

建议：保留六项最小权限，将账号状态读取锁改为 FOR SHARE，并在 D-505/D-506 记录理由：共享锁应仍阻止管理侧改密/禁用/版本更新的排他写，允许不同会话的并发签发；需要明确的双连接并发回归证明，不仅改 SQL 后重跑计数。会话撤销表仍有 UPDATE 权限，其原 FOR UPDATE 不变。

这是安全事务锁契约的设计调整。当前先记录证据并停止改动产品 SQL，向用户请求确认。S1-B 状态为真实门禁阻塞，不能继续标记为仅待环境授权或整体验收通过。现有生产 Store SQL、账号表权限、公开服务及 V1/V2 尚未更改；新实例在 j503/j504/j505 后均已正常关闭。

## Resolution

User explicitly delegated professional technical decisions rather than requesting repeated confirmation. Selected shared account locking without privilege expansion. D-505/D-506 updated; production change is only account FOR UPDATE → FOR SHARE plus explanation, session revocation unchanged. j509 full verify: 200 unit/architecture + 42 MySQL IT, including three new shared-lock races; j512 independent runtime suite: all 29 cases PASS with real exact-grant Store issue/read/revoke/audit; j513 final packaged 24 negatives PASS. Conflict is resolved, not a remaining gate.
