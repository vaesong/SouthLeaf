"use strict";

const settlement = require("./settlement.js");

const STATUS_KEYS = Object.freeze({
  PENDING_CONFIRMATION: "statusPending",
  CONFIRMED: "statusConfirmed",
  REJECTED: "statusRejected",
  EXPIRED: "statusExpired",
  CANCELLED: "statusCancelled",
});
const HINTS = Object.freeze({
  PENDING_CONFIRMATION: "pendingHint",
  CONFIRMED: "confirmedHint",
  REJECTED: "rejectedHint",
  EXPIRED: "expiredHint",
  CANCELLED: "cancelledHint",
});
const statusOf = (code, copy) => {
  const booking = copy && copy.booking;
  const labelKey = STATUS_KEYS[code];
  const hintKey = HINTS[code];
  const line = (key) =>
    key && booking && typeof booking[key] === "string" ? booking[key] : "";
  return { label: line(labelKey), hint: line(hintKey) };
};

const slotLabel = (slot) =>
  slot ? slot.localDate + " " + slot.localStart + "-" + slot.localEnd : "";

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
const requestCard = (request, copy) => {
  const status = statusOf(request.displayStatus, copy);
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
  statusOf,
  slotLabel,
  timeLabel,
  orderView,
  requestCard,
};
