import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { SavedFolderSpec } from '@/lib/saved-folders'
import type { ProtocolKind, ProtoSettings } from '@/lib/types'

/*
 * Pliki PROTO dla programu sklepu (PROTO6 garmażu i przyjęć MM, protokoły PROTO1-6 z wprowadzania danych) idą do
 * jednego folderu, z którego program sklepu je wczytuje. Folder wybiera się raz w przeglądarce; ścieżkę z ustawień
 * sklepu podpowiadamy przy wyborze (przeglądarka nie pozwala podać jej z kodu).
 */

/** Klucz zostaje po PROTO6 garmażu, żeby folder wybrany wcześniej dalej działał. */
const PROTO_FOLDER_KEY = 'deli-order-proto'
const DEFAULT_FOLDER = 'N:\\avisoconfig'

/** Protokoły z wprowadzania danych w kolejności z okna; numer to numer pliku PROTO. */
export const PROTOCOLS: { kind: ProtocolKind; number: number; label: string }[] = [
  { kind: 'LIQUIDATION', number: 1, label: 'Likwidacje' },
  { kind: 'STORE_NEEDS', number: 2, label: 'Potrzeby sklepu' },
  { kind: 'ADVERTISING', number: 3, label: 'Reklama' },
  { kind: 'DONATION', number: 4, label: 'Darowizna' },
  { kind: 'REPRESENTATION', number: 5, label: 'Reprezentacja' },
  { kind: 'TRANSFER', number: 6, label: 'MM' },
]

const settingsKey = (storeId: string) => ['stores', storeId, 'proto-settings'] as const
const settingsPath = (storeId: string) => `/stores/${storeId}/proto-settings`

export function useProtoSettings(storeId: string, enabled = true) {
  return useQuery({
    queryKey: settingsKey(storeId),
    queryFn: () => api<ProtoSettings>(settingsPath(storeId)),
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useSaveProtoSettings(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (settings: {
      folderPath: string | null
      hiddenProtocols: ProtocolKind[]
      productFileFolderPath: string | null
    }) =>
      api<ProtoSettings>(settingsPath(storeId), { method: 'PUT', json: settings }),
    onSuccess: (saved) => queryClient.setQueryData(settingsKey(storeId), saved),
    meta: { invalidates: false },
  })
}

/** Folder plików PROTO z podpowiedzią ścieżki z ustawień sklepu. */
export function useProtoFolder(storeId: string): SavedFolderSpec {
  const { data } = useProtoSettings(storeId)
  return { key: PROTO_FOLDER_KEY, label: 'Plik PROTO', suggestedPath: data?.folderPath ?? DEFAULT_FOLDER }
}

/**
 * Folder pliku nowych i zmienionych artykułów (CADBEZP) z wprowadzania danych: inny program niż pliki PROTO, więc
 * własny folder. Ścieżka z ustawień sklepu to tylko podpowiedź przy wyborze.
 */
export function useProductFileFolder(storeId: string): SavedFolderSpec {
  const { data } = useProtoSettings(storeId)
  return {
    key: 'data-entry-product-file',
    label: 'Plik nowych artykułów',
    suggestedPath: data?.productFileFolderPath ?? undefined,
  }
}
