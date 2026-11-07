"use strict";

// 阶段二没有支付入口。顾客能读到的结算说明只来自已发布目录。
const PAYMENT_AVAILABLE = false;

const available = () => PAYMENT_AVAILABLE;

const actionFor = () => ({ visible: false, enabled: false, label: "" });

const settlementLabel = () => "";

module.exports = {
  available,
  actionFor,
  settlementLabel,
};
