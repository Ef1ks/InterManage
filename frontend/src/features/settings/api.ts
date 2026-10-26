import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { meQueryKey } from '@/features/auth/api'
import { api } from '@/lib/api'
import type { CreateUserRequest, Role, RoleRequest, Store, StoreRequest, UpdateUserRequest, User } from '@/lib/types'

const rolesKey = ['settings', 'roles'] as const
const usersKey = ['settings', 'users'] as const
const storesKey = ['settings', 'stores'] as const

export function useRoles() {
  return useQuery({ queryKey: rolesKey, queryFn: () => api<Role[]>('/settings/roles') })
}

export function useUsers() {
  return useQuery({ queryKey: usersKey, queryFn: () => api<User[]>('/settings/users') })
}

export function useStores() {
  return useQuery({ queryKey: storesKey, queryFn: () => api<Store[]>('/settings/stores') })
}

/** Zmiana ról lub użytkowników wpływa na obie listy (liczniki, nazwy ról) i potencjalnie na własne uprawnienia. */
function useInvalidateSettings() {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: ['settings'] })
    queryClient.invalidateQueries({ queryKey: meQueryKey })
  }
}

export function useSaveRole() {
  const invalidate = useInvalidateSettings()
  return useMutation({
    mutationFn: ({ id, request }: { id?: string; request: RoleRequest }) =>
      id === undefined
        ? api<Role>('/settings/roles', { method: 'POST', json: request })
        : api<Role>(`/settings/roles/${id}`, { method: 'PUT', json: request }),
    onSuccess: invalidate,
  })
}

export function useDeleteRole() {
  const invalidate = useInvalidateSettings()
  return useMutation({
    mutationFn: (id: string) => api(`/settings/roles/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}

export function useCreateUser() {
  const invalidate = useInvalidateSettings()
  return useMutation({
    mutationFn: (request: CreateUserRequest) => api<User>('/settings/users', { method: 'POST', json: request }),
    onSuccess: invalidate,
  })
}

export function useResendActivationLink() {
  return useMutation({
    mutationFn: (id: string) => api(`/settings/users/${id}/activation-link`, { method: 'POST' }),
  })
}

export function useUpdateUser() {
  const invalidate = useInvalidateSettings()
  return useMutation({
    mutationFn: ({ id, request }: { id: string; request: UpdateUserRequest }) =>
      api<User>(`/settings/users/${id}`, { method: 'PUT', json: request }),
    onSuccess: invalidate,
  })
}

/** Nazwa, numer i aktywność sklepu trafiają też do przełącznika sklepów (dane z /auth/me). */
export function useSaveStore() {
  const invalidate = useInvalidateSettings()
  return useMutation({
    mutationFn: ({ id, request }: { id?: string; request: StoreRequest }) =>
      id === undefined
        ? api<Store>('/settings/stores', { method: 'POST', json: request })
        : api<Store>(`/settings/stores/${id}`, { method: 'PUT', json: request }),
    onSuccess: invalidate,
  })
}
