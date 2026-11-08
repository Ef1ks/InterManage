import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'

export type DownloadFileInfo = {
  id: string
  fileName: string
  version: string | null
  sizeBytes: number
  sha256: string
  uploadedAt: string
  uploadedBy: string | null
}

export type Download = {
  id: string
  title: string
  description: string | null
  /** Brak = pozycja jeszcze bez pliku (widzi ją tylko właściciel). */
  file: DownloadFileInfo | null
}

export type DownloadForm = {
  title: string
  description: string
  version: string
  file: File | null
}

const downloadsKey = ['downloads'] as const

/** Adres pliku do zwykłego linku: przeglądarka pokazuje postęp pobierania, ciasteczko sesji idzie samo. */
export const downloadFileUrl = (id: string) => `/api/downloads/${id}/file`

export function useDownloads() {
  return useQuery({ queryKey: downloadsKey, queryFn: () => api<Download[]>('/downloads') })
}

function useSetDownloads() {
  const queryClient = useQueryClient()
  return (list: Download[]) => queryClient.setQueryData(downloadsKey, list)
}

function fileForm(form: Pick<DownloadForm, 'version' | 'file'>) {
  const data = new FormData()
  if (form.file) data.append('file', form.file)
  if (form.version.trim()) data.append('version', form.version.trim())
  return data
}

export function useCreateDownload() {
  const setDownloads = useSetDownloads()
  return useMutation({
    mutationFn: (form: DownloadForm) => {
      const data = fileForm(form)
      data.append('title', form.title.trim())
      if (form.description.trim()) data.append('description', form.description.trim())
      return api<Download[]>('/settings/downloads', { method: 'POST', formData: data })
    },
    onSuccess: setDownloads,
  })
}

/** Nazwa i opis, a gdy wybrano plik, też nowa wersja. */
export function useUpdateDownload() {
  const setDownloads = useSetDownloads()
  return useMutation({
    mutationFn: async ({ id, form }: { id: string; form: DownloadForm }) => {
      let list = await api<Download[]>(`/settings/downloads/${id}`, {
        method: 'PUT',
        json: { title: form.title.trim(), description: form.description.trim() || null },
      })
      if (form.file) {
        list = await api<Download[]>(`/settings/downloads/${id}/file`, { method: 'PUT', formData: fileForm(form) })
      }
      return list
    },
    onSuccess: setDownloads,
  })
}

export function useRemoveDownload() {
  const setDownloads = useSetDownloads()
  return useMutation({
    mutationFn: (id: string) => api<Download[]>(`/settings/downloads/${id}`, { method: 'DELETE' }),
    onSuccess: setDownloads,
  })
}

export function useRestoreDownload() {
  const setDownloads = useSetDownloads()
  return useMutation({
    mutationFn: (id: string) => api<Download[]>(`/settings/downloads/${id}/restore`, { method: 'POST' }),
    onSuccess: setDownloads,
  })
}
