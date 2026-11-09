import { ChevronLeft, ChevronRight, ZoomOut } from 'lucide-react'
import { Fragment, type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'

import { SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { bonNumber, documentPaths, formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { PriceMarkdownButton } from '@/features/inventory/PriceMarkdownDialog'
import {
  type ReportKind,
  type SalesChartTarget,
  useArticleDeliveries,
  useArticleProtocols,
  useSalesArticle,
  useSalesHistory,
} from '@/features/sales/api'
import { type DateRange, formatRange } from '@/features/sales/periods'
import { addDays, addMonths, parseIsoDate, todayIso } from '@/lib/dates'
import type { ArticleDelivery, SalesArticle, SalesFigures, SalesHistory } from '@/lib/types'
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

/** Dni wykresu jako indeksy dni odpowiedzi: od {@code start} do {@code end} bez niego. */
type DayWindow = { start: number; end: number }

/**
 * Słupek wykresu: dzień albo tydzień z sumami sprzedaży i stratami (likwidacje, zakup netto). {@code start} i
 * {@code end} to jego dni w odpowiedzi, po nich słupki leżą na wspólnej osi dni przy zbliżeniu.
 */
type Bar = DayWindow & { from: string; to: string; figures: SalesFigures; lossValue: number }

/** Seria wykresu: słupki albo linia na prawej osi. */
type Series = {
  label: string
  value: (figures: SalesFigures) => number | null
  format: (value: number) => string
  unit: string
}

/** Średnia cena brutto sztuki (kg), jak cena na półce; dzień bez sprzedaży jej nie ma. */
const averagePrice = (figures: SalesFigures) =>
  figures.quantity > 0 ? figures.saleValueGross / figures.quantity : null

const marginPercent = (figures: SalesFigures) =>
  figures.marginValue != null && figures.saleValueNet !== 0 ? (figures.marginValue / figures.saleValueNet) * 100 : null

/**
 * Okno z wykresem sprzedaży wiersza raportu. Przy artykule na górze jego bieżące dane z kartoteki (stan, rotacja,
 * ceny), a pod wykresem ostatnie dostawy; przy pozycji nomenklatury sam wykres. Domyślnie ostatni miesiąc, najdłużej
 * rok.
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
      {/* Kolumna minmax(0,1fr): treść (wykres o szerokości zmierzonej z okna) nie może rozpychać okna w bok. */}
      <DialogContent className="grid-cols-[minmax(0,1fr)] gap-5 p-6 sm:max-w-6xl">
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

/**
 * Ta sama treść co okno wykresu, ale na stronie (karta artykułu w Magazynie i produktach): dane z kartoteki, wykres
 * sprzedaży i dostawy. Sprzedaż brutto, bez filtra nomenklatury.
 */
export function ArticleSalesPanel({
  storeId,
  target,
  lastSaleDate,
}: {
  storeId: string
  target: Extract<SalesChartTarget, { kind: 'article' }>
  lastSaleDate: string
}) {
  return (
    <ChartContent
      key={chartKey(target)}
      storeId={storeId}
      kind="sales"
      target={target}
      filter={NO_FILTER}
      basis="gross"
      lastSaleDate={lastSaleDate}
      inline
    />
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
  inline = false,
}: {
  storeId: string
  kind: ReportKind
  target: SalesChartTarget
  filter: string[]
  basis: SaleBasis
  lastSaleDate: string
  /** Na stronie zamiast w oknie: nagłówek bez elementów okna (te wymagają otwartego Dialog). */
  inline?: boolean
}) {
  const metrics = metricsFor(kind, target)
  const [rangeKey, setRangeKey] = useState<ChartRange>('month')
  const [zoom, setZoom] = useState<DayWindow | null>(null)
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
  const allBars = data ? toBars(data.days, RANGES[rangeKey].step) : []
  // Zbliżenie na słupek dłuższy niż dzień: jego dni (indeksy dni odpowiedzi, koniec bez niego).
  const zoomIndex = zoom ? allBars.findIndex((candidate) => candidate.start === zoom.start) : -1
  const zoomed = zoomIndex >= 0 ? allBars[zoomIndex] : null
  const step = zoomed ? 1 : RANGES[rangeKey].step
  const bars = data && zoomed ? toBars(data.days.slice(zoomed.start, zoomed.end), 1, zoomed.start) : allBars
  const days = zoomed ? zoomed.end - zoomed.start : (data?.days.length ?? 0)
  const viewTotal = zoomed ? zoomed.figures : data?.total
  const viewLoss = zoomed ? zoomed.lossValue : (data?.lossValue ?? 0)
  const total = viewTotal ? (bar.value(viewTotal) ?? 0) : 0
  const lineTotal = viewTotal && line ? line.value(viewTotal) : null
  const zoomTo = (index: number) => {
    const target = allBars[index]
    if (target) setZoom({ start: target.start, end: target.end })
  }

  return (
    <>
      {inline ? (
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid min-w-0 gap-1">
            <h2 className="text-lg leading-snug font-semibold">{title(target, article.data)}</h2>
            <p className="text-sm text-muted-foreground">{subtitle(target, article.data)}</p>
          </div>
          {article.data?.cadencier && (
            <p
              className="shrink-0 rounded-lg bg-red-600 px-4 py-2 text-base font-bold tracking-wide text-white uppercase shadow-sm dark:bg-red-700"
              title="Artykuł jest na bieżącej liście cadencier sklepu"
            >
              Obecny w cadencier
            </p>
          )}
        </div>
      ) : (
        <DialogHeader className="pr-8">
          <DialogTitle className="text-lg leading-snug">{title(target, article.data)}</DialogTitle>
          <DialogDescription>{subtitle(target, article.data)}</DialogDescription>
        </DialogHeader>
      )}

      {target.kind === 'article' && (
        <ArticleFacts
          storeId={storeId}
          target={target}
          lastSaleDate={lastSaleDate}
          article={article.data}
          isPending={article.isPending}
          history={data}
          rangeLabel={RANGES[rangeKey].label.toLowerCase()}
        />
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <SegmentedField label="Co pokazać" options={metrics} value={metric} onChange={setMetric} />
        <SegmentedField
          label="Okres wykresu"
          options={(Object.keys(RANGES) as ChartRange[]).map((key) => ({
            value: key,
            label: RANGES[key].label,
          }))}
          value={rangeKey}
          onChange={(value) => {
            setZoom(null)
            setRangeKey(value)
          }}
        />
      </div>

      <div
        className={cn('grid min-w-0 gap-4 transition-opacity duration-150', history.isPlaceholderData && 'opacity-60')}
      >
        <div className="grid gap-2">
          {zoomed ? (
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => setZoom(null)}>
                <ZoomOut />
                {RANGES[rangeKey].label}
              </Button>
              <div className="flex items-center gap-0.5">
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Poprzednie 7 dni"
                  disabled={zoomIndex === 0}
                  onClick={() => zoomTo(zoomIndex - 1)}
                >
                  <ChevronLeft />
                </Button>
                <p className="min-w-44 text-center text-sm font-medium tabular-nums">
                  {formatRange({ from: zoomed.from, to: zoomed.to })}
                </p>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Następne 7 dni"
                  disabled={zoomIndex === allBars.length - 1}
                  onClick={() => zoomTo(zoomIndex + 1)}
                >
                  <ChevronRight />
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">z okresu {formatRange(range)}</p>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{formatRange(range)}</p>
          )}
          {data && viewTotal && (
            <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border bg-border sm:auto-cols-fr sm:grid-flow-col sm:grid-cols-none">
              <Stat label={`${bar.label} razem`}>{bar.format(total)}</Stat>
              <Stat label="Średnio dziennie">{bar.format(days > 0 ? total / days : 0)}</Stat>
              {line && (
                <Stat label={line === price ? 'Średnia cena w okresie' : 'Marża % w okresie'} accent>
                  {lineTotal == null ? '—' : line.format(lineTotal)}
                </Stat>
              )}
              {line === price && <Stat label="Sprzedaż brutto razem">{formatZloty(viewTotal.saleValueGross)}</Stat>}
              {line === margin && viewTotal.marginValue != null && (
                <Stat label="Marża razem">{formatZloty(viewTotal.marginValue)}</Stat>
              )}
              <Stat label="Straty (zakup netto)">
                <span className={cn(viewLoss > 0 && 'text-destructive')}>{formatZloty(viewLoss)}</span>
                {viewLoss > 0 && viewTotal.saleValueGross > 0 && (
                  <span
                    className="ml-1.5 text-xs font-normal text-muted-foreground"
                    title="Straty jako % sprzedaży brutto, jak w podsumowaniu sprzedaży i strat"
                  >
                    {formatPercentValue((viewLoss / viewTotal.saleValueGross) * 100)} sprzedaży
                  </span>
                )}
              </Stat>
            </dl>
          )}
        </div>

        {history.isError && !data ? (
          <p className="py-16 text-center text-sm text-muted-foreground">Nie udało się wczytać sprzedaży.</p>
        ) : !data ? (
          <div aria-hidden className="h-80 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : (
          <BarChart
            scope={`${data.from}:${data.to}`}
            window={zoomed ?? { start: 0, end: data.days.length }}
            bars={bars}
            bar={bar}
            line={line}
            basis={basis}
            showMargin={showMargin}
            step={step}
            onZoom={zoomed ? undefined : (index) => zoomTo(index)}
          />
        )}
      </div>

      {target.kind === 'article' && (
        <>
          <ArticleDeliveries storeId={storeId} itm8={target.itm8} />
          <ArticleProtocols storeId={storeId} itm8={target.itm8} />
        </>
      )}
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

/** Ile dni przed datą przyjęcia artykuł mógł już leżeć na półce (przyjęcie wpisane później niż wyłożony towar). */
const SHELF_BEFORE_ENTRY_DAYS = 7

/**
 * Na ile dni wystarczy stan. Średnia sprzedaż liczy się od ostatniej dostawy do ostatniego dnia ze sprzedażą, bo
 * wtedy artykuł był na półce; średnia z okresu wykresu byłaby zaniżona dniami, gdy go brakowało. Przyjęcie bywa
 * wpisane po wyłożeniu towaru, więc start to pierwszy dzień ze sprzedażą w tygodniu przed datą przyjęcia, a bez niej
 * data przyjęcia. Bez dostawy we wgranych dokumentach albo z dostawą po ostatnim dniu sprzedaży: okres wykresu.
 */
function useStockCover(
  storeId: string,
  target: Extract<SalesChartTarget, { kind: 'article' }>,
  lastSaleDate: string,
  history: SalesHistory | undefined,
  rangeLabel: string,
): { daily: number; basis: string } | null {
  const deliveries = useArticleDeliveries(storeId, target.itm8)
  const delivered = deliveries.data?.[0]?.documentDate.slice(0, 10)
  const window =
    delivered != null && delivered <= lastSaleDate
      ? { from: addDays(delivered, -SHELF_BEFORE_ENTRY_DAYS), to: lastSaleDate }
      : null
  const recent = useSalesHistory(
    storeId,
    'sales',
    target,
    window ?? { from: lastSaleDate, to: lastSaleDate },
    NO_FILTER,
    window != null,
  )

  if (deliveries.isPending) return null
  if (!window || !delivered) {
    if (!history || history.days.length === 0) return null
    return {
      daily: history.total.quantity / history.days.length,
      basis: `wg sprzedaży z okresu wykresu (${rangeLabel})`,
    }
  }
  if (!recent.data) return null
  const firstSale = recent.data.days.find((day) => day.date < delivered && day.figures.quantity > 0)?.date
  const from = firstSale ?? delivered
  const days = recent.data.days.filter((day) => day.date >= from)
  if (days.length === 0) return null
  const sold = days.reduce((sum, day) => sum + day.figures.quantity, 0)
  const counted = `sprzedano ${formatQuantity(sold)} w ${days.length} ${days.length === 1 ? 'dzień' : 'dni'}`
  return {
    daily: sold / days.length,
    basis: firstSale
      ? `od ${shortDate.format(parseIsoDate(from))} (przyjęcie ${shortDate.format(parseIsoDate(delivered))}) ${counted}`
      : `od dostawy ${shortDate.format(parseIsoDate(delivered))} ${counted}`,
  }
}

const NO_FILTER: string[] = []

/** Z ilu ostatnich miesięcy dostaw wybieramy najtańszego dostawcę. */
const SUPPLIER_MONTHS = 3

/**
 * Dostawca do pokazania przy artykule. Z przyjęć PZ z ostatnich {@link SUPPLIER_MONTHS} miesięcy każdy dostawca ma cenę
 * ze swojej najnowszej dostawy, a wygrywa najtańszy, także gdy nie przywiózł ostatniej dostawy. Linie bez ceny (gratisy)
 * się nie liczą. Bez dostaw w tym czasie: dostawca ostatniego przyjęcia.
 */
function pickSupplier(
  deliveries: ArticleDelivery[],
  today: string,
): { delivery: ArticleDelivery; cheapestOf: number; outsideWindow: boolean } | null {
  const priced = deliveries.filter((delivery) => delivery.purchasePrice != null && delivery.purchasePrice > 0)
  if (priced.length === 0)
    return deliveries[0] ? { delivery: deliveries[0], cheapestOf: 1, outsideWindow: false } : null
  const since = addMonths(today.slice(0, 7), -SUPPLIER_MONTHS) + today.slice(7)
  const recent = priced.filter((delivery) => delivery.documentDate.slice(0, 10) >= since)
  if (recent.length === 0) return { delivery: priced[0], cheapestOf: 1, outsideWindow: true }
  // Lista idzie od najnowszej dostawy, więc pierwsza linia dostawcy to jego aktualna cena.
  const latestBySupplier = new Map<string, ArticleDelivery>()
  for (const delivery of recent) {
    const key = delivery.supplierCode ?? delivery.supplierName ?? ''
    if (!latestBySupplier.has(key)) latestBySupplier.set(key, delivery)
  }
  const candidates = [...latestBySupplier.values()]
  const cheapest = candidates.reduce((best, delivery) =>
    (delivery.purchasePrice ?? Infinity) < (best.purchasePrice ?? Infinity) ? delivery : best,
  )
  return { delivery: cheapest, cheapestOf: candidates.length, outsideWindow: false }
}

/** Bieżące dane artykułu z kartoteki: stan i rotacja z ostatniego raportu kartoteki, ceny, dostawca, nomenklatura. */
function ArticleFacts({
  storeId,
  target,
  lastSaleDate,
  article,
  isPending,
  history,
  rangeLabel,
}: {
  storeId: string
  target: Extract<SalesChartTarget, { kind: 'article' }>
  lastSaleDate: string
  article: SalesArticle | null | undefined
  isPending: boolean
  history: SalesHistory | undefined
  rangeLabel: string
}) {
  const cover = useStockCover(storeId, target, lastSaleDate, history, rangeLabel)
  const canMarkDown = useCan('INVENTORY', 'EDIT')
  const deliveries = useArticleDeliveries(storeId, target.itm8)
  const supplier = deliveries.data ? pickSupplier(deliveries.data, todayIso()) : null
  if (isPending)
    return <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
  if (!article) {
    return (
      <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
        Artykułu nie ma w kartotece sklepu, więc nie znamy jego stanu ani cen. Wykres pokazuje zapisaną sprzedaż.
      </p>
    )
  }

  const stock = article.stock
  const coverDays = stock != null && stock > 0 && cover && cover.daily > 0 ? stock / cover.daily : null
  const path = article.nomenclaturePath.map((item) => item.name.replace(/^\d+_/, '')).join(' › ')

  return (
    <div className="grid gap-3">
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border bg-border sm:grid-cols-4">
        <Fact label="Stan">
          <span className={cn(article.stock != null && article.stock < 0 && 'text-destructive')}>
            {article.stock == null ? '—' : formatQuantity(article.stock)}
          </span>
          {cover && stock != null && stock > 0 && (
            <span className="block text-xs font-normal text-muted-foreground" title={cover.basis}>
              {coverDays == null
                ? 'bez sprzedaży'
                : `na ok. ${coverDays < 1 ? 'mniej niż dzień' : `${dayFormat.format(coverDays)} dni sprzedaży`}`}
              <span className="block">{cover.basis}</span>
            </span>
          )}
        </Fact>
        <Fact label="Rotacja">{article.rotation == null ? '—' : formatQuantity(article.rotation)}</Fact>
        <Fact
          label="Cena sprzedaży"
          action={canMarkDown && article.ean ? <PriceMarkdownButton storeId={storeId} article={article} /> : undefined}
        >
          {article.salePrice == null ? '—' : `${formatMoney(article.salePrice)} zł`}
        </Fact>
        <Fact label="Cena zakupu">
          {article.purchasePrice == null ? '—' : `${formatPrice(article.purchasePrice)} zł`}
        </Fact>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
        {supplier && (
          <Detail label={supplier.cheapestOf > 1 ? 'Najtańszy dostawca' : 'Ostatni dostawca'}>
            <span className="font-medium">
              {[supplier.delivery.supplierCode, supplier.delivery.supplierName].filter(Boolean).join(' ') || '—'}
            </span>
            {supplier.delivery.purchasePrice != null && (
              <span className="tabular-nums"> · {formatPrice(supplier.delivery.purchasePrice)} zł</span>
            )}
            <span className="text-muted-foreground">
              {' '}
              · {shortDate.format(parseIsoDate(supplier.delivery.documentDate.slice(0, 10)))}
              {supplier.cheapestOf > 1 &&
                ` · najniższa cena z ${supplier.cheapestOf} dostawców w ostatnich ${SUPPLIER_MONTHS} miesiącach`}
              {supplier.outsideWindow && ` · brak dostaw w ostatnich ${SUPPLIER_MONTHS} miesiącach`}
            </span>
          </Detail>
        )}
        {article.supplier && <Detail label={supplier ? 'Dostawca w kartotece' : 'Dostawca'}>{article.supplier}</Detail>}
        {path && <Detail label="Nomenklatura">{path}</Detail>}
        {article.caseSize != null && <Detail label="W kartonie">{formatQuantity(article.caseSize)}</Detail>}
        {article.vatPercent != null && <Detail label="VAT">{article.vatPercent}%</Detail>}
        {article.active === false && <Detail label="Status">nieaktywny w kartotece</Detail>}
      </dl>
      <ReportFreshness generatedAt={article.reportGeneratedAt} className="justify-self-start" />
    </div>
  )
}

/**
 * Ostatnie dostawy artykułu (przyjęcia PZ) od najnowszej: ilość i cena zakupu netto sztuki, przy cenie zmiana względem
 * poprzedniej dostawy (droższa na czerwono, tańsza na zielono). Numer bonu prowadzi do przyjęcia.
 */
function ArticleDeliveries({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const deliveries = useArticleDeliveries(storeId, itm8)
  const canOpenDocuments = useCan('DOCUMENTS', 'VIEW')
  const list = deliveries.data

  return (
    // min-w-0: element siatki okna nie może rozpychać go szerokością tabeli.
    <section className="grid min-w-0 gap-2" aria-labelledby="article-deliveries">
      <div className="flex items-baseline justify-between gap-3">
        <h3 id="article-deliveries" className="text-sm font-semibold">
          Dostawy
        </h3>
        {list && list.length > 0 && (
          <p className="text-xs text-muted-foreground">
            {list.length === 50 ? 'ostatnie 50 przyjęć' : `przyjęcia: ${list.length}`}, od najnowszej · cena zakupu
            netto
          </p>
        )}
      </div>
      {!list ? (
        deliveries.isError ? (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">Nie udało się wczytać dostaw.</p>
        ) : (
          <div aria-hidden className="h-32 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        )
      ) : list.length === 0 ? (
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
          Brak przyjęć tego artykułu we wgranych raportach dokumentów.
        </p>
      ) : (
        <div className="max-h-72 min-w-0 overflow-y-auto rounded-lg border">
          {/* Stały układ: długa nazwa dostawcy skraca się wielokropkiem zamiast poszerzać okno. */}
          <table className="w-full table-fixed text-sm tabular-nums">
            <colgroup>
              <col className="w-28" />
              <col className="w-20" />
              <col />
              <col className="w-20" />
              <col className="w-36" />
              <col className="w-28" />
            </colgroup>
            <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 text-left font-medium">Data</th>
                <th className="px-3 py-2 text-left font-medium">Bon</th>
                <th className="px-3 py-2 text-left font-medium">Dostawca</th>
                <th className="px-3 py-2 text-right font-medium">Ilość</th>
                <th className="px-3 py-2 text-right font-medium">Cena zakupu</th>
                <th className="px-3 py-2 text-right font-medium">Wartość</th>
              </tr>
            </thead>
            <tbody>
              {list.map((delivery, index) => (
                <tr key={delivery.documentId} className="border-t">
                  <td className="px-3 py-1.5 whitespace-nowrap">
                    {deliveryDate.format(parseIsoDate(delivery.documentDate.slice(0, 10)))}
                  </td>
                  <td className="px-3 py-1.5 whitespace-nowrap">
                    {canOpenDocuments ? (
                      <Link
                        to={documentPaths.document('PZ', delivery.documentId)}
                        title={`Przyjęcie ${delivery.documentNumber}`}
                        className="underline-offset-4 hover:underline"
                      >
                        {bonNumber(delivery.documentNumber)}
                      </Link>
                    ) : (
                      <span title={`Przyjęcie ${delivery.documentNumber}`}>{bonNumber(delivery.documentNumber)}</span>
                    )}
                  </td>
                  <td className="truncate px-3 py-1.5" title={delivery.supplierName ?? undefined}>
                    {delivery.supplierName ?? delivery.supplierCode ?? '—'}
                  </td>
                  <td className="px-3 py-1.5 text-right">{formatQuantity(delivery.quantity)}</td>
                  <td className="px-3 py-1.5 text-right whitespace-nowrap">
                    <PriceChange delivery={delivery} previous={list[index + 1]} />
                    {delivery.purchasePrice == null ? '—' : `${formatPrice(delivery.purchasePrice)} zł`}
                  </td>
                  <td className="px-3 py-1.5 text-right whitespace-nowrap">{formatZloty(delivery.purchaseValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/**
 * Ostatnie protokoły artykułu inne niż przyjęcia i korekty, od najnowszego: rodzaj, numer i ilość. Przeniesienie stanów
 * zarejestrowane jako gratis ma plakietkę „Gratis”. Numer prowadzi do dokumentu.
 */
function ArticleProtocols({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const protocols = useArticleProtocols(storeId, itm8)
  const canOpenDocuments = useCan('DOCUMENTS', 'VIEW')
  const list = protocols.data

  return (
    <section className="grid min-w-0 gap-2" aria-labelledby="article-protocols">
      <div className="flex items-baseline justify-between gap-3">
        <h3 id="article-protocols" className="text-sm font-semibold">
          Protokoły
        </h3>
        {list && list.length > 0 && (
          <p className="text-xs text-muted-foreground">
            {list.length === 20 ? 'ostatnie 20' : `dokumenty: ${list.length}`}, od najnowszego
          </p>
        )}
      </div>
      {!list ? (
        protocols.isError ? (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
            Nie udało się wczytać protokołów.
          </p>
        ) : (
          <div aria-hidden className="h-20 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        )
      ) : list.length === 0 ? (
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
          Brak protokołów tego artykułu we wgranych raportach dokumentów.
        </p>
      ) : (
        <div className="max-h-56 min-w-0 overflow-y-auto rounded-lg border">
          <table className="w-full table-fixed text-sm tabular-nums">
            <colgroup>
              <col className="w-28" />
              <col />
              <col className="w-36" />
              <col className="w-20" />
            </colgroup>
            <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 text-left font-medium">Data</th>
                <th className="px-3 py-2 text-left font-medium">Protokół</th>
                <th className="px-3 py-2 text-left font-medium">Numer</th>
                <th className="px-3 py-2 text-right font-medium">Ilość</th>
              </tr>
            </thead>
            <tbody>
              {list.map((protocol) => (
                <tr key={protocol.documentId} className="border-t">
                  <td className="px-3 py-1.5 whitespace-nowrap">
                    {deliveryDate.format(parseIsoDate(protocol.documentDate.slice(0, 10)))}
                  </td>
                  <td className="truncate px-3 py-1.5" title={protocol.label}>
                    {protocol.gratis ? (
                      <span className="rounded bg-emerald-200 px-1.5 py-px text-xs font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
                        {protocol.label}
                      </span>
                    ) : (
                      protocol.label
                    )}
                  </td>
                  <td className="truncate px-3 py-1.5">
                    {canOpenDocuments ? (
                      <Link
                        to={documentPaths.document(protocol.documentType, protocol.documentId)}
                        className="underline-offset-4 hover:underline"
                      >
                        {protocol.documentNumber}
                      </Link>
                    ) : (
                      protocol.documentNumber
                    )}
                  </td>
                  <td className="px-3 py-1.5 text-right">{formatQuantity(protocol.quantity)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** Zmiana ceny względem poprzedniej (starszej) dostawy; bez zmiany nic. */
function PriceChange({ delivery, previous }: { delivery: ArticleDelivery; previous: ArticleDelivery | undefined }) {
  const before = previous?.purchasePrice
  const now = delivery.purchasePrice
  if (before == null || now == null || before === 0 || Math.abs(now - before) < 0.005) return null
  const change = ((now - before) / before) * 100
  return (
    <span
      className={cn(
        'mr-2 rounded px-1 py-px text-xs font-medium',
        change > 0
          ? 'bg-red-200 text-red-950 dark:bg-red-900 dark:text-red-50'
          : 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50',
      )}
      title={`Poprzednia dostawa: ${formatPrice(before)} zł`}
    >
      {change > 0 ? '+' : '−'}
      {percentFormat.format(Math.abs(change))}%
    </span>
  )
}

function Fact({ label, action, children }: { label: string; action?: ReactNode; children: ReactNode }) {
  return (
    <div className="bg-card px-4 py-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{children}</p>
      {action}
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

/**
 * Dni albo tygodnie liczone wstecz od ostatniego dnia, żeby ostatni słupek był pełny. {@code offset} to indeks
 * pierwszego z {@code list} w całej odpowiedzi (przy zbliżeniu na tydzień).
 */
function toBars(list: SalesHistory['days'], step: 1 | 7, offset = 0): Bar[] {
  const bars: Bar[] = []
  for (let end = list.length; end > 0; end -= step) {
    const start = Math.max(0, end - step)
    const days = list.slice(start, end)
    bars.unshift({
      start: offset + start,
      end: offset + end,
      from: days[0].date,
      to: days.at(-1)!.date,
      figures: sumFigures(days.map((day) => day.figures)),
      lossValue: days.reduce((sum, day) => sum + day.lossValue, 0),
    })
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
/** Zbliżenie trwa dłużej niż zwykłe przejścia, bo kamera przelatuje przez całą oś (tydzień z roku to 52×). */
const ZOOM_MS = 520

/** To, co widać: okno osi dni i skala lewej osi. */
type Frame = { w0: number; w1: number; low: number; high: number }
/** Ostatnio narysowane słupki z ich klatką, z nich startuje kolejne przejście (także przerwane w połowie). */
type Scene = { scope: string; key: string; bars: Bar[]; values: number[]; frame: Frame }
type Zoom = { from: Frame; outgoing: Scene; start: number }

/** Mocne ease-in-out (quart): kamera rusza i hamuje płynnie, bo przesuwa coś, co już jest na ekranie. */
const easeInOut = (t: number) => (t < 0.5 ? 8 * t ** 4 : 1 - (-2 * t + 2) ** 4 / 2)
const lerp = (from: number, to: number, t: number) => from + (to - from) * t
const clamp01 = (value: number) => Math.min(1, Math.max(0, value))

/**
 * Klatka w chwili {@code t} (0..1) przejścia. Przy zmianie skali okno zmienia się geometrycznie wokół punktu stałego
 * (dzień, który zostaje w tym samym miejscu ekranu), więc zbliżenie wygląda jak lot kamery ze stałą prędkością
 * powiększania, a nie skok na koniec; przy samym przesunięciu (następny tydzień) liniowo.
 */
function frameAt(from: Frame, to: Frame, t: number): Frame {
  const low = lerp(from.low, to.low, t)
  const high = lerp(from.high, to.high, t)
  const k = (to.w1 - to.w0) / (from.w1 - from.w0)
  if (Math.abs(k - 1) < 1e-6) return { w0: lerp(from.w0, to.w0, t), w1: lerp(from.w1, to.w1, t), low, high }
  const fixed = (to.w0 - from.w0 * k) / (1 - k)
  const scale = k ** t
  return { w0: fixed - (fixed - from.w0) * scale, w1: fixed + (from.w1 - fixed) * scale, low, high }
}

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Słupki jednej serii w SVG i opcjonalnie linia drugiej serii na prawej osi (cena, % marży), z własną skalą bez
 * zera, bo cena czy marża wahają się w wąskim przedziale. Cicha siatka, oś zera, przerywana linia średniej słupków,
 * przy dniach podział na tygodnie i podpisane poniedziałki, dzień bez sprzedaży jako kreska na osi. Podpowiedź po
 * najechaniu (pole trafienia to cała kolumna) pokazuje wszystkie liczby słupka. Szerokość z kontenera, bo tekst
 * w SVG nie może się rozciągać.
 *
 * Słupki leżą na wspólnej osi dni odpowiedzi ({@code window} to widoczne dni), więc zbliżenie na tydzień i powrót
 * to przelot kamery po tej osi: tydzień rozrasta się na całą szerokość i przechodzi w swoje dni, a skala lewej osi
 * dopasowuje się po drodze. Linia, średnia, podpisy i podpowiedzi wracają po przelocie. Bez animacji przy
 * {@code prefers-reduced-motion} i przy zmianie okresu wykresu (inny {@code scope}, inne dni).
 */
function BarChart({
  scope,
  window: shownDays,
  bars,
  bar: barSeries,
  line,
  basis,
  showMargin,
  step,
  onZoom,
}: {
  scope: string
  window: DayWindow
  bars: Bar[]
  bar: Series
  line: Series | null
  basis: SaleBasis
  showMargin: boolean
  step: 1 | 7
  /** Zbliżenie na słupek dłuższy niż dzień; bez niego słupków nie da się kliknąć. */
  onZoom?: (index: number) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const clipId = useId()
  const [width, setWidth] = useState(0)
  // Najechany słupek razem z oknem, w którym go najechano: po zbliżeniu ten indeks nic już nie znaczy.
  const [hover, setHover] = useState<{ key: string; index: number } | null>(null)
  // Położenie wykresu na ekranie przy najechaniu, do podpowiedzi w portalu.
  const [origin, setOrigin] = useState<DOMRect | null>(null)
  const [zoom, setZoom] = useState<Zoom | null>(null)
  const [now, setNow] = useState(0)
  const shown = useRef<Scene | null>(null)

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
  const key = `${shownDays.start}:${shownDays.end}`
  const hovered = hover?.key === key && hover.index < bars.length ? hover.index : null
  const target: Frame = { w0: shownDays.start, w1: shownDays.end, low: ticks[0], high: ticks.at(-1)! }

  // Nowe okno w tym samym okresie: przelot od tego, co było na ekranie (także od połowy przerwanego przelotu).
  // Przed malowaniem, żeby nowe słupki nie mignęły w docelowym miejscu.
  useLayoutEffect(() => {
    const previous = shown.current
    if (!previous || previous.key === key) return
    if (previous.scope !== scope || prefersReducedMotion()) return
    const start = performance.now()
    setZoom({ from: previous.frame, outgoing: previous, start })
    setNow(start)
  }, [key, scope])

  useEffect(() => {
    if (!zoom) return
    let frameId = 0
    const tick = (time: number) => {
      if (time - zoom.start >= ZOOM_MS) {
        setZoom(null)
        return
      }
      setNow(time)
      frameId = requestAnimationFrame(tick)
    }
    frameId = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frameId)
  }, [zoom])

  const progress = zoom ? easeInOut(clamp01((now - zoom.start) / ZOOM_MS)) : 1
  const frame = zoom ? frameAt(zoom.from, target, progress) : target

  useLayoutEffect(() => {
    shown.current = { scope, key, bars, values, frame }
  })

  const plotWidth = Math.max(0, width - PADDING.left - paddingRight)
  const plotHeight = CHART_HEIGHT - PADDING.top - PADDING.bottom
  const plotRight = PADDING.left + plotWidth
  const dayX = (day: number) => PADDING.left + ((day - frame.w0) / (frame.w1 - frame.w0 || 1)) * plotWidth
  const y = (value: number) => PADDING.top + ((frame.high - value) / (frame.high - frame.low || 1)) * plotHeight
  const left = (index: number) => dayX(bars[index].start)
  const bandOf = (bar: Bar) => dayX(bar.end) - dayX(bar.start)
  const center = (index: number) => left(index) + bandOf(bars[index]) / 2
  const average = values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : 0

  // Tydzień i jego dni zachodzą na siebie, więc się przenikają; sąsiedni tydzień tylko wjeżdża obok.
  const outgoing = zoom?.outgoing
  const overlaps =
    outgoing != null &&
    outgoing.bars.length > 0 &&
    outgoing.bars[0].start < shownDays.end &&
    shownDays.start < outgoing.bars.at(-1)!.end
  const incomingOpacity = overlaps ? clamp01((progress - 0.15) / 0.6) : 1
  const outgoingOpacity = overlaps ? 1 - clamp01(progress / 0.6) : 1
  // Podziałki: wspólne zostają, stare gasną, nowe pojawiają się w drugiej części przelotu.
  const fromTicks = zoom ? niceTicks(zoom.from.low, zoom.from.high) : ticks
  const tickMarks: (readonly [number, number])[] = [
    ...fromTicks.filter((tick) => !ticks.includes(tick)).map((tick) => [tick, 1 - clamp01(progress / 0.5)] as const),
    ...ticks.map((tick) => [tick, fromTicks.includes(tick) ? 1 : clamp01((progress - 0.3) / 0.7)] as const),
  ]

  const lineValues = line ? bars.map((bar) => line.value(bar.figures)) : []
  const defined = lineValues.filter((value): value is number => value != null)
  const lineTicks = defined.length > 0 ? lineRangeTicks(Math.min(...defined), Math.max(...defined)) : []
  const lineLow = lineTicks[0] ?? 0
  const lineHigh = lineTicks.at(-1) ?? 1
  const lineY = (value: number) => PADDING.top + ((lineHigh - value) / (lineHigh - lineLow || 1)) * plotHeight

  // Przy dniach podpisujemy poniedziałki (co któryś, gdy się nie mieszczą), przy zbliżeniu na tydzień każdy dzień,
  // przy tygodniach co któryś słupek.
  const everyDay = step === 1 && bars.length <= 14
  const maxLabels = Math.max(1, Math.floor(plotWidth / 72))
  const mondays = step === 1 ? bars.flatMap((bar, index) => (isMonday(bar.from) ? [index] : [])) : []
  const candidates = step === 1 && !everyDay ? mondays : bars.map((_, index) => index)
  const every = Math.max(1, Math.ceil(candidates.length / maxLabels))
  const labelled = new Set(candidates.filter((_, position) => position % every === 0))

  const settled = zoom == null
  const active = hovered == null || !settled ? null : bars[hovered]
  const activeLine = hovered == null ? null : lineValues[hovered]
  const activeTop =
    hovered == null ? 0 : Math.min(y(Math.max(0, values[hovered])), activeLine == null ? Infinity : lineY(activeLine))
  const zoomable = (bar: Bar) => onZoom != null && bar.end - bar.start > 1

  const drawBars = (list: Bar[], listValues: number[], opacity: number, prefix: string) =>
    list.map((bar, index) => {
      const band = bandOf(bar)
      // Odstęp między słupkami rośnie z szerokością pasma (do 6px), słupek nie węższy niż 1px.
      const barWidth = Math.max(1, band - Math.max(1, Math.min(6, band * 0.25)))
      const x = dayX(bar.start) + (band - barWidth) / 2
      const value = listValues[index] ?? 0
      return (
        <g key={`${prefix}${bar.start}`} opacity={opacity}>
          {value === 0 ? (
            <rect x={x} y={y(0) - 2} width={barWidth} height={2} rx={1} className="fill-foreground/25" />
          ) : (
            <path
              d={barPath(x, y(0), barWidth, y(value) - y(0))}
              className={cn(
                'transition-opacity duration-100',
                value < 0 ? 'fill-rose-500 dark:fill-rose-400' : 'fill-sky-600 dark:fill-sky-400',
                settled && hovered != null && hovered !== index && 'opacity-55',
              )}
            />
          )}
        </g>
      )
    })

  return (
    <div className="grid min-w-0 gap-2">
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

      {/* overflow-hidden: SVG ma szerokość z pomiaru, więc przy zwężeniu okna (np. pojawia się pasek przewijania)
          nie może trzymać kontenera szerszym, inaczej pomiar nigdy by nie zmalał. */}
      <div ref={ref} className="relative min-w-0 overflow-hidden" onMouseLeave={() => setHover(null)}>
        {width > 0 && (
          <svg
            width={width}
            height={CHART_HEIGHT}
            role="img"
            aria-label={`${barSeries.label}${line ? ` i ${line.label}` : ''}: wykres ${step === 1 ? 'dzienny' : 'tygodniowy'}`}
          >
            <defs>
              {/* Słupki w trakcie przelotu wychodzą poza obszar wykresu, podziałki poza jego wysokość. */}
              <clipPath id={`${clipId}plot`}>
                <rect x={PADDING.left} y={0} width={plotWidth} height={CHART_HEIGHT - PADDING.bottom + 1} />
              </clipPath>
              <clipPath id={`${clipId}ticks`}>
                <rect x={0} y={PADDING.top - 8} width={width} height={plotHeight + 16} />
              </clipPath>
            </defs>

            {settled && (
              <g className="animate-in fade-in duration-200 motion-reduce:animate-none">
                {mondays
                  .filter((index) => index > 0)
                  .map((index) => (
                    <line
                      key={`week${bars[index].start}`}
                      x1={left(index)}
                      x2={left(index)}
                      y1={PADDING.top}
                      y2={CHART_HEIGHT - PADDING.bottom + 6}
                      className="stroke-border"
                    />
                  ))}
              </g>
            )}
            <g clipPath={`url(#${clipId}ticks)`}>
              {tickMarks.map(([tick, opacity]) => (
                <g key={tick} opacity={opacity}>
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
            </g>
            <text
              x={PADDING.left - 8}
              y={PADDING.top - 12}
              textAnchor="end"
              className="fill-sky-700 text-[0.6875rem] font-medium dark:fill-sky-300"
            >
              {barSeries.unit}
            </text>

            {settled && hovered != null && (
              <rect
                x={left(hovered)}
                y={PADDING.top}
                width={bandOf(bars[hovered])}
                height={plotHeight}
                className="fill-foreground/5"
              />
            )}

            <g clipPath={`url(#${clipId}plot)`}>
              {outgoing && drawBars(outgoing.bars, outgoing.values, outgoingOpacity, 'out')}
              {drawBars(bars, values, incomingOpacity, 'in')}
            </g>

            {settled && (
              <g className="animate-in fade-in duration-200 motion-reduce:animate-none">
                {bars.map((bar, index) =>
                  labelled.has(index) ? (
                    <text
                      key={`label${bar.start}`}
                      x={step === 1 && !everyDay ? left(index) + 3 : center(index)}
                      y={CHART_HEIGHT - 8}
                      textAnchor={step === 1 && !everyDay ? 'start' : 'middle'}
                      className="fill-muted-foreground text-[0.6875rem]"
                    >
                      {(step === 1 ? mondayDate : axisDate).format(parseIsoDate(bar.from))}
                    </text>
                  ) : null,
                )}

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
                          key={bars[index].start}
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
                    key={`hit${bar.start}`}
                    x={left(index)}
                    y={PADDING.top}
                    width={bandOf(bar)}
                    height={plotHeight}
                    fill="transparent"
                    className={cn(zoomable(bar) && 'cursor-zoom-in')}
                    onMouseEnter={() => {
                      setOrigin(ref.current?.getBoundingClientRect() ?? null)
                      setHover({ key, index })
                    }}
                    onClick={zoomable(bar) ? () => onZoom?.(index) : undefined}
                  />
                ))}
              </g>
            )}
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
                {step === 1
                  ? tooltipDate.format(parseIsoDate(active.from))
                  : formatRange({ from: active.from, to: active.to })}
              </p>
              <BarDetails figures={active.figures} lossValue={active.lossValue} basis={basis} showMargin={showMargin} />
              {zoomable(active) && <p className="text-xs text-muted-foreground">Kliknij, żeby zobaczyć dni</p>}
            </div>,
            document.body,
          )}
      </div>
      <p className="text-xs text-muted-foreground">
        {step === 1
          ? 'Słupek = dzień, pionowe linie oddzielają tygodnie (od poniedziałku), kreska na osi = dzień bez sprzedaży'
          : `Słupek = 7 dni, do ostatniego dnia ze sprzedażą${onZoom ? ' · kliknij słupek, żeby rozbić go na dni' : ''}`}
      </p>
    </div>
  )
}

/** Wszystkie liczby słupka w podpowiedzi, bez względu na wybraną serię. */
function BarDetails({
  figures,
  lossValue,
  basis,
  showMargin,
}: {
  figures: SalesFigures
  lossValue: number
  basis: SaleBasis
  showMargin: boolean
}) {
  const losses = lossValue > 0 ? <p className="font-medium text-destructive">Straty {formatZloty(lossValue)}</p> : null
  if (figures.quantity === 0 && figures.saleValueGross === 0 && !figures.costValue) {
    return (
      <>
        <p className="font-medium">Brak sprzedaży</p>
        {losses}
      </>
    )
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
    rows.push([
      'Marża',
      `${formatZloty(figures.marginValue)}${margin == null ? '' : ` · ${formatPercentValue(margin)}`}`,
    ])
  }
  if (lossValue > 0) rows.push(['Straty (zakup netto)', formatZloty(lossValue)])
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
  if (!sameValue(min, max)) return niceTicks(min, max)
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

/**
 * Ta sama wartość mimo błędu zaokrąglenia: średnia cena 20,97 / 3 to 6,989999999999999, a 41,94 / 6 to 6,99. Przy
 * takiej różnicy krok podziałek jest mniejszy niż dokładność liczby i pętla w {@link niceTicks} nigdy się nie kończy.
 */
const sameValue = (a: number, b: number) => Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a), Math.abs(b))

/** Okrągłe podziałki osi (1, 2, 2,5 albo 5 × 10^n) od {@code min} do {@code max}. */
function niceTicks(min: number, max: number): number[] {
  if (sameValue(min, max)) return [0, 1]
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
const shortDate = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'numeric' })
const deliveryDate = new Intl.DateTimeFormat('pl-PL', { day: '2-digit', month: '2-digit', year: 'numeric' })
const axisDate = new Intl.DateTimeFormat('pl-PL', {
  day: 'numeric',
  month: 'short',
})
const mondayDate = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'short' })
const tooltipDate = new Intl.DateTimeFormat('pl-PL', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})
