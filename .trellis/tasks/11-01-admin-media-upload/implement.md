# 实施步骤

不改 `V5`，不改公开库迁移，不保存用户上传的原文件，不加 WebP。管理库下一号迁移是 `V14`。

## 1. 后端上限与文件名

1. `ContentRules` 增加 `MAX_STORED_OBJECT_BYTES = 4 * 1024 * 1024`，把 `MAX_UPLOAD_BYTES` 改为 `30 * 1024 * 1024`。
2. `ImageIoProcessor` 的衍生图大小检查和 `FileMediaObjectWriter.read` 改用 `MAX_STORED_OBJECT_BYTES`。上传入口和 `AdminOriginFilter.MAX_UPLOAD_REQUEST_BYTES` 继续用上传上限加 64KB。
3. 新增 `V14__admin_media_upload_limits.sql`：放宽 `ck_admin_media_size` 到 31457280，增加可空 `source_file_name VARCHAR(120)`。不改变体表的 4MB 检查。
4. 上传时保存清洗后的文件名。`MediaAsset`、JDBC 插入和读取、`GET /media` 的 `q` 参数带上它。没有 `q` 时行为与现在的最近 50 张相同。
5. 管理端媒体 JSON 增加 `sourceFileName`，可为 null。公开 DTO 不增加字段。

验证：

```powershell
.\mvnw.cmd -pl admin-content,admin-runtime,admin-migration-cli test
```

确认：30MB 加 1 字节被拒绝；4MB 加 1 字节不再因为「上传太大」被拒绝；衍生图超过 4MB 仍被拒绝；对象读取仍拒绝大于 4MB 的落盘文件。

## 2. 后台选择

1. `MediaField`：无图时点击空位打开抽屉。
2. `MediaPicker` 和图片库文件框支持多选。调用方逐张上传，每张一个新的幂等键。失败时保留已成功项并写出失败文件名。
3. 两个界面按文件名搜索。不再用目录描述或作品描述过滤。
4. 作品加入张数仍停在 9，超出时说明。
5. 更新 `messages.ts` 和图片库说明里的 4MB 文案。

验证：

```powershell
npm test
```

在 `admin-web` 目录执行。覆盖空位点击、多文件只取全部、一张失败不丢已成功项、文件名过滤、作品 9 张上限。

## 3. 部署说明

`docs/开发与演示/阶段二2B部署方案.md` 里，`admin.seekway.xyz` 的 `client_max_body_size` 为 `32m`。公开接口保持 `12m`。这只改文档；线上后台 nginx 要另行重载。

## 4. 小程序前端温润轻奢 UI 落地 (v4)

1. 母版规范参考 `docs/设计笔记集/06_客户端/小程序/04_温润轻奢前端UI设计与视觉规范.md` 与交互设计稿 `docs/设计笔记集/小程序框架终审稿-v4-温润轻奢.html`。
2. 全局调色：燕麦暖底 `#F4EFEA`、瓷白圆角卡片 `#FFFFFF`、焦糖暖棕 `#8E5636`、墨炭黑 `#26201B`、柔灰 `#75685B`。更新 `frontend/app.wxss` 与 `frontend/app.json`。
3. 首页 (`pages/index/`): 杂志大作流（全宽沉浸封面 + 齐平双联对视），消除多余空白与外框分层；底部工作室寄语卡；不放催促类的状态提醒。
4. 作品列表 (`pages/works/`): 默认自适应瀑布流（两列错落贴合图片底边，消灭卡片下方留白），支持右上角切换等高画册。
5. 服务与详情 (`pages/package/`): 章节画册卡片 + 套餐详情三栏数据与规整清单，静默底部常驻咨询条。
6. 关于与我的 (`pages/about/`, `pages/mine/`): 双主理人肖像卡 + 官方联系方式；我的页面温情卡片，剔除冷淡技术词汇。
7. 预约与详情 (`pages/booking/`): 药丸勾选 + 横向日历 + 时段微卡。
8. 验证：在 `frontend` 执行 `npm run verify`，确保 79+ 单元测试与 docs 检查全通过，且页面渲染与交互与设计稿一致。

## 回滚点

- 第 1 步完成前没有数据库变更。
- `V14` 执行后不要删列。回滚应用即可恢复「超过 4MB 直接拒绝」。
- 第 2 步只影响后台页面，不改变已经发布的小程序内容。
