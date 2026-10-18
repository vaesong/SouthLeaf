# SouthLeaf — 阶段一演示工程

摄影工作室公开展示小程序：品牌、作品、业务、套餐、成员及联系渠道。阶段一只验收演示内容与工程能力，不交付后台或生产发布。真实内容、权属/客户授权材料、后台上传维护、正式 AppID/域名/TLS/生产凭据由阶段二交付。

## 技术与目录

- `frontend/`：唯一正式客户端，原生微信 `App / Page / Component`；六页面、四 tab，零 npm 运行依赖，Prettier 为唯一 npm 开发依赖。
- `backend/`：Java 21 / Spring Boot 4.1.1 / Spring JDBC / MySQL；领域、应用、端口、技术适配器与显式迁移工具分开。
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
