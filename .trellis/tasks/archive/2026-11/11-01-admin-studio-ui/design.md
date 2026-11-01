# 设计：后台前端按 v2 设计稿落地

模板：`docs/设计笔记集/后台界面设计稿-v2.html`（单文件 HTML，可直接用浏览器打开对照）。

## 1. 样式体系

- 把设计稿 `<style>` 中的 CSS 变量（`--paper`、`--ink`、`--accent` 等）和通用类（`.btn*`、`.icon-btn`、`.card*`、`.field`、`.input`、`.textarea`、`.seg`、`.switch`、`.chip*`、`.tag*`、`.upload`、`.note`、`.drawer*`、`.modal`、`.scrim`、`.tabs`、`.table`）迁入 `src/style.css` 作为全局设计系统。页面专属样式放各组件的 `<style scoped>`。
- 移除旧的暖色/深木色侧边栏样式，不保留两套并存的风格。
- Element Plus：页面中不再使用 `<el-*>` 组件渲染可见界面（风格冲突）。可以保留 `ElMessage` 做轻提示，但要用 CSS 覆盖成设计稿风格；也可以自己写一个轻量 toast。若最终没有任何引用，从 `main.ts` 和依赖中移除。
- 图标：把设计稿里的内联 SVG 路径整理成 `src/components/AppIcon.vue`（按 name 选择 path），不要引入新的图标库。

## 2. 共享组件（`src/components/`）

| 组件 | 用途 |
|---|---|
| `AppIcon` | 内联 SVG 图标 |
| `AppSwitch` | 开关，`v-model:boolean`，带 `aria-label` |
| `SegmentedControl` | 分段控件，`v-model` + options |
| `TagListEditor` | 标签增删（服务内容、擅长方向） |
| `ListEditor` | 可排序的单行文本列表（拍摄小贴士、预约须知），提供上移/下移按钮代替拖拽 |
| `MediaPicker` | 抽屉：从图片库选图，支持上传；返回 `mediaId`；用 `mediaContentPath(id,'thumbnail')` 显示 |
| `MediaThumb` | 根据 `mediaId` 显示缩略图，空值显示上传占位 |
| `SideDrawer` | 右侧抽屉（标题、正文、底部按钮区、Esc 关闭、遮罩关闭） |
| `ConfirmDialog` | 确认弹窗（图标、标题、说明、取消/确认） |
| `StatusChip` | 状态标签（live / draft / off / warn / accent） |
| `PhonePreview` | 手机外框 + 插槽 |
| `MiniHomePreview` / `MiniAboutPreview` | 根据目录草稿渲染小程序首页/关于页预览（纯展示，不发请求） |

组件只负责展示，不发网络请求、不做权限判断（`.trellis/spec/frontend/component-guidelines.md`）。

## 3. 目录草稿的共享状态

首页布置、关于我们、业务与套餐三个页面编辑的是**同一份** `CatalogDraftInput`（一次 `PUT /content/catalog`，带 `If-Match: "c<revision>"`）。

- 新建 `src/content/catalogStore.ts`（`reactive` + 工厂函数，与 `auth/store.ts` 风格一致）：持有 `revision`、`draft`、`savedSnapshot`（上次加载或保存后的 JSON）、`dirty`（计算属性：草稿与快照不一致）、`load()`、`save()`、`discard()`、`publish()`、`withdraw()`。
- `publish()` 与 `withdraw()` 复用现有 `CatalogView.vue` 里的幂等键与重试逻辑：同一次点击生成一个键，网络重试复用这个键；发布前先 `previewCatalog()`，`publishable=false` 时显示 `blockingReason`。
- 离开页面且 `dirty` 时，用 `ConfirmDialog` 提示“有未保存的修改”（路由守卫 `onBeforeRouteLeave`）。
- 409/412 冲突：提示“内容已在别处更新”，提供“重新加载”按钮，不自动覆盖。

### 顶栏“未发布”提示的判断

接口没有直接返回“线上版本的修订号”。规则：
- `dirty` 为真时显示“有修改尚未保存”。
- 本次会话中保存成功、但还没有发布时，显示“已保存，尚未发布”。
- 其余情况不显示该提示。**不显示修改数量**（接口无法提供）。
- 若实现者在后端找到了可靠的“当前已发布修订号”来源（例如 `/content/catalog/commands` 的返回），可以改用它，但要在交付说明里写明依据。

## 4. 路由与页面

