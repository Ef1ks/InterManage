import { useMutation, useQuery } from '@tanstack/react-query'

import { TOOL_ROUTES } from '@/features/tools/categories'
import { api } from '@/lib/api'
import type { StaffRecord, StaffRecordRow } from '@/lib/types'

export const recordPaths = {
  list: TOOL_ROUTES.STAFF_RECORDS.path,
  record: (id: string) => `${TOOL_ROUTES.STAFF_RECORDS.path}/${id}`,
}

export type RecordEntryPath = 'contracts' | 'contract-changes' | 'work-periods' | 'disabilities' | 'leaves'

const path = (storeId: string, suffix = '') => `/stores/${storeId}/staff/records${suffix}`

export function useStaffRecords(storeId: string, enabled = true) {
  return useQuery({
    queryKey: ['staff-records', storeId],
    queryFn: () => api<StaffRecordRow[]>(path(storeId)),
    enabled,
  })
}

export function useStaffRecord(storeId: string, id: string, year: number) {
  return useQuery({
    queryKey: ['staff-records', storeId, id, year],
    queryFn: () => api<StaffRecord>(path(storeId, `/${id}?year=${year}`)),
  })
}

/** Zmiany kartoteki. Każda zwraca cały profil, a wspólne unieważnienie po mutacji odświeża widok i listę. */
export function useRecordActions(storeId: string, id: string) {
  const base = `/${id}`
  const saveProfile = useMutation({
    mutationFn: (json: unknown) => api<StaffRecord>(path(storeId, `${base}/profile`), { method: 'PUT', json }),
  })
  const saveOpening = useMutation({
    mutationFn: (json: { year: number; days: number }) =>
      api<StaffRecord>(path(storeId, `${base}/leave-opening`), { method: 'PUT', json }),
  })
  const addContractChange = useMutation({
    mutationFn: ({ contractId, json }: { contractId: string; json: unknown }) =>
      api<StaffRecord>(path(storeId, `${base}/contracts/${contractId}/changes`), { method: 'POST', json }),
  })
  const saveEntry = useMutation({
    mutationFn: ({ kind, entryId, json }: { kind: RecordEntryPath; entryId?: string; json: unknown }) =>
      api<StaffRecord>(path(storeId, `${base}/${kind}${entryId ? `/${entryId}` : ''}`), {
        method: entryId ? 'PUT' : 'POST',
        json,
      }),
  })
  const removeEntry = useMutation({
    mutationFn: ({ kind, entryId }: { kind: RecordEntryPath; entryId: string }) =>
      api<StaffRecord>(path(storeId, `${base}/${kind}/${entryId}`), { method: 'DELETE' }),
  })
  const restoreEntry = useMutation({
    mutationFn: ({ kind, entryId }: { kind: RecordEntryPath; entryId: string }) =>
      api<StaffRecord>(path(storeId, `${base}/${kind}/${entryId}/restore`), { method: 'POST' }),
  })
  return { saveProfile, saveOpening, addContractChange, saveEntry, removeEntry, restoreEntry }
}

/** Usunięcie i przywrócenie urlopu dowolnego pracownika, np. z komórki grafiku. */
export function useLeaveRemoval(storeId: string) {
  const remove = useMutation({
    mutationFn: ({ employeeId, leaveId }: { employeeId: string; leaveId: string }) =>
      api<StaffRecord>(path(storeId, `/${employeeId}/leaves/${leaveId}`), { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: ({ employeeId, leaveId }: { employeeId: string; leaveId: string }) =>
      api<StaffRecord>(path(storeId, `/${employeeId}/leaves/${leaveId}/restore`), { method: 'POST' }),
  })
  return { remove, restore }
}
