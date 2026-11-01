import { newBookingIdempotencyKey } from "./api";

export interface BoardSlot {
  id: string;
  startAt: string;
  endAt: string;
  availability: "OPEN" | "OCCUPIED" | "PENDING";
  revision: number;
}

export interface OpenedSlot {
  id: string;
  startAt: string;
  endAt: string;
  revision: number;
  replayed: boolean;
}

export interface WeekSlotResult {
  date: string;
  startTime: string;
  ok: boolean;
  reason: string | null;
  slot: OpenedSlot | null;
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

interface ErrorEnvelope {
  code: string;
  requestId?: string;
}

const BOARD = "/api/admin/v1/bookings/slots/board";
const OPEN = "/api/admin/v1/bookings/slots";
const WEEK = "/api/admin/v1/bookings/slots/weeks";
const CSRF_PATH = "/api/admin/v1/auth/csrf";
const CSRF_HEADER = "X-CSRF-TOKEN";
const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class BookingSlotsApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly outcomeUnknown = false,
  ) {
    super(message);
    this.name = "BookingSlotsApiError";
  }
}

export { newBookingIdempotencyKey };

export function slotFailureText(code: string | null): string {
  if (code === "RULES_NOT_CONFIGURED") return "请先设置预约规则";
  if (code === "SLOT_OCCUPIED") return "这个时段已经有预约，不能关闭";
  if (code === "SLOT_PENDING_REQUEST") return "这个时段还有待确认的申请，不能关闭";
  if (code === "SLOT_OVERLAP") return "和当天已有档期重叠";
  if (code === "SLOT_DAY_FULL") return "这一天的档期已经排满";
  if (code === "SLOT_OUTSIDE_WINDOW") return "不在可以开放的日期范围内";
  return "这个时段暂时不能开放";
}

/** Same-origin slot transport. Writes carry CSRF and one idempotency key. */
export class AdminBookingSlotsApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async board(from: string, to: string): Promise<BoardSlot[]> {
    const envelope = await this.read<BoardSlot[]>(
      await this.send(`${BOARD}?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, {
        method: "GET",
      }),
    );
    if (envelope.code !== "OK" || !Array.isArray(envelope.data) || !envelope.data.every(validBoard))
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async open(date: string, startTime: string, key: string): Promise<OpenedSlot> {
    return this.write<OpenedSlot>(OPEN, { date, startTime }, key, null);
  }

  async openWeek(dates: string[], startTimes: string[], key: string): Promise<WeekSlotResult[]> {
    const envelope = await this.writeEnvelope<WeekSlotResult[]>(
      WEEK,
      { dates, startTimes },
      key,
      null,
    );
    if (
      envelope.code !== "OK" ||
      !Array.isArray(envelope.data) ||
      !envelope.data.every(validWeekItem)
    )
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async close(slotId: string, revision: number, key: string): Promise<OpenedSlot> {
    if (!ID_PATTERN.test(slotId)) throw new Error("invalid slot");
    return this.write<OpenedSlot>(`${OPEN}/${slotId}/close`, {}, key, revision);
  }

  private async write<T>(path: string, body: unknown, key: string, revision: number | null) {
    const envelope = await this.writeEnvelope<T>(path, body, key, revision);
    if (envelope.code !== "OK" || !validOpened(envelope.data))
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  private async writeEnvelope<T>(
    path: string,
    body: unknown,
    key: string,
    revision: number | null,
  ): Promise<Envelope<T>> {
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(key)) throw new Error("invalid idempotency key");
    let retriedCsrf = false;
    for (;;) {
      const token = await this.csrf(retriedCsrf);
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        [CSRF_HEADER]: token,
        "Idempotency-Key": key,
      };
      if (revision !== null) headers["If-Match"] = `"r${revision}"`;
      let response: Response;
      try {
        response = await this.send(path, { method: "POST", headers, body: JSON.stringify(body) });
      } catch (error) {
        if (
          error instanceof BookingSlotsApiError &&
          error.status === 403 &&
          error.code === "CSRF_REJECTED" &&
          !retriedCsrf
        ) {
          this.csrfToken = undefined;
          retriedCsrf = true;
          continue;
        }
        throw unknownIfNeeded(error);
      }
      return this.read<T>(response);
    }
  }

  private async csrf(force = false): Promise<string> {
    if (force) this.csrfToken = undefined;
    if (this.csrfToken) return this.csrfToken;
    const envelope = await this.read<{ csrfToken: string; headerName: string }>(
      await this.send(CSRF_PATH, { method: "GET" }),
    );
    if (
      envelope.code !== "OK" ||
      envelope.data?.headerName !== CSRF_HEADER ||
      typeof envelope.data.csrfToken !== "string" ||
      envelope.data.csrfToken.length < 1
    )
      throw invalidResponse(envelope.requestId);
    this.csrfToken = envelope.data.csrfToken;
    return this.csrfToken;
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetcher(path, {
        ...init,
        mode: "same-origin",
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: { Accept: "application/json", ...init.headers },
      });
    } catch {
      throw new BookingSlotsApiError(
        0,
        "NETWORK_OUTCOME_UNKNOWN",
        "这次操作没能确认结果。",
        undefined,
        init.method !== "GET",
      );
    }
    if (!response.ok) {
      const body = await safeJson<ErrorEnvelope>(response);
      const code = typeof body?.code === "string" ? body.code : "REQUEST_FAILED";
      throw new BookingSlotsApiError(
        response.status,
        code,
        slotFailureText(code),
        body?.requestId,
        false,
      );
    }
    return response;
  }

  private async read<T>(response: Response): Promise<Envelope<T>> {
    const body = await safeJson<Envelope<T>>(response);
    if (
      !body ||
      typeof body.code !== "string" ||
      typeof body.requestId !== "string" ||
      !("data" in body)
    )
      throw invalidResponse(typeof body?.requestId === "string" ? body.requestId : undefined);
    return body;
  }
}

function validBoard(value: unknown): value is BoardSlot {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.id === "string" &&
    ID_PATTERN.test(item.id) &&
    typeof item.startAt === "string" &&
    typeof item.endAt === "string" &&
    (item.availability === "OPEN" ||
      item.availability === "OCCUPIED" ||
      item.availability === "PENDING") &&
    Number.isInteger(item.revision)
  );
}

function validOpened(value: unknown): value is OpenedSlot {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return validBoard({ ...item, availability: "OPEN" }) && typeof item.replayed === "boolean";
}

function validWeekItem(value: unknown): value is WeekSlotResult {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.date === "string" &&
    typeof item.startTime === "string" &&
    typeof item.ok === "boolean" &&
    (item.reason === null || typeof item.reason === "string") &&
    (item.slot === null || validOpened(item.slot))
  );
}

function unknownIfNeeded(error: unknown): never {
  if (error instanceof BookingSlotsApiError) {
    if (error.status === 0 || error.status >= 500) {
      throw new BookingSlotsApiError(
        error.status,
        error.code,
        error.message,
        error.requestId,
        true,
      );
    }
    throw error;
  }
  throw new BookingSlotsApiError(
    0,
    "NETWORK_OUTCOME_UNKNOWN",
    "这次操作没能确认结果。",
    undefined,
    true,
  );
}

async function safeJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

function invalidResponse(requestId?: string) {
  return new BookingSlotsApiError(502, "INVALID_BOOKING_RESPONSE", "档期暂时读不出来。", requestId);
}
