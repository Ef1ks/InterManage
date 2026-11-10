import { fetchPostedFile, saveBlob } from '@/lib/api'
import { queryClient } from '@/lib/query-client'
import { saveToFolder } from '@/lib/saved-folders'

import type { PriceControlPayload } from './api'

/**
 * Plik CTRLPRIX (zmienione ceny sprzedaży) z serwera do folderu plików PROTO; bez folderu się pobiera. Zgodę na
 * folder ({@code ensureFolderAccess}) trzeba wziąć wcześniej, zaraz po kliknięciu. Serwer zapisuje plik w historii
 * i dopisuje jego artykuły z kartoteki do listy cenówek („Cenówki”, jeden plik ETIQUETTE ze zmianami dnia).
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
    'CTRLPRIX.traite0',
  )
  if (folderReady) await saveToFolder(folderKey, fileName, blob)
  else saveBlob(blob, fileName)
  // Serwer dopisał artykuły z pliku do listy cenówek (zmiany cen); pobranie pliku to nie mutacja, więc odświeżamy sami.
  void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'price-markdowns'] })
  return { fileName, downloaded: !folderReady, supplierCode: headers.get('X-Supplier-Code') || null }
}
