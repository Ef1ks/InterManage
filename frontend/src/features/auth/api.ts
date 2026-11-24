import { useCallback } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import { useAppSelector } from '@/store'
import type {
  AutoLogout,
  AvatarChange,
  BackgroundChange,
  Me,
  PermissionCategory,
  PermissionLevel,
  PinSettings,
  SwitchAccount,
} from '@/lib/types'

export const meQueryKey = ['me'] as const

/**
 * Konto widziane w bieżącym sklepie: uprawnienia, ranga i zlecanie zadań z rangi w tym sklepie (`storePermissions`),
 * więc `hasPermission` i `useCan` działają według sklepu wybranego w przełączniku.
 */
export function inStore(me: Me, storeId: string | null): Me {
  const store = me.stores.find((candidate) => candidate.id === storeId) ?? me.stores[0]
  if (!store || !me.storePermissions) return me
  return {
    ...me,
    permissions: me.storePermissions[store.id] ?? {},
    roleName: me.storeRoles?.[store.id] ?? me.roleName,
    canAssignTasks: me.owner || (me.taskAssignStores ?? []).includes(store.id),
  }
}

/** Zalogowany użytkownik albo null, gdy brak sesji. */
export function useMe() {
  const storeId = useAppSelector((state) => state.ui.currentStoreId)
  const select = useCallback((me: Me | null) => (me ? inStore(me, storeId) : me), [storeId])
  return useQuery({
    select,
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

/**
 * Uprawnienie w dowolnym sklepie konta (nie tylko w bieżącym), np. dane innego sklepu na stronie Wyniki sklepów.
 * Właściciel ma wszystko w każdym sklepie.
 */
export function canInStore(
  me: Me,
  storeId: string,
  category: PermissionCategory,
  level: PermissionLevel = 'VIEW',
): boolean {
  if (me.owner) return true
  const granted = me.storePermissions?.[storeId]?.[category]
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

export const switchAccountsQueryKey = ['switch-accounts'] as const
export const pinSettingsQueryKey = ['pin-settings'] as const
export const autoLogoutQueryKey = ['auto-logout'] as const

/** Konta zalogowane w tle w tej przeglądarce (działa też na stronie logowania). */
export function useSwitchAccounts() {
  return useQuery({
    queryKey: switchAccountsQueryKey,
    queryFn: () => api<SwitchAccount[]>('/auth/accounts'),
  })
}

/**
 * Wejście na inne konto: dane poprzedniego konta znikają z pamięci zapytań (inne uprawnienia i sklepy),
 * a nowe konto jest od razu zalogowanym użytkownikiem.
 */
function useBecomeAccount<T>(mutationFn: (value: T) => Promise<Me>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    meta: { invalidates: false },
    onSuccess: (me) => {
      queryClient.clear()
      queryClient.setQueryData(meQueryKey, me)
    },
    // Błędny PIN może zmienić to, czego wymaga konto (po kilku próbach hasło).
    onError: () => queryClient.invalidateQueries({ queryKey: switchAccountsQueryKey }),
  })
}

/** Loguje kolejne konto hasłem; dotychczasowe zostaje zalogowane w tle. */
export function useAddAccount() {
  return useBecomeAccount((credentials: { username: string; password: string; rememberMe: boolean }) =>
    api<Me>('/auth/accounts', { method: 'POST', json: credentials }),
  )
}

export function useSwitchAccount() {
  return useBecomeAccount(({ id, pin, password }: { id: string; pin?: string; password?: string }) =>
    api<Me>(`/auth/accounts/${id}/switch`, { method: 'POST', json: { pin, password } }),
  )
}

/** Wylogowuje jedno konto w tle. */
export function useRemoveAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api(`/auth/accounts/${id}`, { method: 'DELETE' }),
    meta: { invalidates: false },
    onSettled: () => queryClient.invalidateQueries({ queryKey: switchAccountsQueryKey }),
  })
}

/** Wylogowuje wszystkie konta w tej przeglądarce. */
export function useLogoutAll() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api('/auth/accounts/logout', { method: 'POST' }),
    meta: { invalidates: false },
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(meQueryKey, null)
    },
  })
}

export function usePinSettings() {
  return useQuery({ queryKey: pinSettingsQueryKey, queryFn: () => api<PinSettings>('/auth/pin') })
}

function usePinMutation<T>(mutationFn: (value: T) => Promise<PinSettings>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    meta: { invalidates: false },
    onSuccess: (settings) => {
      queryClient.setQueryData(pinSettingsQueryKey, settings)
      // Konto bez wymogu PIN-u nie jest wylogowywane samo, więc zmienia się też `idleLockMinutes`.
      return queryClient.invalidateQueries({ queryKey: meQueryKey })
    },
  })
}

export function useSetPin() {
  return usePinMutation((request: { currentPassword: string; pin: string }) =>
    api<PinSettings>('/auth/pin', { method: 'PUT', json: request }),
  )
}

export function useSetPinRequired() {
  return usePinMutation((required: boolean) =>
    api<PinSettings>('/auth/pin/required', { method: 'PUT', json: { required } }),
  )
}

export function useAutoLogout() {
  return useQuery({ queryKey: autoLogoutQueryKey, queryFn: () => api<AutoLogout>('/auth/auto-logout') })
}

/** Zapisuje się od razu po zmianie; czas wylogowania (`idleLockMinutes` zalogowanego) pobiera się od nowa. */
export function useSetAutoLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ enabled, minutes, workdayLock }: AutoLogout) =>
      api<AutoLogout>('/auth/auto-logout', { method: 'PUT', json: { enabled, minutes, workdayLock } }),
    meta: { invalidates: false },
    onSuccess: (settings) => {
      queryClient.setQueryData(autoLogoutQueryKey, settings)
      return queryClient.invalidateQueries({ queryKey: meQueryKey })
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
