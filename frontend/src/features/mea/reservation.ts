import { todayIso } from '@/lib/dates'

/**
 * Rezerwacja MEA jest zamknięta, gdy minął jej ostatni dzień („rezerwacja do”): ilości już nie przyjmie, więc
 * dokument jest tylko do podglądu (jak `ReadOnly` w eMEA). Bez daty rezerwacji dokument zostaje otwarty.
 */
export const reservationClosed = (reservationTo: string | null, today = todayIso()) =>
  reservationTo != null && reservationTo < today
