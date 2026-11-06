import type { LabelFormat, ProFile } from '@/features/leaflet/leaflet-rows'
import { api, fetchPostedFile, saveBlob } from '@/lib/api'
import { saveToFolder } from '@/lib/saved-folders'
import type { PromotionSetupArticle } from '@/lib/types'

import { codeKey } from './promo-rows'

/** Promocja ma cenę skreśloną, więc etykiety idą w formacie PROMO. */
const LABEL_FORMAT: LabelFormat = 'PRO_PRO_FOR_67'

/** Kody idą w adresie zapytania, więc paczkami. */
const CHUNK = 100

/** Artykuły po kodach (EAN, multikod, kod z etykiety wagi, ITM8), pod kluczem kodu bez zer wiodących. */
export async function fetchPromoArticles(storeId: string, codes: string[]) {
  const chunks = Array.from({ length: Math.ceil(codes.length / CHUNK) }, (_, index) =>
    codes.slice(index * CHUNK, index * CHUNK + CHUNK),
  )
  const found = await Promise.all(
    chunks.map((chunk) => {
      const params = new URLSearchParams(chunk.map((code) => ['code', code]))
      return api<PromotionSetupArticle[]>(`/stores/${storeId}/promotion-setup/articles?${params}`)
    }),
  )
  return new Map(found.flat().map((article) => [article.code, article]))
}

/**
 * Plik etykiet artykułów z plików .PRO, po jednej etykiecie, choć artykuł bywa w kilku promocjach. Jeden plik na
 * zapis, bo nazwa ma czas co do sekundy i kolejny nadpisałby poprzedni. Zwraca opis do komunikatu.
 */
export async function savePromoLabels(storeId: string, files: ProFile[], folderKey: string, folderReady: boolean) {
  const eans = new Map(files.flatMap((file) => file.lines).map((line) => [codeKey(line.ean), line.ean]))
  const { fileName, blob } = await fetchPostedFile(
    `/stores/${storeId}/data-entry/export/labels`,
    { lines: [...eans.values()].map((ean) => ({ ean, count: 1 })), format: LABEL_FORMAT },
    `ETIQUETTE_${LABEL_FORMAT}.traite0`,
  )
  if (folderReady) await saveToFolder(folderKey, fileName, blob)
  else saveBlob(blob, fileName)
  const summary = `${LABEL_FORMAT}: ${eans.size}`
  return folderReady ? summary : `${summary} · pobrane: folder protokołów nie jest wybrany w tej przeglądarce`
}
