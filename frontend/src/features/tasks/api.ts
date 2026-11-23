import { useMutation, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type {
  StoreTask,
  TaskAssignee,
  TaskCounts,
  TaskEvent,
  TaskEventKind,
  TaskPriority,
  TaskStatus,
} from '@/lib/types'

const path = (storeId: string, suffix = '') => `/stores/${storeId}/tasks${suffix}`

/** Pozycja nowego zadania: artykuł z danymi z chwili zlecenia (np. „stan −12 szt. · 340 zł”). */
export type TaskItemDraft = {
  itm8: string | null
  ean: string | null
  name: string
  details: string | null
}

/** Skąd zadanie: rodzaj raportu, jego nazwa i ścieżka widoku z filtrami. */
export type TaskSource = { kind: string; label: string; url: string }

export type TaskInput = {
  title: string
  description: string | null
  dueDate: string | null
  priority: TaskPriority
  /** Jeden wykonawca (zmiana zadania). */
  assigneeId?: string | null
  /** Kilka osób naraz: każda dostaje swoją kopię (tylko nowe zadanie). */
  assigneeIds?: string[]
  /** Cały sklep: wykonuje ktokolwiek z dostępem do sklepu. */
  forStore?: boolean
  sourceKind?: string | null
  sourceLabel?: string | null
  sourceUrl?: string | null
  items?: TaskItemDraft[]
}

/** Najwięcej pozycji w jednym zadaniu (tak samo w API). */
export const MAX_TASK_ITEMS = 500

export const TASK_STATUSES: Record<TaskStatus, { label: string; badge: string }> = {
  OPEN: { label: 'Do zrobienia', badge: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100' },
  DONE: {
    label: 'Czeka na przyjęcie',
    badge: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  ACCEPTED: {
    label: 'Przyjęte',
    badge: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
}

export const TASK_EVENTS: Record<TaskEventKind, string> = {
  CREATED: 'Zlecono',
  EDITED: 'Zmieniono',
  ITEM_CHECKED: 'Odhaczono',
  ITEM_UNCHECKED: 'Odznaczono',
  DONE: 'Wykonano',
  UNDONE: 'Cofnięto wykonanie',
  ACCEPTED: 'Przyjęto',
  RETURNED: 'Odesłano do poprawy',
  DELETED: 'Usunięto',
  RESTORED: 'Przywrócono',
  ATTACHMENT_ADDED: 'Dodano zrzut',
  ATTACHMENT_REMOVED: 'Usunięto zrzut',
  ATTACHMENT_RESTORED: 'Przywrócono zrzut',
}

/** Zadania zalogowanego w sklepie (do zrobienia, czekające i przyjęte z ostatniego tygodnia). */
export function useMyTasks(storeId: string, enabled = true) {
  return useQuery({
    queryKey: ['tasks', storeId, 'mine'],
    queryFn: () => api<StoreTask[]>(path(storeId, '/mine')),
    enabled,
    refetchInterval: 120_000,
  })
}

/** Wszystkie zadania sklepu: widok zlecającego. */
export function useAllTasks(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['tasks', storeId, 'all'],
    queryFn: () => api<StoreTask[]>(path(storeId)),
    enabled,
    refetchInterval: 120_000,
  })
}

export function useTaskCounts(storeId: string | undefined) {
  return useQuery({
    queryKey: ['tasks', storeId, 'counts'],
    queryFn: () => api<TaskCounts>(path(storeId!, '/counts')),
    enabled: storeId !== undefined,
    refetchInterval: 300_000,
  })
}

export function useTaskAssignees(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['tasks', storeId, 'assignees'],
    queryFn: () => api<TaskAssignee[]>(path(storeId, '/assignees')),
    enabled,
    staleTime: 5 * 60_000,
  })
}

/** Czy zalogowany zleca całemu sklepowi (właściciel, Aderon, włączone zlecanie). */
export function useStoreAssignable(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['tasks', storeId, 'store-assignable'],
    queryFn: () => api<{ allowed: boolean }>(path(storeId, '/store-assignable')),
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useTaskHistory(storeId: string, taskId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['tasks', storeId, 'history', taskId],
    queryFn: () => api<TaskEvent[]>(path(storeId, `/${taskId}/history`)),
    enabled,
  })
}

