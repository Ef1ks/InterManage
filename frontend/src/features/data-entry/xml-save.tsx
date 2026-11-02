import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { ApiError, api, fetchPostedFile, saveBlob } from '@/lib/api'
import { usePriceChangeFolder, useProductFileFolder } from '@/lib/proto-files'
import {
  ensureFolderAccess,
  folderAccess,
  folderSavingSupported,
  type SavedFolderSpec,
  saveToFolder,
} from '@/lib/saved-folders'

import type { ExportPayload, PriceControlPayload, ProductFilePayload } from './api'
import { savePriceControlFile } from './price-control'

/** Pliki XML faktur mają własny folder, wybrany raz w ustawieniach zapisu. */
export const XML_FOLDER: SavedFolderSpec = { key: 'data-entry-xml', label: 'Plik XML faktury' }

type SavedFile = { fileName: string; downloaded: boolean }

/**
 * Zapis tabeli: plik XML faktury do folderu z ustawień zapisu (bez obsługi folderów w przeglądarce: pobranie), potem
 * nowe i zmienione artykuły do pliku CADBEZP i zmienione ceny do pliku CTRLPRIX w folderze zmian cen; ich błąd
 * nie cofa zapisanego pliku XML. Na końcu jeden komunikat z listą zapisanych plików.
 */
export function useXmlSave(storeId: string) {
  const [saving, setSaving] = useState(false)
  const queryClient = useQueryClient()
  const productFolder = useProductFileFolder(storeId)
  const priceChangeFolder = usePriceChangeFolder(storeId)

  /** Foldery potrzebne do zapisu, których nie wybrano w tej przeglądarce (bez obsługi folderów: żadne). */
  const missingFolders = async (productFile: ProductFilePayload | null, priceControl: PriceControlPayload | null) => {
    if (!folderSavingSupported) return []
    const needed = [XML_FOLDER, ...(productFile ? [productFolder] : []), ...(priceControl ? [priceChangeFolder] : [])]
    const access = await Promise.all(needed.map((folder) => folderAccess(folder.key, 'readwrite')))
    return needed.filter((_, index) => access[index] === 'missing')
  }

  /** Nazwa zapisanego pliku XML albo {@code null}, gdy zapis się nie udał (komunikat już pokazany). */
  const save = async (
    payload: ExportPayload,
    productFile: ProductFilePayload | null,
    priceControl: PriceControlPayload | null,
  ): Promise<string | null> => {
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(XML_FOLDER.key)
      const productFolderReady = productFile ? await ensureFolderAccess(productFolder.key) : false
      const priceChangeFolderReady = priceControl ? await ensureFolderAccess(priceChangeFolder.key) : false
      const { fileName, blob, headers } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/xml`,
        payload,
        'faktura.xml',
      )
      // Zapis na dysku przed historią: nieudany zapis kończy się tutaj i plik nie trafia do historii.
      if (folderReady) await saveToFolder(XML_FOLDER.key, fileName, blob)
      else saveBlob(blob, fileName)
      const saved: SavedFile[] = [{ fileName, downloaded: !folderReady }]
      const exportId = headers.get('X-Xml-Export-Id')
      let inHistory = false
      if (exportId) {
        try {
          await api(`/stores/${storeId}/data-entry/xml-history/${exportId}/saved`, { method: 'POST', json: payload })
          inHistory = true
          void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'data-entry', 'xml-history'] })
        } catch {
          // Plik jest już na dysku; brak wpisu w historii nie cofa zapisu.
        }
      }
      if (!inHistory) toast.warning(`Plik ${fileName} nie trafił do historii`)
      if (productFile) saved.push(...(await saveProductFile(productFile, productFolderReady)))
      let supplierCode: string | null = null
      if (priceControl) {
        const result = await savePriceControl(priceControl, priceChangeFolderReady)
        if (result) {
          saved.push(result.file)
          supplierCode = result.supplierCode
        }
      }
      toast.success(`Zapisano pliki${supplierCode ? ` dla dostawcy ${supplierCode}` : ''}`, {
        description: (
          <span className="grid gap-0.5">
            {saved.map((file) => (
              <span key={file.fileName} className="break-all">
                {file.fileName}
                {file.downloaded ? ' (pobrany: folder nie jest wybrany w tej przeglądarce)' : ''}
              </span>
            ))}
          </span>
        ),
        duration: 10_000,
      })
      return fileName
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku XML', {
        description: 'Plik nie trafił do historii.',
      })
      return null
    } finally {
      setSaving(false)
    }
  }

  /** Plik CADBEZP po zapisanym pliku XML: błąd tylko ostrzega, plik XML zostaje. */
  const saveProductFile = async (file: ProductFilePayload, folderReady: boolean): Promise<SavedFile[]> => {
    try {
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/product-file`,
        file,
        'CADBEZP.xls',
      )
      if (folderReady) await saveToFolder(productFolder.key, fileName, blob)
      else saveBlob(blob, fileName)
      return [{ fileName, downloaded: !folderReady }]
    } catch (error) {
      toast.error('Nie udało się zapisać pliku nowych artykułów', {
        description: `${error instanceof ApiError ? `${error.message} ` : ''}Plik XML jest zapisany.`,
      })
      return []
    }
  }

  /** Plik CTRLPRIX (zmienione ceny) w folderze zmian cen: błąd tylko ostrzega, plik XML zostaje. */
  const savePriceControl = async (file: PriceControlPayload, folderReady: boolean) => {
    try {
      const { fileName, downloaded, supplierCode } = await savePriceControlFile(storeId, file, priceChangeFolder.key, folderReady)
      void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'data-entry', 'price-control-files'] })
      return { file: { fileName, downloaded }, supplierCode }
    } catch (error) {
      toast.error('Nie udało się zapisać pliku zmiany cen', {
        description: `${error instanceof ApiError ? `${error.message} ` : ''}Plik XML jest zapisany.`,
      })
      return null
    }
  }

  return { save, saving, missingFolders, productFolder, priceChangeFolder }
}
