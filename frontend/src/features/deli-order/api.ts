import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import type {
  DeliDocumentCandidate,
  DeliInvitation,
  DeliOrder,
  DeliOrderSummary,
  DeliOverview,
  OrderForm,
  OrderUnit,
  ProductSearchResult,
  StoreSummary,
} from '@/lib/types'

/** Wyszukiwarka produktów rusza od tylu znaków, nie licząc spacji (tak samo pilnuje backend). */
export const MIN_SEARCH_LENGTH = 3

export function isSearchLongEnough(query: string) {
  return query.replace(/\s+/g, '').length >= MIN_SEARCH_LENGTH
}

const rootKey = (storeId: string) => ['stores', storeId, 'deli-order'] as const
const formKey = (storeId: string) => [...rootKey(storeId), 'form'] as const
const overviewKey = (storeId: string) => [...rootKey(storeId), 'overview'] as const
const invitationsKey = (storeId: string) => [...rootKey(storeId), 'invitations'] as const
const ordersKey = (storeId: string) => [...rootKey(storeId), 'orders'] as const
const basePath = (storeId: string) => `/stores/${storeId}/deli-order`

/**
 * Zamówienie dotyczy dwóch sklepów, a właściciel przełącza się między nimi w tej samej karcie:
 * po każdej zmianie odświeżamy listy i liczniki obu stron, nie tylko bieżącego sklepu.
 */
function invalidateOrderStores(queryClient: QueryClient, ...storeIds: string[]) {
  for (const storeId of new Set(storeIds)) {
    queryClient.invalidateQueries({ queryKey: ordersKey(storeId) })
    queryClient.invalidateQueries({ queryKey: overviewKey(storeId) })
  }
}

/** Dostawcy, licznik nowych zamówień i zaproszenia; odświeżane co minutę, bo zamówienia przychodzą z innych sklepów. */
export function useDeliOverview(storeId: string, enabled = true) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: overviewKey(storeId),
    queryFn: async () => {
      const overview = await api<DeliOverview>(`${basePath(storeId)}/overview`)
      // Licznik w menu zmienił się (przyszło albo zniknęło zamówienie): lista przychodzących też jest nieaktualna.
      const previous = queryClient.getQueryData<DeliOverview>(overviewKey(storeId))
      if (previous && previous.newIncomingCount !== overview.newIncomingCount) {
        queryClient.invalidateQueries({ queryKey: [...ordersKey(storeId), 'incoming'] })
      }
      return overview
    },
    refetchInterval: 60_000,
    enabled,
  })
}

// ---------- Formularz własnego sklepu ----------

export function useOrderForm(storeId: string) {
  return useQuery({
    queryKey: formKey(storeId),
    queryFn: () => api<OrderForm>(`${basePath(storeId)}/form`),
  })
}

type FormChange =
  | { type: 'createSection'; name: string }
  | { type: 'renameSection'; sectionId: string; name: string }
  | { type: 'deleteSection'; sectionId: string }
  | { type: 'createCategory'; sectionId: string; name: string }
  | { type: 'renameCategory'; categoryId: string; name: string }
  | { type: 'deleteCategory'; categoryId: string }
  | { type: 'addProduct'; categoryId: string; productId: string }
  | { type: 'setOrderUnit'; categoryId: string; itemId: string; orderUnit: OrderUnit }
  | { type: 'removeProduct'; categoryId: string; itemId: string }

function sendFormChange(storeId: string, change: FormChange): Promise<OrderForm> {
  const path = basePath(storeId)
  switch (change.type) {
    case 'createSection':
      return api(`${path}/sections`, { method: 'POST', json: { name: change.name } })
    case 'renameSection':
      return api(`${path}/sections/${change.sectionId}`, { method: 'PUT', json: { name: change.name } })
    case 'deleteSection':
      return api(`${path}/sections/${change.sectionId}`, { method: 'DELETE' })
    case 'createCategory':
      return api(`${path}/sections/${change.sectionId}/categories`, { method: 'POST', json: { name: change.name } })
    case 'renameCategory':
      return api(`${path}/categories/${change.categoryId}`, { method: 'PUT', json: { name: change.name } })
    case 'deleteCategory':
      return api(`${path}/categories/${change.categoryId}`, { method: 'DELETE' })
    case 'addProduct':
      return api(`${path}/categories/${change.categoryId}/products`, {
        method: 'POST',
        json: { productId: change.productId },
      })
    case 'setOrderUnit':
      return api(`${path}/categories/${change.categoryId}/products/${change.itemId}`, {
        method: 'PUT',
        json: { orderUnit: change.orderUnit },
      })
    case 'removeProduct':
      return api(`${path}/categories/${change.categoryId}/products/${change.itemId}`, { method: 'DELETE' })
  }
}

