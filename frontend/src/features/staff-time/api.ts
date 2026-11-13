import { useMutation, useQuery } from '@tanstack/react-query'

import { api, downloadFile, printFile } from '@/lib/api'
import type {
  ScheduleDepartment,
  TimeAttention,
  TimePresence,
  TimeBalance,
  TimeCards,
  TimeDecisionKind,
  TimeSegmentPart,
  TimeSettings,
} from '@/lib/types'

const path = (storeId: string, suffix = '') => `/stores/${storeId}/staff/time${suffix}`

export type TimeScope = { storeId: string; departmentId: string; from: string; to: string }

export function useTimeDepartments(storeId: string) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'departments'],
    queryFn: () => api<ScheduleDepartment[]>(path(storeId, '/departments')),
  })
}

/** Karty odświeżają się co minutę: odbicia z czytnika przychodzą bez udziału przeglądarki. */
export function useTimeCards(scope: TimeScope | null) {
  return useQuery({
    queryKey: scope ? ['staff-time', scope.storeId, scope.departmentId, scope.from, scope.to] : ['staff-time', 'none'],
    queryFn: () => {
      const { storeId, departmentId, from, to } = scope!
      return api<TimeCards>(path(storeId, `?${new URLSearchParams({ departmentId, from, to })}`))
    },
    enabled: scope !== null,
    refetchInterval: 60_000,
    // Strzałki dzień / tydzień / miesiąc: do czasu odpowiedzi zostaje poprzedni okres (przygaszony), zamiast
    // migającego szkieletu. Tylko ten sam dział i ten sam rodzaj widoku, inaczej układ by przeskoczył.
    placeholderData: (previous, previousQuery) => {
      const [, storeId, departmentId, from, to] = (previousQuery?.queryKey ?? []) as string[]
      return scope &&
        storeId === scope.storeId &&
        departmentId === scope.departmentId &&
        viewShape(from, to) === viewShape(scope.from, scope.to)
        ? previous
        : undefined
    },
  })
}

/** Rodzaj okresu po liczbie dni: dzień, tydzień albo miesiąc. */
function viewShape(from: string | undefined, to: string | undefined) {
  if (!from || !to) return null
  const days = Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1
  return days === 1 ? 'day' : days === 7 ? 'week' : 'month'
}

/** Pulpit: obecni teraz wobec grafiku. Odbicia przychodzą z czytnika, więc co minutę. */
export function useTimePresence(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'presence'],
    queryFn: () => api<TimePresence>(path(storeId, '/presence')),
    enabled,
    refetchInterval: 60_000,
  })
}

/** Licznik w menu i przy działach: dni do reakcji kierownika z ostatnich 31 dni. */
export function useTimeAttention(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'attention'],
    queryFn: () => api<TimeAttention>(path(storeId, '/attention')),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export type TimeDecisionInput = {
  employeeId: string
  /** {@code BALANCE}: cały dzień (od północy do północy), brak zrównoważony czasem poza grafikiem. */
  kind: Exclude<TimeSegmentPart, 'WORK' | 'BREAK'> | 'BALANCE'
  start: string
  end: string
  decision: TimeDecisionKind
  note?: string | null
}

/** Saldo okresu pracownika (jak w grafiku) do okna decyzji; po decyzji odświeża się z resztą zapytań. */
export function useTimeBalance(storeId: string, employeeId: string, date: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'balance', employeeId, date],
    queryFn: () => api<TimeBalance>(path(storeId, `/balance?employeeId=${employeeId}&date=${date}`)),
    enabled,
  })
}

export function useTimeDecisions(storeId: string, departmentId: string) {
  const decide = useMutation({
    mutationFn: (json: TimeDecisionInput) =>
      api<{ id: string }>(path(storeId, `/decisions?departmentId=${departmentId}`), { method: 'POST', json }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/decisions/${id}`), { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/decisions/${id}/restore`), { method: 'POST' }),
  })
  return { decide, remove, restore }
}

