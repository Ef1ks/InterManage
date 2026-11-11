import { useEffect, useId, useState } from 'react'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useSalesReportsOverview } from '@/features/sales/api'
import { formatShortDate, parseIsoDate } from '@/lib/dates'
import type { DashboardChartMetric, SalesHistory } from '@/lib/types'
import { cn } from '@/lib/utils'

import {
  changePercent,
  hasNoSales,
  metricValue,
  storeWindow,
  TREND_WEEKS,
  useDashboardSettings,
  useSaveDashboardSettings,
  useStoreHistory,
  weeklyChanges,
  type WeekChange,
} from './api'

const METRICS: { value: DashboardChartMetric; label: string }[] = [
  { value: 'SALES', label: 'Sprzedaż' },
  { value: 'MARGIN', label: 'Marża' },
  { value: 'QUANTITY', label: 'Ilość' },
]

const whole = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })
const compact = new Intl.NumberFormat('pl-PL', { notation: 'compact', maximumFractionDigits: 1 })
const percentFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 })
const weekdayFormat = new Intl.DateTimeFormat('pl-PL', { weekday: 'short' })
const dayMonth = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'short' })

const unit = (metric: DashboardChartMetric) => (metric === 'QUANTITY' ? 'szt.' : 'zł')
const formatValue = (metric: DashboardChartMetric, value: number) => `${whole.format(value)} ${unit(metric)}`
const formatTick = (metric: DashboardChartMetric, value: number) =>
  metric === 'QUANTITY' ? compact.format(value) : `${compact.format(value)} zł`
const formatChange = (change: number) => `${change >= 0 ? '+' : '−'}${percentFormat.format(Math.abs(change))}%`
const shortDay = (iso: string) => dayMonth.format(parseIsoDate(iso))

/**
 * Wyniki sklepu na pulpicie (na razie tylko właściciel): ostatnie 7 dni ze sprzedażą wobec tych samych dni tygodnia
 * rok temu, dwa wykresy nałożone na siebie, a pod nimi pasek zmiany rok do roku z 12 tygodni. Wybór miary jest
 * ustawieniem konta, wspólnym z aplikacją mobilną.
 */
export function StoreResultsPanel({ storeId, storeNumber }: { storeId: string; storeNumber: string }) {
  const overview = useSalesReportsOverview(storeId)
  const settings = useDashboardSettings()
  const save = useSaveDashboardSettings()
  const metric = settings.data?.salesChartMetric ?? 'SALES'
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const range = lastSaleDate ? storeWindow(lastSaleDate) : null
  const current = useStoreHistory(storeId, range?.current ?? null)
  const yearAgo = useStoreHistory(storeId, range?.yearAgo ?? null)

  let body
  if (overview.isPending || (range && (current.isPending || yearAgo.isPending))) {
    body = <div className="h-96 animate-pulse rounded-lg bg-muted" />
  } else if (overview.isError || current.isError || yearAgo.isError) {
    body = <p className="text-sm text-muted-foreground">Nie udało się wczytać sprzedaży.</p>
  } else if (!range || !current.data || !yearAgo.data) {
    body = <p className="text-sm text-muted-foreground">Sklep nie ma jeszcze wgranej sprzedaży.</p>
  } else {
    body = <Results metric={metric} current={current.data} yearAgo={yearAgo.data} />
  }

  return (
    <Card>
      <CardHeader className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-1">
          <CardTitle>Wyniki sklepu {storeNumber}</CardTitle>
          <CardDescription>Ostatnie 7 dni ze sprzedażą wobec tych samych dni tygodnia rok temu</CardDescription>
        </div>
        <div role="radiogroup" aria-label="Co pokazuje wykres" className="flex rounded-lg bg-muted p-0.5 text-sm">
          {METRICS.map((item) => (
            <button
              key={item.value}
              type="button"
              role="radio"
              aria-checked={item.value === metric}
              disabled={settings.isPending}
              onClick={() => save.mutate({ salesChartMetric: item.value })}
              className={cn(
                'rounded-md px-3 py-1.5 font-medium text-muted-foreground transition-[color,background-color,transform] duration-150 ease-out',
                'hover:text-foreground active:scale-[0.97] focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
                item.value === metric && 'bg-background text-foreground shadow-sm dark:bg-input/30',
              )}
            >
              {item.label}
            </button>
          ))}
        </div>
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  )
}