/**
 * Zmiany formularza idą po kolei (wspólny `scope`): każda odpowiedź to cały formularz, więc dwie zmiany wysłane
 * szybko jedna po drugiej (np. produkty dodawane Enterem) nie mogą wrócić w odwrotnej kolejności.
 */
const formScope = (storeId: string) => ({ id: `deli-form-${storeId}` })

/**
 * Formularz tego sklepu oglądany przez sklepy, które z niego zamawiają (właściciel przełącza sklepy w tej samej
 * karcie): oznaczamy jako nieaktualny, pobierze się przy wejściu.
 */
function invalidateSupplierForms(queryClient: QueryClient, formStoreId: string) {
  queryClient.invalidateQueries({
    predicate: ({ queryKey }) =>
      queryKey[2] === 'deli-order' && queryKey[3] === 'suppliers' && queryKey[4] === formStoreId,
  })
}

/**
 * Każda zmiana formularza zwraca cały aktualny formularz, który od razu podmieniamy w cache. Zmiany idą szybko jedna
 * po drugiej, więc bez globalnego odświeżania wszystkiego po każdej z nich.
 */
export function useChangeOrderForm(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    scope: formScope(storeId),
    meta: { invalidates: false },
    mutationFn: (change: FormChange) => sendFormChange(storeId, change),
    onSuccess: (form) => {
      queryClient.setQueryData(formKey(storeId), form)
      // Pierwszy dział sprawia, że sklep może zamawiać ze swojego formularza.
      queryClient.invalidateQueries({ queryKey: overviewKey(storeId) })
      invalidateSupplierForms(queryClient, storeId)
    },
  })
}

export type PastedProduct = { ean: string; orderUnit: OrderUnit }
export type PasteProductsResult = { form: OrderForm; added: number; updated: number; notFound: string[] }

/** Lista produktów wklejona z Excela do kategorii (EAN + jednostka). */
export function usePasteOrderProducts(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    scope: formScope(storeId),
    mutationFn: ({ categoryId, rows }: { categoryId: string; rows: PastedProduct[] }) =>
      api<PasteProductsResult>(`${basePath(storeId)}/categories/${categoryId}/products/paste`, {
        method: 'POST',
        json: { rows },
      }),
    meta: { invalidates: false },
    onSuccess: (result) => {
      queryClient.setQueryData(formKey(storeId), result.form)
      invalidateSupplierForms(queryClient, storeId)
    },
  })
}

/** Gdzie szukać produktów: we własnej kartotece (układanie formularza) albo w kartotece dostawcy (zamawianie). */
export type ProductSearchSource = { storeId: string; supplierId?: string }

function searchPath({ storeId, supplierId }: ProductSearchSource) {
  return supplierId === undefined
    ? `${basePath(storeId)}/products`
    : `${basePath(storeId)}/suppliers/${supplierId}/products`
}

export function useProductSearch(source: ProductSearchSource, query: string) {
  const debounced = useDebouncedValue(query.trim(), 250)
  return useQuery({
    queryKey: ['product-search', searchPath(source), debounced],
    queryFn: () => api<ProductSearchResult[]>(`${searchPath(source)}?query=${encodeURIComponent(debounced)}`),
    enabled: isSearchLongEnough(debounced),
    staleTime: 60_000,
    placeholderData: (previous) => previous,
  })
}

// ---------- Zaproszenia ----------

export function useInvitations(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: invitationsKey(storeId),
    queryFn: () => api<DeliInvitation[]>(`${basePath(storeId)}/invitations`),
    enabled,
  })
}

export function useInvitableStores(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...invitationsKey(storeId), 'stores'],
    queryFn: () => api<StoreSummary[]>(`${basePath(storeId)}/invitations/stores`),
    enabled,
  })
}

export function useInviteStores(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (storeIds: string[]) =>
      api<DeliInvitation[]>(`${basePath(storeId)}/invitations`, { method: 'POST', json: { storeIds } }),
    onSuccess: (invitations) => queryClient.setQueryData(invitationsKey(storeId), invitations),
  })
}

export function useRevokeInvitation(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (invitationId: string) =>
      api<DeliInvitation[]>(`${basePath(storeId)}/invitations/${invitationId}`, { method: 'DELETE' }),
    onSuccess: (invitations) => queryClient.setQueryData(invitationsKey(storeId), invitations),
  })
}

export function useRespondInvitation(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ invitationId, accept }: { invitationId: string; accept: boolean }) =>
      api(`${basePath(storeId)}/received-invitations/${invitationId}/${accept ? 'accept' : 'decline'}`, {
        method: 'POST',
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: overviewKey(storeId) }),
  })
}

// ---------- Zamówienia ----------

export function useSupplierForm(storeId: string, formStoreId: string) {
  return useQuery({
    queryKey: [...rootKey(storeId), 'suppliers', formStoreId, 'form'],
    queryFn: () => api<OrderForm>(`${basePath(storeId)}/suppliers/${formStoreId}/form`),
  })
}

