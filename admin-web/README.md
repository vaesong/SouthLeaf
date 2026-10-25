# SouthLeaf 独立管理后台（S1-D）

这是独立的 Vue 3 / TypeScript / Vite 管理端。S1-D 仅实现登录、当前会话身份、无权限提示和 404 页面；不添加示例业务仪表板或尚未实现功能的占位入口。

## 本地验证

```sh
npm ci
npm test
npm run build
npm run format:check
```

浏览器端只请求相对路径 `/api/admin/v1/auth/...`，CSRF 令牌与身份仅保存在内存中。页面不会读取 HttpOnly 会话 Cookie，也不会把凭据或令牌写入浏览器存储。UI 与 API 必须从同一个已配置的 HTTPS 来源提供。普通 Vite 开发服务器不能作为认证验收环境；本地开发须使用仓库认可且受信任的 HTTPS 方案，不能为了打通请求而关闭 Secure Cookie、Origin 校验或 CSRF。客户端没有嵌入主机名、秘密或环境专用 API 地址。
