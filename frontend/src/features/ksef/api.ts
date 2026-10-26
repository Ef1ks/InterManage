import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { toast } from 'sonner'

import { api, downloadFile } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type { KsefInvoice, KsefInvoicePage, KsefOverview, KsefSettings } from '@/lib/types'

/** Faktury i połączenie z KSeF są osobne dla każdego sklepu, więc sklep jest częścią klucza. */
export const ksefKey = (storeId: string) => ['stores', storeId, 'ksef'] as const
const ksefPath = (storeId: string) => `/stores/${storeId}/ksef`

/** Nagłówek; dopóki pobieranie trwa w tle, odpytujemy co 3 s, żeby pokazać postęp i jego koniec. */
export function useKsefOverview(storeId: string) {
  return useQuery({
    queryKey: [...ksefKey(storeId), 'overview'],
    queryFn: () => api<KsefOverview>(ksefPath(storeId)),
    refetchInterval: (query) => (query.state.data?.syncRunning ? 3000 : false),
  })
}

export type KsefColumn = 'issueDate' | 'number' | 'seller' | 'net' | 'gross'
export type EnteredFilter = 'ALL' | 'ENTERED' | 'NOT_ENTERED'
export type KindFilter = 'ALL' | 'INVOICES' | 'CORRECTIONS'

/**
 * Filtry listy; {@code internalId}: "ALL" (wszystkie widoczne w sklepie), "OWN" (tylko z IDWew sklepu), konkretny
 * IDWew sklepu albo "NONE" (tylko bez IDWew). Daty RRRR-MM-DD.
 */
export type KsefFilters = {
  query: string
  from: string
  to: string
  entered: EnteredFilter
  kind: KindFilter
  internalId: string
  showSkipped: boolean
}

