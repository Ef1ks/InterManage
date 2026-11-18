import type { AiFeature, AiUsageSummary } from '@/lib/types'

export const tokenFormat = new Intl.NumberFormat('pl-PL', { useGrouping: 'always' })

export const AI_FEATURE_LABELS: Record<AiFeature, string> = {
  DATA_ENTRY_SUGGESTIONS: 'Wprowadzanie danych: uzupełnianie artykułów',
  DATA_ENTRY_NAMES: 'Wprowadzanie danych: skracanie nazw',
  MEAT_SCAN: 'Zakup mięsa: odczyt WZ',
}

/** Krótki opis zużycia do komunikatu: „Model gemini-…: 12 345 tokenów (2 zapytania)”. */
export function usageLabel(usage: AiUsageSummary) {
  const requests = usage.requests === 1 ? '1 zapytanie' : `${usage.requests} ${plural(usage.requests)}`
  return `Model ${usage.model}: ${tokenFormat.format(usage.totalTokens)} tokenów (${requests})`
}

function plural(count: number) {
  const tens = count % 100
  const ones = count % 10
  return ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? 'zapytania' : 'zapytań'
}
