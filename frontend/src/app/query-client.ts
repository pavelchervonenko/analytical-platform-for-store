import { QueryClient } from "@tanstack/react-query";
import { isApiClientError } from "../api/client";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      gcTime: 10 * 60_000,
      refetchOnReconnect: true,
      refetchOnWindowFocus: true,
      retry: (failureCount, error) => {
        if (isApiClientError(error)) {
          if (error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        }
        return failureCount < 2;
      }
    },
    mutations: {
      retry: false
    }
  }
});
