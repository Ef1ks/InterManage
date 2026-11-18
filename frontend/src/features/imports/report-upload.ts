import { useQuery, type QueryClient } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { ImportReportKind, ProductImportJob } from '@/lib/types'

/**
 * Etap wgrania raportu: `UPLOADING` (wysyłanie pliku, procent liczy przeglądarka), `PROCESSING` (plik dotarł, serwer
 * go czyta i zapisuje; czas nieznany) albo etap zadania w tle z serwera (baza danych: odczyt, zapis, kończenie).
 */
export type ImportProgress = Pick<ProductImportJob, 'percent' | 'processedRows' | 'totalRows'> & {
  phase: ProductImportJob['phase'] | 'UPLOADING' | 'PROCESSING'
  /** Kiedy zaczął się bieżący etap (do licznika czasu przy etapie bez procentów). */
  since: number
}

const importProgressKey = (storeId: string, kind: ImportReportKind) =>
  ['stores', storeId, 'imports', 'progress', kind] as const

/** Postęp trwającego wgrania raportu; wpisuje go mutacja wgrania, więc działa też przy „Odśwież dane”. */
export function useImportProgress(storeId: string, kind: ImportReportKind) {
  return useQuery<ImportProgress | null>({
    queryKey: importProgressKey(storeId, kind),
    queryFn: () => null,
    enabled: false,
    staleTime: Infinity,
  }).data
}

/** Zapis postępu wgrania raportu w pamięci zapytań (`null` = koniec). */
export function importProgress(queryClient: QueryClient, storeId: string, kind: ImportReportKind) {
  const key = importProgressKey(storeId, kind)
  const set = (progress: Omit<ImportProgress, 'since'> | null) => {
    if (progress === null) {
      queryClient.setQueryData(key, null)
      return
    }
    const previous = queryClient.getQueryData<ImportProgress | null>(key)
    const since = previous && previous.phase === progress.phase ? previous.since : Date.now()
    queryClient.setQueryData<ImportProgress>(key, { ...progress, since })
  }
  return {
    set,
    /** Wysyła plik z postępem; po wysłaniu całego pliku etap przechodzi na przetwarzanie na serwerze. */
    post: <T>(path: string, file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      const uploading = (percent: number) =>
        set(
          percent < 100
            ? { phase: 'UPLOADING', percent, processedRows: 0, totalRows: 0 }
            : { phase: 'PROCESSING', percent: 100, processedRows: 0, totalRows: 0 },
        )
      uploading(0)
      return api<T>(path, { method: 'POST', formData, onUploadProgress: uploading })
    },
    /**
     * Wysyła plik kawałkami (`<path>/uploads`), a potem uruchamia import. Przy wolnym łączu sklepu jedno żądanie
     * z całym plikiem trwa minuty i Cloudflare je zrywa; kawałek po 2 MB idzie kilkadziesiąt sekund.
     */
    postChunked: async <T>(path: string, file: File) => {
      const uploading = (percent: number) => set({ phase: 'UPLOADING', percent, processedRows: 0, totalRows: 0 })
      uploading(0)
      const upload = await api<{ id: string; chunkSize: number }>(`${path}/uploads`, {
        method: 'POST',
        json: { fileName: file.name, size: file.size },
      })
      let sent = 0
      for (let index = 0; sent < file.size; index++) {
        const chunk = file.slice(sent, sent + upload.chunkSize)
        await withRetry(() =>
          api(`${path}/uploads/${upload.id}/chunks/${index}`, {
            method: 'PUT',
            blob: chunk,
            onUploadProgress: (percent) =>
              uploading(Math.min(99, Math.floor(((sent + (chunk.size * percent) / 100) / file.size) * 100))),
          }),
        )
        sent += chunk.size
      }
      set({ phase: 'PROCESSING', percent: 100, processedRows: 0, totalRows: 0 })
      return api<T>(`${path}/uploads/${upload.id}/import`, { method: 'POST' })
    },
  }
}

const CHUNK_RETRIES = 3

/** Ponawia kawałek po zerwanym połączeniu albo błędzie bramy (serwer pomija kawałek, który już ma). */
async function withRetry(send: () => Promise<unknown>) {
  for (let attempt = 1; ; attempt++) {
    try {
      return await send()
    } catch (error) {
      const temporary = error instanceof ApiError && (error.status === 0 || error.status >= 500)
      if (!temporary || attempt > CHUNK_RETRIES) throw error
      await new Promise((resolve) => setTimeout(resolve, 2000 * attempt))
    }
  }
}

/** Wgranie raportu jednym żądaniem z paskiem postępu (wszystkie raporty poza bazą danych, która idzie w tle). */
export async function uploadReport<T>(
  queryClient: QueryClient,
  storeId: string,
  kind: ImportReportKind,
  path: string,
  file: File,
): Promise<T> {
  const progress = importProgress(queryClient, storeId, kind)
  try {
    return await progress.post<T>(path, file)
  } finally {
    progress.set(null)
  }
}
