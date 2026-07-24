import type { ZodType } from "zod";
import {
  apiErrorPayloadSchema,
  csrfConfigurationSchema,
  type ApiErrorPayload,
  type CsrfConfiguration
} from "./contracts";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);
const DEFAULT_TIMEOUT_MS = 20_000;

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly path?: string;
  readonly retryAfterSeconds?: number;

  constructor(
    message: string,
    options: {
      status: number;
      code: string;
      path?: string;
      retryAfterSeconds?: number;
      cause?: unknown;
    }
  ) {
    super(message, { cause: options.cause });
    this.name = "ApiClientError";
    this.status = options.status;
    this.code = options.code;
    this.path = options.path;
    this.retryAfterSeconds = options.retryAfterSeconds;
  }
}

interface ApiRequestOptions<T> extends Omit<RequestInit, "body" | "credentials" | "cache"> {
  body?: unknown;
  schema?: ZodType<T>;
  timeoutMs?: number;
  notifyOnUnauthorized?: boolean;
  skipCsrf?: boolean;
}

function getApiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
  if (import.meta.env.PROD && /^https?:\/\//u.test(configured)) {
    throw new Error("Production API base must be same-origin");
  }
  return configured.replace(/\/$/u, "");
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

function fallbackMessage(status: number): string {
  if (status === 401) return "Сессия завершена. Войдите снова.";
  if (status === 403) return "Недостаточно прав для этого действия.";
  if (status === 404) return "Запрошенные данные не найдены.";
  if (status === 409) return "Данные уже изменились. Обновите страницу и повторите действие.";
  if (status === 429) return "Слишком много запросов. Попробуйте позже.";
  if (status >= 500) return "Сервис временно недоступен. Попробуйте позже.";
  return "Не удалось выполнить запрос.";
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
  private csrfRequest: Promise<CsrfConfiguration> | null = null;
  private unauthorizedHandler: (() => void) | null = null;

  setUnauthorizedHandler(handler: (() => void) | null): void {
    this.unauthorizedHandler = handler;
  }

  clearSecurityState(): void {
    this.csrfConfiguration = null;
    this.csrfRequest = null;
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
      ...requestInit
    } = options;
    const method = (requestInit.method ?? "GET").toUpperCase();
    const headers = new Headers(requestInit.headers);

    headers.set("Accept", "application/json");
    if (body !== undefined) {
      headers.set("Content-Type", "application/json");
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

    if (!response.ok) {
      const payload = await readApiError(response);
      if (response.status === 401 && notifyOnUnauthorized) {
        this.unauthorizedHandler?.();
      }
      throw new ApiClientError(payload?.message || fallbackMessage(response.status), {
        status: response.status,
        code: payload?.code || `HTTP_${response.status}`,
        path: payload?.path,
        retryAfterSeconds: parseRetryAfter(response.headers.get("Retry-After"))
      });
    }

    if (response.status === 204) {
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

    if (!schema) return json as T;
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new ApiClientError("Ответ сервера не соответствует ожидаемому контракту.", {
        status: response.status,
        code: "CONTRACT_MISMATCH",
        cause: parsed.error
      });
    }
    return parsed.data;
  }
}

export const apiClient = new ApiClient();

export function isApiClientError(error: unknown): error is ApiClientError {
  return error instanceof ApiClientError;
}
