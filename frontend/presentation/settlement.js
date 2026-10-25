"use strict";

// 阶段二不收款：这里是「支付是否可用」以及全部结算文案的唯一来源。
// 页面只引用这里导出的字符串，不得自己写；将来开放支付只改这个文件。
const PAYMENT_AVAILABLE = false;

const LABELS = Object.freeze({
  action: "支付（未开放）",
  settlement: "待结算（本阶段不收款）",
  blocked:
    "本阶段不收款，金额仅作记录。支付功能尚未开放；到店前请与工作室确认。",
});

const available = () => PAYMENT_AVAILABLE;

// 支付只跟「已生成的订单」绑定：没有订单就没有金额，也就没有支付入口。
const actionFor = (order) =>
  order
    ? { visible: true, enabled: PAYMENT_AVAILABLE, label: LABELS.action }
    : { visible: false, enabled: false, label: "" };

const settlementLabel = () => LABELS.settlement;

const blockedNotice = () => LABELS.blocked;

module.exports = {
  available,
  actionFor,
  settlementLabel,
  blockedNotice,
  LABELS,
};
