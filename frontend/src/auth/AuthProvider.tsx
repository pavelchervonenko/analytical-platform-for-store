import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { apiClient, isApiClientError } from "../api/client";
import { currentUserSchema, type CurrentUser } from "../api/contracts";
import { getCurrentUser } from "../api/queries";

type AuthStatus = "booting" | "anonymous" | "authenticated" | "error";

interface Credentials {
  email: string;
  password: string;
}

interface PasswordChange {
  currentPassword: string;
  newPassword: string;
}

interface AuthContextValue {
  status: AuthStatus;
  user: CurrentUser | null;
  bootstrapError: string | null;
  login: (credentials: Credentials) => Promise<CurrentUser>;
  logout: () => Promise<void>;
  changePassword: (request: PasswordChange) => Promise<void>;
  retryBootstrap: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AuthStatus>("booting");
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [bootstrapError, setBootstrapError] = useState<string | null>(null);
  const [bootstrapAttempt, setBootstrapAttempt] = useState(0);

  const clearSession = useCallback(() => {
    apiClient.clearSecurityState();
    queryClient.clear();
    setUser(null);
    setBootstrapError(null);
    setStatus("anonymous");
  }, [queryClient]);

  useEffect(() => {
    apiClient.setUnauthorizedHandler(clearSession);
    return () => apiClient.setUnauthorizedHandler(null);
  }, [clearSession]);

  useEffect(() => {
    let active = true;
    getCurrentUser()
      .then((currentUser) => {
        if (!active) return;
        setUser(currentUser);
        setStatus("authenticated");
      })
      .catch((error: unknown) => {
        if (!active) return;
        if (isApiClientError(error) && error.status === 401) {
          clearSession();
          return;
        }
        setBootstrapError(isApiClientError(error) ? error.message : "Не удалось связаться с сервером.");
        setStatus("error");
      });

    return () => {
      active = false;
    };
  }, [bootstrapAttempt, clearSession]);

  const login = useCallback(async (credentials: Credentials) => {
    await apiClient.ensureCsrf();
    const currentUser = await apiClient.request("/api/auth/login", {
      method: "POST",
      body: credentials,
      schema: currentUserSchema,
      notifyOnUnauthorized: false
    });
    await apiClient.refreshCsrf();
    queryClient.clear();
    setUser(currentUser);
    setStatus("authenticated");
    return currentUser;
  }, [queryClient]);

  const logout = useCallback(async () => {
    try {
      await apiClient.request<void>("/api/auth/logout", { method: "POST" });
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const changePassword = useCallback(async (request: PasswordChange) => {
    await apiClient.request<void>("/api/auth/change-password", { method: "POST", body: request });
    clearSession();
  }, [clearSession]);

  const retryBootstrap = useCallback(() => {
    setStatus("booting");
    setBootstrapError(null);
    setBootstrapAttempt((attempt) => attempt + 1);
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    status,
    user,
    bootstrapError,
    login,
    logout,
    changePassword,
    retryBootstrap
  }), [bootstrapError, changePassword, login, logout, retryBootstrap, status, user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
