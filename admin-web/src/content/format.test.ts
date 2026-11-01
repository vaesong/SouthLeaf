import { describe, expect, it } from "vitest";
import {
  businessNameLabel,
  countLabel,
  formatDate,
  formatDateTime,
  formatPackagePrice,
  formatRelative,
  greeting,
  mediaStatusLabel,
  notificationIntentLabel,
  qrStatusLabel,
  reservationSourceLabel,
  publishBlockLabel,
  reservationStatusLabel,
  tailNumber,
} from "./format";

const noonBeijing = new Date("2026-11-01T04:00:00Z");

describe("beijing display formatting", () => {
  it("formats a date and weekday clock in Beijing time", () => {
    expect(formatDate("2026-11-02T08:00:00Z")).toBe("11 月 2 日");
    expect(formatDateTime("2026-11-02T08:00:00Z")).toBe("11 月 2 日 周一 16:00");
  });

  it("describes expiry as today, tomorrow, or hours", () => {
    expect(formatRelative("2026-11-01T08:00:00Z", noonBeijing)).toBe("4 小时后过期");
    expect(formatRelative("2026-11-02T02:00:00Z", noonBeijing)).toBe("明天 10:00 过期");
  });

  it("greets by Beijing hour", () => {
    expect(greeting(new Date("2026-11-01T00:30:00Z"))).toBe("早上好");
    expect(greeting(new Date("2026-11-01T06:30:00Z"))).toBe("下午好");
    expect(greeting(new Date("2026-11-01T14:30:00Z"))).toBe("晚上好");
  });

  it("maps enums to Chinese and hides unknown codes", () => {
    expect(reservationStatusLabel("PENDING_CONFIRMATION")).toBe("待确认");
    expect(reservationStatusLabel("REJECTED")).toBe("已婉拒");
    expect(reservationSourceLabel("MINI_PROGRAM")).toBe("小程序");
    expect(reservationSourceLabel("STAFF_OFFLINE")).toBe("后台");
    expect(qrStatusLabel("UNCONFIGURED")).toBe("未上传");
    expect(mediaStatusLabel("READY")).toBe("处理完成");
    expect(notificationIntentLabel("RESERVATION_CONFIRMED")).toBe("预约已确认");
    expect(businessNameLabel("family")).toBe("亲子全家福");
    expect(reservationStatusLabel("SOMETHING_NEW")).toBe("其他");
  });

  it("formats prices and only shows a count near the limit", () => {
    expect(formatPackagePrice("FROM", "1280")).toBe("¥1,280 起");
    expect(formatPackagePrice("FIXED", "699")).toBe("¥699");
    expect(formatPackagePrice("INQUIRY", "")).toBe("到店咨询");
    expect(formatPackagePrice("UNDISCLOSED", "")).toBe("不公开");
    expect(countLabel(6, 1024)).toBe("");
    expect(countLabel(900, 1024)).toBe("900/1024");
    expect(tailNumber("3c9a6900-0000-4000-8000-0000000000aa")).toBe("尾号 00aa");
  });

  it("maps publish blocks to Chinese and hides unknown codes", () => {
    expect(publishBlockLabel("AUTHORIZATION_EXPIRED")).toBe("肖像授权已经过期。");
    expect(publishBlockLabel("AUTHORIZATION_NEW")).toBe("暂时无法发布，请检查授权和图片");
  });
});
