import type { ReactNode } from 'react'

import { canInStore, useCurrentUser } from '@/features/auth/api'
import { useProfitTargetProgress } from '@/features/sales/api'
import { yearShare } from '@/features/sales/year-share'
import { formatShortDate } from '@/lib/dates'
import { cn } from '@/lib/utils'

const whole = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })
const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const money = (value: number) => `${whole.format(value)} zł`

/**
 * Pasek na całą szerokość panelu wyników: marża po odliczeniu likwidacji od 1 stycznia do ostatniego dnia ze sprzedażą
 * wobec rocznego celu z ustawień Podsumowania sprzedaży i strat. Kreska to plan na ten dzień (cel rozłożony równo na
 * dni roku, jak w kafelkach podsumowania). Nad paskiem po kolei: osiągnięta marża, cel roku, plan na ten dzień i o ile
 * sklep jest przed planem albo ile brakuje do planu.
 */
export function ProfitGoalBar({ storeId, lastSaleDate }: { storeId: string; lastSaleDate: string }) {
  const canSetGoal = canInStore(useCurrentUser(), storeId, 'SALES', 'EDIT')
  const progress = useProfitTargetProgress(storeId, lastSaleDate)
  const data = progress.data
  if (!data) return null

  const target = data.target?.marginAfterLossesValue ?? null
  const achieved = data.marginAfterLossesValue
  if (target == null || achieved == null) {
    return canSetGoal ? (
      <p className="border-b pb-5 text-xs text-muted-foreground">
        Ustaw roczny cel marży po stratach w Podsumowaniu sprzedaży i strat (koło zębate), żeby widzieć tu postęp{' '}
        {data.year}.
      </p>
    ) : null
  }

  const share = achieved / target
  const planned = yearShare(data.to)
  const plannedValue = target * planned
  const difference = achieved - plannedValue
  const ahead = difference >= 0

  return (
    <div className="space-y-2.5 border-b pb-5 tabular-nums">
      <div className="flex flex-wrap items-end gap-x-8 gap-y-2">
        <GoalFigure label="Marża zrealizowana">
          <span className="text-2xl font-semibold tracking-tight">{money(achieved)}</span>
          <span className="ml-2 text-sm text-muted-foreground">{percentFormat.format(share * 100)}% celu</span>
        </GoalFigure>
        <GoalFigure label={`Cel ${data.year}`}>
          <span className="text-base font-semibold">{money(target)}</span>
        </GoalFigure>
        <GoalFigure label={`Plan na ${formatShortDate(data.to)}`}>
          <span className="text-base font-semibold">{money(plannedValue)}</span>
          <span className="ml-1.5 text-sm text-muted-foreground">{percentFormat.format(planned * 100)}%</span>
        </GoalFigure>
        <span
          className={cn(
            'ml-auto rounded-md px-2 py-0.5 text-sm font-semibold',
            ahead
              ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-100'
              : 'bg-amber-200 text-amber-950 dark:bg-amber-950/60 dark:text-amber-100',
          )}
        >
          {ahead ? `${money(difference)} przed planem` : `${money(-difference)} do planu`}
        </span>
      </div>
      <div
        role="meter"
        aria-label={`Marża po likwidacjach wobec celu ${data.year}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(share * 1000) / 10}
        aria-valuetext={`${percentFormat.format(share * 100)}% celu, plan na ten dzień ${percentFormat.format(planned * 100)}%`}
        className="relative h-2.5 w-full overflow-hidden rounded-full bg-muted"
      >
        <div
          className={cn(
            'h-full rounded-full',
            ahead ? 'bg-emerald-500 dark:bg-emerald-600' : 'bg-amber-500 dark:bg-amber-600',
          )}
          style={{ width: `${Math.min(Math.max(share, 0), 1) * 100}%` }}
        />
        <div
          aria-hidden
          title={`Plan na ${formatShortDate(data.to)}: ${money(plannedValue)}`}
          className="absolute inset-y-0 w-0.5 bg-foreground"
          style={{ left: `${Math.min(planned, 1) * 100}%` }}
        />
      </div>
    </div>
  )
}

/** Jedna liczba nad paskiem: mały podpis i wartość pod nim. */
function GoalFigure({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="flex items-baseline">{children}</div>
    </div>
  )
}
