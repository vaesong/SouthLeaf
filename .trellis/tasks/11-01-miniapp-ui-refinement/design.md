# 技术设计：小程序温润轻奢 UI 细化与体验打磨

## 1. 架构与边界

1. **底栏架构 (Custom TabBar)**:
   - 微信原生 `custom-tab-bar` 遵循组件规范 `custom-tab-bar/index.{js,json,wxml,wxss}`。
   - `app.json` 中保持 5 个批准的 Tab 路由与 10 个已批准的 PNG 图标路径不变，满足 `build-config.mjs` 中的 `approvedTabAssets(app)` 约束。
   - `build-native.mjs` 的 `files` 白名单中补充 `custom-tab-bar` 组件声明，确保 demo 与 release 构建均能完整打包该组件。
   - 5 个 Tab 页面（`index`, `works`, `package`, `about`, `mine`）在 `onShow()` 生命周期中通过 `this.getTabBar()?.setData({ selected: index })` 联动选中态。
   - 全局样式 `app.wxss` 为 5 个 Tab 页面设置 `padding-bottom: 160rpx;` 保证悬浮底栏不遮挡正文。

2. **首页板块布局重排**:
   - `pages/index/index.wxml` 顺序调整：
     `brand-strip` -> `hero-wrap` -> `pill-scroll` (拍摄主题横滑) -> `featuredWorks` (近期摄影故事) -> `packages` (精选拍摄方案) -> `studio-story-card`。
   - 修复签名 HTML 实体字符 `&amp;` -> `&`。

3. **作品列表自适应瀑布流**:
   - `works.js`:
     - 切换模式从 `["grid", "editorial", "list"]` 缩减为 `["grid", "editorial"]`。
     - 在 `present()` 中根据索引模 2 拆分为 `leftCol` 和 `rightCol`，在 WXML 中采用两列独立列容器渲染，使数量少（1~2张）时左右两列天然平衡，不再受 CSS `column-count` 的多列高度堆叠缺陷影响。
   - `works.wxml` / `works.wxss`:
     - 去除 `list` 样式与结构。
     - 修复排序三角形样式，使用 CSS border 等腰三角形实现 100% 垂直对齐。

4. **关于页面二维码交互与安全契约**:
   - 保持契约规则：只有 `CONFIRMED` 状态才调用 `wx.previewImage`。
   - 当 `qrCodeStatus === 'PLACEHOLDER'` 且用户点击查看二维码时，调用 `wx.showToast({ title: '当前为演示占位码，正式营业后开放扫码', icon: 'none' })`，既给用户清晰明确的反馈，又保证单元测试 `placeholder QR is never presented as a confirmed scannable account` 零破坏。

5. **我的页面异常状态简化**:
   - 当 `status === 'error'` 时，以温润卡片提示“预约服务暂未开放”，去除无效且误导用户的“重试”按钮。

6. **价格与分享卡片**:
   - `frontend/api/public-display.js` 中 `priceLabel` 统一补齐 `¥`。
   - 各核心页面 `onShareAppMessage` 注入 `imageUrl`。
