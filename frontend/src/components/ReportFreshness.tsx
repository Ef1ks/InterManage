import { formatReportDate, isReportFresh } from '@/lib/report-freshness'
import { cn } from '@/lib/utils'

type Props = {
  /** Data wygenerowania najnowszego wgranego raportu; brak = nic jeszcze nie wgrano. */
  generatedAt: string | null | undefined
  className?: string
}

/** Znacznik "Aktualne" / "Nieaktualne - wymaga wgrania" przy danych z raportu sklepu. */
export function ReportFreshness({ generatedAt, className }: Props) {
  const fresh = isReportFresh(generatedAt)
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium tabular-nums',
        fresh
          ? 'border-emerald-300/70 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300'
          : 'border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300',
        className,
      )}
    >
      <span aria-hidden className={cn('size-1.5 rounded-full', fresh ? 'bg-emerald-500' : 'bg-amber-500')} />
      {fresh
        ? `Aktualne · raport z ${formatReportDate(generatedAt!)}`
        : generatedAt
          ? `Nieaktualne · raport z ${formatReportDate(generatedAt)} · wymaga wgrania`
          : 'Brak danych · wgraj pierwszy raport'}
    </span>
  )
}
