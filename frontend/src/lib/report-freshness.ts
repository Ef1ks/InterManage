import { todayIso } from '@/lib/dates'

/**
 * Raport z systemu sklepu jest aktualny tylko w dniu, w którym go wygenerowano. Data przychodzi bez strefy
 * ("2026-10-24T16:10:50", czas sklepu), więc porównujemy samą datę z dzisiejszą datą przeglądarki.
 */
export function isReportFresh(generatedAt: string | null | undefined): boolean {
  return generatedAt != null && generatedAt.slice(0, 10) === todayIso()
}

/** "2026-10-24T16:10:50" -> "24.10.2026, 16:10" (albo "dziś, 16:10"). */
export function formatReportDate(generatedAt: string): string {
  const [date, time = ''] = generatedAt.split('T')
  const [year, month, day] = date.split('-')
  const clock = time.slice(0, 5)
  const dayLabel = date === todayIso() ? 'dziś' : `${day}.${month}.${year}`
  return clock ? `${dayLabel}, ${clock}` : dayLabel
}