function Results({
  metric,
  current,
  yearAgo,
}: {
  metric: DashboardChartMetric
  current: SalesHistory
  yearAgo: SalesHistory
}) {
  const weeks = weeklyChanges(metric, current, yearAgo)
  const week = weeks[weeks.length - 1]
  const days = current.days.slice(-7)
  const daysBefore = yearAgo.days.slice(-7)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-x-10 gap-y-5">
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">
            {formatShortDate(week.from)} – {formatShortDate(week.to)}
          </p>
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <p className="text-5xl font-semibold tracking-tight">{formatValue(metric, week.now)}</p>
            {week.change != null ? <ChangeBadge change={week.change} /> : null}
          </div>
          <p className="text-sm text-muted-foreground">
            rok temu <span className="font-medium text-foreground/80">{formatValue(metric, week.before)}</span>
          </p>
          {week.change != null && week.skipped.length > 0 ? (
            <p className="text-xs text-muted-foreground">
              Zmiana bez dni bez sprzedaży (teraz albo rok temu): {week.skipped.map(shortDay).join(', ')}
            </p>
          ) : null}
        </div>
        <TrendStrip weeks={weeks} metric={metric} />
      </div>

      <div className="flex gap-5 text-sm text-muted-foreground" aria-hidden>
        <span className="flex items-center gap-2">
          <span className="h-3 w-2 rounded-t-[3px] bg-blue-600 dark:bg-blue-500" /> Ostatnie 7 dni
        </span>
        <span className="flex items-center gap-2">
          <span className="h-3 w-3.5 rounded-t-[3px] border-[1.5px] border-b-0 border-amber-600 bg-amber-600/15 dark:border-amber-700 dark:bg-amber-700/20" />
          Rok temu
        </span>
      </div>

      <DayChart metric={metric} days={days} daysBefore={daysBefore} />

      <table className="sr-only">
        <caption>{METRICS.find((item) => item.value === metric)?.label} dzień po dniu wobec roku temu</caption>
        <thead>
          <tr>
            <th>Dzień</th>
            <th>Ostatnie 7 dni</th>
            <th>Rok temu</th>
            <th>Zmiana</th>
          </tr>
        </thead>
        <tbody>
          {days.map((day, index) => {
            const now = metricValue(metric, day.figures)
            const before = metricValue(metric, daysBefore[index].figures)
            const change = changePercent(now, before)
            return (
              <tr key={day.date}>
                <td>{formatShortDate(day.date)}</td>
                <td>{hasNoSales(day.figures) ? 'brak danych' : formatValue(metric, now)}</td>
                <td>{formatValue(metric, before)}</td>
                <td>{change == null ? '–' : formatChange(change)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function ChangeBadge({ change }: { change: number }) {
  const up = change >= 0
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-sm font-semibold',
        up
          ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-100'
          : 'bg-red-200 text-red-950 dark:bg-red-950/60 dark:text-red-100',
      )}
    >
      <span aria-hidden>{up ? '▲' : '▼'}</span>
      {formatChange(change)}
      <span className="sr-only">rok do roku</span>
    </span>
  )
}

/** Wejście słupków: rosną od osi z małym przesunięciem; zmiana miary przesuwa je płynnie (tylko transform). */
function useEntered(ready: boolean) {
  const [entered, setEntered] = useState(false)
  const [settled, setSettled] = useState(false)
  useEffect(() => {
    if (!ready) return
    // Dwie klatki: najpierw przeglądarka maluje słupki pod osią, dopiero potem dostają docelowe położenie.
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => setEntered(true))
    })
    const timer = setTimeout(() => setSettled(true), 900)
    return () => {
      cancelAnimationFrame(frame)
      clearTimeout(timer)
    }
  }, [ready])
  return { entered, settled }
}

const BAR_TRANSITION = 'transform 480ms cubic-bezier(0.23, 1, 0.32, 1)'

const TOP = 14
const BASE = 234
const PLOT = BASE - TOP
const LEFT = 64
const AXIS_BAND = 56
const HEIGHT = BASE + AXIS_BAND

