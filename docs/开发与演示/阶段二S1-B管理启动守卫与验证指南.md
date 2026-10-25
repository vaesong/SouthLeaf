# 阶段二 S1-B 管理启动守卫与验证指南

> 日期：2026-10-18；状态：**S1-B 非 Web 守卫已实现，本地验证与独立 MySQL 最小权限验收通过。**
> 不等同生产部署或后台登录验收。计划：[P03](../计划/P03_阶段二分批交付与编码执行计划.md)；设计：[D-506](../设计笔记集/05_数据与存储/06_后台管理运行隔离与Writer最小权限设计.md)；[机器证据](证据/stage-two-s1b-runtime-validation.json)。

## 1. 当前实现

```text
六项管理环境变量 → 严格配置/URL 检查
  → 独立非 Web Spring 环境与显式组合根
  → 私有 Hikari 池 → 实际身份 / TLS / 完整 grants / 角色检查
  → VerifiedAdminDataSource → 同源 JdbcAdminSessionStore 与事务管理器
```

- 不回退公开 Reader，不加载工作目录外部 Config Data、公开默认配置或 Spring 系统/环境属性源。
- 没有组件扫描、自动配置、裸 DataSource Bean、Flyway 自动迁移、SQL 初始化或管理 HTTP listener。
- 失败不发布 Store 并关闭池；验证后上下文失败或正常关闭也释放池。
- 公开源/产物、V1/V2 和旧运行输出保持；没有停启原 MySQL/公开服务。
- 仅实现安全会话存储的运行边界，尚无密码核验、账号 CLI、登录/CSRF、后台 UI 或后续业务。

## 2. 配置契约

六项均必填，凭据仅经受控来源注入进程环境：

| 变量 | 要求 |
|---|---|
| `SOUTHLEAF_ADMIN_MODE` | `demo` 或 `production` |
| `SOUTHLEAF_ADMIN_DB_URL` | 单主机 MySQL URL；schema 路径精确匹配；禁止凭据、fragment、编码别名、未知/重复参数 |
| `SOUTHLEAF_ADMIN_DB_USERNAME` | 专用非 root；当前支持 ASCII 字母开头、字母数字下划线、最长 32 字符 |
| `SOUTHLEAF_ADMIN_DB_PASSWORD` | 非空秘密，不进入仓库、命令行或普通日志 |
| `SOUTHLEAF_ADMIN_DB_SCHEMA` | production 固定 `southleaf_admin`；demo 必须带受限隔离后缀 |
| `SOUTHLEAF_ADMIN_DB_EXPECTED_USER` | 完整 `用户名@授权host`，与 `CURRENT_USER()` 逐字相等，不用 `USER()` 或仅用户名替代 |

`sslMode` 必须显式配置：production 仅 `VERIFY_IDENTITY`，demo 可用 `REQUIRED`、`VERIFY_CA`、`VERIFY_IDENTITY`；实际 `Ssl_cipher` 仍须非空。**迁移 CLI 始终要求 VERIFY_IDENTITY，没有为测试放宽。**

其他 URL 参数限于 `allowPublicKeyRetrieval=false`、UTC、强制会话 UTC、UTF-8，以及 1000–15000 ms 的 connect/socket timeout；缺省补安全值，两个超时各 5000 ms。池最大 4、minIdle=0、等待 5000 ms、连接验证 2000 ms，关闭 JMX。

未知 `SOUTHLEAF_ADMIN_*`（包括迁移变量）、Spring/config/profile/logging/server/debug/trace 覆盖及未知 CLI 参数均拒绝。管理配置在 Config Data 读取前固定为唯一 classpath 管理文件；不靠“激活 admin profile”冒充隔离。TLS 信任可通过受控 JSSE truststore 配置提供，不改变系统/JDK 默认信任。本地测试信任库只含测试 CA 公钥，私钥和加密凭据保留在仓库外受限目录。

这是受支持配置边界，不防御可改 jar、Java agent、JVM 或主机的管理员。尤其不能用 JVM 参数传秘密，JVM 可能在 main 运行前回显选项。

## 3. 身份、权限与锁