export type OrderLine = { itemId: string; quantity: number }
export type ExtraLine = { productId: string; sectionId: string; unit: OrderUnit; quantity: number }

export function useSubmitOrder(storeId: string, formStoreId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { deliveryDate: string; items: OrderLine[]; extras: ExtraLine[] }) =>
      api<DeliOrder>(`${basePath(storeId)}/suppliers/${formStoreId}/orders`, { method: 'POST', json: request }),
    // Wysłane u nas, przychodzące i licznik u dostawcy (właściciel może zaraz przełączyć się na jego sklep).
    onSuccess: () => invalidateOrderStores(queryClient, storeId, formStoreId),
  })
}

/**
 * Zamówienia zmienia też drugi sklep, więc listy i szczegóły pobieramy przy każdym wejściu (staleTime 0):
 * najpierw widać zapamiętane dane, a świeże dochodzą od razu, bez F5.
 */
export function useOrders(storeId: string, kind: 'sent' | 'incoming') {
  return useQuery({
    queryKey: [...ordersKey(storeId), kind],
    queryFn: () => api<DeliOrderSummary[]>(`${basePath(storeId)}/orders/${kind}`),
    staleTime: 0,
    refetchInterval: kind === 'incoming' ? 60_000 : false,
  })
}

const orderKey = (storeId: string, orderId: string) => [...ordersKey(storeId), 'detail', orderId] as const

export function useOrder(storeId: string, orderId: string) {
  return useQuery({
    queryKey: orderKey(storeId, orderId),
    queryFn: () => api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}`),
    staleTime: 0,
  })
}

function useOrderMutation<T>(storeId: string, orderId: string, request: (variables: T) => Promise<DeliOrder>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (order) => {
      queryClient.setQueryData(orderKey(storeId, orderId), order)
      invalidateOrderStores(queryClient, order.store.id, order.formStore.id)
    },
  })
}

export function useCorrectOrder(storeId: string, orderId: string) {
  return useOrderMutation(storeId, orderId, (items: { id: string; quantity: number }[]) =>
    api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}/items`, { method: 'PUT', json: { items } }),
  )
}

/**
 * Ruchy międzymagazynowe dostawcy do przypięcia: bez zapytania z okolic daty dostawy, z zapytaniem pasujące
 * do numeru. Tylko dla dostawcy z uprawnieniem Edycja ({@code enabled}).
 */
export function useDocumentCandidates(storeId: string, orderId: string, query: string, enabled: boolean) {
  const debounced = useDebouncedValue(query.trim(), 250)
  return useQuery({
    queryKey: [...orderKey(storeId, orderId), 'document-candidates', debounced],
    queryFn: () =>
      api<DeliDocumentCandidate[]>(
        `${basePath(storeId)}/orders/${orderId}/documents/candidates${debounced ? `?query=${encodeURIComponent(debounced)}` : ''}`,
      ),
    enabled,
    staleTime: 0,
  })
}

/** Przypięcie dokumentu ruchu międzymagazynowego (dostawca); odpięty da się przypiąć z powrotem tym samym. */
export function useLinkOrderDocument(storeId: string, orderId: string) {
  return useOrderMutation(storeId, orderId, (documentId: string) =>
    api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}/documents`, { method: 'POST', json: { documentId } }),
  )
}

/** Odpięcie zostawia wpis w historii (kto i kiedy) - nic nie jest kasowane. */
export function useUnlinkOrderDocument(storeId: string, orderId: string) {
  return useOrderMutation(storeId, orderId, (documentId: string) =>
    api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}/documents/${documentId}`, { method: 'DELETE' }),
  )
}

export function useFulfillOrder(storeId: string, orderId: string) {
  return useOrderMutation(storeId, orderId, () =>
    api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}/fulfill`, { method: 'POST' }),
  )
}

/**
 * Usunięcie nowego zamówienia po stronie tego sklepu i jego przywrócenie. Nic nie jest kasowane: zamówienie
 * znika z naszej listy, druga strona widzi je jako usunięte. Odświeżenie list jest w hooku (a nie w mutate),
 * bo "Przywróć" klika się w toaście, gdy szczegóły zamówienia są już zamknięte.
 */
export function useDeleteOrder(storeId: string, orderId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (order: DeliOrder) => {
    queryClient.setQueryData(orderKey(storeId, orderId), order)
    invalidateOrderStores(queryClient, order.store.id, order.formStore.id)
  }
  const remove = useMutation({
    mutationFn: () => api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}`, { method: 'DELETE' }),
    onSuccess,
  })
  const restore = useMutation({
    mutationFn: () => api<DeliOrder>(`${basePath(storeId)}/orders/${orderId}/restore`, { method: 'POST' }),
    onSuccess,
  })
  return { remove, restore }
}
