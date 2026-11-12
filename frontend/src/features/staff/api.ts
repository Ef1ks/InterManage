import { useMutation, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type {
  StaffAccount,
  StaffDepartment,
  StaffEmployee,
  StaffRoleOption,
  StaffShift,
  StoreSummary,
} from '@/lib/types'

export const staffPaths = {
  employees: '/pracownicy',
  structure: '/pracownicy/dzialy',
}

const path = (storeId: string, suffix: string) => `/stores/${storeId}/staff${suffix}`
const staffKey = (storeId: string, part: string) => ['staff', storeId, part] as const

export function useStaffStructure(storeId: string) {
  return useQuery({
    queryKey: staffKey(storeId, 'structure'),
    queryFn: () => api<StaffDepartment[]>(path(storeId, '/structure')),
  })
}

export function useStaffEmployees(storeId: string) {
  return useQuery({
    queryKey: staffKey(storeId, 'employees'),
    queryFn: () => api<StaffEmployee[]>(path(storeId, '/employees')),
  })
}

export function useStaffRoles(storeId: string) {
  return useQuery({
    queryKey: staffKey(storeId, 'roles'),
    queryFn: () => api<StaffRoleOption[]>(path(storeId, '/roles')),
  })
}

/** Sklepy firmy (ten sam NIP) dostępne dla użytkownika, razem z bieżącym. */
export function useCompanyStores(storeId: string) {
  return useQuery({
    queryKey: staffKey(storeId, 'company-stores'),
    queryFn: () => api<StoreSummary[]>(path(storeId, '/company-stores')),
  })
}

export function useStaffAccounts(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: staffKey(storeId, 'accounts'),
    queryFn: () => api<StaffAccount[]>(path(storeId, '/accounts')),
    enabled,
  })
}

/** Wolny login dla imienia i nazwiska. */
export function useLoginSuggestion(storeId: string, name: string) {
  return useQuery({
    queryKey: [...staffKey(storeId, 'login'), name],
    queryFn: () => api<{ username: string }>(path(storeId, `/login-suggestion?name=${encodeURIComponent(name)}`)),
    enabled: name.length > 0,
  })
}

export type EmployeeInvite = {
  displayName: string
  email: string
  /** Pusty = serwer złoży login sam. */
  username: string
  positionId: string
  shifts: StaffShift[]
}

export type EmployeeWithoutAccount = { displayName: string; positionId: string; shifts: StaffShift[] }

export type EmployeeAssignment = { userId: string; positionId: string; shifts: StaffShift[] }

export type EmployeeUpdate = {
  displayName: string
  email: string | null
  positionId: string
  shifts: StaffShift[]
}

export type EmployeeTransfer = { storeId: string; positionId: string; shifts: StaffShift[] }

export type PositionForm = { departmentId: string; name: string; roleId: string; shifts: StaffShift[] }

export function useEmployeeActions(storeId: string) {
  const invite = useMutation({
    mutationFn: (request: EmployeeInvite) =>
      api<StaffEmployee>(path(storeId, '/employees'), { method: 'POST', json: request }),
  })
  const createWithoutAccount = useMutation({
    mutationFn: (request: EmployeeWithoutAccount) =>
      api<StaffEmployee>(path(storeId, '/employees/without-account'), { method: 'POST', json: request }),
  })
  const assign = useMutation({
    mutationFn: (request: EmployeeAssignment) =>
      api<StaffEmployee>(path(storeId, '/employees/existing'), { method: 'POST', json: request }),
  })
  const update = useMutation({
    mutationFn: ({ id, request }: { id: string; request: EmployeeUpdate }) =>
      api<StaffEmployee>(path(storeId, `/employees/${id}`), { method: 'PUT', json: request }),
  })
  const end = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/employees/${id}`), { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (id: string) => api<StaffEmployee>(path(storeId, `/employees/${id}/restore`), { method: 'POST' }),
  })
  const resendInvitation = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/employees/${id}/invitation`), { method: 'POST' }),
  })
  const transfer = useMutation({
    mutationFn: ({ id, request }: { id: string; request: EmployeeTransfer }) =>
      api<StaffEmployee>(path(storeId, `/employees/${id}/transfer`), { method: 'POST', json: request }),
  })
  return { invite, createWithoutAccount, assign, update, end, restore, resendInvitation, transfer }
}

export function useStructureActions(storeId: string) {
  const saveDepartment = useMutation({
    mutationFn: ({ id, name }: { id?: string; name: string }) =>
      api<StaffDepartment[]>(path(storeId, id ? `/departments/${id}` : '/departments'), {
        method: id ? 'PUT' : 'POST',
        json: { name },
      }),
  })
  const removeDepartment = useMutation({
    mutationFn: (id: string) => api<StaffDepartment[]>(path(storeId, `/departments/${id}`), { method: 'DELETE' }),
  })
  const restoreDepartment = useMutation({
    mutationFn: (id: string) => api<StaffDepartment[]>(path(storeId, `/departments/${id}/restore`), { method: 'POST' }),
  })
  const savePosition = useMutation({
    mutationFn: ({ id, form }: { id?: string; form: PositionForm }) =>
      api<StaffDepartment[]>(path(storeId, id ? `/positions/${id}` : '/positions'), {
        method: id ? 'PUT' : 'POST',
        json: form,
      }),
  })
  const removePosition = useMutation({
    mutationFn: (id: string) => api<StaffDepartment[]>(path(storeId, `/positions/${id}`), { method: 'DELETE' }),
  })
  const restorePosition = useMutation({
    mutationFn: (id: string) => api<StaffDepartment[]>(path(storeId, `/positions/${id}/restore`), { method: 'POST' }),
  })
  return { saveDepartment, removeDepartment, restoreDepartment, savePosition, removePosition, restorePosition }
}