固定只读查询检查 `DATABASE()`、`CURRENT_USER()`、`CURRENT_ROLE()`、`@@GLOBAL.mandatory_roles`、`Ssl_cipher` 和 `SHOW GRANTS FOR CURRENT_USER()`。查询超时 5 秒，授权最多 64 行、单值最多 4096 字符；未知/不完整结果失败。

当前角色须为 `NONE`，mandatory roles 须为空；即使角色未激活，其直接 role grant 也拒绝。支持 MySQL 8.4 backtick 输出，不宽松猜测格式。

| 表 | 唯一允许的权限 |
|---|---|
| `admin_account` | SELECT |
| `admin_account_permission` | SELECT |
| `admin_session` | SELECT、INSERT、UPDATE |
| `admin_security_audit` | INSERT |

另须恰好一次 `USAGE ON *.*`。六项权限必须齐全；额外 global/schema/table/column grant、角色、GRANT OPTION、DDL/DELETE、重复权限、未知语法及 Flyway 历史表访问全部拒绝。启动检查不是永久授权监控，运行后的授权变动仍须重新核验。

### 真实最小权限测试促成的修正

原账号查询使用 `FOR UPDATE`，在 MySQL 8.4.11 的 SELECT-only Writer 下实际报 1142/42000。没有通过授予账号 UPDATE 来绕过，而是采用 [D-505 §8](../设计笔记集/05_数据与存储/05_后台安全持久化与事务设计.md) 的 **`FOR SHARE` 账号行锁**：

- 签发只读账号状态，共享锁阻止改密/禁用/版本更新的排他写在会话与审计提交前穿过。
- 账号修改先提交时，等待中的签发重新读取当前锁定行，拒绝旧版本或已禁用账号。
- 不同会话可以并行持有账号共享锁，各自原子提交。
- 撤销仍锁定 session 行并使用原 `FOR UPDATE`；矩阵、表结构和 V2 未扩权/修改。

真实事务回归新增 3 例，并保留原双向重置竞争测试。并发签发测试要求两线程同时通过账号锁后才释放写入屏障，退回排他账号锁将无法通过，不是仅“开两个线程跑完”。

## 4. 最终验证结果

| 验证 | 结果与边界 |
|---|---|
| 完整后端 verify | **200 单元/架构 + 42 真实 MySQL IT，0 失败/错误/跳过**；j509。IT 为公开 21 + 安全事务 21，其中新增 3 个共享锁竞争用例 |
| 测试追踪 | 保留 S1-A 135 个 case；恒定拒绝入口旧 case 被替换；新增/替代 107 例。独立实例脚本的场景不混入 JUnit 数量 |
| 独立 MySQL 8.4.11 | **29 场景通过**；j512。精确 demo/production-mode Writer、角色/mandatory roles、权限缺失/过量、跨库拒绝、证书链/主机名拒绝、真实受限 Store 与审计、正常关闭 |
| 真实管理 jar 负向 | **24 场景再次通过**；j513。最终 jar 输出严格匹配固定码；数据库不可用两场景分别观察 53/52 次，无 listener |
| 真实 Store | 同一已验证最小权限池签发、读取、撤销及幂等撤销成功；独立控制连接确认仅新增两条审计 |
| 非 Web 生命周期 | 默认进程保持运行且无 listener；仅向其独有控制台发送 Ctrl+C，退出 130，Writer 连接归零；`--check` 成功退出 0 |
| 模块/产物 | 声明图含 test、纯模块 jdeps、递归 jar 扫描和 2 个故意破坏自测通过；无公开/迁移/Web/Flyway 运行依赖泄入管理包 |
| 公开与小程序 | 新公开 jar 230 公开 + 8 安全检查、10 次调用/9 类契约操作通过，j487/j488；最终公开 jar 与该候选/S1-A 逐字节相同 |
| 依赖 | OSV 52 resolved 坐标、0 matches；管理 24 个外部 jar 与公开 46 个的子集逐字节相同。不涵盖插件、未来漏洞或可利用性 |
| 保全 | 80 个既有源/build 基线仅 7 个已说明文件变化，其余 73 与旧运行 85 文件哈希不变；j514。原 8090 正常，8091/8092/测试端口均关闭 |

