import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiTarget = env.DEV_API_TARGET || "http://127.0.0.1:8080";

  if (!/^https?:\/\//u.test(apiTarget)) {
    throw new Error("DEV_API_TARGET must be an http(s) URL");
  }

  return {
    plugins: [react()],
    test: {
      environment: "jsdom",
      setupFiles: "./src/test/setup.ts",
      css: true
    },
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: false,
          secure: true
        }
      }
    },
    preview: {
      host: "127.0.0.1",
      port: 4174,
      strictPort: true
    },
    build: {
      sourcemap: false,
      target: "es2022",
      cssCodeSplit: true,
      reportCompressedSize: true
    }
  };
});
