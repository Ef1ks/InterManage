import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, downloadFile } from '@/lib/api'
import type {
  FinishedProduct,
  PriceCalculation,
  PriceCalculationRequest,
  PriceCalculationSummary,
  PriceChange,
  PriceProduct,
  RefreshChoice,
} from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Kalkulacje są osobne dla każdego sklepu (składniki pochodzą z jego kartoteki), więc sklep jest częścią klucza. */
export const calculationsKey = (storeId: string) => ['stores', storeId, 'price-calculations'] as const
const calculationsPath = (storeId: string) => `/stores/${storeId}/price-calculations`

/** Wszystkie kalkulacje sklepu; jest ich najwyżej kilkaset, więc wyszukiwarka filtruje w przeglądarce. */
export function useCalculations(storeId: string) {
  return useQuery({
    queryKey: [...calculationsKey(storeId), 'list'],
    queryFn: () => api<PriceCalculationSummary[]>(calculationsPath(storeId)),
  })
}

export function useCalculation(storeId: string, id: string | undefined) {
  return useQuery({
    queryKey: [...calculationsKey(storeId), id],
    queryFn: () => api<PriceCalculation>(`${calculationsPath(storeId)}/${id}`),
    enabled: id !== undefined,
  })
}

/** Zapis z kreatora: bez {@code id} nowa kalkulacja, z {@code id} zmiana istniejącej. */
export function useSaveCalculation(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id?: string; request: PriceCalculationRequest }) =>
      api<PriceCalculation>(id ? `${calculationsPath(storeId)}/${id}` : calculationsPath(storeId), {
        method: id ? 'PUT' : 'POST',
        json: request,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData([...calculationsKey(storeId), saved.id], saved)
      queryClient.invalidateQueries({ queryKey: [...calculationsKey(storeId), 'list'] })
    },
  })
}

/** Usunięcie to znacznik; {@code restore} cofa je (toast "Przywróć"). */
export function useDeleteCalculation(storeId: string) {
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey: calculationsKey(storeId) })
  return {
    remove: useMutation({
      mutationFn: (id: string) => api(`${calculationsPath(storeId)}/${id}`, { method: 'DELETE' }),
      onSuccess: refresh,
    }),
    restore: useMutation({
      mutationFn: (id: string) => api<PriceCalculation>(`${calculationsPath(storeId)}/${id}/restore`, { method: 'POST' }),
      onSuccess: refresh,
    }),
  }
}

export const MIN_SEARCH_LENGTH = 3

/** Produkty kartoteki z ceną zakupu jednostki; szukamy na serwerze od 3 znaków (bez spacji). */
export function usePriceProductSearch(storeId: string, query: string) {
  const debounced = useDebouncedValue(query.trim(), 250)
  const longEnough = debounced.replace(/\s+/g, '').length >= MIN_SEARCH_LENGTH
  return {
    longEnough,
    ...useQuery({
      queryKey: [...calculationsKey(storeId), 'products', debounced],
      queryFn: () =>
        api<PriceProduct[]>(`${calculationsPath(storeId)}/products?query=${encodeURIComponent(debounced)}`),
      enabled: longEnough,
      staleTime: 60_000,
      placeholderData: (previous) => previous,
    }),
  }
}

/** Gotowe wyroby z działów garmażu (nomenklatury 24, 36, 46) do wyboru w kreatorze - kilkaset, filtruje przeglądarka. */
export function useFinishedProducts(storeId: string) {
  return useQuery({
    queryKey: [...calculationsKey(storeId), 'finished-products'],
    queryFn: () => api<FinishedProduct[]>(`${calculationsPath(storeId)}/finished-products`),
  })
}

/**
 * "Odśwież ceny" w dwóch krokach: {@code preview} pokazuje nowe wyliczone ceny bez zmian w bazie, {@code apply}
 * odświeża ceny składników z decyzjami dla kalkulacji, które o nie pytały. Bez {@code calculationId} - wszystkie.
 */
export function useRefreshPrices(storeId: string) {
  const queryClient = useQueryClient()
  return {
    preview: useMutation({
      mutationFn: (calculationId?: string) =>
        api<PriceChange[]>(
          `${calculationsPath(storeId)}/refresh-prices/preview${calculationId ? `?calculationId=${calculationId}` : ''}`,
          { method: 'POST' },
        ),
      // Podgląd niczego nie zapisuje.
      meta: { invalidates: false },
    }),
    apply: useMutation({
      mutationFn: (request: {
        calculationId: string | null
        decisions: { calculationId: string; choice: RefreshChoice }[]
      }) => api<PriceCalculationSummary[]>(`${calculationsPath(storeId)}/refresh-prices`, { method: 'POST', json: request }),
      onSuccess: () => queryClient.invalidateQueries({ queryKey: calculationsKey(storeId) }),
    }),
  }
}

/** Excel z cenami sprzedaży: EAN, nazwa, cena brutto, VAT. */
export function useExportCalculations(storeId: string) {
  return useMutation({
    mutationFn: () => downloadFile(`${calculationsPath(storeId)}/export/xlsx`, 'ceny-garmazu.xlsx'),
    meta: { invalidates: false },
  })
}
