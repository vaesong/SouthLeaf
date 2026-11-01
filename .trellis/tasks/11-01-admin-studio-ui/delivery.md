# 交付说明：后台前端按 v2 设计稿落地

## 检查

在 `admin-web` 下最终结果：

- `npm run typecheck`：通过（`vue-tsc --noEmit` 无输出）
- `npm test`：通过（12 个文件、49 个测试，含 `src/content/format.test.ts`）
- `npm run build`：通过（`vue-tsc` 与 `vite build`）
- `npm run format:check`：通过

未运行：带真实后端与数据库的浏览器端到端（`e2e/*.mjs`）。脚本已改成抽屉选图、标签、分段价格和页面内保存，但没有连数据库跑通。

本地模拟：`npm run dev:mock`，端口 5173。`dist` 中搜索 `MOCKFIX`、`陈予安`、`Wutong`、`element-plus` 均无匹配。正式回退名「枝南影像」会出现在构建结果里，不用它做夹具残留特征串。已在该地址看过今日工作台和关于我们（视口约 1440，右侧出现手机预览）。没有单独再缩到 1280 截一张。

`admin-web/src` 中没有硬编码「林小满」「138 0000 0000」。未加载目录时的回退品牌名是「枝南影像」，加载后以 `brand.name` 为准。

## 每页用到的接口

| 页面 | 接口 |
|---|---|
| 登录 | `auth`：会话、CSRF、登录。退出在外壳账号菜单里，先确认再 `logout` |
| 今日工作台 | `bookings.pending()`、每条再查 `reservation()`，有订单再查 `order()`，`openSlots`、`outbox.list()`、`catalogStore.load()` |
| 首页布置 / 关于我们 / 业务与套餐 | 同一份 `catalogStore`：`getCatalog`、`saveCatalog`（顶栏，带当前修订的 `If-Match`）、`previewCatalog`、`publishCatalog`、`withdrawCatalog`、`findCatalogCommand`。关于与业务页另调 `listMedia()`。首页另调 `listWorks()` |
| 作品集 | `listWorks`、`createWork`（同一意图复用 Idempotency-Key） |
| 编辑作品 | `getWork`、`listMedia`、`saveDraft`、`preview`、`publish`、`withdraw` |
| 图片库 | `listMedia`、`upload`。已引用图片的描述写入目录草稿的 `mediaAltTexts`，随各内容页的「保存草稿」一起提交 |
| 预约处理 | `pending`、`reservation`、`order`、`openSlots`、`confirm`、`reject`、`cancel`、`reschedule`（沿用原来的修订号与幂等键） |
| 档期安排 | `openSlots(周一,下周一)`，只读 |
| 消息通知 | `outbox.list`、`outbox.recover` |
| 发布记录 | `listCatalogVersions`、`getCatalogVersion`、载入编辑器或恢复为草稿后再 `saveCatalog`。下架在本页，走 `withdrawCatalog` |

旧路由 `/catalog` 转到 `/about`，`/outbox` 转到 `/notifications`。

## 字段映射

`CatalogDraftInput` 第 5 节字段都有编辑入口：

- 品牌 `name`、`englishName`、`tagline`、`description`、`logoMediaId`：关于我们
- `discoveryHeading`、`shareTitle`：只在首页布置
- 联系电话、地址、营业时间：关于我们
- 团队各字段：关于我们卡片 +「编辑成员」抽屉，可增删、上下移、显示开关
- 社交账号各字段：关于我们，按行排列；二维码状态显示为未上传 / 占位图 / 已上传
- 六个固定业务：业务与套餐，不能增删
- 套餐各字段：业务与套餐展开区；`priceType` 为固定价、起价、到店咨询、不公开，后两者不显示价格
- 精选业务、精选套餐（点选最多 3 个）、精选作品：首页布置
- `mediaAltTexts`：只在图片库编辑。发布确认弹窗列出还没有描述的图片，并可去图片库补充

## 降级或省略

- 轮播编辑器改为只读说明：取精选作品前 3 个，没有标题、按钮、跳转
- 不显示「开放新档期」和预约规则编辑。说明写成：「档期和预约规则暂时需要联系技术人员在服务器上设置。」
- 预约只有「待确认」。不显示客人手机号、留言、已确认 / 已取消分栏
- 通知只有失败列表和「等待发送 N 条」，没有已送达历史
- 账号菜单是「管理员」、本次登录到期时间和退出确认。没有修改密码、登录记录，也没有「预览小程序」
- 工作台不显示摄影师、客人姓名或编造的修改条数。未发布提示只有「有修改尚未保存」或「已保存，尚未发布」
- 侧边栏名称用目录草稿的 `brand.name`，没有加载时是「工作室后台」
- 档期是周一到周日、按开始时间分行的周视图。格子只写「可预约」。没有已占用或已保留的数据，所以不画这两种颜色
- 登录左侧是色块，不用外部样图

## 第二轮审查后的界面

- 时间、预约状态、来源、二维码、媒体状态、通知类型都经 `src/content/format.ts` 显示成中文。未知取值显示「其他」。时间按北京时间。
- 顶栏不再显示修订号。未发布提示仍是「有修改尚未保存」或「已保存，尚未发布」。
- 字数只在达到上限 80% 时出现。卡片按钮去掉了浏览器默认边框。
- 工作台问候按北京时间的早/下午/晚上，待处理显示拍摄时间、套餐名或「新的预约请求」，以及过期倒计时。
- 预约卡片按日期块、套餐、来源、编号尾号四位、倒计时和操作排列。查找框在页头。
- 作品从卡片打开 640px 抽屉；直接打开 `/works/:workId` 也会打开作品集并弹出抽屉。标题旁是状态标签。图片是四列缩略图，悬停可设封面或移除。可发布时显示「可以发布」，否则显示中文原因。保存、发布和撤下在抽屉底部。
- 肖像授权编号是文本框，规则与后端一致：可留空以便保存草稿，否则只能是字母、数字和 `.` `_` `:` `-`，最长 64 个字符。没有编号不能发布。
- 生产构建 `dist` 中搜索 `MOCKFIX`、`陈予安`、`Wutong`、`element-plus` 无匹配。「枝南影像」是正式回退名，会出现在构建结果里。

### 源码自查

在 `admin-web/src` 搜索 `T\d\d:\d\d|PENDING_|MINI_PROGRAM|接口|服务器返回|会话|修订`：

- `*.vue` 模板里没有这些词。`BookingsView.vue` 的脚本里仍用 `PENDING_CONFIRMATION` 判断要不要显示确认按钮，页面上显示的是「待确认」。
- `format.ts` 和它的测试用这些代码做映射，不渲染到页面。
- `api.ts`、各 `*.test.ts`、`*.spec.ts` 里有时间串和状态码，用于请求和断言。

## 仍未对齐的地方

- 档期只有「可预约」。已占用、已保留的颜色块没有数据。今天这一列的日期会用强调色标出。
- 预约仍只有待确认列表。已确认、已取消要靠页头搜索。没有客人姓名和手机号。
- 作品「有未发布修改」只在已发布且草稿版本和已发布版本不同时出现。
- 带真实后端的 `e2e/*.mjs` 这次没有跑。脚本里的可见文案已改成中文说法。
- 字数、手机预览在窄屏隐藏，需要在 1280 和 1440 下再看一眼；本轮没有新的浏览器截图。
