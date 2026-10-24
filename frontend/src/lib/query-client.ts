import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/lib/api'
import { meQueryKey } from '@/features/auth/api'

/** 401 z dowolnego żądania = sesja wygasła lub konto zablokowane: czyścimy dane, RequireAuth przekieruje na logowanie. */
function handleUnauthorized(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] })
    queryClient.setQueryData(meQueryKey, null)
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthorized }),
  mutationCache: new MutationCache({ onError: handleUnauthorized }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2,
    },
  },
})
