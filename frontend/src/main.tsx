import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router";
import "@fontsource-variable/inter";
import { App } from "./app/App";
import { AppErrorBoundary } from "./app/AppErrorBoundary";
import { queryClient } from "./app/query-client";
import { AuthProvider } from "./auth/AuthProvider";
import "./stores/period.css";
import "./quality/styles.css";
import "./admin/styles.css";
import "./styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("Application root is missing");

createRoot(root).render(
  <StrictMode>
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AuthProvider>
      </QueryClientProvider>
    </AppErrorBoundary>
  </StrictMode>
);
