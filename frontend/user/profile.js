"use strict";

function gaps(profile) {
  const nickname =
    profile && profile.displayName ? String(profile.displayName).trim() : "";
  const hasAvatar = Boolean(profile && profile.hasAvatar);
  return {
    nickname,
    hasAvatar,
    askName: !nickname,
    askAvatar: !hasAvatar,
    complete: Boolean(nickname) && hasAvatar,
  };
}

function sheetModel(input) {
  const savedName = input && input.nickname ? String(input.nickname).trim() : "";
  const hasAvatar = Boolean(input && input.hasAvatar);
  const askName = Boolean(input && input.askName);
  const askAvatar = Boolean(input && input.askAvatar);
  const draft = input && input.draftName != null ? String(input.draftName) : savedName;
  const chosenPath = input && input.chosenPath ? String(input.chosenPath) : "";
  const preview = chosenPath || (hasAvatar && input.avatarSrc ? String(input.avatarSrc) : "");
  const nameOk = !askName || Boolean(draft.trim());
  const avatarOk = !askAvatar || Boolean(chosenPath) || hasAvatar;
  let title = "头像和用户名";
  if (askName && askAvatar && !savedName && !hasAvatar) title = "补上头像和用户名";
  else if (askAvatar && !askName) title = "补上头像";
  else if (askName && !askAvatar) title = "补上用户名";
  return {
    draftName: draft,
    chosenPath,
    preview,
    title,
    ready: nameOk && avatarOk,
    avatarLabel: preview ? "已选微信头像" : "选择头像",
    nameEmpty: !draft.trim(),
  };
}

module.exports = { gaps, sheetModel };
