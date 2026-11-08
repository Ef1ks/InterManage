import type { StaffDay, StaffShift } from '@/lib/types'

export const DAYS: { value: StaffDay; label: string }[] = [
  { value: 'MONDAY', label: 'Pon' },
  { value: 'TUESDAY', label: 'Wt' },
  { value: 'WEDNESDAY', label: 'Śr' },
  { value: 'THURSDAY', label: 'Czw' },
  { value: 'FRIDAY', label: 'Pt' },
  { value: 'SATURDAY', label: 'Sob' },
  { value: 'SUNDAY', label: 'Niedz' },
]

export type ShiftHours = Pick<StaffShift, 'start' | 'end'>

export const sameHours = (a: ShiftHours, b: ShiftHours) => a.start === b.start && a.end === b.end

export const sameShift = (a: StaffShift, b: StaffShift) => a.dayOfWeek === b.dayOfWeek && sameHours(a, b)

/** 06:00 → 6, 07:30 → 7:30. */
function hour(time: string) {
  const [hours, minutes] = time.split(':')
  return minutes === '00' ? String(Number(hours)) : `${Number(hours)}:${minutes}`
}

/** Tak, jak mówi się w sklepie: 6–14, 7:30–15:30. */
export const shiftLabel = (shift: ShiftHours) => `${hour(shift.start)}–${hour(shift.end)}`

const byStart = (a: ShiftHours, b: ShiftHours) => a.start.localeCompare(b.start) || a.end.localeCompare(b.end)

/** Różne godziny z listy, od najwcześniejszych. */
export function distinctHours(shifts: ShiftHours[]): ShiftHours[] {
  const result: ShiftHours[] = []
  for (const { start, end } of shifts) {
    if (!result.some((hours) => sameHours(hours, { start, end }))) result.push({ start, end })
  }
  return result.sort(byStart)
}

/** Skrót tygodnia: kolejne dni z tymi samymi godzinami w jednym wierszu, np. Pon–Pt 6–14, 7–15 i Sob 8–14. */
export function weekSummary(shifts: StaffShift[]): { days: string; hours: string }[] {
  const lines: { from: string; to: string; hours: string }[] = []
  let previous: StaffDay | null = null
  for (const day of DAYS) {
    const hours = distinctHours(shifts.filter((shift) => shift.dayOfWeek === day.value))
      .map(shiftLabel)
      .join(', ')
    if (hours === '') {
      previous = null
      continue
    }
    const last = lines.at(-1)
    if (previous !== null && last?.hours === hours) last.to = day.label
    else lines.push({ from: day.label, to: day.label, hours })
    previous = day.value
  }
  return lines.map(({ from, to, hours }) => ({ days: from === to ? from : `${from}–${to}`, hours }))
}

/** 1 osoba, 2 osoby, 5 osób, 22 osoby. */
export function peopleWord(count: number) {
  if (count === 1) return 'osoba'
  const lastDigit = count % 10
  const lastTwo = count % 100
  return lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'osoby' : 'osób'
}
