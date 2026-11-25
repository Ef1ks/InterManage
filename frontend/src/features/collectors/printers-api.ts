import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { StorePrinter } from '@/lib/types'

const printersKey = (storeId: string) => ['stores', storeId, 'printers'] as const

export type PrinterInput = { name: string; mac: string; model: string; pin: string }

/** Drukarki Bluetooth sklepu, które kolektory dostają na podstawce. */
export function usePrinters(storeId: string) {
  return useQuery({
    queryKey: printersKey(storeId),
    queryFn: () => api<StorePrinter[]>(`/stores/${storeId}/printers`),
  })
}

function usePrinterMutation<T>(storeId: string, request: (value: T) => Promise<StorePrinter[]>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (data) => queryClient.setQueryData(printersKey(storeId), data),
    meta: { invalidates: false },
  })
}

export function useAddPrinter(storeId: string) {
  return usePrinterMutation(storeId, (input: PrinterInput) =>
    api<StorePrinter[]>(`/stores/${storeId}/printers`, { method: 'POST', json: input }),
  )
}

export function useChangePrinter(storeId: string) {
  return usePrinterMutation(storeId, ({ id, ...input }: PrinterInput & { id: string }) =>
    api<StorePrinter[]>(`/stores/${storeId}/printers/${id}`, { method: 'PUT', json: input }),
  )
}

export function useRemovePrinter(storeId: string) {
  return usePrinterMutation(storeId, (id: string) =>
    api<StorePrinter[]>(`/stores/${storeId}/printers/${id}`, { method: 'DELETE' }),
  )
}

export function useRestorePrinter(storeId: string) {
  return usePrinterMutation(storeId, (id: string) =>
    api<StorePrinter[]>(`/stores/${storeId}/printers/${id}/restore`, { method: 'POST' }),
  )
}

/** AC3FA4123456 → AC:3F:A4:12:34:56. */
export const formatMac = (mac: string) => mac.match(/.{1,2}/g)?.join(':') ?? mac

/** Adres z dwukropkami, kreskami albo bez; zły = null. Tak samo liczy serwer (StorePrinterService.normalizeMac). */
export function normalizeMac(value: string) {
  const hex = value.replace(/[\s:.-]/g, '').toUpperCase()
  return /^[0-9A-F]{12}$/.test(hex) && hex !== '000000000000' ? hex : null
}
