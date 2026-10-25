# 阶段二 S1-A 模块迁移与验证指南

> 历史切片记录：后续 S1-B 已实现非 Web 守卫，本页保留 S1-A 当时状态；当前入口与剩余门禁见 [S1-B 指南](阶段二S1-B管理启动守卫与验证指南.md)。

> 日期：2026-10-18；状态：S1-A 已实现并完成本地验证；S1-B/C/D 未实现。
> 计划：[P03](../计划/P03_阶段二分批交付与编码执行计划.md)；设计：[D-105](../设计笔记集/01_总体设计/05_阶段二S1模块拆分与构建迁移设计.md)；结果：[S1-A 验证证据](证据/stage-two-s1a-module-validation.json)。

## 1. 当前真实结构

```text
backend/pom.xml                 聚合父；保留 wrapper、原 BOM/Java/插件版本
backend/public-api/             原公开 main/API/Reader/公开 V1；坐标仍为 uniapp-api
backend/admin-security-core/    纯 Java domain/application/port
backend/admin-db-policy/        原迁移 URL/schema/TLS 规则的纯函数提取
backend/admin-migration-cli/    普通 CLI jar + 唯一管理 V2 SQL
backend/admin-security-jdbc/    显式 JDBC adapter；CLI 仅为 test scope 迁移资源依赖
backend/admin-runtime/          独立 Boot jar、生产 token codec；入口明确拒绝启动
backend/scripts/                共用工具入口；凭据解析器未改动
```

公开主类仍为 `cn.zhinan.uniapp.UniappApiApplication`。管理包移出公开默认扫描树；主源码 core/policy 无外部依赖。JDBC IT 和 core 会话测试各自使用 test-only 确定性 token fixture，避免反向依赖 runtime；生产 SecureRandom 实现及两个测试在 runtime。

V1/V2 SQL 字节未改变，公开源码/原公开测试/资源仅移动；管理领域规则和 JDBC SQL/事务语义未改变。迁移 policy 保持原严格 VERIFY_IDENTITY，不提前加入 demo Writer 放宽。

## 2. 构建与测试（Windows cmd.exe）

在仓库根执行完整测试：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-DatabaseIntegration.ps1
npm --prefix frontend run verify
```

后端脚本调用六模块 reactor verify，随后检查声明的 compile/runtime/test 图、纯模块 jdeps、最终 Boot 嵌套 jar 的类与资源。既有受控凭据解析器仅给测试/公开 Reader 注入环境；缺测试库或凭据直接失败，不自动创建、不 skip、不改 grants。

在 `backend` 目录只跑单元测试：

```bat
mvnw.cmd test
```

单元通过不等于 MySQL IT 通过。所有 Maven 和共享数据库测试串行执行，不同时对同一输出/fixture 运行多个构建。旧 `backend/target` 暂留给本机运行中的阶段一实例，不执行 clean 或删除。新模块输出在各自 target；不要向 Git 添加 jar、classes、日志或本机凭据。

## 3. 公开启动与候选验证

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Start-PublicApi.ps1
```

该脚本明确运行 public-api 子模块，默认不覆盖应用原端口配置；可显式 `-Port 8092` 启动另一个本机候选实例。不要在聚合父或带 `-am` 的多模块目标上无差别运行 `spring-boot:run`。

公开最终产物：`backend/public-api/target/uniapp-api-0.1.0-SNAPSHOT.jar`。已有公开负向启动脚本已指向新产物。候选检查必须指向新实例，不能把旧 8090 的成功当新 jar 验证：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/smoke-public.ps1 -BaseUrl http://127.0.0.1:8092
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/smoke-safety.ps1 -BaseUrl http://127.0.0.1:8092
set SOUTHLEAF_API_ORIGIN=http://127.0.0.1:8092
npm --prefix frontend run test:contract
```

本批实际分别验证了新 jar 的 8091 实例和更新启动脚本的 8092 实例；两个临时实例已结束。原 MySQL/公开服务未停止、未重启，85 个旧运行 classes/资源哈希保持不变。

## 4. 管理 CLI 与运行入口

管理 runtime 当前必定在 Spring context/连接池/listener 创建前抛出 `ADMIN_RUNTIME_NOT_ENABLED`。这是 S1-A 的明确关闭状态，不是后台已可用；S1-B 实现 verified bootstrap 后才改变。不要通过改 main、扩大扫描或直接注入 Writer 来绕过。

管理迁移 CLI 的独立打包（在 backend）：

```bat
mvnw.cmd -pl admin-db-policy,admin-migration-cli package dependency:copy-dependencies -DincludeScope=runtime
```

维护入口是 `backend/scripts/Invoke-AdminDatabaseMigration.ps1 -Action Validate` 或 `-Action Migrate`，必须已有显式管理迁移环境变量与另获授权的目标库/身份。脚本不读取公开 Reader 凭据、不自动迁移、不自动建账号。密码只能由受控环境提供，不能写入参数、仓库或普通日志。

本批只验证 CLI classpath 可运行、缺动作/非法动作/缺配置时明确拒绝；未对任何管理运行/生产库执行 Validate/Migrate，也未部署 Writer 权限。原公开迁移脚本仅改模块定位，没有被本批执行。

## 5. 依赖与历史验收

在 backend 可重新扫描当前公开运行依赖：

```bat
mvnw.cmd -f public-api/pom.xml dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt -DappendOutput=false
node scripts/check-runtime-advisories.mjs
```

工具明确扫描 public-api，要求 inventory 不早于父/子 POM。此次 52 个 resolved Maven 坐标，OSV 返回 0 matches；管理产物的外部库版本集合已证明为公开产物的子集。此结论不覆盖构建插件、漏洞可利用性或后续依赖变更，不能称为永久无漏洞。

阶段一候选指纹和源码导出工具保持历史职责。当前源树不再有旧 backend/src 时，candidateFiles 明确拒绝重新冻结，提示使用历史 checkout；不修改旧候选清单来伪造阶段一未变化。阶段二证据与提交另行维护，本批未提交/推送。

## 6. 真实验证与限制

- 后端最终完整 verify：97 单元/架构 + 39 真实 MySQL IT，0 失败/错误/跳过；IT 为原公开 21 + 管理持久化 18。
- 原 131 个实例中，129 个按类名/用例名逐项保留；两个旧源码路径级架构测试由更强的核心、公开/管理 classpath 与最终产物检查替代；新增/替代 7 个实例，净增 5。
- 65 个旧源文件都有新位置；46 个公开/SQL 文件逐字节保持，包含 V1/V2；旧运行目录 85 文件哈希保持。
- 新 jar 公开 smoke 230 项、安全 smoke 8 项、小程序真实 API 契约 10 次成功调用/9 类操作通过；启动脚本候选再过 230 项。
- 前端与文档最终检查、错误/恢复过程和命令 ID 以证据 JSON 为准，不把首次失败藏掉。

没有验收：独立管理 Writer grants/启动身份校验、凭据与账号命令、管理 HTTP/Cookie/CSRF、admin-web、S2 业务、生产环境/真机/灾备。下一切片为 S1-B；独立管理测试库与身份准备需另获明确授权，当前共库 IT 不代替该证据。
