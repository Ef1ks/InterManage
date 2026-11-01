import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { suppliersKey } from '@/features/documents/api'
import { api } from '@/lib/api'
import type { SupplierContactKind, SupplierDetails } from '@/lib/types'

export { useCreateSupplier, useSuppliers } from '@/features/documents/api'

export const supplierKey = (storeId: string, supplierId: string) =>
  [...suppliersKey(storeId), supplierId] as const

const supplierPath = (storeId: string, supplierId: string) => `/stores/${storeId}/suppliers/${supplierId}`

export function useSupplier(storeId: string, supplierId: string | undefined) {
  return useQuery({
    queryKey: supplierKey(storeId, supplierId ?? ''),
    queryFn: () => api<SupplierDetails>(supplierPath(storeId, supplierId!)),
    enabled: supplierId != null,
  })
}

export type SupplierDetailsForm = {
  name: string
  nip: string
  address: string
  postalCode: string
  city: string
  website: string
  email: string
  phone: string
  notes: string
}

export type SupplierContactForm = {
  kind: SupplierContactKind
  name: string
  jobTitle: string
  phone: string
  email: string
  notes: string
}

/**
 * Zmiany karty dostawcy i jego kontaktów. Każda zwraca całą kartę, którą od razu podmieniamy; lista dostawców
 * (przedstawiciel, liczba kontaktów) odświeża się przez wspólne unieważnienie po mutacji.
 */
export function useSupplierActions(storeId: string, supplierId: string) {
  const queryClient = useQueryClient()
  const path = supplierPath(storeId, supplierId)
  const onSuccess = (details: SupplierDetails) => queryClient.setQueryData(supplierKey(storeId, supplierId), details)

  const update = useMutation({
    mutationFn: (form: SupplierDetailsForm) => api<SupplierDetails>(path, { method: 'PUT', json: form }),
    onSuccess,
  })
  const saveContact = useMutation({
    mutationFn: ({ id, ...form }: SupplierContactForm & { id?: string }) =>
      api<SupplierDetails>(id ? `${path}/contacts/${id}` : `${path}/contacts`, {
        method: id ? 'PUT' : 'POST',
        json: form,
      }),
    onSuccess,
  })
  const removeContact = useMutation({
    mutationFn: (contactId: string) => api<SupplierDetails>(`${path}/contacts/${contactId}`, { method: 'DELETE' }),
    onSuccess,
  })
  const restoreContact = useMutation({
    mutationFn: (contactId: string) =>
      api<SupplierDetails>(`${path}/contacts/${contactId}/restore`, { method: 'POST' }),
    onSuccess,
  })
  return { update, saveContact, removeContact, restoreContact }
}
