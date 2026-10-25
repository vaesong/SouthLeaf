"use strict";

const settlement = require("./settlement.js");

const STATUS = Object.freeze({
  PENDING_CONFIRMATION: {
    label: "待确认",
    hint: "工作室会人工复核时段，确认前不会占用档期。",
  },
  CONFIRMED: {
    label: "已确认",
    hint: "档期已确认，到店前请留意工作室的联系。",
  },
  REJECTED: {
    label: "未接受",
    hint: "这次申请未通过，可以重新选择时段再提交。",
  },
  EXPIRED: {
    label: "已过期",
    hint: "申请已超过有效期，可以重新提交。",
  },
  CANCELLED: {
    label: "已取消",
    hint: "该预约已取消。",
  },
});

const UNKNOWN = Object.freeze({ label: "状态未知", hint: "" });

const statusOf = (code) => STATUS[code] || UNKNOWN;

const slotLabel = (slot) =>
  slot
    ? slot.localDate + " " + slot.localStart + "-" + slot.localEnd
    : "未指定时段";

const timeLabel = (value) =>
  value ? String(value).slice(0, 16).replace("T", " ") : "";

const orderView = (order) =>
  order
    ? {
        packageDisplayName: order.packageDisplayName,
        slotLabel: order.slotLabel,
        amountLabel: order.amountLabel,
        settlementNotice: order.settlementNotice,
        settlementLabel: settlement.settlementLabel(),
      }
    : null;

// 一张卡片就是一条申请：列表页与详情页共用同一套标签与金额口径。
const requestCard = (request) => {
  const status = statusOf(request.displayStatus);
  return {
    reservationId: request.reservationId,
    slotLabel: slotLabel(request.requestedSlot),
    statusCode: request.displayStatus,
    statusLabel: status.label,
    statusHint: status.hint,
    hasOrder: Boolean(request.order),
    order: orderView(request.order),
    payAction: settlement.actionFor(request.order),
    expiresLabel: timeLabel(request.expiresAt),
  };
};

module.exports = {
  STATUS,
  statusOf,
  slotLabel,
  timeLabel,
  orderView,
  requestCard,
};
