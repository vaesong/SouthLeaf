# 实施步骤：后台前端按 v2 设计稿落地

每完成一步，在 `admin-web` 下运行 `npm run typecheck` 和 `npm test`，通过后再继续。不要提交 git。

1. **读资料**：`prd.md`、`design.md`、设计稿 HTML、`.trellis/spec/frontend/*`、现有 `src/views/*.vue`（尤其 `CatalogView.vue` 的发布/撤回与幂等键逻辑、`WorkEditView.vue` 的保存与发布逻辑）。在 `backend/admin-content` 里查清目录草稿的校验规则（枚举、长度、条数上限）。
2. **设计系统**：迁移 CSS 变量与通用类到 `style.css`；实现 `src/components/` 下的共享组件。
3. **外壳**：重写 `AdminShell.vue`（侧边栏分组、计数角标、顶栏面包屑、未发布提示、发布按钮、账号菜单 + 退出确认）。更新 `router.ts`（新路由 + 旧路由重定向）。
4. **目录草稿状态**：实现 `content/catalogStore.ts` 及其单元测试（加载、编辑合并、dirty 判断、保存时带正确 revision、冲突处理、发布幂等键复用）。
5. **内容页**：关于我们 → 业务与套餐 → 首页布置（含手机预览组件）。逐项对照 design.md 第 5 节的字段映射表打勾。
6. **作品与图片**：作品集网格、作品编辑抽屉（标题、介绍、所属业务、授权范围 `subjectScopeRef`、图片与描述、封面、保存/发布/撤下）、图片库（上传、状态、描述编辑写入 `mediaAltTexts`）。
7. **经营页**：预约处理、档期周视图、消息通知、发布记录（查看历史版本、恢复为草稿 = 读取该版本后作为当前草稿保存、撤回）。
8. **工作台**与**登录页**。
9. **清理**：删除不再使用的旧视图（`CatalogView.vue`、`OutboxView.vue` 等被替代的文件）及其样式；按新文案更新 `*.spec.ts`；同步 `e2e/*.mjs` 的选择器与文案。
10. **自检**：`npm run typecheck && npm test && npm run build && npm run format:check`；`rg` 检查繁体字残留和设计稿示例数据硬编码；在 1280px 和 1440px 下用 `npm run dev` 目测（无后端时可只检查登录页和布局）。
11. **交付说明**：写到 `.trellis/tasks/11-01-admin-studio-ui/delivery.md`：改动文件清单、每页使用的接口、降级/省略项、未能运行的检查及原因。
