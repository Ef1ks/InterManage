import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { meQueryKey } from '@/features/auth/api'
import { api } from '@/lib/api'
import type {
  Me,
  NomenclatureMargin,
  NomenclatureMarginImport,
  PriceRoundingRule,
  StorePartners,
  SupplierSalePrice,
} from '@/lib/types'

/** Sklepy zaprzyjaźnione i zaproszenia wybranego sklepu (z obu stron) oraz sklepy, które można zaprosić. */
export const partnersKey = (storeId: string) => ['stores', storeId, 'partners'] as const
const partnersPath = (storeId: string) => `/stores/${storeId}/partners`

export function useStorePartners(storeId: string, enabled = true) {
  return useQuery({
    queryKey: partnersKey(storeId),
    queryFn: () => api<StorePartners>(partnersPath(storeId)),
    enabled,
  })
}

/** Każda zmiana zwraca całą listę, którą od razu podmieniamy. */
export function useStorePartnerActions(storeId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (partners: StorePartners) => queryClient.setQueryData(partnersKey(storeId), partners)
  return {
    invite: useMutation({
      mutationFn: (partnerStoreId: string) =>
        api<StorePartners>(partnersPath(storeId), { method: 'POST', json: { storeId: partnerStoreId } }),
      onSuccess,
    }),
    accept: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}/accept`, { method: 'POST' }),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}`, { method: 'DELETE' }),
      onSuccess,
    }),
    restore: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}/restore`, { method: 'POST' }),
      onSuccess,
    }),
  }
}

/** Kolejność kategorii menu bocznego sklepu; pusta lista = domyślna. Menu czyta ją z zalogowanego użytkownika. */
export function useSaveSidebarOrder(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (groups: string[]) =>
      api<{ groups: string[] }>(`/stores/${storeId}/sidebar-order`, { method: 'PUT', json: { groups } }),
    onSuccess: ({ groups }) =>
      queryClient.setQueryData<Me | null>(meQueryKey, (me) =>
        me ? { ...me, sidebarOrders: { ...me.sidebarOrders, [storeId]: groups } } : me,
      ),
    meta: { invalidates: false },
    scope: { id: `sidebar-order:${storeId}` },
  })
}

/** Marża wymagana na nomenklaturze: cała nomenklatura sklepu z marżą ustawioną i obowiązującą. */
export const nomenclatureMarginsKey = (storeId: string) => ['stores', storeId, 'nomenclature-margins'] as const
export const nomenclatureMarginsPath = (storeId: string) => `/stores/${storeId}/nomenclature-margins`

export function useNomenclatureMargins(storeId: string, enabled = true) {
  return useQuery({
    queryKey: nomenclatureMarginsKey(storeId),
    queryFn: () => api<NomenclatureMargin[]>(nomenclatureMarginsPath(storeId)),
    enabled,
  })
}

export function useSaveNomenclatureMargins(storeId: string) {
  return useMutation({
    mutationFn: (margins: { code: string; marginPercent: number | null }[]) =>
      api<NomenclatureMargin[]>(nomenclatureMarginsPath(storeId), { method: 'PUT', json: { margins } }),
  })
}

/** Uzupełniony plik do uzupełnienia: zastępuje wszystkie marże naraz. */
export function useImportNomenclatureMargins(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<NomenclatureMarginImport>(`${nomenclatureMarginsPath(storeId)}/import`, { method: 'POST', formData })
    },
  })
}

/** Przedziały zaokrąglania cen sugerowanych; ten sam klucz co we wprowadzaniu danych. */
export const priceRoundingKey = (storeId: string) => ['stores', storeId, 'price-rounding'] as const

export function usePriceRoundingRules(storeId: string, enabled = true) {
  return useQuery({
    queryKey: priceRoundingKey(storeId),
    queryFn: () => api<PriceRoundingRule[]>(`/stores/${storeId}/price-rounding`),
    enabled,
    // Jak marża wymagana: zmiana w drugiej karcie od razu przelicza ceny sugerowane we wprowadzaniu danych.
    refetchOnWindowFocus: 'always',
  })
}

/** Zapis zastępuje całą listę przedziałów. */
export function useSavePriceRounding(storeId: string) {
  return useMutation({
    mutationFn: (rules: { priceFrom: number; priceTo: number | null; ending: number }[]) =>
      api<PriceRoundingRule[]>(`/stores/${storeId}/price-rounding`, { method: 'PUT', json: { rules } }),
  })
}

/** Stałe ceny sprzedaży dostawców; ten sam klucz co we wprowadzaniu danych. */
export const supplierSalePricesKey = (storeId: string) => ['stores', storeId, 'supplier-sale-prices'] as const
const supplierSalePricesPath = (storeId: string) => `/stores/${storeId}/supplier-sale-prices`

export function useSupplierSalePrices(storeId: string, enabled = true) {
  return useQuery({
    queryKey: supplierSalePricesKey(storeId),
    queryFn: () => api<SupplierSalePrice[]>(supplierSalePricesPath(storeId)),
    enabled,
    // Jak zaokrąglanie: zmiana w drugiej karcie od razu zmienia ceny sugerowane we wprowadzaniu danych.
    refetchOnWindowFocus: 'always',
  })
}

/** Każda zmiana zwraca całą listę, którą od razu podmieniamy. */
export function useSupplierSalePriceActions(storeId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (prices: SupplierSalePrice[]) => queryClient.setQueryData(supplierSalePricesKey(storeId), prices)
  return {
    save: useMutation({
      mutationFn: ({ supplierId, salePrice }: { supplierId: string; salePrice: number }) =>
        api<SupplierSalePrice[]>(`${supplierSalePricesPath(storeId)}/${supplierId}`, {
          method: 'PUT',
          json: { salePrice },
        }),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: (supplierId: string) =>
        api<SupplierSalePrice[]>(`${supplierSalePricesPath(storeId)}/${supplierId}`, { method: 'DELETE' }),
      onSuccess,
    }),
  }
}
