# S1-D 一次性 L5 隔离验收：设计与影响清单

> 状态：**已执行；专用隔离 L5 attempt 27 PASS（仅限本地可销毁 fixture）**。这不是生产变更授权，也不允许借用 C2 的数据库、服务、证书或结果。所有资源须在开始前写入本次 run manifest，结束时只回收 manifest 所拥有的资源。

## 1. 目标

用真实的专用 MySQL、HTTPS 管理候选和浏览器，验证登录→当前会话→退出的同源 Cookie/CSRF 流程及代表性拒绝路径。测试账号只通过现有 C3 `AccountCliMain` 命令路径创建；不直接 SQL 写账号、不开放 HTTP 生命周期写接口。

## 2. 精确影响清单

| 资源 | 唯一范围 | 创建/使用方式 | 回收方式 |
|---|---|---|---|
| Fixture 根目录 | `%LOCALAPPDATA%\SouthLeaf\S1DAdminWebVerification\<runId>` | 新建空目录并限制为当前 Windows 用户；若基目录存在非空内容则拒绝，不覆盖、不清理旧内容 | 只在 PID、manifest、MySQL `server_uuid`、datadir 和监听端口全部匹配时停止该次子进程；确认进程退出后删除本次 run 目录 |
| MySQL | `D:\SoftWare\MySQL\bin\mysqld.exe`，绑定 `127.0.0.1`，每次随机高端口 | 空 datadir；新 schema `southleaf_admin_s1d<runId>`；TLS only；创建本次专用 `s1d_migrator`、`s1d_cli`、`s1d_runtime` 身份和最小 grants；不得连既有服务/3306 | 仅对 PID/manifest/UUID/datadir/port 匹配的本实例发送正常 `SHUTDOWN`；等待登记子进程退出并确认监听消失后删除 owned datadir |
| TLS 材料 | 只在上述 owned 根目录内 | 为此 fixture 生成独立 localhost 测试 CA/服务器证书；不导入 Windows 系统信任，不读取/复制 C2 或生产证书；Java truststore 与 HTTPS keystore 只服务本候选 | 与 fixture 根目录一起删除；报告仅记录公钥/配置摘要，不记录私钥或密码 |
| 数据库迁移/账号 | 上述唯一 schema | 仅用显式 `--migrate`/`--validate`；测试身份由 C3 CLI 创建，仅一名合成 staff；密码随机产生，DPAPI 保护落盘，仅在子进程环境/内存中使用 | 删除整个 owned MySQL datadir；不在共享库中清表或执行 TRUNCATE |
| 管理候选 | 当前 `admin-runtime` 源码构建的临时 HTTPS JAR，绑定 `localhost` 随机端口 | 将 `admin-web` 的生产构建静态资源打入本次临时 JAR；API 与 UI 同源；候选日志静默收集并只输出安全标记/摘要 | 只停止本次启动的 Java 子进程；回收临时 JAR 和日志 |
| 浏览器 | 本机已安装 Microsoft Edge，由独立 Playwright L5 用例启动 | 无用户 profile、无扩展；不复用用户浏览器数据；不改系统信任；Playwright 报告不含密码、Cookie、CSRF 值、请求正文或截图 | 关闭本次 BrowserContext/Edge 子进程，删除本次临时 profile/报告 |
| 证据 | 默认 `docs/开发与演示/证据/stage-two-s1d-isolated-l5.json`；重试时用 `--evidence-path <文件名>` 指定同目录下的新 JSON | 只记录阶段、runId、候选源码/JAR 哈希、测试计数、失败原因类别、清理标志；不记录口令、原始 Cookie 或个人数据；禁止覆盖既有证据 | 证据保留在仓库；fixture 内容必须已清除 |

## 3. 不触碰范围

禁止访问或修改 C2 的 `127.0.0.1:3306`、任何现存 MySQL、原服务、生产、系统信任、实际客户/通知、C2/C3 既有 manifest/密钥/数据库。不得运行 C2 的 `verify-admin-isolated.py` 作为本次 L5；不得使用真实账号、默认管理员、真实凭据或生产证书。不得提交/推送。

