"use strict";
function commit(page, present, patch) {
  if (page._unloaded) return;
  page.setData(present({ ...page.data, ...patch }));
}
module.exports = { commit };
