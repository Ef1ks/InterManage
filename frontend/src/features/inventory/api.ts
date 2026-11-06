import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { ProductListPage, ProductPriceHistory } from '@/lib/types'

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
