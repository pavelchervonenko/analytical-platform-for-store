import type { ZodType } from "zod";
import {
  apiErrorPayloadSchema,
  csrfConfigurationSchema,
  type ApiErrorPayload,
  type CsrfConfiguration
} from "./contracts";
import { apiErrorMessage } from "./errorMessages";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);
const DEFAULT_TIMEOUT_MS = 20_000;

export interface EtaggedResource<T> {
  value: T;
  etag: string;
}

export interface OptionalEtaggedResource<T> {
  value: T;
  etag: string | null;
}

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly path?: string;
  readonly correlationId?: string;
  readonly retryAfterSeconds?: number;

  constructor(
    message: string,
    options: {
      status: number;
      code: string;
      path?: string;
      correlationId?: string;
      retryAfterSeconds?: number;
      cause?: unknown;
    }
  ) {
    super(message, { cause: options.cause });
    this.name = "ApiClientError";
    this.status = options.status;
    this.code = options.code;
    this.path = options.path;
    this.correlationId = options.correlationId;
    this.retryAfterSeconds = options.retryAfterSeconds;
  }
}

interface ApiRequestOptions<T> extends Omit<RequestInit, "body" | "credentials" | "cache"> {
  body?: unknown;
  schema?: ZodType<T>;
  timeoutMs?: number;
  notifyOnUnauthorized?: boolean;
  skipCsrf?: boolean;
  idempotencyScope?: string;
  responseObserver?: (response: Response) => void;
}

interface PendingIdempotencyKey {
  fingerprint: string;
  key: string;
}

export function normalizeApiBaseUrl(
  configured: string | undefined,
  production: boolean
): string {
  const value = configured?.trim() ?? "";
  if (!production) return value.replace(/\/$/u, "");
  if (value === "" || value === "/") return "";

  const normalized = value.replace(/\/+$/u, "");
  const segments = normalized.slice(1).split("/");
  if (!/^\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*$/u.test(normalized)
      || segments.some((segment) => segment === "." || segment === "..")) {
    throw new Error(
      "Production API base must be an empty or normalized same-origin path prefix"
    );
  }
  return normalized;
}

function getApiBaseUrl(): string {
  return normalizeApiBaseUrl(
    import.meta.env.VITE_API_BASE_URL,
    import.meta.env.PROD
  );
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  const cookie = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));

  if (!cookie) {
    return null;
  }

  const value = cookie.slice(prefix.length);
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

