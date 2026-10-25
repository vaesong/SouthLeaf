# Execution

1. 记录 S1-A 基线和 source hashes；先补 D-506 精确实现约定。
2. 提取共享 URL shape parser，保留旧 migration TLS 验证；添加 runtime URL/grants 纯策略与边界测试。
3. 添加 redacted properties、稳定拒绝码、私有池/metadata verifier/verified wrapper、显式组合根和生命周期；只加已有 BOM 的 Hikari/MySQL 依赖。
4. 测试配置来源、角色/精确授权、有界查询/资源释放、Store 注册顺序、无裸 DataSource、SQL 错误不泄露。
5. 串行格式/完整 verify/原 MySQL IT/嵌套包检查；不把 metadata double 计为真实 grants IT。
6. 最终 jar 子进程负向测试：缺/错配置、禁止参数、连接失败与日志脱敏、非 Web；不使用任何真实管理凭据。
7. 前端/文档/差异/诊断、旧公开输出哈希和服务保护复核；记录真实 grants BLOCKED，并提出最小测试环境授权请求。

无子代理工具，主会话执行并另做清单复核；不宣称独立审查。任务不自动 commit/archive。用户已批准 P03 中 S1-B 范围；具体数据库运维不在本轮授权内。

## Authorized isolated verification execution

已明确授权新本机实例（不安装软件、不动原服务）。预检 j493/j494：MySQL 8.4.11、既有 Git OpenSSL 3.5.6、JDK 21 工具可用。仅新增 test-source AdminRuntimeIsolatedProbe.java 与 backend/scripts/verify-admin-isolated.py；不更改生产守卫来通过测试。脚本 prepare 需显式确认标志，verify 只接受当前用户 LOCALAPPDATA/SouthLeaf/S1BVerification 下的专属 manifest/UUID/独立数据目录。仅监听 loopback，mysqlx 关闭；首次本地共享内存无 TCP 引导，立即设置随机 root 密码。目录 ACL 仅当前用户/SYSTEM，凭据 DPAPI 加密，不读既有 mysql login path，不在命令行/SQL文件/普通日志存秘密。

只在新实例准备 demo/production-name 管理测试库和公开 canary 库。migrator 独立 schema DDL/DML，writer 精确六权限，probe 仅作授权反例，reader 只读 canary；迁移走原 CLI + VERIFY_IDENTITY 和专属 CA truststore，不修改 JDK/系统信任。角色/mandatory_roles/secure_transport 反例仅在此实例改变，随后恢复。验证实际 Store 签发/读取/撤销/审计、跨库拒绝、角色、授权、证书链/主机名拒绝、默认进程非 Web 常驻及独有控制台正常退出。真实明文连接只经 test-source 工厂进入相同 verifier，主产物没有 bypass。测试完成正常 SQL SHUTDOWN，仅保留受保护测试目录供复验，不删除其他目录。

