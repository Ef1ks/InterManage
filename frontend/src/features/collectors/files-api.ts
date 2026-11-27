import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, fetchNamedFile, saveBlob } from '@/lib/api'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { CollectorFile, CollectorListDetails } from '@/lib/types'

import { collectorListsKey } from './lists-api'

/*
 * Inwentaryzacje i zamówienia z kolektora w historii. Plik dla programu sklepu zapisuje się w folderze protokołów
 * (ten sam co PROTO, ETIQUETTE i COMMANDE); dopiero zapisany plik zmienia stan pozycji na „wgrana”.
 */

export const collectorFilesKey = (storeId: string) => ['stores', storeId, 'collector-files'] as const
const basePath = (storeId: string) => `/stores/${storeId}/collector-files`

export function useCollectorFiles(storeId: string) {
  return useQuery({
    queryKey: [...collectorFilesKey(storeId), 'list'],
    queryFn: () => api<CollectorFile[]>(basePath(storeId)),
  })
}

export function useWaitingCollectorFiles(storeId: string, kind: CollectorFile['kind'], enabled: boolean) {
  return useQuery({
    queryKey: [...collectorFilesKey(storeId), 'count', kind],
    queryFn: () => api<{ waiting: number }>(`${basePath(storeId)}/count?kind=${kind}`),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export function useCollectorFileDetails(storeId: string, id: string | null) {
  return useQuery({
    queryKey: [...collectorFilesKey(storeId), 'details', id],
    queryFn: () => api<CollectorListDetails>(`${basePath(storeId)}/${id}`),
    enabled: id !== null,
  })
}

/** Artykuł inwentaryzacji spoza listy kolektora. */
export type InventoryItem = { itm8: string | null; ean: string; name: string; brand: string | null; quantity: number }

export type CreateCollectorFile = {
  kind: CollectorFile['kind']
  /** Listy z kolektora; bez nich inwentaryzacja z items (tabela wprowadzania danych albo Protokołów). */
  listIds?: string[]
  items?: InventoryItem[]
  note?: string
  /** Zamówienie: kartony poprawione w oknie. */
  lines?: { ean: string; quantity: number }[]
}

export function useCreateCollectorFile(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    meta: { invalidates: false },
    mutationFn: (input: CreateCollectorFile) =>
      api<{ file: CollectorFile; skipped: number }>(basePath(storeId), { method: 'POST', json: input }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: collectorFilesKey(storeId) })
      void queryClient.invalidateQueries({ queryKey: collectorListsKey(storeId) })
    },
  })
}

export function useRemoveCollectorFile(storeId: string) {
  const queryClient = useQueryClient()
  const options = {
    meta: { invalidates: false },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: collectorFilesKey(storeId) }),
  }
  const remove = useMutation({
    ...options,
    mutationFn: (id: string) => api(`${basePath(storeId)}/${id}`, { method: 'DELETE' }),
  })
  const restore = useMutation({
    ...options,
    mutationFn: (id: string) => api(`${basePath(storeId)}/${id}/restore`, { method: 'POST' }),
  })
  return { remove, restore }
}

/**
 * Plik z historii do folderu programu sklepu i oznaczenie „wgrana”. Zgoda na folder musi iść zaraz po kliknięciu,
 * więc to pierwsza rzecz. Bez folderu plik się pobiera, a pozycja zostaje czekająca (`downloaded`).
 */
export async function writeCollectorFile(
  storeId: string,
  folderKey: string,
  id: string,
  /** Zgoda już sprawdzona zaraz po kliknięciu (gdy przed plikiem idzie inne żądanie). */
  checkedFolder?: boolean,
): Promise<{ fileName: string; downloaded: boolean }> {
  const folderReady = checkedFolder ?? (await ensureFolderAccess(folderKey))
  const { fileName, blob } = await fetchNamedFile(`${basePath(storeId)}/${id}/file`, 'plik.Traite0')
  if (!folderReady) {
    saveBlob(blob, fileName)
    return { fileName, downloaded: true }
  }
  await saveToFolder(folderKey, fileName, blob)
  await markLoaded(storeId, id, fileName)
  return { fileName, downloaded: false }
}

export function markLoaded(storeId: string, id: string, fileName: string) {
  return api<CollectorFile>(`${basePath(storeId)}/${id}/loaded`, { method: 'POST', json: { fileName } })
}

export const collectorFilePath = (storeId: string, id: string, kind: 'xlsx' | 'pdf') =>
  `${basePath(storeId)}/${id}/${kind}`
