import { useMutation, useQuery } from '@tanstack/react-query'

import { screenshotFileName } from '@/features/tasks/api'
import { api } from '@/lib/api'
import type { FeedbackCounts, FeedbackKind, FeedbackReport, FeedbackStatus } from '@/lib/types'

/** Najwięcej zrzutów przy jednym zgłoszeniu (tak samo w API). */
export const MAX_FEEDBACK_SHOTS = 10

export const FEEDBACK_KINDS: Record<FeedbackKind, string> = {
  BUG: 'Błąd',
  IMPROVEMENT: 'Ulepszenie',
}

export const FEEDBACK_STATUSES: Record<FeedbackStatus, { label: string; badge: string }> = {
  NEW: { label: 'Nowe', badge: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100' },
  IN_PROGRESS: { label: 'W trakcie', badge: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100' },
  DONE: { label: 'Zrobione', badge: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100' },
  REJECTED: { label: 'Odrzucone', badge: 'bg-zinc-300 text-zinc-900 dark:bg-zinc-700 dark:text-zinc-100' },
}

export type FeedbackInput = {
  kind: FeedbackKind
  description: string
  pageUrl: string
  pageLabel: string
  storeId: string | null
  shots: Blob[]
}

/** Adres zrzutu przy zgłoszeniu (tylko właściciel). */
export const feedbackAttachmentUrl = (reportId: string, attachmentId: string) =>
  `/api/settings/feedback/${reportId}/attachments/${attachmentId}`

export function useCreateFeedback() {
  return useMutation({
    mutationFn: (input: FeedbackInput) => {
      const formData = new FormData()
      formData.append('kind', input.kind)
      formData.append('description', input.description)
      formData.append('pageUrl', input.pageUrl)
      formData.append('pageLabel', input.pageLabel)
      if (input.storeId) formData.append('storeId', input.storeId)
      input.shots.forEach((shot) =>
        formData.append('files', shot, shot instanceof File ? shot.name : screenshotFileName(shot)),
      )
      return api<FeedbackReport>('/feedback', { method: 'POST', formData })
    },
  })
}

export function useFeedbackList(status: FeedbackStatus | null) {
  return useQuery({
    queryKey: ['feedback', status ?? 'ALL'],
    queryFn: () => api<FeedbackReport[]>(`/settings/feedback${status ? `?status=${status}` : ''}`),
  })
}

/** Liczniki do menu i zakładek; tylko właściciel (inaczej zapytanie nie rusza). */
export function useFeedbackCounts(enabled: boolean) {
  return useQuery({
    queryKey: ['feedback', 'counts'],
    queryFn: () => api<FeedbackCounts>('/settings/feedback/counts'),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export function useFeedbackActions() {
  const changeStatus = useMutation({
    mutationFn: ({ id, status, note }: { id: string; status: FeedbackStatus; note: string | null }) =>
      api<FeedbackReport>(`/settings/feedback/${id}/status`, { method: 'PUT', json: { status, note } }),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api(`/settings/feedback/${id}`, { method: 'DELETE' }),
  })
  const restore = useMutation({
    mutationFn: (id: string) => api<FeedbackReport>(`/settings/feedback/${id}/restore`, { method: 'POST' }),
  })
  return { changeStatus, remove, restore }
}
