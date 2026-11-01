import { describe, expect, it } from "vitest";
import { ContentApiError } from "./api";
import { uploadFailureText, uploadFilesSequentially } from "./uploadFiles";

describe("uploadFilesSequentially", () => {
  it("uploads every file with its own key and keeps successes when one fails", async () => {
    const stored: string[] = [];
    const keys: string[] = [];
    const files = [
      new File(["a"], "ok.jpg", { type: "image/jpeg" }),
      new File(["b"], "bad.jpg", { type: "image/jpeg" }),
      new File(["c"], "later.jpg", { type: "image/jpeg" }),
    ];

    const failure = await uploadFilesSequentially(files, async (file, key) => {
      keys.push(key);
      if (file.name === "bad.jpg") {
        throw new ContentApiError(413, "MEDIA_TOO_LARGE", "too big", "r", "MEDIA_TOO_LARGE");
      }
      stored.push(file.name);
    });

    expect(stored).toEqual(["ok.jpg"]);
    expect(failure?.file.name).toBe("bad.jpg");
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(2);
    expect(keys[0]).toMatch(/^[A-Za-z0-9_-]{16,64}$/);
    expect(uploadFailureText("bad.jpg", failure?.error)).toBe("bad.jpg：图片超过 30MB。");
  });

  it("does not start the next file until the current upload finishes", async () => {
    let started = 0;
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const files = [new File(["a"], "a.jpg"), new File(["b"], "b.jpg")];
    const running = uploadFilesSequentially(files, async (file) => {
      started += 1;
      if (file.name === "a.jpg") await gate;
    });

    await Promise.resolve();
    expect(started).toBe(1);
    release();
    await running;
    expect(started).toBe(2);
  });
});
