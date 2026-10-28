import { type ReactNode, useEffect, useRef, useState } from 'react'

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
type Metric = 'sale' | 'quantity' | 'cost' | 'margin'
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

const METRICS: { value: Metric; label: string }[] = [
  { value: 'sale', label: 'Sprzedaż' },
  { value: 'quantity', label: 'Ilość' },
  { value: 'margin', label: 'Marża' },
]

/**
 * Koszty garmażu: przy całym garmażu (rejon 36) koszt protokołów reprezentacji i marża, niżej kosztu nie da się
 * przypisać, więc zostaje sprzedaż i ilość.
 */
const DELI_METRICS: { value: Metric; label: string }[] = [
  { value: 'sale', label: 'Sprzedaż' },
  { value: 'quantity', label: 'Ilość' },
  { value: 'cost', label: 'Koszt reprezentacji' },
  { value: 'margin', label: 'Marża' },
]
const DELI_GROUP_METRICS = DELI_METRICS.slice(0, 2)

function metricsFor(kind: ReportKind, target: SalesChartTarget) {
  if (kind === 'sales') return METRICS
  return target.kind === 'group' && target.code === '36' ? DELI_METRICS : DELI_GROUP_METRICS
}

/** Słupek wykresu: dzień albo tydzień. */
type Bar = { from: string; to: string; value: number }

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
      <DialogContent className="gap-5 p-6 sm:max-w-4xl">
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
  const [rangeKey, setRangeKey] = useState<ChartRange>('month')
  const [metric, setMetric] = useState<Metric>('sale')
  const range: DateRange = {
    from: addDays(lastSaleDate, -(RANGES[rangeKey].days - 1)),
    to: lastSaleDate,
  }
  const history = useSalesHistory(storeId, kind, target, range, filter)
  const metrics = metricsFor(kind, target)
  const article = useSalesArticle(storeId, target.kind === 'article' ? target.itm8 : null)

  const value = (figures: SalesFigures) =>
    metric === 'quantity'
      ? figures.quantity
      : metric === 'margin'
        ? (figures.marginValue ?? 0)
        : metric === 'cost'
          ? (figures.costValue ?? 0)
          : basis === 'gross'
            ? figures.saleValueGross
            : figures.saleValueNet
  const format = metric === 'quantity' ? formatQuantity : formatZloty
  const metricLabel =
    metric === 'quantity'
      ? 'Ilość'
      : metric === 'margin'
        ? 'Marża'
        : metric === 'cost'
          ? 'Koszt reprezentacji'
          : basis === 'gross'
            ? 'Sprzedaż brutto'
            : 'Sprzedaż netto'

  const data = history.data
  const bars = data ? toBars(data, RANGES[rangeKey].step, value) : []
  const days = data?.days.length ?? 0

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

      <div className={cn('grid gap-3 transition-opacity duration-150', history.isPlaceholderData && 'opacity-60')}>
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 text-sm tabular-nums">
          <p className="text-muted-foreground">{formatRange(range)}</p>
          {data && (
            <>
              <p>
                <span className="text-muted-foreground">{metricLabel} razem </span>
                <span className="font-medium">{format(value(data.total))}</span>
              </p>
              <p>
                <span className="text-muted-foreground">średnio dziennie </span>
                <span className="font-medium">{format(value(data.total) / days)}</span>
              </p>
              {metric === 'margin' && data.total.marginValue != null && data.total.saleValueNet !== 0 && (
                <p>
                  <span className="text-muted-foreground">marża </span>
                  <span className="font-medium">
                    {percentFormat.format((data.total.marginValue / data.total.saleValueNet) * 100)}%
                  </span>
                </p>
              )}
            </>
          )}
        </div>

        {history.isError && !data ? (
          <p className="py-16 text-center text-sm text-muted-foreground">Nie udało się wczytać sprzedaży.</p>
        ) : !data ? (
          <div aria-hidden className="h-64 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : (
          <BarChart bars={bars} format={format} label={metricLabel} step={RANGES[rangeKey].step} />
        )}
      </div>
    </>
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
function toBars(history: SalesHistory, step: 1 | 7, value: (figures: SalesFigures) => number): Bar[] {
  const bars: Bar[] = []
  for (let end = history.days.length; end > 0; end -= step) {
    const days = history.days.slice(Math.max(0, end - step), end)
    bars.unshift({
      from: days[0].date,
      to: days.at(-1)!.date,
      value: days.reduce((sum, day) => sum + value(day.figures), 0),
    })
  }
  return bars
}