/** Faktury stronami po 100; filtry, sortowanie i sumy liczą się w bazie, kolejne strony dochodzą przy przewijaniu. */
export function useKsefInvoices(storeId: string, filters: KsefFilters, sort: SortState<KsefColumn>) {
  return useInfiniteQuery({
    queryKey: [...ksefKey(storeId), 'invoices', { ...filters, ...sort }],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({
        page: String(pageParam),
        sort: sort.key,
        direction: sort.direction,
        entered: filters.entered,
        kind: filters.kind,
        showSkipped: String(filters.showSkipped),
      })
      if (filters.query) params.set('query', filters.query)
      if (filters.from) params.set('from', filters.from)
      if (filters.to) params.set('to', filters.to)
      if (filters.internalId !== 'ALL') params.set('internalId', filters.internalId)
      return api<KsefInvoicePage>(`${ksefPath(storeId)}/invoices?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
    placeholderData: keepPreviousData,
  })
}

/** Ręczne "wprowadzona" i jego cofnięcie; listy odświeżamy, bo zmienia się filtr statusu. */
export function useMarkEntered(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, entered }: { id: string; entered: boolean }) =>
      api<KsefInvoice>(`${ksefPath(storeId)}/invoices/${id}/entered`, { method: entered ? 'PUT' : 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...ksefKey(storeId), 'invoices'] }),
  })
}

/**
 * Faktura do pobrania: PDF do czytania i wydruku (serwer robi go z XML z KSeF) albo oryginalny XML.
 * Oba pliki serwer pobiera z KSeF przy każdym kliknięciu.
 */
export function useDownloadInvoice(storeId: string) {
  return useMutation({
    mutationFn: ({ invoice, format }: { invoice: KsefInvoice; format: 'pdf' | 'xml' }) =>
      downloadFile(`${ksefPath(storeId)}/invoices/${invoice.id}/${format}`, `${invoice.ksefNumber}.${format}`),
  })
}

/**
 * "Pobierz z KSeF" teraz (poza tym serwer pobiera co pół godziny). Serwer pobiera w tle i od razu zwraca nagłówek
 * z {@code syncRunning}; dalej postęp śledzi {@link useKsefOverview}.
 */
export function useKsefSync(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api<KsefOverview>(`${ksefPath(storeId)}/sync`, { method: 'POST' }),
    onSuccess: (overview) => queryClient.setQueryData([...ksefKey(storeId), 'overview'], overview),
  })
}

/**
 * Lista faktur nadąża za pobieraniem w tle: odświeża się, gdy przybywa faktur i gdy pobieranie się kończy, a koniec
 * pobierania (z przycisku albo harmonogramu, gdy ekran był otwarty) pokazuje komunikat z wynikiem.
 */
export function useSyncFollow(storeId: string, overview: KsefOverview) {
  const queryClient = useQueryClient()
  const previous = useRef({ running: overview.syncRunning, newInvoices: overview.syncNewInvoices })
  useEffect(() => {
    const before = previous.current
    previous.current = { running: overview.syncRunning, newInvoices: overview.syncNewInvoices }
    const finished = before.running && !overview.syncRunning
    if (finished || overview.syncNewInvoices !== before.newInvoices) {
      queryClient.invalidateQueries({ queryKey: [...ksefKey(storeId), 'invoices'] })
    }
    if (finished && overview.syncMessage) {
      if (!overview.syncOk) toast.error(overview.syncMessage)
      else if (overview.syncMessage.startsWith('Limit')) toast.warning(overview.syncMessage)
      else toast.success(overview.syncMessage)
    }
  }, [queryClient, storeId, overview.syncRunning, overview.syncNewInvoices, overview.syncMessage, overview.syncOk])
}

export function useKsefSettings(storeId: string) {
  return useQuery({
    queryKey: [...ksefKey(storeId), 'settings'],
    queryFn: () => api<KsefSettings>(`${ksefPath(storeId)}/settings`),
  })
}

/**
 * Zmiany ustawień: każda zwraca całe ustawienia, które od razu podmieniamy; lista faktur i nagłówek się odświeżają
 * (IDWew i pomijani dostawcy zmieniają, co widać).
 */
export function useKsefSettingsMutations(storeId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (settings: KsefSettings) => {
    queryClient.setQueryData([...ksefKey(storeId), 'settings'], settings)
    queryClient.invalidateQueries({ queryKey: [...ksefKey(storeId), 'invoices'] })
    queryClient.invalidateQueries({ queryKey: [...ksefKey(storeId), 'overview'] })
  }
  const settingsPath = `${ksefPath(storeId)}/settings`
  const mutation = <T>(request: (input: T) => Promise<KsefSettings>) => ({ mutationFn: request, onSuccess })
  return {
    saveConnection: useMutation(
      mutation((input: { nip: string; certificate?: File; privateKey?: File; password: string }) => {
        const formData = new FormData()
        formData.set('nip', input.nip)
        if (input.certificate) formData.set('certificate', input.certificate)
        if (input.privateKey) formData.set('privateKey', input.privateKey)
        if (input.password) formData.set('password', input.password)
        return api<KsefSettings>(`${settingsPath}/connection`, { method: 'PUT', formData })
      }),
    ),
    test: useMutation(mutation(() => api<KsefSettings>(`${settingsPath}/test`, { method: 'POST' }))),
    /** Podłączenie innych sklepów użytkownika do firmy tego sklepu - bez wgrywania certyfikatu w każdym. */
    shareConnection: useMutation(
      mutation((storeIds: string[]) =>
        api<KsefSettings>(`${settingsPath}/connection/share`, { method: 'POST', json: { storeIds } }),
      ),
    ),
    /** Podłączenie do firmy połączonej w innym sklepie użytkownika - faktury firmy są od razu widoczne. */
    linkConnection: useMutation(
      mutation((nip: string) =>
        api<KsefSettings>(`${settingsPath}/connection/link`, { method: 'POST', json: { nip } }),
      ),
    ),
    addInternalId: useMutation(
      mutation((json: { internalId: string; name: string }) =>
        api<KsefSettings>(`${settingsPath}/internal-ids`, { method: 'POST', json }),
      ),
    ),
    removeInternalId: useMutation(
      mutation((id: string) => api<KsefSettings>(`${settingsPath}/internal-ids/${id}`, { method: 'DELETE' })),
    ),
    restoreInternalId: useMutation(
      mutation((id: string) => api<KsefSettings>(`${settingsPath}/internal-ids/${id}/restore`, { method: 'POST' })),
    ),
    addSkippedSupplier: useMutation(
      mutation((json: { nip: string; name: string }) =>
        api<KsefSettings>(`${settingsPath}/skipped-suppliers`, { method: 'POST', json }),
      ),
    ),
    removeSkippedSupplier: useMutation(
      mutation((id: string) => api<KsefSettings>(`${settingsPath}/skipped-suppliers/${id}`, { method: 'DELETE' })),
    ),
    restoreSkippedSupplier: useMutation(
      mutation((id: string) =>
        api<KsefSettings>(`${settingsPath}/skipped-suppliers/${id}/restore`, { method: 'POST' }),
      ),
    ),
  }
}
