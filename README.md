# SouthLeaf — 摄影工作室展示、预约与后台

单个摄影工作室的微信小程序和管理后台。顾客浏览品牌、作品、业务与套餐，并提交待确认预约。工作室在后台维护内容、图片和档期，确认后占档并生成订单。这一阶段不收款。在线支付、退款、履约完成和私密成片属于阶段三，尚未实现。

## 当前状态（2026-11-06）

阶段一公开展示演示已经交付。阶段二开发联调（2A，S0–S6）已有隔离验收，G2A 十一条成立，全仓库验证见实施跟踪 §26。阶段二真实接入（2B / S7）尚未开始：正式 AppID、品牌与价格、作品授权材料、真实档期、通知模板、域名证书和对象存储都还没接入。

`main` 与 `origin/main` 对齐到 `cc87086`。工程收口之后已提交的后台增量包括简体暖色工作室界面，首页轮播、预约规则与档期配置，图片库文件夹与多选，30MB 的 JPEG/PNG，以及作品多图、授权登记和发布。工作区里还有未提交的小程序「温润轻奢」界面改动，验收记录尚未补上。

事实与证据以 [阶段二实施跟踪](docs/开发与演示/阶段二实施跟踪.md) 为准。本文以前写的「管理运行时尚未开放」和「S1-C2 进行中」已过时。

## 技术与目录

- `frontend/`：顾客端，原生微信小程序。五个标签（首页、作品、服务、关于、我的），另有作品详情、套餐详情和预约页。运行时零 npm 依赖，Prettier 是唯一 npm 开发依赖。
- `admin-web/`：工作室后台，Vue 3 / TypeScript / Vite。页面由 `admin-runtime` 与管理 API 同源提供。
- `backend/`：Java 21 / Spring Boot 4.1.1 / Spring JDBC / MySQL。十个 Maven 模块；父 POM 只做聚合，不直接运行 Spring Boot。公开 API 与管理运行时是两个进程。迁移由显式 CLI 执行，不在应用启动时自动跑。公开库迁移到 V4，管理库迁移到 V15。
- `docs/`：需求、架构、计划、设计与验收证据。
- `tools/`：文档链接检查等工程工具。

## 本机验证（cmd.exe）

后端在 `backend`：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-DatabaseIntegration.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-PublicApi.ps1
```

公开 API 默认只读，监听 `127.0.0.1:8090`。管理端网页、内容和预约要对应的环境开关齐全才启动。全仓库门禁是 `scripts/Verify-StageTwoGate.ps1`，它使用一次性隔离实例。

前端在 `frontend`（公开 API 已启动）：

```bat
npm ci --ignore-scripts --registry=https://registry.npmjs.org
npm run verify
npm audit --registry=https://registry.npmjs.org
set SOUTHLEAF_API_ORIGIN=http://127.0.0.1:8090
npm run build:demo
npm run test:contract
```

微信开发者工具导入 `frontend/dist/build/mp-weixin`，不是源码目录或旧 `unpackage`。demo 使用明确游客 AppID，仅用于工具演示。API origin 必须显式配置，不能把手机自身 127.0.0.1 当作电脑。正式构建用 `npm run build:release`，读取 `frontend/config/release.json`。

后台在 `admin-web`：

```bat
npm ci
npm test
npm run build
npm run format:check
```

页面只请求相对路径 `/api/admin/v1/...`。会话是 HttpOnly Cookie，CSRF 令牌只留在内存，不写入浏览器存储。UI 与 API 必须从同一个已配置的 HTTPS 来源提供。普通 Vite 开发服务器不能作为认证验收环境。

## 交付入口

- [阶段二实施跟踪](docs/开发与演示/阶段二实施跟踪.md)
- [P03 分批交付与编码计划](docs/计划/P03_阶段二分批交付与编码执行计划.md)
- [阶段二 G2A 退出对照](docs/开发与演示/阶段二G2A退出对照.md)
- [阶段二 2B 对接清单](docs/开发与演示/阶段二2B对接清单.md)
- [阶段一收敛交付记录](docs/开发与演示/阶段一收敛交付记录.md)
- [运行、初始化与复验指南](docs/开发与演示/阶段一演示运行指南.md)
- [文档索引](docs/README.md)
- [公开 API](docs/接口/public-api.openapi.json)
- [管理 API](docs/接口/admin-api.openapi.json)

不自动提交 Git。生成物、安装依赖和本机配置不属于源码。只读运行账号不负责迁移。已应用的迁移不改写。阶段二 2A 验收不代表正式上线，也不代表支付或私密交付已经开始。
