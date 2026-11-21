import { useEffect, useRef, useState } from 'react'
import { ChevronRightIcon } from 'lucide-react'
import { Link, useLocation, useNavigationType } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { canInStore, useCurrentUser } from '@/features/auth/api'
import { StoreResultsPanel } from '@/features/dashboard/StoreResultsPanel'
import { useSalesComparison, useSalesPromotionShare, useSalesReportsOverview } from '@/features/sales/api'
import { YEAR_AGO_DAYS, type Periods } from '@/features/sales/periods'
import { ComparisonTotalTiles } from '@/features/sales/SalesComparisonReport'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { addDays, formatLongDate } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { storeAnchor } from './paths'
import { useOpenInStore } from './store-jump'
import { StoreGoalMini } from './StoreGoalMini'
import { useResultStores } from './stores'

const NO_FILTER: string[] = []

const dayComparison = (date: string) => `/sprzedaz/porownanie?okres=dzien&data=${date}`

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Wyniki wszystkich sklepów konta (uprawnienie Wyniki sklepów w każdym z nich): pod sobą panel wyników z pulpitu i
 * kafelki porównania ostatniego dnia ze sprzedażą z tym samym dniem tygodnia rok temu. Klik w dzień, tydzień albo
 * kafelki otwiera porównanie sprzedaży w tym sklepie; „Wstecz” wraca tu i do sklepu sprzed przejścia. Po prawej
 * przyklejona lista sklepów do szybkiego skakania.
 */
export function StoreResultsPage() {
  // Stała kolejność z listy sklepów (bez bieżącego na górze): „Wstecz” przełącza sklep z powrotem, a przywrócone
  // miejsce strony nie może się przesunąć przez zmianę kolejności.
  const ordered = useResultStores()
  const current = useCurrentStore()
  const listRef = useRef<HTMLDivElement>(null)
  const active = useActiveSection(ordered)
  useHashTarget(listRef)

  const jump = (store: StoreSummary) => {
    const element = document.getElementById(storeAnchor(store.number))
    if (!element) return
    element.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' })
    window.history.replaceState(window.history.state, '', `#${storeAnchor(store.number)}`)
  }

  return (
    <>
      <PageHeader
        title="Wyniki sklepów"
        description="Tydzień wobec tych samych dni rok temu, cel marży i ostatni dzień ze sprzedażą w każdym sklepie. Kliknij dzień, tydzień albo kafelki, żeby otworzyć porównanie sprzedaży w tym sklepie."
      />

      {ordered.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Brak sklepów</CardTitle>
            <CardDescription>Żaden z Twoich sklepów nie pokazuje Ci wyników.</CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_15rem] lg:items-start lg:gap-6">
          {/* Na węższym ekranie lista sklepów to przyklejony pasek nad wynikami. */}
          <nav
            aria-label="Sklepy"
            className="sticky top-14 z-10 -mx-4 mb-4 flex gap-2 overflow-x-auto bg-background/80 px-4 py-2 backdrop-blur lg:hidden"
          >
            {ordered.map((store) => (
              <button
                key={store.id}
                type="button"
                onClick={() => jump(store)}
                aria-current={active === store.id ? 'location' : undefined}
                className={cn(
                  'shrink-0 rounded-full border px-3 py-1 text-sm font-medium transition-colors',
                  active === store.id ? 'border-foreground bg-foreground text-background' : 'bg-card hover:bg-muted',
                )}
              >
                {store.number}
              </button>
            ))}
          </nav>

          <div ref={listRef} className="space-y-12">
            {ordered.map((store) => (
              <StoreSection key={store.id} store={store} current={store.id === current?.id} />
            ))}
          </div>

          <nav aria-label="Sklepy" className="sticky top-20 hidden lg:block">
            <Card className="gap-1 py-3">
              <p className="px-4 pb-1 text-xs font-medium text-muted-foreground">Przejdź do sklepu</p>
              <ul className="px-2">
                {ordered.map((store) => (
                  <li key={store.id}>
                    <button
                      type="button"
                      onClick={() => jump(store)}
                      aria-current={active === store.id ? 'location' : undefined}
                      className={cn(
                        'w-full rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-muted/60',
                        'focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
                        active === store.id && 'bg-muted',
                      )}
                    >
                      <StoreGoalMini store={store} />
                    </button>
                  </li>
                ))}
              </ul>
            </Card>
          </nav>
        </div>
      )}
    </>
  )
}

function StoreSection({ store, current }: { store: StoreSummary; current: boolean }) {
  return (
    <section id={storeAnchor(store.number)} aria-label={storeLabel(store)} className="scroll-mt-20 space-y-3">
      <StoreResultsPanel
        storeId={store.id}
        storeNumber={store.number}
        title={current ? `${storeLabel(store)} · bieżący sklep` : storeLabel(store)}
      />
      <LastDayTiles store={store} />
    </section>
  )
}

