import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { AvatarChange, BackgroundChange, Me, PermissionCategory, PermissionLevel } from '@/lib/types'

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
    mutationFn: (credentials: { username: string; password: string; rememberMe: boolean }) =>
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

/** Zmiany zdjęcia w tle podmieniają je od razu w danych zalogowanego, bez odświeżania wszystkich zapytań. */
function useBackgroundMutation<T>(mutationFn: (value: T) => Promise<BackgroundChange>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    meta: { invalidates: false },
    onSuccess: (change) => {
      queryClient.setQueryData<Me | null>(meQueryKey, (me) => (me ? { ...me, background: change.background } : me))
    },
  })
}

export function useUploadBackground() {
  return useBackgroundMutation((file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api<BackgroundChange>('/auth/background', { method: 'PUT', formData })
  })
}

export function useBackgroundVeil() {
  return useBackgroundMutation((veilPercent: number) =>
    api<BackgroundChange>('/auth/background/veil', { method: 'PUT', json: { veilPercent } }),
  )
}

export function useRemoveBackground() {
  return useBackgroundMutation(() => api<BackgroundChange>('/auth/background', { method: 'DELETE' }))
}

export function useRestoreBackground() {
  return useBackgroundMutation((id: string) =>
    api<BackgroundChange>(`/auth/background/${id}/restore`, { method: 'POST' }),
  )
}

/** Zmiany zdjęcia profilowego podmieniają je od razu w danych zalogowanego, bez odświeżania wszystkich zapytań. */
function useAvatarMutation<T>(mutationFn: (value: T) => Promise<AvatarChange>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    meta: { invalidates: false },
    onSuccess: (change) => {
      queryClient.setQueryData<Me | null>(meQueryKey, (me) => (me ? { ...me, avatar: change.avatar } : me))
    },
  })
}

export function useUploadAvatar() {
  return useAvatarMutation((file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api<AvatarChange>('/auth/avatar', { method: 'PUT', formData })
  })
}

export function useRemoveAvatar() {
  return useAvatarMutation(() => api<AvatarChange>('/auth/avatar', { method: 'DELETE' }))
}

export function useRestoreAvatar() {
  return useAvatarMutation((id: string) => api<AvatarChange>(`/auth/avatar/${id}/restore`, { method: 'POST' }))
}