const CHART_HEIGHT = 256
const PADDING = { top: 12, right: 8, bottom: 28, left: 64 }

/**
 * Słupki jednej serii w SVG: cicha siatka, oś zera, zaokrąglony koniec słupka od strony wartości, podpowiedź po
 * najechaniu (pole trafienia to cała kolumna). Szerokość z kontenera, bo tekst w SVG nie może się rozciągać.
 */
function BarChart({ bars, format, label, step }: { bars: Bar[]; format: (value: number) => string; label: string; step: 1 | 7 }) {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [hovered, setHovered] = useState<number | null>(null)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const values = bars.map((bar) => bar.value)
  const ticks = niceTicks(Math.min(0, ...values), Math.max(0, ...values))
  const low = ticks[0]
  const high = ticks.at(-1)!
  const plotWidth = Math.max(0, width - PADDING.left - PADDING.right)
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom
  const y = (value: number) => PADDING.top + ((high - value) / (high - low || 1)) * plotHeight
  const band = bars.length > 0 ? plotWidth / bars.length : 0
  // Odstęp 2px między słupkami, ale słupek nie węższy niż 1px.
  const barWidth = Math.max(1, band - 2)
  const labelEvery = Math.max(1, Math.ceil(bars.length / Math.max(1, Math.floor(plotWidth / 72))))
  const active = hovered == null ? null : bars[hovered]

  return (
    <div ref={ref} className="relative" onMouseLeave={() => setHovered(null)}>
      {width > 0 && (
        <svg
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={`${label}: wykres ${step === 1 ? 'dzienny' : 'tygodniowy'}`}
        >
          {ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={PADDING.left}
                x2={width - PADDING.right}
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
          {bars.map((bar, index) => {
            const x = PADDING.left + index * band + (band - barWidth) / 2
            return (
              <g key={bar.from}>
                <path
                  d={barPath(x, y(0), barWidth, y(bar.value) - y(0))}
                  className={cn(
                    'transition-[fill] duration-100',
                    bar.value < 0 ? 'fill-rose-500 dark:fill-rose-400' : 'fill-sky-600 dark:fill-sky-400',
                    hovered != null && hovered !== index && 'opacity-50',
                  )}
                />
                {index % labelEvery === 0 && (
                  <text
                    x={PADDING.left + index * band + band / 2}
                    y={CHART_HEIGHT - 8}
                    textAnchor="middle"
                    className="fill-muted-foreground text-[0.6875rem]"
                  >
                    {axisDate.format(parseIsoDate(bar.from))}
                  </text>
                )}
                <rect
                  x={PADDING.left + index * band}
                  y={PADDING.top}
                  width={band}
                  height={plotHeight}
                  fill="transparent"
                  onMouseEnter={() => setHovered(index)}
                />
              </g>
            )
          })}
        </svg>
      )}
      {active && hovered != null && (
        <div
          className="pointer-events-none absolute z-10 w-max rounded-lg bg-popover px-3 py-2 text-sm shadow-md ring-1 ring-foreground/10"
          style={{
            left: Math.min(Math.max(PADDING.left + hovered * band + band / 2, 80), width - 80),
            top: Math.max(0, Math.min(y(active.value), y(0)) - 8),
            transform: 'translate(-50%, -100%)',
          }}
        >
          <p className="text-xs text-muted-foreground">{formatRange({ from: active.from, to: active.to })}</p>
          <p className="font-medium tabular-nums">{format(active.value)}</p>
        </div>
      )}
      <p className="mt-1 text-xs text-muted-foreground">
        {step === 1 ? 'Słupek = dzień' : 'Słupek = 7 dni, do ostatniego dnia ze sprzedażą'}
      </p>
    </div>
  )
}

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

/** Okrągłe podziałki osi (1, 2, 2,5 albo 5 × 10^n), od {@code min} do {@code max} włącznie z zerem. */
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

const formatZloty = (value: number) => `${formatMoney(value)} zł`
const compactFormat = new Intl.NumberFormat('pl-PL', {
  notation: 'compact',
  maximumFractionDigits: 1,
})
const percentFormat = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 1,
})
const dayFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })
const axisDate = new Intl.DateTimeFormat('pl-PL', {
  day: 'numeric',
  month: 'short',
})