function DayChart({
  metric,
  days,
  daysBefore,
}: {
  metric: DashboardChartMetric
  days: SalesHistory['days']
  daysBefore: SalesHistory['days']
}) {
  const clipId = useId()
  const [box, WIDTH] = useWidth()
  const { entered, settled } = useEntered(WIDTH > 0)
  const [active, setActive] = useState<number | null>(null)
  const now = days.map((day) => Math.max(0, metricValue(metric, day.figures)))
  const before = daysBefore.map((day) => Math.max(0, metricValue(metric, day.figures)))
  const { max, ticks } = niceScale(Math.max(1, ...now, ...before))
  const slot = (WIDTH - LEFT) / 7
  const back = Math.min(46, slot * 0.6)
  const front = Math.min(24, slot * 0.3)
  const lift = (value: number) => (entered ? PLOT * (1 - value / max) : PLOT + 4)
  const barStyle = (value: number, index: number, extraDelay = 0) => ({
    transform: `translateY(${lift(value)}px)`,
    transition: BAR_TRANSITION,
    transitionDelay: settled ? '0ms' : `${index * 40 + extraDelay}ms`,
  })

  const tip = active == null ? null : { index: active, top: Math.min(lift(now[active]), lift(before[active])) + TOP }

  return (
    <div ref={box} className="relative" style={{ height: HEIGHT }} onMouseLeave={() => setActive(null)}>
      {WIDTH > 0 ? (
        <svg width={WIDTH} height={HEIGHT} className="overflow-visible" aria-hidden>
          <defs>
            <clipPath id={clipId}>
              <rect x={0} y={0} width={WIDTH} height={BASE} />
            </clipPath>
          </defs>

          {ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={LEFT}
                x2={WIDTH}
                y1={BASE - (tick / max) * PLOT}
                y2={BASE - (tick / max) * PLOT}
                className="stroke-border"
                strokeWidth={1}
                shapeRendering="crispEdges"
              />
              <text
                x={LEFT - 10}
                y={BASE - (tick / max) * PLOT + 4}
                textAnchor="end"
                className="fill-muted-foreground text-[12px] tabular-nums"
              >
                {formatTick(metric, tick)}
              </text>
            </g>
          ))}

          {days.map((day, index) => {
            const x = LEFT + index * slot
            const missing = hasNoSales(day.figures)
            const change = missing ? null : changePercent(now[index], before[index])
            return (
              <g key={day.date}>
                <rect
                  x={x + 2}
                  y={TOP - 6}
                  width={slot - 4}
                  height={HEIGHT - TOP}
                  rx={8}
                  className={cn(
                    'fill-muted transition-opacity duration-150',
                    active === index ? 'opacity-70' : 'opacity-0',
                  )}
                />
                <g clipPath={`url(#${clipId})`}>
                  {/* Rok temu: kontur z delikatnym wypełnieniem, w tle. Dolna krawędź chowa się pod osią. */}
                  <rect
                    x={x + (slot - back) / 2}
                    y={TOP}
                    width={back}
                    height={PLOT + 8}
                    rx={5}
                    strokeWidth={1.5}
                    className="fill-amber-600/15 stroke-amber-600 motion-reduce:!transition-none dark:fill-amber-700/20 dark:stroke-amber-700"
                    style={barStyle(before[index], index)}
                  />
                  {/* Ostatnie 7 dni: wąski pełny słupek z przodu, z 2 px przerwy w kolorze karty. */}
                  <rect
                    x={x + (slot - front) / 2}
                    y={TOP}
                    width={front}
                    height={PLOT + 8}
                    rx={4}
                    strokeWidth={2}
                    className="fill-blue-600 stroke-card motion-reduce:!transition-none dark:fill-blue-500"
                    style={barStyle(now[index], index, 60)}
                  />
                </g>
                <text
                  x={x + slot / 2}
                  y={BASE + 19}
                  textAnchor="middle"
                  className={cn(
                    'text-[13px]',
                    active === index ? 'fill-foreground font-semibold' : 'fill-muted-foreground',
                  )}
                >
                  {weekdayFormat.format(parseIsoDate(day.date))} {shortDay(day.date)}
                </text>
                <text
                  x={x + slot / 2}
                  y={BASE + 42}
                  textAnchor="middle"
                  className="text-[12px] font-medium tabular-nums"
                >
                  {missing ? (
                    <tspan className="fill-muted-foreground">brak danych</tspan>
                  ) : change == null ? (
                    <tspan className="fill-muted-foreground">–</tspan>
                  ) : (
                    <>
                      <tspan
                        className={
                          change >= 0 ? 'fill-emerald-600 dark:fill-emerald-400' : 'fill-red-600 dark:fill-red-400'
                        }
                      >
                        {change >= 0 ? '▲ ' : '▼ '}
                      </tspan>
                      <tspan className="fill-foreground/80">{formatChange(change)}</tspan>
                    </>
                  )}
                </text>
                <rect
                  x={x}
                  y={0}
                  width={slot}
                  height={HEIGHT}
                  fill="transparent"
                  onMouseEnter={() => setActive(index)}
                />
              </g>
            )
          })}
        </svg>
      ) : null}

      {tip && WIDTH > 0 ? (
        <div
          className="pointer-events-none absolute z-10 w-max -translate-x-1/2 -translate-y-full rounded-lg border bg-popover px-3 py-2 text-sm text-popover-foreground shadow-md"
          style={{
            left: `${((LEFT + (tip.index + 0.5) * slot) / WIDTH) * 100}%`,
            top: `calc(${(tip.top / HEIGHT) * 100}% - 10px)`,
          }}
        >
          <p className="font-medium">{formatShortDate(days[tip.index].date)}</p>
          <p className="flex items-center gap-2 tabular-nums">
            <span className="size-2 rounded-full bg-blue-600 dark:bg-blue-500" />
            {hasNoSales(days[tip.index].figures) ? 'brak danych' : formatValue(metric, now[tip.index])}
          </p>
          <p className="flex items-center gap-2 text-muted-foreground tabular-nums">
            <span className="size-2 rounded-full bg-amber-600 dark:bg-amber-700" />
            {formatValue(metric, before[tip.index])}
            <span className="text-xs">· {formatShortDate(daysBefore[tip.index].date)}</span>
          </p>
        </div>
      ) : null}
    </div>
  )
}

