/** Dzień roku jako część roku (1.01 = 1/365), do planu celu zysku „na ten dzień” (podsumowanie i pulpit). */
export function yearShare(date: string): number {
  const [year, month, day] = date.split('-').map(Number)
  const start = Date.UTC(year, 0, 1)
  const days = (Date.UTC(year + 1, 0, 1) - start) / 86_400_000
  return ((Date.UTC(year, month - 1, day) - start) / 86_400_000 + 1) / days
}
