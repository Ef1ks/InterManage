import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type {
  Schedule,
  ScheduleBreak,
  ScheduleDayOffKind,
  ScheduleDepartment,
  ScheduleSettings,
  TimeOffInitiative,
} from '@/lib/types'

const path = (storeId: string, suffix: string) => `/stores/${storeId}/staff/schedule${suffix}`

export type ScheduleScope = { storeId: string; departmentId: string; month: string }

/** Puste godziny i {@code dayOff} czyszczą dzień. {@code storeId}: inny sklep firmy, gdy pracownik jest tam tylko tego dnia. */
export type ScheduleChange = {
  employeeId: string
  date: string
  start: string | null
  end: string | null
  storeId: string | null
  dayOff?: ScheduleDayOffKind | null
  forDate?: string | null
  /** Godziny na umowie zlecenia (osobny wiersz). */
  civil?: boolean
}

const scheduleKey = ({ storeId, departmentId, month }: ScheduleScope, version: number | null) =>
  ['staff-schedule', storeId, departmentId, month, version] as const

export function useScheduleDepartments(storeId: string) {
  return useQuery({
    queryKey: ['staff-schedule', storeId, 'departments'],
    queryFn: () => api<ScheduleDepartment[]>(path(storeId, '/departments')),
  })
}

export function useSchedule(scope: ScheduleScope | null, version: number | null) {
  return useQuery({
    queryKey: scope ? scheduleKey(scope, version) : ['staff-schedule', 'none'],
    queryFn: () => {
      const { storeId, departmentId, month } = scope!
      const query = new URLSearchParams({ departmentId, month })
      if (version !== null) query.set('version', String(version))
      return api<Schedule>(path(storeId, `?${query}`))
    },
    enabled: scope !== null,
  })
}

/** Odpowiedź to cały grafik po zmianie, więc nie trzeba niczego pobierać od nowa. Zmiany idą po kolei. */
export function useChangeSchedule(scope: ScheduleScope) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (changes: ScheduleChange[]) =>
      api<Schedule>(path(scope.storeId, '/entries'), {
        method: 'PUT',
        json: { departmentId: scope.departmentId, month: scope.month, changes },
      }),
    meta: { invalidates: false },
    scope: { id: `schedule-${scope.storeId}` },
    onSuccess: (schedule) => client.setQueryData(scheduleKey(scope, null), schedule),
  })
}

/** Czas wolny za nadgodziny danego dnia; puste minuty go usuwają. Odpowiedź to cały grafik. */
export type ScheduleTimeOff = {
  employeeId: string
  date: string
  minutes: number | null
  initiative: TimeOffInitiative | null
}

export function useScheduleTimeOff(scope: ScheduleScope) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (timeOff: ScheduleTimeOff) =>
      api<Schedule>(path(scope.storeId, '/time-off'), {
        method: 'PUT',
        json: { departmentId: scope.departmentId, month: scope.month, ...timeOff },
      }),
    meta: { invalidates: false },
    scope: { id: `schedule-${scope.storeId}` },
    onSuccess: (schedule) => client.setQueryData(scheduleKey(scope, null), schedule),
  })
}

/** Stawka za godzinę od miesiąca grafiku; pusta usuwa ją od tego miesiąca. Odpowiedź to cały grafik. */
export function useScheduleRate(scope: ScheduleScope) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (rate: { employeeId: string; civil: boolean; rate: number | null }) =>
      api<Schedule>(path(scope.storeId, '/rates'), {
        method: 'PUT',
        json: { departmentId: scope.departmentId, month: scope.month, ...rate },
      }),
    meta: { invalidates: false },
    scope: { id: `schedule-${scope.storeId}` },
    onSuccess: (schedule) => client.setQueryData(scheduleKey(scope, null), schedule),
  })
}

/** Pracownicy bez grupy i grupy, każde w kolejności wyświetlania. Grupa bez id jest nowa. */
export type ScheduleLayout = {
  ungrouped: string[]
  groups: { id: string | null; name: string; employeeIds: string[] }[]
}

export function useSaveScheduleLayout(scope: ScheduleScope) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (layout: ScheduleLayout) =>
      api<Schedule>(path(scope.storeId, '/layout'), {
        method: 'PUT',
        json: { departmentId: scope.departmentId, month: scope.month, ...layout },
      }),
    onSuccess: (schedule) => client.setQueryData(scheduleKey(scope, null), schedule),
  })
}

/** Dotyczy całego sklepu, więc odświeża też grafiki innych działów. */
export function useOpenHoliday(scope: ScheduleScope) {
  return useMutation({
    mutationFn: ({ date, open }: { date: string; open: boolean }) =>
      api<Schedule>(path(scope.storeId, '/open-holidays'), {
        method: 'PUT',
        json: { departmentId: scope.departmentId, month: scope.month, date, open },
      }),
  })
}

export function usePublishSchedule(scope: ScheduleScope) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () =>
      api<Schedule>(path(scope.storeId, '/publish'), {
        method: 'POST',
        json: { departmentId: scope.departmentId, month: scope.month },
      }),
    meta: { invalidates: false },
    onSuccess: (schedule) => client.setQueryData(scheduleKey(scope, null), schedule),
  })
}

export function useScheduleSettings(storeId: string, departmentId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-schedule', storeId, 'settings', departmentId],
    queryFn: () => api<ScheduleSettings>(path(storeId, `/settings/${departmentId}`)),
    enabled,
  })
}

export function useSaveScheduleSettings(storeId: string, departmentId: string) {
  return useMutation({
    mutationFn: (settings: {
      managerIds: string[]
      minimums: { positionId: string; minCount: number | null; targetCount: number | null }[]
      breaks: ScheduleBreak[]
      toleranceMinutes: number
      payEnabled: boolean
    }) => api<ScheduleSettings>(path(storeId, `/settings/${departmentId}`), { method: 'PUT', json: settings }),
  })
}