| 路由 | 页面 | 数据来源 |
|---|---|---|
| `/login` | 登录（设计稿“登录页”） | `auth` |
| `/` | 今日工作台 | `bookings.pending()`、`bookings.openSlots(今天,明天)`、`outbox.list()`、`catalogStore` |
| `/home-layout` | 首页布置 | `catalogStore` |
| `/about` | 关于我们 | `catalogStore` |
| `/businesses` | 业务与套餐 | `catalogStore` |
| `/works`、`/works/:workId` | 作品集；作品编辑用抽屉或独立页均可，视觉按设计稿“编辑作品”抽屉 | 现有 works 接口 |
| `/media` | 图片库 | `listMedia()`、`upload()` |
| `/bookings` | 预约处理 | 现有 bookings 接口 |
| `/slots` | 档期安排（只读周视图） | `openSlots(from,to)` |
| `/notifications` | 消息通知 | `outbox.list()`、`recover()` |
| `/releases` | 发布记录 | `listCatalogVersions()`、`getCatalogVersion()`、`withdrawCatalog()` |

旧路由 `/catalog`、`/outbox` 重定向到新路由。设计稿侧边栏里的“登录页（设计）”只是演示入口，不要做进正式导航。

## 5. 字段 → 页面映射（必须全覆盖）

| `CatalogDraftInput` 字段 | 页面 / 区块 |
|---|---|
| `brand.name`、`englishName`、`tagline`、`description`、`logoMediaId` | 关于我们 · 品牌信息 |
| `brand.discoveryHeading`、`brand.shareTitle` | 首页布置 · 首页文案 |
| `contact.phone`、`address`、`workingHours` | 关于我们 · 联系方式 |
| `team[]`（name、role、bio、specialties、portraitMediaId、position、published） | 关于我们 · 团队卡片 + “编辑成员”抽屉；增删、上移下移、显示开关 |
| `channels[]`（platform、label、account、qrMediaId、qrStatus、position、published） | 关于我们 · 社交账号；`qrStatus` 按后端允许的取值用分段控件或下拉 |
| `businesses[]`（name、summary、coverMediaId、position、published） | 业务与套餐 · 左侧列表 + 业务卡片。业务固定 6 个，不提供增删 |
| `packages[]`（businessId、name、summary、suitableFor、durationMinutes、displayPrice、priceType、serviceItems、tips、notices、published、coverMediaId、position） | 业务与套餐 · 套餐折叠行 + 展开编辑区 |
| `featuredBusinessIds`（≤3） | 首页布置 · 精选业务（点选，显示序号） |
| `featuredPackageIds`（≤3） | 首页布置 · 精选套餐 |
| `featuredWorkIds` | 首页布置 · 精选作品（从已发布作品中选） |
| `mediaAltTexts` | 图片库 · 每张图片的描述编辑；作品编辑中的图片描述走作品自己的 `media[].altText` |

`priceType` 显示文案：`FIXED` 固定价、`FROM` 起价、`INQUIRY` 到店咨询、`UNDISCLOSED` 不公开；后两者禁用价格输入。本阶段不收款的提示保留。

各枚举、长度、条数限制以后端校验为准：实现前在 `backend/admin-content` 中查清（例如 `qrStatus` 取值、文本最大长度、精选数量上限），前端做相同限制并显示字数。

## 6. 降级规则（接口缺失时）

| 设计稿元素 | 处理 |
|---|---|
| 首页轮播编辑器 | 改为只读说明卡：“轮播图目前取自精选作品的前 3 个”，展示这 3 张，并提供“调整精选作品”入口。不渲染标题、按钮、跳转等编辑项 |
| 档期“开放新档期”、预约规则卡片 | 不显示按钮；用 `.note` 说明“档期与预约规则暂时只能在服务器上配置” |
| 预约“已确认 / 已取消”标签页 | 接口只有待确认列表，只做“待确认”；不显示其他标签页 |
| 预约客人手机号、留言 | 接口没有，不显示 |
| 通知“已送达”历史 | 接口只有失败列表与 `pendingCount`；页面展示失败列表（可重试）和“等待发送 N 条” |
| 账号菜单姓名、修改密码、登录记录 | 身份只有 `accountId` 与到期时间；菜单显示“管理员”、会话到期时间和“退出登录”，不放修改密码/登录记录 |
| “预览小程序”按钮 | 不做 |
| 工作台“今天的拍摄” | 只能从档期数据推出时间段与状态，不显示摄影师等接口没有的信息 |
| 侧边栏品牌名 | 用目录草稿里的 `brand.name`，未加载时显示“工作室后台” |

## 7. 不变项

- `auth/*`、`content/api.ts`、`bookings/api.ts`、`outbox/api.ts` 的请求、校验、错误分类保持不变；现有这些模块的单元测试必须继续通过。
- 登录、会话检查、`safeReturnPath` 逻辑保持不变，只换外观。
