import { fetchPostedFile, saveBlob } from '@/lib/api'
import { saveToFolder } from '@/lib/saved-folders'

import type { PriceControlPayload } from './api'

/**
 * Plik CTRLPRIX (zmienione ceny sprzedaży) z serwera do folderu plików PROTO; bez folderu się pobiera. Zgodę na
 * folder ({@code ensureFolderAccess}) trzeba wziąć wcześniej, zaraz po kliknięciu. Serwer zapisuje plik w historii.
 */
export async function savePriceControlFile(
  storeId: string,
  payload: PriceControlPayload,
  folderKey: string,
  folderReady: boolean,
) {
  const { fileName, blob, headers } = await fetchPostedFile(
    `/stores/${storeId}/data-entry/export/price-control`,
    payload,
    'CTRLPRIX.Traite1',
  )
  if (folderReady) await saveToFolder(folderKey, fileName, blob)
  else saveBlob(blob, fileName)
  return { fileName, downloaded: !folderReady, supplierCode: headers.get('X-Supplier-Code') || null }
}