const STRIP_WIDTH = 264
const STRIP_HEIGHT = 40

/** Mały pasek trendu: zmiana rok do roku każdego z ostatnich 12 tygodni, bieżący wyróżniony. */
function TrendStrip({ weeks, metric }: { weeks: WeekChange[]; metric: DashboardChartMetric }) {
  const [active, setActive] = useState<number | null>(null)
  const changes = weeks.map((week) => week.change ?? 0)
  const extent = Math.max(5, ...changes.map(Math.abs))
  // Oś zera w połowie tylko wtedy, gdy są tygodnie na minusie i na plusie; inaczej słupki mają całą wysokość.
  const anyDown = changes.some((change) => change < 0)
  const anyUp = changes.some((change) => change > 0)
  const middle = anyDown && anyUp ? STRIP_HEIGHT / 2 : anyDown ? 1 : STRIP_HEIGHT - 1
  const room = anyDown && anyUp ? STRIP_HEIGHT / 2 - 3 : STRIP_HEIGHT - 4
  const slot = STRIP_WIDTH / weeks.length
  const bar = Math.min(12, slot - 6)
  const shown = active ?? weeks.length - 1
  const week = weeks[shown]
  const growing = weeks.filter((item) => (item.change ?? 0) > 0).length

  return (
    <div className="w-full max-w-[17rem] space-y-1.5" onMouseLeave={() => setActive(null)}>
      <div className="flex items-baseline justify-between gap-3 text-xs">
        <span className="text-muted-foreground">Rok do roku, {TREND_WEEKS} tygodni</span>
        <span className="font-medium tabular-nums">
          {growing}/{weeks.length} na plus
        </span>
      </div>
      <svg
        viewBox={`0 0 ${STRIP_WIDTH} ${STRIP_HEIGHT}`}
        className="w-full"
        role="img"
        aria-label={`Zmiana rok do roku w ostatnich ${weeks.length} tygodniach, ${growing} na plus`}
      >
        <line
          x1={0}
          x2={STRIP_WIDTH}
          y1={middle}
          y2={middle}
          className="stroke-border"
          strokeWidth={1}
          shapeRendering="crispEdges"
        />
        {weeks.map((item, index) => {
          const value = item.change ?? 0
          const length = Math.max(item.change == null ? 0 : 2, (Math.abs(value) / extent) * room)
          const latest = index === weeks.length - 1
          return (
            <g key={item.from} onMouseEnter={() => setActive(index)}>
              <rect x={index * slot} y={0} width={slot} height={STRIP_HEIGHT} fill="transparent" />
              <rect
                x={index * slot + (slot - bar) / 2}
                y={value >= 0 ? middle - length : middle}
                width={bar}
                height={length}
                rx={2}
                className={cn(
                  'transition-[fill] duration-150',
                  latest || active === index ? 'fill-blue-600 dark:fill-blue-500' : 'fill-muted-foreground/35',
                )}
              />
            </g>
          )
        })}
      </svg>
      <p className="text-xs text-muted-foreground tabular-nums">
        {shortDay(week.from)} – {shortDay(week.to)}:{' '}
        <span className="font-medium text-foreground">
          {week.change == null ? 'brak danych' : formatChange(week.change)}
        </span>
        {week.change != null && week.skipped.length > 0 ? ` (bez ${week.skipped.map(shortDay).join(', ')})` : ''} ·{' '}
        {formatTick(metric, week.now)}
      </p>
    </div>
  )
}

/**
 * Podziałka osi: krok 1, 2, 2,5 albo 5 razy potęga dziesięciu, tak żeby wyszły 3-4 przedziały, a góra osi stała tuż
 * nad najwyższym słupkiem (bez pustego zapasu).
 */
function niceScale(value: number) {
  const rough = value / 3
  const power = 10 ** Math.floor(Math.log10(rough))
  const step = ([1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= rough) ?? 10) * power
  const max = Math.ceil(value / step) * step
  const ticks: number[] = []
  for (let tick = 0; tick <= max + step / 2; tick += step) ticks.push(tick)
  return { max, ticks }
}

/** Szerokość kontenera w pikselach: wykres rysuje się w prawdziwych pikselach, więc tekst nie rośnie z kartą. */
function useWidth() {
  const [element, setElement] = useState<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.round(entry.contentRect.width)))
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [setElement, width] as const
}