export function useCreateTask(storeId: string) {
  return useMutation({
    mutationFn: (input: TaskInput) => api<StoreTask>(path(storeId), { method: 'POST', json: input }),
  })
}

export function useUpdateTask(storeId: string) {
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: TaskInput }) =>
      api<StoreTask>(path(storeId, `/${id}`), { method: 'PUT', json: input }),
  })
}

/** Odhaczanie pozycji, wykonanie, przyjęcie, odesłanie, usunięcie i przywrócenie. */
export function useTaskActions(storeId: string) {
  const checkItem = useMutation({
    mutationFn: ({ id, itemId, checked }: { id: string; itemId: string; checked: boolean }) =>
      api<StoreTask>(path(storeId, `/${id}/items/${itemId}`), { method: 'POST', json: { checked, note: null } }),
  })
  const done = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment?: string | null }) =>
      api<StoreTask>(path(storeId, `/${id}/done`), { method: 'POST', json: { comment: comment ?? null } }),
  })
  /** „Wszyscy już zrobili”: zamyka otwarte kopie zlecenia kilku osobom. */
  const doneAll = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment?: string | null }) =>
      api<StoreTask>(path(storeId, `/${id}/done-all`), { method: 'POST', json: { comment: comment ?? null } }),
  })
  const undone = useMutation({
    mutationFn: (id: string) => api<StoreTask>(path(storeId, `/${id}/undone`), { method: 'POST' }),
  })
  const accept = useMutation({
    mutationFn: (id: string) => api<StoreTask>(path(storeId, `/${id}/accept`), { method: 'POST' }),
  })
  const sendBack = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment?: string | null }) =>
      api<StoreTask>(path(storeId, `/${id}/return`), { method: 'POST', json: { comment: comment ?? null } }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(path(storeId, `/${id}`), { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (id: string) => api<StoreTask>(path(storeId, `/${id}/restore`), { method: 'POST' }),
  })
  return { checkItem, done, doneAll, undone, accept, sendBack, remove, restore }
}

/** Zadanie po terminie: termin minął, a nie jest jeszcze wykonane. */
export function isOverdue(task: StoreTask, today: string) {
  return task.status === 'OPEN' && task.dueDate !== null && task.dueDate < today
}

/** Najwięcej zrzutów przy jednym zadaniu (tak samo w API). */
export const MAX_TASK_ATTACHMENTS = 20

/** Adres zrzutu przy zadaniu (oddaje go backend po sprawdzeniu dostępu). */
export const attachmentUrl = (storeId: string, taskId: string, attachmentId: string) =>
  `/api${path(storeId, `/${taskId}/attachments/${attachmentId}`)}`

/** Nazwa pliku zrzutu z chwili zapisu, np. „zrzut-2026-11-20-1532.webp”. */
export function screenshotFileName(blob: Blob, at = new Date()) {
  const pad = (value: number) => String(value).padStart(2, '0')
  const extension = blob.type === 'image/png' ? 'png' : blob.type === 'image/jpeg' ? 'jpg' : 'webp'
  return `zrzut-${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}.${extension}`
}

/** Dodanie, usunięcie (znacznik) i przywrócenie zrzutu przy zadaniu. */
export function useTaskAttachmentActions(storeId: string) {
  const add = useMutation({
    mutationFn: ({ taskId, file }: { taskId: string; file: Blob }) => {
      const formData = new FormData()
      formData.append('file', file, file instanceof File ? file.name : screenshotFileName(file))
      return api<StoreTask>(path(storeId, `/${taskId}/attachments`), { method: 'POST', formData })
    },
  })
  const remove = useMutation({
    mutationFn: ({ taskId, attachmentId }: { taskId: string; attachmentId: string }) =>
      api<StoreTask>(path(storeId, `/${taskId}/attachments/${attachmentId}`), { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: ({ taskId, attachmentId }: { taskId: string; attachmentId: string }) =>
      api<StoreTask>(path(storeId, `/${taskId}/attachments/${attachmentId}/restore`), { method: 'POST' }),
  })
  return { add, remove, restore }
}
