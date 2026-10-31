import { Fragment, type ReactNode, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

import { SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { type ReportKind, type SalesChartTarget, useSalesArticle, useSalesHistory } from '@/features/sales/api'
import { type DateRange, formatRange } from '@/features/sales/periods'
import { addDays, parseIsoDate } from '@/lib/dates'
import type { SalesArticle, SalesFigures, SalesHistory } from '@/lib/types'
import { cn } from '@/lib/utils'

type ChartRange = 'month' | 'quarter' | 'half' | 'year'
type Metric = 'price' | 'quantity' | 'sale' | 'cost'
type SaleBasis = 'gross' | 'net'

/**
 * Zakresy wykresu kończą się na ostatnim dniu ze sprzedażą. Dłuższe niż kwartał rysujemy tygodniami (liczonymi
 * wstecz od ostatniego dnia, więc każdy słupek to pełne 7 dni), bo 182 czy 364 słupki dnia są nieczytelne.
 * Rok to 52 tygodnie, jak porównanie rok do roku.
 */
const RANGES: Record<ChartRange, { label: string; days: number; step: 1 | 7 }> = {
  month: { label: 'Miesiąc', days: 30, step: 1 },
  quarter: { label: '3 miesiące', days: 91, step: 1 },
  half: { label: '6 miesięcy', days: 182, step: 7 },
  year: { label: 'Rok', days: 364, step: 7 },
}

/**
 * Artykuł: najpierw średnia cena sprzedaży z ilością, potem to, co przy nomenklaturze: ilość i sprzedaż z % marży.
 * Koszty garmażu: marża i koszt reprezentacji są tylko przy całym garmażu (rejon 36), niżej kosztu nie da się
 * przypisać, więc sprzedaż jest bez marży.
 */
function metricsFor(kind: ReportKind, target: SalesChartTarget): { value: Metric; label: string }[] {
  const metrics: { value: Metric; label: string }[] = [
    { value: 'quantity', label: 'Ilość' },
    { value: 'sale', label: hasMargin(kind, target) ? 'Sprzedaż i marża %' : 'Sprzedaż' },
  ]
  if (target.kind === 'article') metrics.unshift({ value: 'price', label: 'Cena i ilość' })
  if (kind === 'deli' && isDeliTotal(target)) metrics.push({ value: 'cost', label: 'Koszt reprezentacji' })
  return metrics
}

const isDeliTotal = (target: SalesChartTarget) => target.kind === 'group' && target.code === '36'
const hasMargin = (kind: ReportKind, target: SalesChartTarget) => kind === 'sales' || isDeliTotal(target)

/** Słupek wykresu: dzień albo tydzień z sumami sprzedaży. */
type Bar = { from: string; to: string; figures: SalesFigures }

/** Seria wykresu: słupki albo linia na prawej osi. */
type Series = {
  label: string
  value: (figures: SalesFigures) => number | null
  format: (value: number) => string
  unit: string
}

/** Średnia cena brutto sztuki (kg), jak cena na półce; dzień bez sprzedaży jej nie ma. */
const averagePrice = (figures: SalesFigures) => (figures.quantity > 0 ? figures.saleValueGross / figures.quantity : null)

const marginPercent = (figures: SalesFigures) =>
  figures.marginValue != null && figures.saleValueNet !== 0 ? (figures.marginValue / figures.saleValueNet) * 100 : null

/**
 * Okno z wykresem sprzedaży wiersza raportu. Przy artykule na górze jego bieżące dane z kartoteki (stan, rotacja,
 * ceny), przy pozycji nomenklatury sam wykres. Domyślnie ostatni miesiąc, najdłużej rok.
 */
export function SalesChartDialog({
  storeId,
  kind,
  target,
  filter,
  basis,
  lastSaleDate,
  onClose,
}: {
  storeId: string
  kind: ReportKind
  target: SalesChartTarget | null
  filter: string[]
  basis: SaleBasis
  lastSaleDate: string
  onClose: () => void
}) {
  // Ostatni cel zostaje w oknie na czas animacji zamykania.
  const [shown, setShown] = useState(target)
  if (target && target !== shown) setShown(target)

  return (
    <Dialog open={target != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-5xl">
        {shown && (
          <ChartContent
            key={chartKey(shown)}
            storeId={storeId}
            kind={kind}
            target={shown}
            filter={filter}
            basis={basis}
            lastSaleDate={lastSaleDate}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

const chartKey = (target: SalesChartTarget) =>
  target.kind === 'article' ? `a:${target.itm8}` : `g:${target.unassigned ? 'brak' : target.code}`

function ChartContent({
  storeId,
  kind,
  target,
  filter,
  basis,
  lastSaleDate,
}: {
  storeId: string
  kind: ReportKind
  target: SalesChartTarget
  filter: string[]
  basis: SaleBasis
  lastSaleDate: string
}) {
  const metrics = metricsFor(kind, target)
  const [rangeKey, setRangeKey] = useState<ChartRange>('month')
  const [metric, setMetric] = useState<Metric>(metrics[0].value)
  const range: DateRange = {
    from: addDays(lastSaleDate, -(RANGES[rangeKey].days - 1)),
    to: lastSaleDate,
  }
  const history = useSalesHistory(storeId, kind, target, range, filter)
  const article = useSalesArticle(storeId, target.kind === 'article' ? target.itm8 : null)
  const showMargin = hasMargin(kind, target)

  const quantity: Series = { label: 'Ilość', value: (f) => f.quantity, format: formatQuantity, unit: 'ilość' }
  const sale: Series = {
    label: basis === 'gross' ? 'Sprzedaż brutto' : 'Sprzedaż netto',
    value: (f) => (basis === 'gross' ? f.saleValueGross : f.saleValueNet),
    format: formatZloty,
    unit: 'zł',
  }
  const cost: Series = { label: 'Koszt reprezentacji', value: (f) => f.costValue ?? 0, format: formatZloty, unit: 'zł' }
  const price: Series = { label: 'Średnia cena brutto', value: averagePrice, format: formatZloty, unit: 'zł' }
  const margin: Series = { label: 'Marża', value: marginPercent, format: formatPercentValue, unit: '%' }

  const bar = metric === 'sale' ? sale : metric === 'cost' ? cost : quantity
  const line = metric === 'price' ? price : metric === 'sale' && showMargin ? margin : null

  const data = history.data
  const step = RANGES[rangeKey].step
  const bars = data ? toBars(data, step) : []
  const days = data?.days.length ?? 0
  const total = data ? (bar.value(data.total) ?? 0) : 0
  const lineTotal = data && line ? line.value(data.total) : null

  return (
    <>
      <DialogHeader className="pr-8">
        <DialogTitle className="text-lg leading-snug">{title(target, article.data)}</DialogTitle>
        <DialogDescription>{subtitle(target, article.data)}</DialogDescription>
      </DialogHeader>

      {target.kind === 'article' && <ArticleFacts article={article.data} isPending={article.isPending} history={data} />}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <SegmentedField label="Co pokazać" options={metrics} value={metric} onChange={setMetric} />
        <SegmentedField
          label="Okres wykresu"
          options={(Object.keys(RANGES) as ChartRange[]).map((key) => ({
            value: key,
            label: RANGES[key].label,
          }))}
          value={rangeKey}
          onChange={setRangeKey}
        />
      </div>

      <div className={cn('grid gap-4 transition-opacity duration-150', history.isPlaceholderData && 'opacity-60')}>
        <div className="grid gap-2">
          <p className="text-sm text-muted-foreground">{formatRange(range)}</p>
          {data && (
            <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border bg-border sm:grid-cols-4">
              <Stat label={`${bar.label} razem`}>{bar.format(total)}</Stat>
              <Stat label="Średnio dziennie">{bar.format(days > 0 ? total / days : 0)}</Stat>
              {line && (
                <Stat label={line === price ? 'Średnia cena w okresie' : 'Marża % w okresie'} accent>
                  {lineTotal == null ? '—' : line.format(lineTotal)}
                </Stat>
              )}
              {line === price && <Stat label="Sprzedaż brutto razem">{formatZloty(data.total.saleValueGross)}</Stat>}
              {line === margin && data.total.marginValue != null && (
                <Stat label="Marża razem">{formatZloty(data.total.marginValue)}</Stat>
              )}
            </dl>
          )}
        </div>

        {history.isError && !data ? (
          <p className="py-16 text-center text-sm text-muted-foreground">Nie udało się wczytać sprzedaży.</p>
        ) : !data ? (
          <div aria-hidden className="h-80 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : (
          <BarChart bars={bars} bar={bar} line={line} basis={basis} showMargin={showMargin} step={step} />
        )}
      </div>
    </>
  )
}

function Stat({ label, accent, children }: { label: string; accent?: boolean; children: ReactNode }) {
  return (
    <div className="bg-card px-4 py-2.5">
      <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {accent && <span aria-hidden className="size-2 rounded-full bg-amber-500 dark:bg-amber-400" />}
        {label}
      </dt>
      <dd className="text-base font-semibold tabular-nums">{children}</dd>
    </div>
  )
}

function title(target: SalesChartTarget, article: SalesArticle | null | undefined) {
  if (target.kind === 'group') return target.name
  return article?.name ?? target.name ?? `ITM8 ${target.itm8}`
}

function subtitle(target: SalesChartTarget, article: SalesArticle | null | undefined) {
  if (target.kind === 'group') return target.levelLabel
  const ean = article?.ean ?? target.ean
  return [`ITM8 ${target.itm8}`, ean && `EAN ${ean}`, article?.brand ?? target.brand].filter(Boolean).join(' · ')
}

/** Bieżące dane artykułu z kartoteki: stan i rotacja z ostatniego raportu kartoteki, ceny, dostawca, nomenklatura. */
function ArticleFacts({
  article,
  isPending,
  history,
}: {
  article: SalesArticle | null | undefined
  isPending: boolean
  history: SalesHistory | undefined
}) {
  if (isPending) return <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
  if (!article) {
    return (
      <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
        Artykułu nie ma w kartotece sklepu, więc nie znamy jego stanu ani cen. Wykres pokazuje zapisaną sprzedaż.
      </p>
    )
  }

  // Na ile dni wystarczy stan przy średniej sprzedaży z okresu wykresu.
  const dailyQuantity = history && history.days.length > 0 ? history.total.quantity / history.days.length : 0
  const coverDays = article.stock != null && article.stock > 0 && dailyQuantity > 0 ? article.stock / dailyQuantity : null
  const path = article.nomenclaturePath.map((item) => item.name.replace(/^\d+_/, '')).join(' › ')

  return (
    <div className="grid gap-3">
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border bg-border sm:grid-cols-4">
        <Fact label="Stan">
          <span className={cn(article.stock != null && article.stock < 0 && 'text-destructive')}>
            {article.stock == null ? '—' : formatQuantity(article.stock)}
          </span>
          {coverDays != null && (
            <span className="block text-xs font-normal text-muted-foreground">
              na ok. {coverDays < 1 ? 'mniej niż dzień' : `${dayFormat.format(coverDays)} dni sprzedaży`}
            </span>
          )}
        </Fact>
        <Fact label="Rotacja">{article.rotation == null ? '—' : formatQuantity(article.rotation)}</Fact>
        <Fact label="Cena sprzedaży">{article.salePrice == null ? '—' : `${formatMoney(article.salePrice)} zł`}</Fact>
        <Fact label="Cena zakupu">{article.purchasePrice == null ? '—' : `${formatPrice(article.purchasePrice)} zł`}</Fact>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
        {article.supplier && <Detail label="Dostawca">{article.supplier}</Detail>}
        {path && <Detail label="Nomenklatura">{path}</Detail>}
        {article.caseSize != null && <Detail label="W kartonie">{formatQuantity(article.caseSize)}</Detail>}
        {article.active === false && <Detail label="Status">nieaktywny w kartotece</Detail>}
      </dl>
      <ReportFreshness generatedAt={article.reportGeneratedAt} className="justify-self-start" />
    </div>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="bg-card px-4 py-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{children}</p>
    </div>
  )
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </>
  )
}

/** Dni albo tygodnie liczone wstecz od ostatniego dnia, żeby ostatni słupek był pełny. */
function toBars(history: SalesHistory, step: 1 | 7): Bar[] {
  const bars: Bar[] = []
  for (let end = history.days.length; end > 0; end -= step) {
    const days = history.days.slice(Math.max(0, end - step), end)
    bars.unshift({ from: days[0].date, to: days.at(-1)!.date, figures: sumFigures(days.map((day) => day.figures)) })
  }
  return bars
}

function sumFigures(list: SalesFigures[]): SalesFigures {
  const sum = (pick: (figures: SalesFigures) => number) => list.reduce((total, figures) => total + pick(figures), 0)
  const sumNullable = (pick: (figures: SalesFigures) => number | null) =>
    list.some((figures) => pick(figures) != null) ? sum((figures) => pick(figures) ?? 0) : null
  return {
    quantity: sum((figures) => figures.quantity),
    costValue: sumNullable((figures) => figures.costValue),
    saleValueGross: sum((figures) => figures.saleValueGross),
    saleValueNet: sum((figures) => figures.saleValueNet),
    marginValue: sumNullable((figures) => figures.marginValue),
  }
}

const CHART_HEIGHT = 300
const PADDING = { top: 24, bottom: 28, left: 56 }

/**
 * Słupki jednej serii w SVG i opcjonalnie linia drugiej serii na prawej osi (cena, % marży), z własną skalą bez
 * zera, bo cena czy marża wahają się w wąskim przedziale. Cicha siatka, oś zera, przerywana linia średniej słupków,
 * przy dniach podział na tygodnie i podpisane poniedziałki, dzień bez sprzedaży jako kreska na osi. Podpowiedź po
 * najechaniu (pole trafienia to cała kolumna) pokazuje wszystkie liczby słupka. Szerokość z kontenera, bo tekst
 * w SVG nie może się rozciągać.
 */
function BarChart({
  bars,
  bar: barSeries,
  line,
  basis,
  showMargin,
  step,
}: {
  bars: Bar[]
  bar: Series
  line: Series | null
  basis: SaleBasis
  showMargin: boolean
  step: 1 | 7
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [hovered, setHovered] = useState<number | null>(null)
  // Położenie wykresu na ekranie przy najechaniu, do podpowiedzi w portalu.
  const [origin, setOrigin] = useState<DOMRect | null>(null)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const paddingRight = line ? 56 : 12
  const values = bars.map((bar) => barSeries.value(bar.figures) ?? 0)
  const ticks = niceTicks(Math.min(0, ...values), Math.max(0, ...values))
  const low = ticks[0]
  const high = ticks.at(-1)!
  const plotWidth = Math.max(0, width - PADDING.left - paddingRight)
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom
  const plotRight = PADDING.left + plotWidth
  const y = (value: number) => PADDING.top + ((high - value) / (high - low || 1)) * plotHeight
  const band = bars.length > 0 ? plotWidth / bars.length : 0
  // Odstęp między słupkami rośnie z szerokością pasma (do 6px), słupek nie węższy niż 1px.
  const barWidth = Math.max(1, band - Math.max(1, Math.min(6, band * 0.25)))
  const left = (index: number) => PADDING.left + index * band
  const center = (index: number) => left(index) + band / 2
  const average = values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : 0

  const lineValues = line ? bars.map((bar) => line.value(bar.figures)) : []
  const defined = lineValues.filter((value): value is number => value != null)
  const lineTicks = defined.length > 0 ? lineRangeTicks(Math.min(...defined), Math.max(...defined)) : []
  const lineLow = lineTicks[0] ?? 0
  const lineHigh = lineTicks.at(-1) ?? 1
  const lineY = (value: number) => PADDING.top + ((lineHigh - value) / (lineHigh - lineLow || 1)) * plotHeight

  // Przy dniach podpisujemy poniedziałki (co któryś, gdy się nie mieszczą), przy tygodniach co któryś słupek.
  const maxLabels = Math.max(1, Math.floor(plotWidth / 72))
  const mondays = step === 1 ? bars.flatMap((bar, index) => (isMonday(bar.from) ? [index] : [])) : []
  const candidates = step === 1 ? mondays : bars.map((_, index) => index)
  const every = Math.max(1, Math.ceil(candidates.length / maxLabels))
  const labelled = new Set(candidates.filter((_, position) => position % every === 0))

  const active = hovered == null ? null : bars[hovered]
  const activeLine = hovered == null ? null : lineValues[hovered]
  const activeTop =
    hovered == null ? 0 : Math.min(y(Math.max(0, values[hovered])), activeLine == null ? Infinity : lineY(activeLine))

  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="size-2.5 rounded-sm bg-sky-600 dark:bg-sky-400" />
          {barSeries.label} (oś z lewej)
        </span>
        {line && (
          <span className="flex items-center gap-1.5">
            <span aria-hidden className="h-0.5 w-4 rounded-full bg-amber-500 dark:bg-amber-400" />
            {line.label} (oś z prawej)
          </span>
        )}
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="w-4 border-t border-dashed border-foreground/60" />
          średnio na {step === 1 ? 'dzień' : 'tydzień'}
        </span>
      </div>

      <div ref={ref} className="relative" onMouseLeave={() => setHovered(null)}>
        {width > 0 && (
          <svg
            width={width}
            height={CHART_HEIGHT}
            role="img"
            aria-label={`${barSeries.label}${line ? ` i ${line.label}` : ''}: wykres ${step === 1 ? 'dzienny' : 'tygodniowy'}`}
          >
            {mondays
              .filter((index) => index > 0)
              .map((index) => (
                <line
                  key={`week${index}`}
                  x1={left(index)}
                  x2={left(index)}
                  y1={PADDING.top}
                  y2={CHART_HEIGHT - PADDING.bottom + 6}
                  className="stroke-border"
                />
              ))}
            {ticks.map((tick) => (
              <g key={tick}>
                <line
                  x1={PADDING.left}
                  x2={plotRight}
                  y1={y(tick)}
                  y2={y(tick)}
                  className={tick === 0 ? 'stroke-foreground/30' : 'stroke-border'}
                  strokeDasharray={tick === 0 ? undefined : '2 4'}
                />
                <text
                  x={PADDING.left - 8}
                  y={y(tick)}
                  dy="0.32em"
                  textAnchor="end"
                  className="fill-muted-foreground text-[0.6875rem] tabular-nums"
                >
                  {compactFormat.format(tick)}
                </text>
              </g>
            ))}
            <text
              x={PADDING.left - 8}
              y={PADDING.top - 12}
              textAnchor="end"
              className="fill-sky-700 text-[0.6875rem] font-medium dark:fill-sky-300"
            >
              {barSeries.unit}
            </text>

            {hovered != null && (
              <rect x={left(hovered)} y={PADDING.top} width={band} height={plotHeight} className="fill-foreground/5" />
            )}

            {bars.map((bar, index) => {
              const x = left(index) + (band - barWidth) / 2
              const value = values[index]
              return (
                <g key={bar.from}>
                  {value === 0 ? (
                    <rect x={x} y={y(0) - 2} width={barWidth} height={2} rx={1} className="fill-foreground/25" />
                  ) : (
                    <path
                      d={barPath(x, y(0), barWidth, y(value) - y(0))}
                      className={cn(
                        'transition-opacity duration-100',
                        value < 0 ? 'fill-rose-500 dark:fill-rose-400' : 'fill-sky-600 dark:fill-sky-400',
                        hovered != null && hovered !== index && 'opacity-55',
                      )}
                    />
                  )}
                  {labelled.has(index) && (
                    <text
                      x={step === 1 ? left(index) + 3 : center(index)}
                      y={CHART_HEIGHT - 8}
                      textAnchor={step === 1 ? 'start' : 'middle'}
                      className="fill-muted-foreground text-[0.6875rem]"
                    >
                      {(step === 1 ? mondayDate : axisDate).format(parseIsoDate(bar.from))}
                    </text>
                  )}
                </g>
              )
            })}

            {average > 0 && (
              <g className="pointer-events-none">
                <line
                  x1={PADDING.left}
                  x2={plotRight}
                  y1={y(average)}
                  y2={y(average)}
                  className="stroke-foreground/60"
                  strokeDasharray="5 4"
                />
                <text
                  x={plotRight - 4}
                  y={y(average) - 5}
                  textAnchor="end"
                  className="fill-foreground text-[0.6875rem] font-medium tabular-nums [paint-order:stroke] stroke-card stroke-[3px]"
                >
                  śr. {compactFormat.format(average)}
                </text>
              </g>
            )}

            {line && lineTicks.length > 0 && (
              <g className="pointer-events-none">
                {lineTicks.map((tick) => (
                  <text
                    key={tick}
                    x={plotRight + 8}
                    y={lineY(tick)}
                    dy="0.32em"
                    className="fill-amber-700 text-[0.6875rem] tabular-nums dark:fill-amber-300"
                  >
                    {lineTickFormat.format(tick)}
                  </text>
                ))}
                <text
                  x={plotRight + 8}
                  y={PADDING.top - 12}
                  className="fill-amber-700 text-[0.6875rem] font-medium dark:fill-amber-300"
                >
                  {line.unit}
                </text>
                <path
                  d={linePath(lineValues, center, lineY)}
                  fill="none"
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  className="stroke-amber-500 dark:stroke-amber-400"
                />
                {lineValues.map((value, index) =>
                  value == null || (bars.length > 60 && hovered !== index) ? null : (
                    <circle
                      key={bars[index].from}
                      cx={center(index)}
                      cy={lineY(value)}
                      r={hovered === index ? 4.5 : 3}
                      strokeWidth={2}
                      className="fill-card stroke-amber-500 dark:stroke-amber-400"
                    />
                  ),
                )}
              </g>
            )}

            {bars.map((bar, index) => (
              <rect
                key={`hit${bar.from}`}
                x={left(index)}
                y={PADDING.top}
                width={band}
                height={plotHeight}
                fill="transparent"
                onMouseEnter={() => {
                  setOrigin(ref.current?.getBoundingClientRect() ?? null)
                  setHovered(index)
                }}
              />
            ))}
          </svg>
        )}
        {/* Podpowiedź w portalu z position: fixed, więc może wyjść poza okno zamiast przewijać je w bok. */}
        {active &&
          hovered != null &&
          origin &&
          createPortal(
            <div
              className="pointer-events-none fixed z-100 grid w-max gap-1 rounded-lg bg-popover px-3 py-2 text-sm text-popover-foreground shadow-md ring-1 ring-foreground/10"
              style={{
                left: Math.min(Math.max(origin.left + center(hovered), 120), window.innerWidth - 120),
                top: Math.max(8, origin.top + activeTop - 10),
                transform: 'translate(-50%, -100%)',
              }}
            >
              <p className="text-xs text-muted-foreground">
                {step === 1 ? tooltipDate.format(parseIsoDate(active.from)) : formatRange({ from: active.from, to: active.to })}
              </p>
              <BarDetails figures={active.figures} basis={basis} showMargin={showMargin} />
            </div>,
            document.body,
          )}
      </div>
      <p className="text-xs text-muted-foreground">
        {step === 1
          ? 'Słupek = dzień, pionowe linie oddzielają tygodnie (od poniedziałku), kreska na osi = dzień bez sprzedaży'
          : 'Słupek = 7 dni, do ostatniego dnia ze sprzedażą'}
      </p>
    </div>
  )
}

/** Wszystkie liczby słupka w podpowiedzi, bez względu na wybraną serię. */
function BarDetails({ figures, basis, showMargin }: { figures: SalesFigures; basis: SaleBasis; showMargin: boolean }) {
  if (figures.quantity === 0 && figures.saleValueGross === 0 && !figures.costValue) {
    return <p className="font-medium">Brak sprzedaży</p>
  }
  const price = averagePrice(figures)
  const margin = marginPercent(figures)
  const rows: [string, string][] = [
    ['Ilość', formatQuantity(figures.quantity)],
    [
      basis === 'gross' ? 'Sprzedaż brutto' : 'Sprzedaż netto',
      formatZloty(basis === 'gross' ? figures.saleValueGross : figures.saleValueNet),
    ],
  ]
  if (price != null) rows.push(['Średnia cena brutto', formatZloty(price)])
  if (figures.costValue != null && showMargin) rows.push(['Koszt', formatZloty(figures.costValue)])
  if (showMargin && figures.marginValue != null) {
    rows.push(['Marża', `${formatZloty(figures.marginValue)}${margin == null ? '' : ` · ${formatPercentValue(margin)}`}`])
  }
  return (
    <dl className="grid grid-cols-[auto_auto] gap-x-4 gap-y-0.5 tabular-nums">
      {rows.map(([label, value]) => (
        <Fragment key={label}>
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="text-right font-medium">{value}</dd>
        </Fragment>
      ))}
    </dl>
  )
}

/** Linia przez słupki z wartością; słupek bez wartości (brak sprzedaży) przerywa linię. */
function linePath(values: (number | null)[], x: (index: number) => number, y: (value: number) => number) {
  let path = ''
  let drawing = false
  values.forEach((value, index) => {
    if (value == null) {
      drawing = false
      return
    }
    path += `${drawing ? 'L' : 'M'}${x(index)},${y(value)} `
    drawing = true
  })
  return path
}

/** Podziałki prawej osi bez wymuszonego zera; przy jednej wartości przedział wokół niej. */
function lineRangeTicks(min: number, max: number) {
  if (min !== max) return niceTicks(min, max)
  const spread = Math.abs(min) * 0.1 || 1
  return niceTicks(min - spread, max + spread)
}

const isMonday = (iso: string) => parseIsoDate(iso).getDay() === 1

/** Słupek z zaokrąglonym końcem (4px) od strony wartości i płaską podstawą na osi zera. */
function barPath(x: number, base: number, width: number, height: number) {
  if (height === 0) return ''
  const radius = Math.min(4, width / 2, Math.abs(height))
  const top = base + height
  const direction = height < 0 ? 1 : -1
  return [
    `M${x},${base}`,
    `V${top + direction * radius}`,
    `Q${x},${top} ${x + radius},${top}`,
    `H${x + width - radius}`,
    `Q${x + width},${top} ${x + width},${top + direction * radius}`,
    `V${base}`,
    'Z',
  ].join(' ')
}

/** Okrągłe podziałki osi (1, 2, 2,5 albo 5 × 10^n) od {@code min} do {@code max}. */
function niceTicks(min: number, max: number): number[] {
  if (min === max) return [0, 1]
  const rough = (max - min) / 4
  const power = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 2.5, 5, 10].map((factor) => factor * power).find((candidate) => candidate >= rough)!
  const ticks: number[] = []
  for (let tick = Math.floor(min / step) * step; tick <= Math.ceil(max / step) * step + step / 2; tick += step) {
    ticks.push(Math.round(tick * 1e6) / 1e6)
  }
  return ticks
}

const percentFormat = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 1,
})
const formatZloty = (value: number) => `${formatMoney(value)} zł`
const formatPercentValue = (value: number) => `${percentFormat.format(value)}%`
const compactFormat = new Intl.NumberFormat('pl-PL', {
  notation: 'compact',
  maximumFractionDigits: 1,
})
const lineTickFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2 })
const dayFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })
const axisDate = new Intl.DateTimeFormat('pl-PL', {
  day: 'numeric',
  month: 'short',
})
const mondayDate = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'short' })
const tooltipDate = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
