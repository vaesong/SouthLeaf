import { describe, expect, it } from "vitest";
import { safeReturnPath } from "./navigation";

describe("safeReturnPath", () => {
  it("allows only supported in-app paths and rejects open-redirect forms", () => {
    expect(safeReturnPath("/forbidden")).toBe("/forbidden");
    expect(safeReturnPath("/")).toBe("/");
    expect(safeReturnPath("//attacker.example")).toBe("/");
    expect(safeReturnPath("https://attacker.example")).toBe("/");
    expect(safeReturnPath("/\\attacker.example")).toBe("/");
    expect(safeReturnPath("/%2f%2fattacker.example")).toBe("/");
    expect(safeReturnPath("/not-yet-built")).toBe("/");
  });
});
