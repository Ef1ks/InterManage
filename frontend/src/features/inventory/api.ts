import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { MarkdownPrices, PriceMarkdown, ProductListPage, ProductPriceHistory } from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/products`
const productsKey = (storeId: string) => ['stores', storeId, 'products'] as const

/**
 * Kartoteka sklepu stronami po 100, z wyszukiwaniem po nazwie, marce, ITM8 i EAN-ie oraz filtrami
 * ({@code filters}: parametry zapytania z {@code filterParams}).
 */
export function useProductList(storeId: string, query: string, filters: string) {
  return useInfiniteQuery({
    queryKey: [...productsKey(storeId), 'list', query, filters],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams(filters)
      params.set('page', String(pageParam))
      if (query) params.set('query', query)
      return api<ProductListPage>(`${path(storeId)}?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length : undefined),
    placeholderData: keepPreviousData,
  })
}

/** Pierwsza strona artykułów pasujących do kodu, bez filtrów listy. */
export function findProducts(storeId: string, code: string) {
  return api<ProductListPage>(`${path(storeId)}?${new URLSearchParams({ query: code })}`)
}

/** Przeceny (lista promocji) i zmiany ceny sprzedaży artykułu. */
export function useProductPrices(storeId: string, itm8: string) {
  return useQuery({
    queryKey: [...productsKey(storeId), itm8, 'prices'],
    queryFn: () => api<ProductPriceHistory>(`${path(storeId)}/${itm8}/prices`),
  })
}

const markdownsPath = (storeId: string) => `/stores/${storeId}/price-markdowns`
const markdownsKey = (storeId: string) => ['stores', storeId, 'price-markdowns'] as const

/** Lista cenówek: przeceny z karty artykułu, od najnowszej. */
export function usePriceMarkdowns(storeId: string) {
  return useQuery({
    queryKey: markdownsKey(storeId),
    queryFn: () => api<PriceMarkdown[]>(markdownsPath(storeId)),
  })
}

/** Cena bez promocji i ostatnia dostawa artykułu do okna przeceny, liczone jak w ustawianiu promocji. */
export function useMarkdownPrices(storeId: string, itm8: string) {
  return useQuery({
    queryKey: [...markdownsKey(storeId), 'prices', itm8],
    queryFn: () => api<MarkdownPrices>(`${markdownsPath(storeId)}/prices?${new URLSearchParams({ itm8 })}`),
  })
}

export function useCreatePriceMarkdown(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (markdown: { itm8: string; newPrice: number; validFrom: string; validTo: string }) =>
      api<PriceMarkdown[]>(markdownsPath(storeId), { method: 'POST', json: markdown }),
    onSuccess: (list) => queryClient.setQueryData(markdownsKey(storeId), list),
    meta: { invalidates: false },
  })
}

/** Znacznik „w pliku” po zapisie na dysku, usunięcie (znacznik) i „Przywróć”; każda zmiana zwraca listę. */
export function usePriceMarkdownChange(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, ids }: { action: 'labels-generated' | 'delete' | 'restore'; ids: string[] }) =>
      api<PriceMarkdown[]>(`${markdownsPath(storeId)}/${action}`, { method: 'POST', json: { ids } }),
    onSuccess: (list) => queryClient.setQueryData(markdownsKey(storeId), list),
    meta: { invalidates: false },
  })
}

export const priceLabelsFilePath = (storeId: string) => `${markdownsPath(storeId)}/labels-file`
