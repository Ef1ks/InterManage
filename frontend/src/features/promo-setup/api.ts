import { api } from '@/lib/api'
import type { PromotionSetupArticle } from '@/lib/types'

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
