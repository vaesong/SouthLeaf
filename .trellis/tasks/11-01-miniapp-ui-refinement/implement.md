# 实施计划与步骤

## 步骤 1：底栏方案 A 悬浮毛玻璃水滴胶囊落地
1. 创建 `frontend/custom-tab-bar/index.{js,json,wxml,wxss}`，编写悬浮毛玻璃胶囊样式与切换逻辑。
2. 更新 `frontend/app.json`，在 `tabBar` 声明 `"custom": true`。
3. 更新 `frontend/scripts/build-native.mjs`，将 `custom-tab-bar` 纳入构建白名单与打包流。
4. 更新 `frontend/tests/native-build.test.mjs`，在 `sources` 中加入 `"custom-tab-bar"`。
5. 在 5 个 Tab 页面（`index`, `works`, `package`, `about`, `mine`）的 `onShow()` 中接入 `this.getTabBar()?.setData({ selected })`。
6. 在 `frontend/app.wxss` 增加底部 padding 防止悬浮胶囊遮挡。

## 步骤 2：首页大片故事前置与文本实体修复
1. 在 `frontend/pages/index/index.wxml` 中把 `featuredWorks` 移到 `packages` 前面。
2. 修复 `&amp;` 为 `&`。

## 步骤 3：作品页面精修（瀑布流双列平衡、去掉列表、对齐箭头）
1. `frontend/pages/works/works.js`：移除 `list`，仅在 `["grid", "editorial"]` 间切换，在 `present` 中输出 `leftCol` 与 `rightCol`。
2. `frontend/pages/works/works.wxml` 与 `works.wxss`：
   - 移除列表模式相关代码；
   - 瀑布流采用左右双列独立排版；
   - 修复排序按钮的小三角对齐。

## 步骤 4：价格符号、二维码反馈、我的页面空态与微信分享卡片
1. `frontend/api/public-display.js`：`priceLabel` 前缀补充 `¥` 符号。
2. `frontend/pages/about/about.wxml` 与 `about.js`：提供友好占位码点击提示。
3. `frontend/pages/mine/mine.wxml`：移除 `status === 'error'` 下的重试按钮。
4. 在各页面的 `onShareAppMessage` 补充封面图片 `imageUrl`。

## 步骤 5：验证与构建
1. 运行 `npm run verify`，确保 100% 通过（格式、单元测试、文档检查）。
2. 运行 `$env:SOUTHLEAF_API_ORIGIN="https://api.seekway.xyz"; npm run build:demo` 构建最新产物。

## 步骤 6：纯文字悬浮底栏与关于我们专访手记流重构
1. `custom-tab-bar/index.wxml` 与 `index.wxss`: 去除 `<image>` 图标渲染，改用纯文字悬浮胶囊（96rpx 高度、48rpx 圆角），文字大小 27rpx，激活项焦糖色高亮并附带 18rpx*4rpx 底部居中指示线。
2. `pages/about/about.wxml` 与 `about.wxss`:
   - 品牌寄语升级为「相馆初心」手记卡（左侧焦糖细引线，人文宋体左对齐）；
   - 主理人改为单列横向大卡（左图右文：左侧 200rpx 竖肖像，右侧姓名、角色、擅长标签、完整 bio 心声），彻底移除 `-webkit-line-clamp: 2`；
   - 擅长领域采用 `.specialties-row` 与 `.spec-chip` 标签类，严禁使用 `team-specialties` 以遵循边界测试防守契约；
   - 保留每张主理人卡片的 `team-row` 类以满足既有测试断言。
3. 验证并构建演示产物。

