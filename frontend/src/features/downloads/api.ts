import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'

export type DownloadChangeKind = 'ADDED' | 'CHANGED' | 'FIXED'

export type DownloadChange = { kind: DownloadChangeKind; text: string }

export type DownloadFileInfo = {
  id: string
  fileName: string
  version: string | null
  sizeBytes: number
  sha256: string
  uploadedAt: string
  uploadedBy: string | null
  /** Wersja pokazuje pracownikom baner „Nowa wersja”. */
  announce: boolean
  changes: DownloadChange[]
}

export type Download = {
  id: string
  title: string
  description: string | null
  /** Brak = pozycja jeszcze bez pliku (widzi ją tylko właściciel). */
  file: DownloadFileInfo | null
  /** Zalogowane konto nie pobrało tej wersji i nie potwierdziło, że ją ma. */
  newVersion: boolean
}

/** Lista zmian w formularzu: wpis w linii. */
export type DownloadChangesForm = { added: string; changed: string; fixed: string }

export type DownloadForm = DownloadChangesForm & {
  title: string
  description: string
  version: string
  announce: boolean
  file: File | null
  /** Bez nowego pliku: czy poprawiono listę zmian obowiązującej wersji. */
  changesEdited: boolean
}

const downloadsKey = ['downloads'] as const

/** Adres pliku do zwykłego linku: przeglądarka pokazuje postęp pobierania, ciasteczko sesji idzie samo. */
export const downloadFileUrl = (id: string) => `/api/downloads/${id}/file`

export function useDownloads() {
  // Baner „Nowa wersja” stoi na każdej stronie, więc lista odświeża się też bez wchodzenia do „Do pobrania”.
  return useQuery({
    queryKey: downloadsKey,
    queryFn: () => api<Download[]>('/downloads'),
    refetchInterval: 10 * 60_000,
  })
}

/** Wszystkie wersje pozycji z listami zmian, od najnowszej. */
export function useDownloadVersions(id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...downloadsKey, id, 'versions'],
    queryFn: () => api<DownloadFileInfo[]>(`/downloads/${id}/versions`),
    enabled,
  })
}

function useSetDownloads() {
  const queryClient = useQueryClient()
  return (list: Download[]) => queryClient.setQueryData(downloadsKey, list)
}

/** „Mam już tę wersję” albo kliknięcie „Pobierz”: baner o tej wersji znika od razu. */
export function useMarkDownloadSeen() {
  const queryClient = useQueryClient()
  const setDownloads = useSetDownloads()
  return useMutation({
    mutationFn: (id: string) => api<Download[]>(`/downloads/${id}/seen`, { method: 'POST' }),
    onMutate: (id) =>
      queryClient.setQueryData<Download[]>(downloadsKey, (list) =>
        list?.map((download) => (download.id === id ? { ...download, newVersion: false } : download)),
      ),
    onSuccess: setDownloads,
    meta: { invalidates: false },
  })
}

function changesFields(data: FormData, form: DownloadChangesForm) {
  for (const key of ['added', 'changed', 'fixed'] as const) {
    if (form[key].trim()) data.append(key, form[key].trim())
  }
}

function fileForm(form: DownloadForm) {
  const data = new FormData()
  if (form.file) data.append('file', form.file)
  if (form.version.trim()) data.append('version', form.version.trim())
  changesFields(data, form)
  data.append('announce', String(form.announce))
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

/** Nazwa i opis, a gdy wybrano plik, też nowa wersja z listą zmian; bez pliku ewentualna poprawka listy zmian. */
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
      } else if (form.changesEdited) {
        list = await api<Download[]>(`/settings/downloads/${id}/changes`, {
          method: 'PUT',
          json: { added: form.added, changed: form.changed, fixed: form.fixed },
        })
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

/** Skrót Windows do folderu (np. raporty na dysku sieciowym); pobiera się jako `fileName`. */
export type FolderShortcut = {
  id: string
  name: string
  fileName: string
  /** Folder odczytany ze skrótu; brak = nie udało się odczytać. */
  target: string | null
  sizeBytes: number
  uploadedAt: string
  uploadedBy: string | null
}

const shortcutsKey = ['downloads', 'shortcuts'] as const

/** Skrót w ZIP-ie: sam plik .lnk przeglądarki blokują jako niebezpieczny. */
export const shortcutFileUrl = (id: string) => `/api/downloads/shortcuts/${id}/zip`
export const shortcutsZipUrl = '/api/downloads/shortcuts/zip'

export function useShortcuts() {
  return useQuery({ queryKey: shortcutsKey, queryFn: () => api<FolderShortcut[]>('/downloads/shortcuts') })
}

function useSetShortcuts() {
  const queryClient = useQueryClient()
  return (list: FolderShortcut[]) => queryClient.setQueryData(shortcutsKey, list)
}

export function useCreateShortcut() {
  const setShortcuts = useSetShortcuts()
  return useMutation({
    mutationFn: ({ name, file }: { name: string; file: File }) => {
      const data = new FormData()
      data.append('name', name.trim())
      data.append('file', file)
      return api<FolderShortcut[]>('/settings/downloads/shortcuts', { method: 'POST', formData: data })
    },
    onSuccess: setShortcuts,
    meta: { invalidates: false },
  })
}

export function useRenameShortcut() {
  const setShortcuts = useSetShortcuts()
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      api<FolderShortcut[]>(`/settings/downloads/shortcuts/${id}`, { method: 'PUT', json: { name: name.trim() } }),
    onSuccess: setShortcuts,
    meta: { invalidates: false },
  })
}

export function useRemoveShortcut() {
  const setShortcuts = useSetShortcuts()
  return useMutation({
    mutationFn: (id: string) => api<FolderShortcut[]>(`/settings/downloads/shortcuts/${id}`, { method: 'DELETE' }),
    onSuccess: setShortcuts,
    meta: { invalidates: false },
  })
}

export function useRestoreShortcut() {
  const setShortcuts = useSetShortcuts()
  return useMutation({
    mutationFn: (id: string) =>
      api<FolderShortcut[]>(`/settings/downloads/shortcuts/${id}/restore`, { method: 'POST' }),
    onSuccess: setShortcuts,
    meta: { invalidates: false },
  })
}
