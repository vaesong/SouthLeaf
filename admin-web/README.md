# SouthLeaf 工作室后台

Vue 3 / TypeScript / Vite 管理端，标题为「枝南影像 · 工作室后台」。它和 `/api/admin/v1` 由 `admin-runtime` 放在同一个 HTTPS 来源上。本目录没有 UI 组件库，页面组件是自写的。

S1-D 先交付了登录、当前会话、无权限提示和 404。其后的内容、图片、预约和通知页面已经接上现有管理 API。本阶段预约页写明不收款。通知页只接受测试投递（`TEST_SINK`），不接外部通知通道。

## 页面

路由在 `src/router.ts`。除登录、无权限和 404 外，其余页面要求已登录会话，侧栏再按权限显示入口。

| 路径 | 页面 |
|---|---|
| `/login` | 账号与密码 |
| `/` | 今日工作台 |
| `/home-layout` | 首页布置：轮播、文案、精选 |
| `/about` | 品牌、联系、成员、社交渠道 |
| `/businesses` | 业务与套餐 |
| `/works`、`/works/:workId` | 作品列表与编辑；最多 9 张图，封面、授权编号、预览、发布、撤回 |
| `/media` | 图片库：最多三层文件夹、按文件名搜索、多选、拖入文件夹、逐张上传、删除、放大 |
| `/bookings` | 预约：确认、拒绝、改期、取消 |
| `/slots` | 档期与营业规则 |
| `/notifications` | 失败通知与人工恢复 |
| `/releases` | 目录版本、发布与撤回 |
| `/catalog` | 转到 `/about` |
| `/outbox` | 转到 `/notifications` |

上传一次可选多张 JPEG 或 PNG，体积上限 30MB。每张使用各自的幂等键，按顺序上传；某一张失败时停住后续文件，已经成功的保留。选择图片时可以点空位打开选择器，并按文件名搜索。

## 会话

浏览器只请求相对路径 `/api/admin/v1/...`。登录与会话见 `src/auth/api.ts`：`GET/POST /api/admin/v1/auth/{csrf,session,login,logout}`。会话 Cookie 由浏览器持有（`credentials: "same-origin"`）。CSRF 令牌放在内存里，随 `X-CSRF-TOKEN` 发送。登录和退出会清掉内存中的令牌。页面不把凭据或令牌写入 `localStorage`。

写请求另带 `Idempotency-Key`。目录和作品更新使用 `If-Match`。客户端没有嵌入主机名、秘密或环境专用 API 地址。

普通 Vite 开发服务器不能作为认证验收环境。本地开发须使用仓库认可且受信任的 HTTPS 方案，不能为了打通请求而关闭 Secure Cookie、Origin 校验或 CSRF。`npm run dev:mock` 只提供进程内模拟接口。

## 本地命令

Node `>=20.19`。

```sh
npm ci
npm test
npm run build
npm run format:check
```

`npm run build` 会先做 `vue-tsc --noEmit`，再把静态资源输出到 `dist`。打进管理运行时 jar 的步骤在 `backend/scripts/build-admin-runtime.py`。
