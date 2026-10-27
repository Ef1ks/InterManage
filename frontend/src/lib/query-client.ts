import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/lib/api'
import { meQueryKey } from '@/features/auth/api'

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /**
       * {@code false}: mutacja niczego nie zmienia (pobranie pliku, podgląd) albo sama podmienia swoje dane w cache
       * i nie chcemy po niej odświeżać wszystkiego (np. szybkie zmiany formularza garmażu).
       */
      invalidates?: boolean
    }
  }
}

/** 401 z dowolnego żądania = sesja wygasła lub konto zablokowane: czyścimy dane, RequireAuth przekieruje na logowanie. */
function handleUnauthorized(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] })
    queryClient.setQueryData(meQueryKey, null)
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthorized }),
  mutationCache: new MutationCache({
    onError: handleUnauthorized,
    /**
     * Po każdej udanej zmianie wszystkie dane są nieaktualne, a te na ekranie pobierają się od razu. Zmiana jednego
     * modułu często zmienia inne (przypięta faktura w dokumentach i w KSeF, zamówienie u obu sklepów, import
     * w raportach), więc nie polegamy na tym, że każdy hook wie, co jeszcze odświeżyć. Bez czekania na wynik,
     * żeby przyciski i toasty nie czekały na odświeżenie.
     */
    onSuccess: (_data, _variables, _context, mutation) => {
      if (mutation.meta?.invalidates === false) return
      void queryClient.invalidateQueries()
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2,
    },
  },
})
