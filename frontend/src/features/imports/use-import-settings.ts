import { useEffect, useMemo, useRef } from 'react'

import {
  clearLegacyImportLocations,
  importSettingsFrom,
  legacyImportLocations,
} from '@/features/imports/import-sources'
import { useSaveStoreLocations, useStoreLocations } from '@/lib/store-locations'

/**
 * Ustawienia importu sklepu (maski, ścieżki zapisu makr, numery z Mercalys, foldery raportów) ze ścieżek sklepu.
 * Ustawienia zapisane wcześniej tylko w tej przeglądarce idą raz na serwer, o ile sklep nie ma jeszcze własnych
 * (pierwszy komputer, który wejdzie, ustawia je dla całego sklepu), i znikają z przeglądarki.
 */
export function useImportSettings(storeId: string) {
  const locations = useStoreLocations(storeId)
  const save = useSaveStoreLocations(storeId)
  const migrated = useRef<string | null>(null)

  useEffect(() => {
    if (!locations.data || migrated.current === storeId) return
    migrated.current = storeId
    const legacy = legacyImportLocations(storeId)
    if (Object.keys(legacy).length === 0) return
    const missing = Object.fromEntries(Object.entries(legacy).filter(([key]) => !locations.data[key]))
    if (Object.keys(missing).length === 0) {
      clearLegacyImportLocations(storeId)
      return
    }
    save.mutate(missing, { onSuccess: () => clearLegacyImportLocations(storeId) })
  }, [locations.data, storeId, save])

  const settings = useMemo(() => importSettingsFrom(locations.data), [locations.data])
  return { settings, loaded: locations.data !== undefined }
}
