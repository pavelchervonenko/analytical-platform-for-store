/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ENABLE_INSIGHTS_PREVIEW?: "true" | "false";
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare const __FRONTEND_BUILD_VERSION__: string;
