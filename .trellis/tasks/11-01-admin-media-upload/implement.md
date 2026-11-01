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
3. 两个界面增加文件名搜索，并继续用目录描述过滤。作品抽屉同时认当前作品里的描述。
4. 作品加入张数仍停在 9，超出时说明。
5. 更新 `messages.ts` 和图片库说明里的 4MB 文案。

验证：

```powershell
npm test
```

在 `admin-web` 目录执行。覆盖空位点击、多文件只取全部、一张失败不丢已成功项、文件名过滤、作品 9 张上限。

## 3. 部署说明

`docs/开发与演示/阶段二2B部署方案.md` 里，`admin.seekway.xyz` 的 `client_max_body_size` 为 `32m`。公开接口保持 `12m`。这只改文档；线上后台 nginx 要另行重载。

## 回滚点

- 第 1 步完成前没有数据库变更。
- `V14` 执行后不要删列。回滚应用即可恢复「超过 4MB 直接拒绝」。
- 第 2 步只影响后台页面，不改变已经发布的小程序内容。