## 4. 执行门禁与流程

1. 每一步核对 manifest 的 `kind= S1D_DISPOSABLE_L5`、随机 runId、唯一 datadir/schema/port 和本机环回地址；任一值不匹配立即停止。
2. 生成随机口令，使用 Windows DPAPI 保护；命令行、源码、日志和证据均不出现明文。TLS 私钥只在 ACL 受限的 owned fixture 内短暂存在，且不进入工作区、Git 或证据；成功/失败均由专属 cleanup 删除。
3. 迁移后用 C3 CLI 创建合成 staff；随后启动同源 HTTPS runtime/UI candidate。若任一阶段无法证明目标归属，不执行下一阶段。
4. 浏览器覆盖 csrf、错误密码、缺失/伪造 Origin/CSRF、成功登录与身份 DTO、Cookie 属性/无 `JSESSIONID`、退出后旧 session 失效、无浏览器存储；并保存脱敏计数。
5. 正常停止 Java 与 MySQL 子进程，检查 PID/端口归零，确认本次 fixture 目录删除。清理失败则报告 `FAIL_CLEANUP` 并保留证据，不删除无法证明归属的资源。

## 5. 本次结果的边界

只有完成上述 manifest-owned fixture、浏览器/API/MySQL 检查和 owned cleanup，才可把 S1-D L5 记为 `PASS`。L1/L2、C2 旧结果、MockMvc 单测或打包边界检查均不能替代。

## 6. 首次尝试与重试约定

首次确认运行 `runId=8d39839f` 在 MySQL 启动阶段失败；历史证据 `stage-two-s1d-isolated-l5.json` 保留为 `FAIL`，不得覆盖，也不能作为 L5 通过证据。失败后根据 manifest、随机监听端口、TLS 查询得到的 `server_uuid`/datadir 与 MySQL 启动配置核实该实例归属，经正常 `SHUTDOWN`、确认进程与监听退出并删除本次 run 目录，完成事后清理。Runner 随后改为 `--no-monitor`，并要求监听 PID 与 manifest 一致；发现任何残留监听即拒绝删除 fixture。

第二次尝试 `runId=64daa297` 已通过 MySQL TLS/loopback 启动检查，但在 schema/user/grant 初始化批次中以 `MYSQL_CONTROL_QUERY` 失败；证据 `stage-two-s1d-isolated-l5-attempt-2-20261025.json` 保留为 `FAIL`，`cleanup.complete=true`。该次未进入迁移、CLI、HTTPS 或 Edge。随后把初始化顺序拆为：先建隔离 schema 与 migrator，完成显式迁移后再创建 CLI/runtime 用户及表级最小 grants。

重试使用新的证据路径，例如：

```sh
python backend/scripts/verify-s1d-admin-web-isolated.py --confirm-disposable-s1d-l5 --evidence-path stage-two-s1d-isolated-l5-attempt-3-20261025.json
```

只有新证据显示 `status=PASS`、`cleanup.complete=true` 且 Java/MySQL 均正常关闭，才可报告 L5 通过。

## 7. 最终执行结果（2026-10-25）

专用 attempt 27 / runId `6fa82949` 满足上述门禁：35 项检查、27 项浏览器检查，`cleanup.complete=true`；Java、MySQL 和浏览器均正常停止，owned fixture 已删除。最终证据为 `docs/开发与演示/证据/stage-two-s1d-isolated-l5-attempt-27-20261025.json`；Candidate JAR SHA-256 为 `da91decdf559d92069b8446d30673fc784b708c0614f720be0447c6aeb4b0567`。

证据明确 `realOrProductionDataUsed=false`、`secretsRecorded=false`、`c2FixtureUsed=false`。本次结果只证明 S1-D 指定候选在该一次性隔离 fixture 上的本地验证，不构成生产接入或整阶段验收；历史 FAIL 证据继续保留。