import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { brandView } = require("../presentation/views.js");

test("a cover story uses the first line as the quiet line", () => {
  const view = brandView({
    name: "枝南影像",
    description: "第一句\n第二句\n第三句",
    coverMedia: { id: "cover" },
    team: [],
  });
  assert.equal(view.coverKicker, "第一句");
  assert.deepEqual(
    view.coverTitleLines.map((line) => line.text),
    ["第二句", "第三句"],
  );
});

test("one story line stays the large line", () => {
  const view = brandView({
    name: "枝南影像",
    description: "只有一句",
    team: [],
  });
  assert.equal(view.coverKicker, "");
  assert.deepEqual(
    view.coverTitleLines.map((line) => line.text),
    ["只有一句"],
  );
});