真实空 TLS cipher 的反例使用 **test-source 工厂 + 真正未加密 JDBC 连接**进入同一 verifier；生产 URL 策略依旧拒绝 DISABLED，没有环境绕过开关。真实并发锁竞争在原隔离 JUnit fixture 上验证；新实例证明实际最小权限与 Store 兼容，两种证据分开报告。

前端 53 测试、格式、文档链接及最终差异检查以机器证据中的最新结果为准。最终管理 jar SHA-256：`62d780860efeb3033e4ce6365f67a1ed892270b9185f0aef513de31e9d873cbf`。

## 5. 构建与复验

在仓库根串行执行，不清理仍供旧服务使用的 `backend/target`：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-DatabaseIntegration.ps1
python backend/scripts/test-admin-runtime-guards.py
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-StartupGuards.ps1
npm --prefix frontend run verify
```

既有真实 IT 缺环境即失败，不跳过或替换成 H2。新增无库负向脚本只使用虚构凭据、子进程环境和绑定但不监听的临时端口。

用户已明确授权本轮独立实例准备。Windows 专属测试脚本使用现有 MySQL/OpenSSL/JDK，不安装软件；只接受自身私有目录的 manifest、UUID 和数据目录，禁用 MySQL monitor 以保持精确 PID 归属：

```bat
python backend/scripts/verify-admin-isolated.py prepare --confirm-isolated-provisioning
python backend/scripts/verify-admin-isolated.py verify --manifest "%LOCALAPPDATA%\SouthLeaf\S1BVerification\s1b-4a3e2e62540f\manifest.json"
```

第一条会新建另一套专用测试目录，不是重置现有目录；当前本轮实例已准备，可在证书有效期内直接使用第二条。测试 CA/叶证书有效期仅 2 天，过期应明确重新准备测试环境，不能关闭验证。首次准备遇到 Windows monitor 后，经受控恢复完成迁移；完整失败/恢复轨迹保留在机器证据，不把阶段性恢复伪称为首次无错通过。

实例只监听 loopback，mysqlx 关闭；首次引导使用唯一 Windows 共享内存通道、不开 TCP。目录 ACL 仅当前用户/SYSTEM，随机凭据由当前用户 DPAPI 加密，MySQL 客户端不读既有 login path。全局角色/TLS 反例仅作用于新实例，最后恢复并正常 SQL SHUTDOWN。私有测试数据/证书/加密凭据留存供复验，未删除；没有系统服务安装、JDK/系统 CA 修改或现有账号授权变更。

获授权部署已注入六项管理配置后，真实入口为：

```bat
java -jar backend/admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar --check
```

`--check` 验证后关闭池并退出；不带参数则非 Web 常驻至关闭。失败退出 2，只打印固定拒绝类别；成功标志为 `ADMIN_RUNTIME_READY:admin-isolated-v1:NON_WEB`。运维应以退出码与完整流程为准，不只匹配一个成功字样。

## 6. 发现、修复与剩余范围

没有隐藏失败：健康探针曾误用缺少 `/v1` 的路径；隔离准备曾因 Windows MySQL monitor 的父/子 PID 拒绝；真实 Store 暴露 FOR UPDATE/SELECT-only 冲突；新增测试曾有一个局部重构编译错误；控制台正常关闭后曾暴露测试脚本继承 stdin 句柄失效。均有原因、修复和重跑记录，详见证据 JSON。

**S1-B 的本地与独立测试环境门禁现已关闭。** 未完成的是生产部署/正式 CA 与凭据验收、S1-C 密码和账号生命周期、S1-D HTTP/Cookie/CSRF、后台 UI 及后续业务。test-instance 的 production 模式和测试证书不能冒充生产环境验收。

后续按 S1-C 推进，普通技术决定由助手按专业规范落实，不反复询问。未 commit/push，Trellis 未归档；没有独立子代理工具，本轮为主会话实施与分步清单复核，不冒称独立安全审计。
