# SouthLeaf — 公开展示与阶段二后台建设

摄影工作室公开展示小程序：品牌、作品、业务、套餐、成员及联系渠道。阶段一只验收演示内容与工程能力，不交付后台或生产发布。真实内容、权属/客户授权材料、后台上传维护、正式 AppID/域名/TLS/生产凭据由阶段二交付。

## 技术与目录

- `frontend/`：唯一正式客户端，原生微信 `App / Page / Component`；六页面、四 tab，零 npm 运行依赖，Prettier 为唯一 npm 开发依赖。
- `backend/`：Java 21 / Spring Boot 4.1.1 / Spring JDBC / MySQL；现为七模块 reactor（含独立凭据 crypto），公开 API、管理安全核心/适配器、运行时和显式迁移 CLI 分产物；管理运行时尚未开放。
- `docs/`：需求、架构、设计、运行与验收证据。
- `tools/`：文档校验、源码候选指纹等工程工具。

## 本机验证（cmd.exe）

后端在 `backend`：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-DatabaseIntegration.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-PublicApi.ps1
```

前端在 `frontend`（API 已启动）：

```bat
npm ci --ignore-scripts --registry=https://registry.npmjs.org
npm run verify
npm audit --registry=https://registry.npmjs.org
set SOUTHLEAF_API_ORIGIN=http://127.0.0.1:8090
npm run build:demo
npm run test:contract
```

微信开发者工具导入 `frontend/dist/build/mp-weixin`，不是源码目录或旧 `unpackage`。demo 使用明确游客 AppID，仅用于工具演示。API origin 必须显式配置，不能把手机自身 127.0.0.1 当作电脑。

## 交付入口

- [阶段一收敛交付记录](docs/开发与演示/阶段一收敛交付记录.md)
- [运行、初始化与复验指南](docs/开发与演示/阶段一演示运行指南.md)
- [文档索引](docs/README.md)
- [公开 API](docs/接口/public-api.openapi.json)

不自动提交 Git。生成物、安装依赖和本机配置不属于源码；只读运行账号不负责迁移。已应用 V1 不改写，旧库不作兜底。阶段一验收不代表正式上线、安全零风险或阶段二后台已经完成。

## 阶段二当前进度

S1-A 模块/构建/产物隔离已完成；当前不是可登录后台。代码位于 `backend/public-api`、`admin-security-core`、`admin-security-jdbc`、`admin-db-policy`、`admin-runtime`、`admin-migration-cli`，C1 新增 `admin-credential-crypto`。公开行为和 V1/V2 迁移字节保持；不在聚合父直接运行 Spring Boot。

- [P03 分批交付与编码计划](docs/计划/P03_阶段二分批交付与编码执行计划.md)
- [S1-A 模块迁移与验证指南](docs/开发与演示/阶段二S1-A模块迁移与验证指南.md)
- [阶段二实施跟踪](docs/开发与演示/阶段二实施跟踪.md)

旧阶段一冻结/导出清单仅用于其历史 checkout，不对当前阶段二源树重新冻结。管理 Writer 守卫及本地/独立 MySQL 最小权限验证已完成；C1 凭据核心/KDF 有历史验证；C2 JDBC 凭据、认证事件及非 Web 登录组合已有源码，当前正在收口验证；账号 CLI、HTTP/后台 UI、业务与生产接入仍按后续门禁实施。现有服务不会因源树迁移自动更新；重启/部署另按授权执行。

### S1-B 当前状态

非 Web 管理配置/私有池/身份-TLS-grants-角色守卫及独立测试实例的真实最小权限验证已完成，仍不是可登录后台或生产部署。[S1-B 复验指南](docs/开发与演示/阶段二S1-B管理启动守卫与验证指南.md)；[证据](docs/开发与演示/证据/stage-two-s1b-runtime-validation.json)。本轮已获授权的独立测试实例已正常关闭；原服务/授权保持，不放宽迁移 TLS。S1-C1 结果见下节。

### S1-C1 当前状态

纯 Java 凭据端口/登录编排与独立 Argon2id adapter 已实现，本地 234 单元/架构 + 42 MySQL IT、独立权限回归及两份依赖扫描通过。核心无技术库依赖，公开产物不含密码库；runtime 尚未接线 login/crypto，不是可登录后台。下一步 C2 JDBC/认证事件，再完成 C3 账号生命周期与 S1-D HTTP/UI。[C1 指南](docs/开发与演示/阶段二S1-C1凭据核心与KDF验证指南.md)。


### S1-C2 当前进行中（2026-10-24）

V3 认证事件、JDBC 凭据/事件短事务、版本化精确权限及非 Web 组合已有源码；独立管理范围真实 IT/运行探针已通过，测试证书按用户要求复用原私钥续发30天。完整公开回归因既定测试入口不可达仍 BLOCKED，不能标记整个 C2 已验收或可登录 Web 后台。前述 C1 未接线属于历史候选。见[C2 指南](docs/开发与演示/阶段二S1-C2凭据持久化与接线验证指南.md)。

总体保持 2A 开发联调（S0–S6）→G2A→S7/2B 真实接入→G2B/G3 授权开放。后台界面从 S1-D 登录开始，S2–S6 与各业务切片同步交付。完整步骤、文档/代码/测试/对接及当前 C2 顺序见 [P03](docs/计划/P03_阶段二分批交付与编码执行计划.md) §12；真实结果见[实施跟踪](docs/开发与演示/阶段二实施跟踪.md)。本次规划确认不授权生产操作或提交推送。