async function readApiError(response: Response): Promise<ApiErrorPayload | null> {
  if (!response.headers.get("content-type")?.includes("application/json")) {
    return null;
  }

  try {
    const result = apiErrorPayloadSchema.safeParse(await response.json());
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

export class ApiClient {
  private csrfConfiguration: CsrfConfiguration | null = null;
  private readonly pendingIdempotencyKeys = new Map<string, PendingIdempotencyKey>();
  private csrfRequest: Promise<CsrfConfiguration> | null = null;
  private unauthorizedHandler: (() => void) | null = null;

  setUnauthorizedHandler(handler: (() => void) | null): void {
    this.unauthorizedHandler = handler;
  }

  clearSecurityState(): void {
    this.csrfConfiguration = null;
    this.csrfRequest = null;
    this.pendingIdempotencyKeys.clear();
  }

  async ensureCsrf(): Promise<CsrfConfiguration> {
    if (this.csrfConfiguration) return this.csrfConfiguration;
    if (this.csrfRequest) return this.csrfRequest;

    this.csrfRequest = this.request("/api/auth/csrf", {
      schema: csrfConfigurationSchema,
      skipCsrf: true,
      notifyOnUnauthorized: false
    }).then((configuration) => {
      this.csrfConfiguration = configuration;
      return configuration;
    }).finally(() => {
      this.csrfRequest = null;
    });

    return this.csrfRequest;
  }

  async refreshCsrf(): Promise<CsrfConfiguration> {
    this.clearSecurityState();
    return this.ensureCsrf();
  }

  async request<T>(path: string, options: ApiRequestOptions<T> = {}): Promise<T> {
    const {
      body,
      schema,
      timeoutMs = DEFAULT_TIMEOUT_MS,
      notifyOnUnauthorized = true,
      skipCsrf = false,
      idempotencyScope,
      responseObserver,
      ...requestInit
    } = options;
    const method = (requestInit.method ?? "GET").toUpperCase();
    const headers = new Headers(requestInit.headers);

    headers.set("Accept", "application/json");
    if (body !== undefined) {
      headers.set("Content-Type", "application/json");
    }

    let idempotencyKey: string | undefined;
    if (idempotencyScope) {
      if (!UNSAFE_METHODS.has(method)) {
        throw new Error("Idempotency scope is only valid for unsafe HTTP methods");
      }
      const fingerprint = JSON.stringify([method, path, body ?? null]);
      const pending = this.pendingIdempotencyKeys.get(idempotencyScope);
      if (pending?.fingerprint === fingerprint) {
        idempotencyKey = pending.key;
      } else {
        idempotencyKey = globalThis.crypto.randomUUID();
        this.pendingIdempotencyKeys.set(idempotencyScope, {
          fingerprint,
          key: idempotencyKey
        });
      }
      headers.set("Idempotency-Key", idempotencyKey);
    }

    if (UNSAFE_METHODS.has(method) && !skipCsrf) {
      const configuration = await this.ensureCsrf();
      const token = readCookie(configuration.cookieName);
      if (!token) {
        this.clearSecurityState();
        throw new ApiClientError("Не удалось подтвердить безопасность запроса. Обновите страницу.", {
          status: 0,
          code: "CSRF_TOKEN_MISSING"
        });
      }
      headers.set(configuration.headerName, token);
    }

    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    const externalSignal = requestInit.signal;
    const abortFromExternal = () => controller.abort();
    if (externalSignal?.aborted) {
      controller.abort();
    } else {
      externalSignal?.addEventListener("abort", abortFromExternal, { once: true });
    }

    let response: Response;
    try {
      response = await fetch(`${getApiBaseUrl()}${path}`, {
        ...requestInit,
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "include",
        cache: "no-store",
        signal: controller.signal
      });
    } catch (error) {
      const timedOut = controller.signal.aborted && !externalSignal?.aborted;
      throw new ApiClientError(
        timedOut ? "Сервер не ответил вовремя." : "Нет соединения с сервером.",
        { status: 0, code: timedOut ? "REQUEST_TIMEOUT" : "NETWORK_ERROR", cause: error }
      );
    } finally {
      window.clearTimeout(timeout);
      externalSignal?.removeEventListener("abort", abortFromExternal);
    }

    responseObserver?.(response);
    if (!response.ok) {
      if (idempotencyScope && response.status < 500) {
        this.pendingIdempotencyKeys.delete(idempotencyScope);
      }
      const payload = await readApiError(response);
      if (response.status === 401 && notifyOnUnauthorized) {
        this.unauthorizedHandler?.();
      }
      throw new ApiClientError(apiErrorMessage(payload?.code, response.status), {
        status: response.status,
        code: payload?.code || `HTTP_${response.status}`,
        path: payload?.path,
        correlationId: payload?.correlationId ?? response.headers.get("X-Correlation-ID") ?? undefined,
        retryAfterSeconds: parseRetryAfter(response.headers.get("Retry-After"))
      });
    }

    if (response.status === 204) {
      if (idempotencyScope) this.pendingIdempotencyKeys.delete(idempotencyScope);
      return undefined as T;
    }

    if (!response.headers.get("content-type")?.includes("application/json")) {
      throw new ApiClientError("Сервер вернул ответ неизвестного формата.", {
        status: response.status,
        code: "INVALID_RESPONSE_TYPE"
      });
    }

    let json: unknown;
    try {
      json = await response.json();
    } catch (error) {
      throw new ApiClientError("Не удалось прочитать ответ сервера.", {
        status: response.status,
        code: "INVALID_JSON",
        cause: error
      });
    }

    if (!schema) {
      if (idempotencyScope) this.pendingIdempotencyKeys.delete(idempotencyScope);
      return json as T;
    }
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new ApiClientError("Ответ сервера не соответствует ожидаемому контракту.", {
        status: response.status,
        code: "CONTRACT_MISMATCH",
        cause: parsed.error
      });
    }
    if (idempotencyScope) this.pendingIdempotencyKeys.delete(idempotencyScope);
    return parsed.data;
  }

  async requestEtagged<T>(
    path: string,
    options: ApiRequestOptions<T> = {}
  ): Promise<EtaggedResource<T>> {
    const resource = await this.requestWithOptionalEtag(path, options);
    if (!resource.etag) {
      throw new ApiClientError("Сервер не вернул обязательную версию ресурса.", {
        status: 200,
        code: "ETAG_MISSING"
      });
    }
    return { value: resource.value, etag: resource.etag };
  }

  async requestWithOptionalEtag<T>(
    path: string,
    options: ApiRequestOptions<T> = {}
  ): Promise<OptionalEtaggedResource<T>> {
    let etag: string | null = null;
    const value = await this.request(path, {
      ...options,
      responseObserver: (response) => {
        etag = response.headers.get("ETag");
        options.responseObserver?.(response);
      }
    });
    const observedEtag = etag as string | null;
    if (observedEtag && (
      observedEtag.startsWith("W/")
      || (!observedEtag.startsWith('"') && observedEtag !== "*")
    )) {
      throw new ApiClientError("Сервер вернул некорректную версию ресурса.", {
        status: 200,
        code: "ETAG_INVALID"
      });
    }
    return { value, etag: observedEtag };
  }
}

export const apiClient = new ApiClient();

export function isApiClientError(error: unknown): error is ApiClientError {
  return error instanceof ApiClientError;
}
