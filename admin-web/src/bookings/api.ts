export type ReservationStatus =
  | "PENDING_CONFIRMATION"
  | "CONFIRMED"
  | "REJECTED"
  | "EXPIRED"
  | "CANCELLED";

export interface ReservationSummary {
  id: string;
  status: ReservationStatus;
  source: string;
  expiresAt: string;
  createdAt: string;
  version: number;
  slotId: string | null;
}

export interface Assignment {
  slotId: string;
  previousSlotId: string | null;
  assignedAt: string;
  reasonCode: string;
  revision: number;
}

export interface ReservationDetail {
  reservation: ReservationSummary;
  orderId: string | null;
  assignments: Assignment[];
}

export interface OpenSlot {
  id: string;
  startAt: string;
  endAt: string;
  businessZone: string;
  displayOffset: string;
  status: string;
  capacity: number;
  ruleVersion: string;
  revision: number;
}

export interface OrderDetail {
  id: string;
  reservationId: string;
  lifecycle: string;
  fulfilment: string;
  currentRevision: number;
  createdAt: string;
  reservationStatus: string | null;
  snapshot: {
    packageVersionRef: string;
    packageDisplayName: string;
    currencyCode: string;
    amountMinorUnits: number;
    priceVersion: string;
    slotId: string;
    startAt: string;
    endAt: string;
    businessZone: string;
    displayOffset: string;
    scheduleRevision: number;
    source: string;
    capturedAt: string;
  };
  revisions: Array<{
    revision: number;
    actorRef: string;
    changedFields: string;
    reasonCode: string;
    createdAt: string;
  }>;
}

export interface BookingAction {
  status: "CANCELLED" | "CONFIRMED" | "REJECTED";
  commandId: string;
  reservationId: string;
  orderId: string | null;
  slotId: string | null;
  orderRevision: number;
  replayed: boolean;
}

interface Envelope<T> {
  code: string;
  requestId: string;
  data: T;
}

interface ErrorEnvelope {
  code: string;
  requestId: string;
  message?: string;
}

const BASE = "/api/admin/v1/bookings";
const CSRF_PATH = "/api/admin/v1/auth/csrf";
const CSRF_HEADER = "X-CSRF-TOKEN";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class BookingApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
    readonly outcomeUnknown = false,
  ) {
    super(message);
    this.name = "BookingApiError";
  }
}

