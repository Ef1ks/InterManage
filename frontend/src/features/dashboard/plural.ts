/** Polska odmiana po liczbie: 1 osoba, 2 osoby, 5 osób (12–14 jak 5). */
export function plural(count: number, one: string, few: string, many: string) {
  if (count === 1) return one
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many
}
