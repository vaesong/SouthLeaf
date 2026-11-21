"use strict";

const GROUPS = [
  "home",
  "about",
  "works",
  "workDetail",
  "services",
  "packageDetail",
  "booking",
  "mine",
  "requestDetail",
  "settlement",
  "hero",
];
// 界面自己的句子。没网时不画作品和套餐；这些字不是目录内容。
// 后台发布了同名非空句子时，用后台的替换默认。
const DEFAULTS = Object.freeze({
  "home.themesTitle": "拍摄主题",
  "home.storiesTitle": "近期摄影故事",
  "home.allWorks": "全部作品 →",
  "home.packagesTitle": "精选拍摄方案",
  "home.allServices": "全部服务 →",
  "home.signOff": "—— 安娜 & 方女士",
  "home.aboutLink": "认识工作室理念与主理人 →",
  "home.story":
    "用镜头收藏你生活里柔软的光，把每一份平凡的温暖，都拍成值得长久珍藏的故事。",
  "about.title": "关于枝南",
  "about.summary": "两位主理人，把日子拍成可以长久翻看的故事。",
  "about.signOff": "—— 枝南影像 · 记录你生活里柔软的光",
  "about.boundary": "联系动作不会自动创建预约、订单或支付事实。",
  "about.teamTitle": "主理人档案",
  "about.teamSubtitle": "安娜 & 方女士",
  "about.channelsTitle": "联系我们",
  "about.placeholderQr": "演示占位，不可扫码",
  "about.channelsEmpty": "公开联系方式暂未配置",
  "about.phoneLabel": "电话",
  "about.call": "拨打",
  "about.addressLabel": "地址",
  "about.copy": "复制",
  "about.hoursLabel": "营业时间",
  "about.qrHint": "二维码",
  "about.copyAccountFailed": "复制失败，请手动记录账号",
  "about.copyAddressFailed": "复制失败，请手动记录地址",
  "works.title": "作品集",
  "works.summary": "真实、克制地记录每一次相聚。",
  "works.all": "全部",
  "works.empty": "暂时没有可展示的作品",
  "works.sortNewest": "最新发布",
  "works.sortOldest": "最早发布",
  "works.loadMore": "加载更多",
  "works.layoutEditorial": "等高",
  "works.layoutGrid": "瀑布",
  "workDetail.dockTitle": "喜欢这组拍摄风格？",
  "workDetail.dockSubtitle": "安娜全程引导 · 含专属定制妆造",
  "workDetail.book": "预约同款",
  "workDetail.share": "分享",
  "workDetail.photoSuffix": "张影像",
  "workDetail.layoutAlbum": "画册",
  "workDetail.layoutOverview": "总览",
  "services.title": "拍摄服务",
  "services.summary": "从一个主题开始，找到适合你的记录方式。",
  "services.view": "查看方案 ›",
  "services.empty": "暂时没有可展示的服务",
  "services.loadMore": "加载更多方案",
  "packageDetail.book": "预约咨询",
  "packageDetail.share": "分享",
  "packageDetail.durationLabel": "参考时长（分钟）",
  "packageDetail.includesMetric": "包含内容",
  "packageDetail.noticesMetric": "拍摄须知",
  "packageDetail.audiencePrefix": "适合人群 ·",
  "packageDetail.includesTitle": "服务包含",
  "packageDetail.tipsTitle": "温馨提示",
  "packageDetail.noticesTitle": "拍摄须知",
  "packageDetail.priceLabel": "参考价",
  "packageDetail.priceInquiry": "价格请询",
  "packageDetail.priceFromMissing": "起价以确认资料为准",
  "packageDetail.priceFixedMissing": "价格以确认资料为准",
  "packageDetail.priceUnpublished": "价格待公布",
  "booking.title": "预约拍摄",
  "booking.summary": "选一个舒服的日子，我们再和你确认档期。",
  "booking.closedTitle": "预约通道正在轻轻升级",
  "booking.closedBody":
    "这段时间可以直接打电话，或复制微信，让主理人帮你锁定档期。确认之前，不会自动占用。",
  "booking.submitHint":
    "提交后，工作室会和你确认档期。确认之前，不会自动占用。",
  "booking.pendingHint": "工作室会人工复核时段，确认前不会占用档期。",
  "booking.confirmedHint": "请提前 15 分钟到店，安娜与方女士期待与你们见面。",
  "booking.rejectedHint": "这次申请未通过，可以重新选择时段再提交。",
  "booking.expiredHint": "申请已超过有效期，可以重新提交。",
  "booking.cancelledHint": "该预约已取消。",
  "booking.wechatLabel": "微信",
  "booking.copyWechat": "复制微信",
  "booking.copyWechatFailed": "复制失败，请手动记录微信号",
  "booking.phoneUnavailable": "电话暂未公开，可以先复制微信",
  "booking.wechatUnavailable": "微信号暂未公开，可以直接来电",
  "booking.phoneLabel": "电话",
  "booking.call": "拨打电话",
  "booking.backToWork": "返回作品",
  "booking.packagesTitle": "选择套餐",
  "booking.dateTitle": "选择日期",
  "booking.rangeStart": "开始",
  "booking.rangeEnd": "结束",
  "booking.slotsTitle": "选择开始时间",
  "booking.submit": "提交预约申请",
  "booking.mineEntry": "查看我的预约 ›",
  "booking.statusPending": "待确认",
  "booking.statusConfirmed": "已确认",
  "booking.statusRejected": "未接受",
  "booking.statusExpired": "已过期",
  "booking.statusCancelled": "已取消",
  "mine.title": "我的预约",
  "mine.summary": "把约好的日子，安安静静收在这里。",
  "mine.welcome": "你好，欢迎来到枝南",
  "mine.signedOutTitle": "还没有进入你的档案",
  "mine.signedOutHint": "去预约时，会用你的微信称呼和你见面",
  "mine.closedTitle": "预约服务暂未开放",
  "mine.closedBody":
    "先看看作品和拍摄方案吧。开放之后，约好的日子会安静地出现在这里。",
  "mine.empty": "还没有提交过预约申请",
  "mine.book": "去预约",
  "mine.upcomingTitle": "近期安排",
  "mine.newBooking": "＋ 新预约",
  "mine.holdPrefix": "申请保留到",
  "mine.menuBooking": "预约咨询",
  "mine.menuServices": "拍摄服务",
  "mine.menuAbout": "关于我们",
  "mine.scopeHint": "这里只显示你在这支小程序里提交的预约。",
  "requestDetail.title": "我的预约",
  "requestDetail.holdPrefix": "申请保留到",
  "requestDetail.requestedTitle": "申请时段",
  "requestDetail.confirmedTitle": "确认约定",
  "requestDetail.slotPrefix": "时段",
  "requestDetail.amountPrefix": "参考金额",
  "requestDetail.anotherSlot": "再看一个时段",
  "settlement.notice":
    "本阶段不收款，金额仅作记录。支付功能尚未开放；到店前请与工作室确认。",
});
const nest = (flat) => {
  const root = {};
  for (const group of GROUPS) root[group] = {};
  const merged = { ...DEFAULTS };
  if (flat && typeof flat === "object") {
    for (const [key, value] of Object.entries(flat)) {
      if (typeof value === "string" && value) merged[key] = value;
    }
  }
  for (const [key, value] of Object.entries(merged)) {
    const dot = key.indexOf(".");
    if (dot <= 0 || dot !== key.lastIndexOf(".")) continue;
    const group = key.slice(0, dot);
    const name = key.slice(dot + 1);
    if (!root[group] || !name) continue;
    root[group][name] = value;
  }
  return root;
};

const ready = (copy) => (copy && copy.home ? copy : nest(null));

const showing = (status) => status === "success" || status === "empty";

module.exports = { nest, ready, showing };
