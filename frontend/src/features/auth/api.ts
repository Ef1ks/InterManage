import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { Me, PermissionCategory, PermissionLevel } from '@/lib/types'

export const meQueryKey = ['me'] as const

/** Zalogowany użytkownik albo null, gdy brak sesji. */
export function useMe() {
  return useQuery({
    queryKey: meQueryKey,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await api<Me>('/auth/me')
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
    staleTime: Infinity,
  })
}

/** Dla komponentów renderowanych wewnątrz RequireAuth, gdzie użytkownik na pewno jest zalogowany. */
export function useCurrentUser(): Me {
  const { data } = useMe()
  if (!data) throw new Error('useCurrentUser poza RequireAuth')
  return data
}

export function hasPermission(me: Me, category: PermissionCategory, level: PermissionLevel = 'VIEW'): boolean {
  const granted = me.permissions[category]
  if (!granted) return false
  return level === 'VIEW' || granted === 'EDIT'
}

export function useCan(category: PermissionCategory, level: PermissionLevel = 'VIEW'): boolean {
  return hasPermission(useCurrentUser(), category, level)
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) =>
      api<Me>('/auth/login', { method: 'POST', json: credentials }),
    onSuccess: (me) => {
      queryClient.setQueryData(meQueryKey, me)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api('/auth/logout', { method: 'POST' }),
    meta: { invalidates: false },
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(meQueryKey, null)
    },
  })
}

/** Link z maila: zużywa token i loguje pracownika (z obowiązkiem ustawienia hasła). */
export function useActivateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (token: string) => api<Me>('/auth/activate', { method: 'POST', json: { token } }),
    onSuccess: (me) => {
      queryClient.setQueryData(meQueryKey, me)
    },
  })
}

export function useSetUpPassword() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (newPassword: string) => api<Me>('/auth/password/setup', { method: 'POST', json: { newPassword } }),
    onSuccess: (me) => {
      queryClient.setQueryData(meQueryKey, me)
    },
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (request: { currentPassword: string; newPassword: string }) =>
      api('/auth/password', { method: 'PUT', json: request }),
  })
}