export function newBookingIdempotencyKey(random: Crypto = globalThis.crypto): string {
  const bytes = new Uint8Array(16);
  random.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

/** Same-origin booking transport; writes use CSRF, conditional revisions and durable idempotency. */
export class AdminBookingApi {
  private csrfToken: string | undefined;

  constructor(private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis)) {}

  async pending(): Promise<ReservationSummary[]> {
    const envelope = await this.read<ReservationSummary[]>(
      await this.send(`${BASE}/reservations/pending`, { method: "GET" }),
    );
    if (
      envelope.code !== "OK" ||
      !Array.isArray(envelope.data) ||
      !envelope.data.every(validReservation)
    )
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async reservation(id: string): Promise<{ data: ReservationDetail; etag: string }> {
    const identifier = requireId(id);
    const response = await this.send(`${BASE}/reservations/${encodeURIComponent(identifier)}`, {
      method: "GET",
    });
    const envelope = await this.read<ReservationDetail>(response);
    const etag = response.headers.get("ETag") ?? "";
    if (
      envelope.code !== "OK" ||
      !validReservationDetail(envelope.data) ||
      envelope.data.reservation.id !== identifier ||
      !/^"r(?:0|[1-9][0-9]{0,8})"$/.test(etag) ||
      etag !== `"r${envelope.data.reservation.version}"`
    )
      throw invalidResponse(envelope.requestId);
    return { data: envelope.data, etag };
  }

  async openSlots(from: string, to: string): Promise<OpenSlot[]> {
    const query = new URLSearchParams({ from, to });
    const envelope = await this.read<OpenSlot[]>(
      await this.send(`${BASE}/slots?${query.toString()}`, { method: "GET" }),
    );
    if (envelope.code !== "OK" || !Array.isArray(envelope.data) || !envelope.data.every(validSlot))
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async order(id: string): Promise<OrderDetail> {
    const identifier = requireId(id);
    const envelope = await this.read<OrderDetail>(
      await this.send(`${BASE}/orders/${encodeURIComponent(identifier)}`, { method: "GET" }),
    );
    if (envelope.code !== "OK" || !validOrder(envelope.data) || envelope.data.id !== identifier)
      throw invalidResponse(envelope.requestId);
    return envelope.data;
  }

  async cancel(id: string, etag: string, key: string): Promise<BookingAction> {
    const identifier = requireId(id);
    return this.write(
      `${BASE}/reservations/${encodeURIComponent(identifier)}/cancel`,
      etag,
      key,
      { reasonCode: "STAFF_REQUESTED" },
      "CANCELLED",
      identifier,
    );
  }

  async reject(id: string, etag: string, key: string): Promise<BookingAction> {
    const identifier = requireId(id);
    return this.write(
      `${BASE}/reservations/${encodeURIComponent(identifier)}/reject`,
      etag,
      key,
      { reasonCode: "STAFF_REJECTED" },
      "REJECTED",
      identifier,
    );
  }

  async reschedule(id: string, slotId: string, etag: string, key: string): Promise<BookingAction> {
    const identifier = requireId(id);
    const targetSlotId = requireId(slotId);
    return this.write(
      `${BASE}/reservations/${encodeURIComponent(identifier)}/reschedule`,
      etag,
      key,
      { reasonCode: "STAFF_RESCHEDULED", slotId: targetSlotId },
      "CONFIRMED",
      identifier,
    );
  }

  /**
   * Atomic confirm-and-create-order. Q02: nothing is auto-confirmed — the operator must name the
   * slot that will be occupied, so the human review decision is part of the command itself.
   */
  async confirm(id: string, slotId: string, etag: string, key: string): Promise<BookingAction> {
    const identifier = requireId(id);
    const targetSlotId = requireId(slotId);
    return this.write(
      `${BASE}/reservations/${encodeURIComponent(identifier)}/confirm`,
      etag,
      key,
      { slotId: targetSlotId },
      "CONFIRMED",
      identifier,
    );
  }

  private async write(
    path: string,
    etag: string,
    key: string,
    body: Record<string, string>,
    expectedStatus: BookingAction["status"],
    reservationId: string,
  ): Promise<BookingAction> {
    if (!/^"r(?:0|[1-9][0-9]{0,8})"$/.test(etag)) throw new Error("invalid reservation revision");
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(key)) throw new Error("invalid idempotency key");
    let retriedCsrf = false;
    for (;;) {
      const token = await this.csrf(retriedCsrf);
      let response: Response;
      try {
        response = await this.send(path, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            [CSRF_HEADER]: token,
            "If-Match": etag,
            "Idempotency-Key": key,
          },
          body: JSON.stringify(body),
        });
      } catch (error) {
        if (
          error instanceof BookingApiError &&
          error.status === 403 &&
          error.code === "CSRF_REJECTED" &&
          !retriedCsrf
        ) {
          this.csrfToken = undefined;
          retriedCsrf = true;
          continue;
        }
        if (error instanceof BookingApiError) {
          if (error.status === 0 || error.status >= 500) {
            throw new BookingApiError(
              error.status,
              error.code,
              error.message,
              error.requestId,
              true,
            );
          }
          throw error;
        }
        throw new BookingApiError(
          0,
          "NETWORK_OUTCOME_UNKNOWN",
          "The write outcome is unknown.",
          undefined,
          true,
        );
      }
      try {
        const envelope = await this.read<BookingAction>(response);
        const result = envelope.data;
        if (
          envelope.code !== "OK" ||
          !validAction(result) ||
          result.status !== expectedStatus ||
          result.reservationId !== reservationId
        )
          throw invalidResponse(envelope.requestId);
        return result;
      } catch (error) {
        if (error instanceof BookingApiError) {
          if (error.status === 403 && error.code === "CSRF_REJECTED" && !retriedCsrf) {
            this.csrfToken = undefined;
            retriedCsrf = true;
            continue;
          }
          if (error.status === 502 || error.status === 0 || error.status >= 500) {
            throw new BookingApiError(
              error.status,
              error.code,
              error.message,
              error.requestId,
              true,
            );
          }
          throw error;
        }
        throw new BookingApiError(
          0,
          "NETWORK_OUTCOME_UNKNOWN",
          "The write outcome is unknown.",
          undefined,
          true,
        );
      }
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
      envelope.data.csrfToken.length < 1 ||
      envelope.data.csrfToken.length > 4096
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
      throw new BookingApiError(
        0,
        "NETWORK_OUTCOME_UNKNOWN",
        "The request could not be confirmed.",
        undefined,
        init.method === "POST",
      );
    }
    if (!response.ok) {
      const body = await safeJson<ErrorEnvelope>(response);
      throw new BookingApiError(
        response.status,
        typeof body?.code === "string" ? body.code : "REQUEST_FAILED",
        safeErrorMessage(response.status),
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

async function safeJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

function invalidResponse(requestId?: string) {
  return new BookingApiError(502, "INVALID_BOOKING_RESPONSE", "預約服務回應無效。", requestId);
}

function safeErrorMessage(status: number) {
  if (status === 401) return "登入工作階段已失效，請重新登入。";
  if (status === 403) return "目前工作階段沒有執行此操作的伺服器能力。";
  if (status === 404) return "找不到此預約或訂單，或目前無權查看。";
  if (status === 409) return "預約資料已變更或時段不可用；請重新載入資料後再操作。";
  if (status === 400 || status === 413 || status === 415 || status === 428)
    return "預約請求格式或版本不符合要求；請重新載入資料。";
  if (status >= 500) return "預約服務暫時無法使用。";
  return "預約請求無法完成。";
}

function requireId(value: string) {
  if (!UUID.test(value)) throw new Error("invalid booking identifier");
  return value.toLowerCase();
}

function validReservation(value: unknown): value is ReservationSummary {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.id === "string" &&
    UUID.test(item.id) &&
    ["PENDING_CONFIRMATION", "CONFIRMED", "REJECTED", "EXPIRED", "CANCELLED"].includes(
      String(item.status),
    ) &&
    typeof item.source === "string" &&
    typeof item.expiresAt === "string" &&
    typeof item.createdAt === "string" &&
    Number.isInteger(item.version) &&
    (item.slotId === null || (typeof item.slotId === "string" && UUID.test(item.slotId)))
  );
}

function validReservationDetail(value: unknown): value is ReservationDetail {
  if (!value || typeof value !== "object") return false;
  const detail = value as Record<string, unknown>;
  return (
    validReservation(detail.reservation) &&
    (detail.orderId === null ||
      (typeof detail.orderId === "string" && UUID.test(detail.orderId))) &&
    Array.isArray(detail.assignments) &&
    detail.assignments.every((assignment) => {
      if (!assignment || typeof assignment !== "object") return false;
      const item = assignment as Record<string, unknown>;
      return (
        typeof item.slotId === "string" &&
        UUID.test(item.slotId) &&
        (item.previousSlotId === null ||
          (typeof item.previousSlotId === "string" && UUID.test(item.previousSlotId))) &&
        typeof item.assignedAt === "string" &&
        typeof item.reasonCode === "string" &&
        Number.isInteger(item.revision)
      );
    })
  );
}

function validSlot(value: unknown): value is OpenSlot {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.id === "string" &&
    UUID.test(item.id) &&
    typeof item.startAt === "string" &&
    typeof item.endAt === "string" &&
    typeof item.businessZone === "string" &&
    typeof item.displayOffset === "string" &&
    typeof item.status === "string" &&
    Number.isInteger(item.capacity) &&
    typeof item.ruleVersion === "string" &&
    Number.isInteger(item.revision)
  );
}

function validOrder(value: unknown): value is OrderDetail {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  const snapshot = item.snapshot as Record<string, unknown> | null;
  return (
    typeof item.id === "string" &&
    UUID.test(item.id) &&
    typeof item.reservationId === "string" &&
    UUID.test(item.reservationId) &&
    typeof item.lifecycle === "string" &&
    typeof item.fulfilment === "string" &&
    Number.isInteger(item.currentRevision) &&
    typeof item.createdAt === "string" &&
    (item.reservationStatus === null || typeof item.reservationStatus === "string") &&
    !!snapshot &&
    typeof snapshot.packageVersionRef === "string" &&
    typeof snapshot.packageDisplayName === "string" &&
    typeof snapshot.currencyCode === "string" &&
    typeof snapshot.amountMinorUnits === "number" &&
    Number.isSafeInteger(snapshot.amountMinorUnits) &&
    typeof snapshot.priceVersion === "string" &&
    typeof snapshot.slotId === "string" &&
    typeof snapshot.startAt === "string" &&
    typeof snapshot.endAt === "string" &&
    typeof snapshot.businessZone === "string" &&
    typeof snapshot.displayOffset === "string" &&
    Number.isInteger(snapshot.scheduleRevision) &&
    typeof snapshot.source === "string" &&
    typeof snapshot.capturedAt === "string" &&
    Array.isArray(item.revisions) &&
    item.revisions.every((revision) => {
      if (!revision || typeof revision !== "object") return false;
      const entry = revision as Record<string, unknown>;
      return (
        Number.isInteger(entry.revision) &&
        typeof entry.actorRef === "string" &&
        typeof entry.changedFields === "string" &&
        typeof entry.reasonCode === "string" &&
        typeof entry.createdAt === "string"
      );
    })
  );
}

function validAction(value: unknown): value is BookingAction {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  // A rejection closes a request before any order exists, so it is the only status with no order.
  const orderIdOk =
    item.status === "REJECTED"
      ? item.orderId === null
      : typeof item.orderId === "string" && UUID.test(item.orderId);
  return (
    (item.status === "CANCELLED" || item.status === "CONFIRMED" || item.status === "REJECTED") &&
    typeof item.commandId === "string" &&
    UUID.test(item.commandId) &&
    typeof item.reservationId === "string" &&
    UUID.test(item.reservationId) &&
    orderIdOk &&
    (item.slotId === null || (typeof item.slotId === "string" && UUID.test(item.slotId))) &&
    Number.isInteger(item.orderRevision) &&
    typeof item.replayed === "boolean"
  );
}
