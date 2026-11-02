import { addDays } from '@/lib/dates'
import type { ProductionArticle, ProductionSchema } from '@/lib/types'

export const quantityFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })

/** Parametry schematu w skrócie, np. "140°C · wewn. 75°C · schładzanie 20 do 4°C · 5 dni". */
export function schemaSummary(schema: Omit<ProductionSchema, 'id' | 'name' | 'articleCount'>) {
  const parts: string[] = []
  if (schema.bakingTemperature != null) parts.push(`pieczenie ${schema.bakingTemperature}°C`)
  if (schema.internalTemperature != null) parts.push(`wewn. ${schema.internalTemperature}°C`)
  if (schema.coolingTime != null || schema.coolingTemperature != null) {
    parts.push(
      `schładzanie${schema.coolingTime != null ? ` ${schema.coolingTime}` : ''}${
        schema.coolingTemperature != null ? ` do ${schema.coolingTemperature}°C` : ''
      }`,
    )
  }
  if (schema.shelfLifeDays != null) parts.push(`ważność ${days(schema.shelfLifeDays)}`)
  return parts.length > 0 ? parts.join(' · ') : 'bez parametrów'
}

export function days(count: number) {
  return count === 1 ? '1 dzień' : `${count} dni`
}

/** Data ważności: data produkcji + dni ze schematu. */
export function expiryDate(productionDate: string, shelfLifeDays: number | null) {
  return shelfLifeDays == null || !productionDate ? null : addDays(productionDate, shelfLifeDays)
}

/** Godzina rozpoczęcia procesu jak w dawnym arkuszu: losowo od 6 do 14. Pracownik może ją poprawić. */
export function randomStartHour() {
  return 6 + Math.floor(Math.random() * 9)
}

/**
 * Artykuł po zeskanowanym kodzie: dokładnie, bez zer wiodących, a kod z etykiety wagi (2 + cyfra, 13 cyfr, w środku
 * cena albo waga) po pierwszych 7 cyfrach, tak jak w kartotece: 2416090 + zera.
 */
export function findByCode(articles: ProductionArticle[], scanned: string) {
  const code = scanned.trim()
  if (code === '') return null
  const plain = code.replace(/^0+/, '')
  const exact = articles.find((article) => article.code === code || article.code?.replace(/^0+/, '') === plain)
  if (exact) return exact
  if (/^2[1-9]\d{10,11}$/.test(code)) {
    const prefix = code.slice(0, 7)
    return articles.find((article) => article.code?.startsWith(prefix) && /^0+$/.test(article.code.slice(7))) ?? null
  }
  return null
}

/** Wyszukiwanie po nazwie: każde słowo jako fragment nazwy, bez polskich znaków. */
export function matchesName(article: ProductionArticle, query: string) {
  const name = plain(article.name)
  return plain(query)
    .split(/\s+/)
    .filter(Boolean)
    .every((word) => name.includes(word))
}

function plain(value: string) {
  return value
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/ł/g, 'l')
}
