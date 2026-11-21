import { describe, expect, it } from "vitest";
import {
  COPY_SCREENS,
  PRESENTATION_FIELDS,
  readPresentationLine,
  writePresentationLine,
} from "./catalogDraft";
import { PRESENTATION_DEFAULTS } from "./presentationDefaults";

describe("interface copy lines", () => {
  it("shows the built-in sentence until a draft value replaces or hides it", () => {
    expect(readPresentationLine(undefined, "about.teamSubtitle", PRESENTATION_DEFAULTS)).toBe(
      "安娜 & 方女士",
    );
    expect(readPresentationLine({}, "about.teamSubtitle", PRESENTATION_DEFAULTS)).toBe(
      "安娜 & 方女士",
    );
    expect(
      readPresentationLine(
        { "about.teamSubtitle": "安娜 & Judy" },
        "about.teamSubtitle",
        PRESENTATION_DEFAULTS,
      ),
    ).toBe("安娜 & Judy");
    expect(
      readPresentationLine(
        { "about.teamSubtitle": "" },
        "about.teamSubtitle",
        PRESENTATION_DEFAULTS,
      ),
    ).toBe("");
  });

  it("stores a change, drops a value that matches the built-in sentence, and keeps a blank", () => {
    const presentation: Record<string, string> = {};
    writePresentationLine(presentation, "about.teamSubtitle", "安娜 & Judy", PRESENTATION_DEFAULTS);
    expect(presentation["about.teamSubtitle"]).toBe("安娜 & Judy");
    writePresentationLine(
      presentation,
      "about.teamSubtitle",
      PRESENTATION_DEFAULTS["about.teamSubtitle"] ?? "",
      PRESENTATION_DEFAULTS,
    );
    expect(presentation).not.toHaveProperty("about.teamSubtitle");
    writePresentationLine(presentation, "about.teamSubtitle", "", PRESENTATION_DEFAULTS);
    expect(presentation["about.teamSubtitle"]).toBe("");
  });

  it("puts every built-in sentence on one screen card", () => {
    const placed = new Set<string>();
    for (const screen of COPY_SCREENS) {
      for (const field of PRESENTATION_FIELDS) {
        if (!Object.prototype.hasOwnProperty.call(PRESENTATION_DEFAULTS, field.key)) continue;
        if (screen.prefixes.some((prefix) => field.key.startsWith(prefix))) placed.add(field.key);
      }
    }
    expect([...placed].sort()).toEqual(Object.keys(PRESENTATION_DEFAULTS).sort());
  });
});
