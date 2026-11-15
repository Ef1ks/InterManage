import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'

/*
 * Ścieżki plików sklepu na dysku sieciowym (foldery raportów w Imporcie danych, foldery zapisu protokołów, CADBEZP,
 * plików XML…, maski nazw i ścieżki zapisu makr z Mercalys). W jednym sklepie są takie same na każdym komputerze,
 * więc zapisujemy je na serwerze dla sklepu: ustawione raz działają u wszystkich. Sam folder (uchwyt File System
 * Access API) dalej wybiera się raz w każdej przeglądarce, bo uchwytu nie da się przenieść; ścieżka sklepu trafia
 * wtedy do schowka i wkleja się ją w pasek adresu okna wyboru.
 */

/** Klucz → wartość; puste ścieżki serwer pomija. */
export type StoreLocations = Record<string, string>

const locationsKey = (storeId: string) => ['stores', storeId, 'file-locations'] as const
const locationsPath = (storeId: string) => `/stores/${storeId}/file-locations`

/** Klucz ścieżki folderu z danym kluczem przeglądarki (np. `data-entry-xml`). */
export const folderLocationKey = (folderKey: string) => `folder:${folderKey}`

export function useStoreLocations(storeId: string | undefined) {
  return useQuery({
    queryKey: locationsKey(storeId ?? ''),
    queryFn: () => api<{ locations: StoreLocations }>(locationsPath(storeId!)).then((response) => response.locations),
    enabled: !!storeId,
    staleTime: 5 * 60_000,
  })
}

/** Zapisuje podane klucze (pusty tekst czyści ścieżkę), pozostałe zostają bez zmian. */
export function useSaveStoreLocations(storeId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (changes: StoreLocations) =>
      api<{ locations: StoreLocations }>(locationsPath(storeId!), { method: 'PUT', json: { locations: changes } }).then(
        (response) => response.locations,
      ),
    onSuccess: (saved) => queryClient.setQueryData(locationsKey(storeId ?? ''), saved),
    meta: { invalidates: false },
  })
}

/** Folder, w którym leży plik ze ścieżki (`P:\Raporty\baza.xlsx` → `P:\Raporty`); null bez folderu. */
export function parentFolder(path: string | undefined): string | null {
  const value = path?.trim() ?? ''
  const slash = value.lastIndexOf('\\')
  return slash > 2 ? value.slice(0, slash) : null
}
