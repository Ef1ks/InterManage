import { ChevronRightIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { useProfitTargetProgress, useSalesReportsOverview } from '@/features/sales/api'
import { yearShare } from '@/features/sales/year-share'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { formatShortDate } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { storeAnchor } from './paths'
import { useResultStores } from './stores'

const whole = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })
const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const money = (value: number) => `${whole.format(value)} zł`

/**
 * Pulpit, prawy dolny róg: cel marży pozostałych sklepów w skrócie, żeby od razu widzieć, jak radzi sobie każdy sklep.
 * Klik prowadzi do sklepu na stronie Wyniki sklepów.
 */
export function OtherStoresCard() {
  const me = useCurrentUser()
  const current = useCurrentStore()
  const stores = useResultStores().filter((store) => store.id !== current?.id)
  if (!hasPermission(me, 'STORE_RESULTS') || stores.length === 0) return null
  const page = TOOL_ROUTES.STORE_RESULTS.path

  return (
    <Card className="gap-3">
      <CardHeader className="flex items-start justify-between gap-3">
        <div className="space-y-1">
          <CardTitle>Inne sklepy</CardTitle>
          <CardDescription>Marża po likwidacjach wobec celu roku</CardDescription>
        </div>
        <Link
          to={page}
          className="flex shrink-0 items-center gap-0.5 rounded-sm text-sm text-muted-foreground hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          Wyniki sklepów
          <ChevronRightIcon className="size-4" />
        </Link>
      </CardHeader>
      <ul className="px-2 pb-1">
        {stores.map((store) => (
          <li key={store.id}>
            <Link
              to={`${page}#${storeAnchor(store.number)}`}
              className="block rounded-lg px-4 py-2.5 transition-colors hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              <StoreGoalMini store={store} />
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** Cel marży sklepu w jednej linii z paskiem: osiągnięta marża, % celu, kreska planu i różnica do planu. */
export function StoreGoalMini({ store, showName = true }: { store: StoreSummary; showName?: boolean }) {
  const overview = useSalesReportsOverview(store.id)
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const progress = useProfitTargetProgress(store.id, lastSaleDate ?? '', lastSaleDate != null)
  const name = showName ? <span className="truncate font-medium">{storeLabel(store)}</span> : null

  if (overview.isPending || (lastSaleDate && progress.isPending)) {
    return (
      <div className="space-y-2" aria-hidden>
        <div className="h-4 w-40 loading-shimmer rounded-md" />
        <div className="h-2 w-full loading-shimmer rounded-full" />
      </div>
    )
  }
  const data = progress.data
  const target = data?.target?.marginAfterLossesValue ?? null
  const achieved = data?.marginAfterLossesValue ?? null
  if (!lastSaleDate || overview.isError || progress.isError || !data || target == null || achieved == null) {
    const reason =
      overview.isError || progress.isError
        ? 'nie udało się wczytać'
        : !lastSaleDate
          ? 'brak wgranej sprzedaży'
          : `brak celu marży na ${data?.year ?? ''}`.trim()
    return (
      <div className="flex items-baseline justify-between gap-3 text-sm">
        {name}
        <span className="shrink-0 text-xs text-muted-foreground">{reason}</span>
      </div>
    )
  }

  const share = achieved / target
  const planned = yearShare(data.to)
  const difference = achieved - target * planned
  const ahead = difference >= 0
  return (
    <div className="space-y-1.5 tabular-nums">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        {name}
        <span
          className={cn(
            'shrink-0 rounded-md px-1.5 py-0.5 text-xs font-semibold',
            ahead
              ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-100'
              : 'bg-amber-200 text-amber-950 dark:bg-amber-950/60 dark:text-amber-100',
          )}
        >
          {ahead ? `+${money(difference)}` : `−${money(-difference)}`}
        </span>
      </div>
      <div
        role="meter"
        aria-label={`Marża po likwidacjach sklepu ${store.number} wobec celu ${data.year}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(share * 1000) / 10}
        aria-valuetext={`${percentFormat.format(share * 100)}% celu, plan ${percentFormat.format(planned * 100)}%, ${
          ahead ? `${money(difference)} przed planem` : `${money(-difference)} do planu`
        }`}
        className="relative h-2 w-full overflow-hidden rounded-full bg-muted"
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
          className="absolute inset-y-0 w-0.5 bg-foreground"
          style={{ left: `${Math.min(planned, 1) * 100}%` }}
        />
      </div>
      <div className="flex items-baseline justify-between gap-3 text-xs text-muted-foreground">
        <span>
          <span className="font-medium text-foreground">{money(achieved)}</span> · {percentFormat.format(share * 100)}%
          celu
        </span>
        <span>
          plan na {formatShortDate(data.to)}: {percentFormat.format(planned * 100)}%
        </span>
      </div>
    </div>
  )
}