/** Kafelki porównania ostatniego dnia ze sprzedażą (jak nad porównaniem sprzedaży) z tym samym dniem rok temu. */
function LastDayTiles({ store }: { store: StoreSummary }) {
  const overview = useSalesReportsOverview(store.id)
  const day = overview.data?.lastSaleDate
  if (!day) return null
  return <DayTiles store={store} day={day} />
}

function DayTiles({ store, day }: { store: StoreSummary; day: string }) {
  const me = useCurrentUser()
  const canCompare = canInStore(me, store.id, 'SALES')
  const { open, href, prepare } = useOpenInStore()
  const yearAgo = addDays(day, -YEAR_AGO_DAYS)
  const periods: Periods = { current: { from: day, to: day }, previous: { from: yearAgo, to: yearAgo } }
  const comparison = useSalesComparison(store.id, 'sales', periods, NO_FILTER, '')
  const promotions = useSalesPromotionShare(store.id, periods, NO_FILTER)
  const target = dayComparison(day)

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-1 text-sm">
        <p className="text-muted-foreground">
          Ostatni dzień: <span className="font-medium text-foreground">{formatLongDate(day)}</span> wobec{' '}
          {formatLongDate(yearAgo)}
        </p>
        {canCompare ? (
          <Link
            to={href(target, store.id)}
            onClick={() => prepare(store.id)}
            className="flex items-center gap-0.5 rounded-sm text-muted-foreground hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            Porównanie sprzedaży
            <ChevronRightIcon className="size-4" />
          </Link>
        ) : null}
      </div>
      {comparison.isError ? (
        <p className="px-1 text-sm text-muted-foreground">Nie udało się wczytać porównania dnia.</p>
      ) : (
        // Klik w kafelki to skrót do porównania (klawiaturą: link „Porównanie sprzedaży” wyżej).
        <div
          onClick={canCompare ? () => open(target, store.id) : undefined}
          title={canCompare ? 'Otwórz porównanie sprzedaży tego dnia' : undefined}
          className={cn(
            canCompare &&
              'cursor-pointer rounded-xl transition-transform duration-150 ease-out active:scale-[0.995] [&_[data-slot=card]]:transition-colors [&_[data-slot=card]]:hover:bg-muted/40',
          )}
        >
          <ComparisonTotalTiles
            data={comparison.data}
            promotions={promotions.data}
            promotionsFailed={promotions.isError}
            compare="year"
            basis="gross"
            className="md:grid-cols-3"
          />
        </div>
      )}
    </div>
  )
}

/** Sklep, którego sekcja jest teraz u góry ekranu (podświetlenie w liście sklepów). */
function useActiveSection(stores: StoreSummary[]) {
  const [active, setActive] = useState<string | null>(stores[0]?.id ?? null)
  const key = stores.map((store) => store.id).join(',')
  useEffect(() => {
    const ids = key ? key.split(',') : []
    const update = () => {
      let found = ids[0] ?? null
      for (const id of ids) {
        const store = stores.find((candidate) => candidate.id === id)
        const element = store && document.getElementById(storeAnchor(store.number))
        if (element && element.getBoundingClientRect().top <= 160) found = id
      }
      setActive(found)
    }
    update()
    window.addEventListener('scroll', update, { passive: true })
    window.addEventListener('resize', update)
    return () => {
      window.removeEventListener('scroll', update)
      window.removeEventListener('resize', update)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- sklepy wyznacza ich lista id
  }, [key])
  return active
}

/**
 * Wejście z kotwicą sklepu (np. z pulpitu): sekcje nad nim dociągają dane i rosną, więc sklep trzymamy u góry
 * ekranu, aż wszystko się wczyta albo użytkownik sam przewinie (najwyżej kilka sekund).
 */
function useHashTarget(listRef: React.RefObject<HTMLDivElement | null>) {
  const { hash } = useLocation()
  // Powrót „Wstecz” przywraca dokładne miejsce strony (ScrollRestoration), więc wtedy nie przewijamy do sklepu.
  const pop = useNavigationType() === 'POP'
  useEffect(() => {
    if (pop) return
    const id = decodeURIComponent(hash.slice(1))
    const list = listRef.current
    const element = id ? document.getElementById(id) : null
    if (!list || !element) return
    const pin = () => element.scrollIntoView({ block: 'start' })
    pin()
    const observer = new ResizeObserver(pin)
    observer.observe(list)
    const stop = () => {
      observer.disconnect()
      window.removeEventListener('wheel', stop)
      window.removeEventListener('touchstart', stop)
      window.removeEventListener('keydown', stop)
      clearTimeout(timer)
    }
    const timer = setTimeout(stop, 4000)
    window.addEventListener('wheel', stop, { passive: true })
    window.addEventListener('touchstart', stop, { passive: true })
    window.addEventListener('keydown', stop)
    return stop
  }, [hash, listRef, pop])
}