/** Podpisane polecenie albo wniosek o nadgodziny: skan albo zdjęcie dołączone do decyzji. */
export function useSignedFile(storeId: string, decisionId: string) {
  const base = path(storeId, `/decisions/${decisionId}/file`)
  const attach = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<{ id: string }>(base, { method: 'PUT', formData })
    },
  })
  const remove = useMutation({
    mutationFn: () => api<void>(base, { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (fileId: string) => api<void>(`${base}/${fileId}/restore`, { method: 'POST' }),
  })
  return { attach, remove, restore }
}

/** Adres pliku do otwarcia w nowej karcie (przeglądarka wysyła ciasteczko sesji sama). */
export const signedFileUrl = (storeId: string, decisionId: string) =>
  `/api${path(storeId, `/decisions/${decisionId}/file`)}`

export const decisionPdfPath = (storeId: string, id: string) => path(storeId, `/decisions/${id}/pdf`)

export const printDecision = (storeId: string, id: string) => printFile(decisionPdfPath(storeId, id))

/** Karta ewidencji czasu pracy za miesiąc (RRRR-MM): cały dział albo jedna osoba. */
export const printTimeSheet = (storeId: string, departmentId: string, month: string, employeeId?: string) =>
  printFile(
    path(storeId, `/sheet/pdf?${new URLSearchParams({ departmentId, month, ...(employeeId ? { employeeId } : {}) })}`),
  )

/** Godziny całego sklepu za miesiąc z numerami kart RCP. */
export const downloadTimeExport = (storeId: string, month: string) =>
  downloadFile(path(storeId, `/sheet/xlsx?month=${month}`), `czas-pracy-${month}.xlsx`)

/** Plik importu ewidencji czasu pracy do Symfonii R2Płatnik (Import RCP), cały sklep za miesiąc. */
export const downloadRcpFile = (storeId: string, month: string) =>
  downloadFile(path(storeId, `/sheet/rcp?month=${month}`), `rcp-${month}.txt`)

export function useSimulator(storeId: string) {
  const punch = useMutation({
    mutationFn: (json: { employeeId: string; at: string | null }) =>
      api<void>(path(storeId, '/punches'), { method: 'POST', json }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/punches/${id}`), { method: 'DELETE' }),
  })
  return { punch, remove }
}

export function useTimeSettings(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'settings'],
    queryFn: () => api<TimeSettings>(path(storeId, '/settings')),
    enabled,
  })
}

export function useTimeSettingsActions(storeId: string) {
  const saveEmployer = useMutation({
    mutationFn: (json: Omit<TimeSettings['employer'], 'saved' | 'fromGus'>) =>
      api<TimeSettings>(path(storeId, '/settings/employer'), { method: 'PUT', json }),
  })
  const addReader = useMutation({
    mutationFn: (name: string) =>
      api<TimeSettings>(path(storeId, '/settings/readers'), { method: 'POST', json: { name } }),
  })
  const removeReader = useMutation({
    mutationFn: (id: string) => api<TimeSettings>(path(storeId, `/settings/readers/${id}`), { method: 'DELETE' }),
  })
  const restoreReader = useMutation({
    mutationFn: (id: string) => api<TimeSettings>(path(storeId, `/settings/readers/${id}/restore`), { method: 'POST' }),
  })
  const saveCard = useMutation({
    mutationFn: ({ employeeId, cardUid }: { employeeId: string; cardUid: string }) =>
      api<TimeSettings>(path(storeId, `/settings/cards/${employeeId}`), { method: 'PUT', json: { cardUid } }),
  })
  const removeCard = useMutation({
    mutationFn: (employeeId: string) =>
      api<TimeSettings>(path(storeId, `/settings/cards/${employeeId}`), { method: 'DELETE' }),
  })
  return { saveEmployer, addReader, removeReader, restoreReader, saveCard, removeCard }
}
