import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { gaps, sheetModel } from "../user/profile.js";

test("登录之后仍可能缺用户信息", () => {
  assert.deepEqual(gaps(null), {
    nickname: "",
    hasAvatar: false,
    askName: true,
    askAvatar: true,
    complete: false,
  });
  assert.equal(gaps({ displayName: "  南北  ", hasAvatar: false }).complete, false);
  assert.equal(gaps({ displayName: "南北", hasAvatar: false }).askName, false);
  assert.equal(gaps({ displayName: "", hasAvatar: true }).askAvatar, false);
  assert.equal(gaps({ displayName: "南北", hasAvatar: true }).complete, true);
});

test("卡片在两项凑齐前不能保存，只缺一项时只问那一项", () => {
  const both = sheetModel({
    nickname: "",
    hasAvatar: false,
    askName: true,
    askAvatar: true,
    draftName: "",
    chosenPath: "",
  });
  assert.equal(both.title, "补上头像和用户名");
  assert.equal(both.ready, false);
  const namedOnly = sheetModel({
    nickname: "",
    hasAvatar: false,
    askName: true,
    askAvatar: true,
    draftName: "南北",
    chosenPath: "",
  });
  assert.equal(namedOnly.ready, false);
  const ready = sheetModel({
    nickname: "",
    hasAvatar: false,
    askName: true,
    askAvatar: true,
    draftName: "南北",
    chosenPath: "wxfile://a.jpg",
  });
  assert.equal(ready.ready, true);
  const avatarOnly = sheetModel({
    nickname: "南北",
    hasAvatar: false,
    askName: false,
    askAvatar: true,
    draftName: "南北",
    chosenPath: "",
  });
  assert.equal(avatarOnly.title, "补上头像");
  assert.equal(avatarOnly.ready, false);
  const nameOnly = sheetModel({
    nickname: "",
    hasAvatar: true,
    askName: true,
    askAvatar: false,
    avatarSrc: "avatar.jpg",
    draftName: "南北",
    chosenPath: "",
  });
  assert.equal(nameOnly.title, "补上用户名");
  assert.equal(nameOnly.ready, true);
});

test("卡片上不写操作说明，也没有以后再说", () => {
  const markup = fs.readFileSync(
    new URL("../components/profile-sheet/index.wxml", import.meta.url),
    "utf8",
  );
  assert.equal(markup.includes("以后再说"), false);
  assert.equal(markup.includes("头像从微信里选"), false);
  assert.equal(markup.includes("两项都有了才能保存"), false);
  assert.match(markup, /open-type="chooseAvatar"/);
  assert.match(markup, /type="nickname"/);
  assert.match(markup, /bindtap="onDismiss"/);
});
