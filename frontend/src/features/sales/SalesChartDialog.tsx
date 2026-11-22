import { useQuery } from '@tanstack/react-query'
import { ChartColumnIcon, ChevronLeft, ChevronRight, EyeOffIcon, Loader2Icon, ZoomOut } from 'lucide-react'
import { Fragment, type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'

import { CopyButton } from '@/components/CopyButton'
import { SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { bonNumber, documentPaths, formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { findProducts, useProductPrices } from '@/features/inventory/api'
import { ArticlePriceHistory } from '@/features/inventory/ArticlePriceHistory'
import { meaDocumentPath } from '@/features/mea/paths'
import { PriceMarkdownButton } from '@/features/inventory/PriceMarkdownDialog'
import { ArticleCodeFields, ArticleCodesCard } from '@/features/sales/ArticleCodes'
import { ArticleUpcomingMea } from '@/features/sales/ArticleUpcomingMea'
import { ArticleTaskButton } from '@/features/tasks/ArticleTaskButton'
import {
  type ReportKind,
  type SalesChartTarget,
  useArticleDeliveries,
  useArticleDeliveryRows,
  useArticleMea,
  useArticleProtocolRows,
  useSalesArticle,
  useSalesHistory,
} from '@/features/sales/api'
import { type DateRange, formatRange } from '@/features/sales/periods'
import { addDays, addMonths, parseIsoDate, todayIso } from '@/lib/dates'
import { marginOf, marginShare } from '@/lib/margin'
import type { ArticleDelivery, MergedArticle, SalesArticle, SalesFigures, SalesHistory } from '@/lib/types'
import { cn } from '@/lib/utils'
import { LoadingPanel } from '@/components/LoadingPanel'
import {
  ArticleListSection,
  EmptyListRow,
  LoadMoreRow,
  SortTh,
  useListControls,
} from '@/features/sales/ArticleListSection'
import type { SortState } from '@/lib/table-sort'

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

/** % marży od sprzedaży brutto (`lib/margin`). */
const marginPercent = (figures: SalesFigures) => marginShare(figures.marginValue, figures.saleValueGross)

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

const withoutZeros = (code: string) => code.replace(/^0+/, '')

/**
 * Artykuł z kartoteki dla celu okna. Linie dokumentów od dostawców spoza bazy mają lokalny ITM8 dostawcy (`99…`), którego
 * nie ma w kartotece (IMBIR LUZ pod 99019930 w przyjęciu od KARBO): wtedy szukamy artykułu po EAN-ie, a znaleziony
 * zastępuje cel, żeby okno pokazało stan, ceny i sprzedaż artykułu sklepu. Bez trafienia zostaje cel z wiersza.
 */
function useCatalogTarget(storeId: string, target: SalesChartTarget) {
  const itm8 = target.kind === 'article' ? target.itm8 : null
  const ean = target.kind === 'article' && target.ean ? target.ean : null
  const article = useSalesArticle(storeId, itm8)
  const byEan = useQuery({
    queryKey: ['stores', storeId, 'products', 'by-ean', ean],
    queryFn: () => findProducts(storeId, ean!),
    enabled: article.data === null && ean != null && /^\d{1,14}$/.test(ean),
    staleTime: 5 * 60_000,
  })
  // Do czasu odpowiedzi kartoteki (i szukania po EAN-ie) nie wiadomo, który artykuł pokazać.
  const resolving = article.isLoading || byEan.isLoading
  if (target.kind !== 'article' || article.data !== null || !ean) return { target, foreignItm8: null, resolving }
  // Główny EAN ma pierwszeństwo; kod z multikodów albo dawnej pary daje artykuł tylko, gdy jest jeden.
  const match =
    byEan.data?.items.find((item) => item.ean && withoutZeros(item.ean) === withoutZeros(ean)) ??
    (byEan.data?.total === 1 ? byEan.data.items[0] : undefined)
  if (!match || match.itm8 === target.itm8) return { target, foreignItm8: null, resolving }
  return {
    target: { ...target, itm8: match.itm8, name: match.name ?? target.name, brand: match.brand ?? target.brand },
    foreignItm8: target.itm8,
    resolving,
  }
}

function ChartContent(props: Omit<Parameters<typeof ChartBody>[0], 'foreignItm8'>) {
  const { target, foreignItm8, resolving } = useCatalogTarget(props.storeId, props.target)
  if (resolving) return <CardLoading target={props.target} inline={props.inline} />
  return <ChartBody key={chartKey(target)} {...props} target={target} foreignItm8={foreignItm8} />
}

/**
 * Karta artykułu pokazuje się dopiero ze wszystkimi danymi naraz (kartoteka, wykres, gazetki MEA, dostawy, protokoły,
 * historia cen), zamiast doskakiwać sekcjami; do tego czasu jeden blok wczytywania. Błąd zapytania też kończy
 * czekanie, a sekcja pokazuje wtedy swój komunikat. Te same klucze zapytań co sekcje, więc nic nie idzie dwa razy.
 */
function useArticleCardLoading(storeId: string, itm8: string | null, historyLoading: boolean) {
  const canSeePrices = useCan('INVENTORY')
  const canSeeSalesPrices = useCan('SALES')
  const article = useSalesArticle(storeId, itm8)
  const deliveries = useArticleDeliveries(storeId, itm8)
  const deliveryRows = useArticleDeliveryRows(storeId, itm8, firstPage(DELIVERY_SORT))
  const protocols = useArticleProtocolRows(storeId, itm8, firstPage(PROTOCOL_SORT))
  const mea = useArticleMea(storeId, itm8)
  const prices = useProductPrices(storeId, itm8 ?? '', itm8 != null && (canSeePrices || canSeeSalesPrices))
  if (itm8 == null) return false
  return [article, deliveries, deliveryRows, protocols, mea, prices].some((query) => query.isLoading) || historyLoading
}

function CardLoading({ target, inline }: { target: SalesChartTarget; inline?: boolean }) {
  return (
    <>
      {!inline && (
        <DialogHeader className="pr-8">
          <DialogTitle className="text-lg leading-snug">{title(target, undefined)}</DialogTitle>
          <DialogDescription>Statystyki artykułu</DialogDescription>
        </DialogHeader>
      )}
      <LoadingPanel className="h-[36rem] rounded-xl" />
    </>
  )
}

function ChartBody({
  storeId,
  kind,
  target,
  filter,
  basis,
  lastSaleDate,
  inline = false,
  foreignItm8 = null,
}: {
  storeId: string
  kind: ReportKind
  target: SalesChartTarget
  filter: string[]
  basis: SaleBasis
  lastSaleDate: string
  /** Na stronie zamiast w oknie: nagłówek bez elementów okna (te wymagają otwartego Dialog). */
  inline?: boolean
  /** Kod z wiersza spoza kartoteki (lokalny ITM8 dostawcy), zamieniony na artykuł z kartoteki po EAN-ie. */
  foreignItm8?: string | null
}) {
  const metrics = metricsFor(kind, target)
  const [rangeKey, setRangeKey] = useState<ChartRange>('month')
  const [zoom, setZoom] = useState<DayWindow | null>(null)
  const [metric, setMetric] = useState<Metric>(metrics[0].value)
  // Przesunięcie wykresu w przeszłość (dni przed ostatnim dniem ze sprzedażą, także ułamek: wykres zostaje tam, gdzie
  // go puszczono) i bieżące przeciąganie (dodatnie = wstecz). Pobieranie idzie po pełnych krokach słupka (`shift`).
  const [position, setPosition] = useState(0)
  const [drag, setDrag] = useState(0)
  const { days: rangeDays, step: rangeStep } = RANGES[rangeKey]
  const shift = Math.max(0, Math.round(position / rangeStep) * rangeStep)
  const range: DateRange = {
    from: addDays(lastSaleDate, -(shift + rangeDays - 1)),
    to: addDays(lastSaleDate, -shift),
  }
  // Pobieramy też okres przed widocznym (i po nim, gdy jest przesunięty), żeby przy przeciąganiu słupki już były;
  // serwer przyjmuje najwyżej 366 dni, więc rok przeciąga się bez zapasu (dni dociągają się po puszczeniu).
  const spare = Math.min(rangeDays, Math.floor((366 - rangeDays) / 2 / rangeStep) * rangeStep)
  const fetched: DateRange = {
    from: addDays(range.from, -spare),
    to: addDays(range.to, Math.min(spare, shift)),
  }
  const history = useSalesHistory(storeId, kind, target, fetched, filter)
  const article = useSalesArticle(storeId, target.kind === 'article' ? target.itm8 : null)
  const cardLoading = useArticleCardLoading(storeId, target.kind === 'article' ? target.itm8 : null, history.isLoading)
  const showMargin = hasMargin(kind, target)
  // Wykres da się schować przy artykule (zostają dane z kartoteki, dostawy i protokoły); grupa ma tylko wykres.
  const [chartHiddenSetting, setChartHidden] = useChartHidden()
  const chartHidden = target.kind === 'article' && chartHiddenSetting
  // Karta przypisanych kodów otwiera się przyciskiem przy polach ITM8 i EAN; wraca zamknięta przy innym artykule.
  const [codesOpen, setCodesOpen] = useState(false)
  const codeFields =
    target.kind === 'article' ? (
      <>
        <ArticleCodeFields
          itm8={target.itm8}
          ean={article.data?.ean ?? target.ean}
          codesOpen={codesOpen}
          onToggleCodes={() => setCodesOpen((open) => !open)}
        />
        {foreignItm8 && (
          <p className="mt-1 text-xs text-muted-foreground">
            W dokumencie pod kodem dostawcy {foreignItm8}, którego nie ma w kartotece; pokazujemy artykuł sklepu z tym
            EAN-em.
          </p>
        )}
      </>
    ) : null

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

  const response = history.data
  // Widoczne dni jako indeksy dni odpowiedzi (przy starszej odpowiedzi w trakcie pobierania mogą wyjść poza nią).
  const baseStart = response ? daysBetween(response.from, range.from) : 0
  const baseEnd = baseStart + rangeDays
  // Wstecz bez ograniczenia (dni spoza pobranych dociągają się po puszczeniu), do przodu najwyżej do ostatniego dnia.
  const back = Math.max(0, position + drag)
  const visible: DayWindow = { start: baseStart - (back - shift), end: baseEnd - (back - shift) }
  const data = response ? visibleHistory(response, visible, range) : undefined
  const allBars = response ? toBars(response.days, rangeStep) : []
  // Zbliżenie na słupek dłuższy niż dzień: jego dni (indeksy dni odpowiedzi, koniec bez niego).
  const zoomIndex = zoom ? allBars.findIndex((candidate) => candidate.start === zoom.start) : -1
  const zoomed = zoomIndex >= 0 ? allBars[zoomIndex] : null
  const step = zoomed ? 1 : RANGES[rangeKey].step
  const bars = response && zoomed ? toBars(response.days.slice(zoomed.start, zoomed.end), 1, zoomed.start) : allBars
  const days = zoomed ? zoomed.end - zoomed.start : (data?.days.length ?? 0)
  const viewTotal = zoomed ? zoomed.figures : data?.total
  const viewLoss = zoomed ? zoomed.lossValue : (data?.lossValue ?? 0)
  const total = viewTotal ? (bar.value(viewTotal) ?? 0) : 0
  const lineTotal = viewTotal && line ? line.value(viewTotal) : null
  const zoomTo = (index: number) => {
    const target = allBars[index]
    if (target) setZoom({ start: target.start, end: target.end })
  }
  const moveBy = (daysBack: number) => {
    setZoom(null)
    setPosition((current) => Math.max(0, current + daysBack))
  }
  const pan = (daysBack: number, done: boolean) => {
    if (!done) {
      setDrag(daysBack)
      return
    }
    setDrag(0)
    moveBy(daysBack)
  }
  const shownRange = data && data.days.length > 0 ? { from: data.from, to: data.to } : range

  if (cardLoading) return <CardLoading target={target} inline={inline} />

  return (
    <>
      {inline ? (
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid min-w-0 gap-1">
            <h2 className="text-lg leading-snug font-semibold">{title(target, article.data)}</h2>
            {subtitle(target, article.data) && (
              <p className="text-sm text-muted-foreground">{subtitle(target, article.data)}</p>
            )}
            {codeFields && <div className="mt-1">{codeFields}</div>}
          </div>
          <div className="flex items-start gap-2">
            {target.kind === 'article' && <ArticleTaskButton target={target} article={article.data ?? null} />}
            {article.data?.cadencier && <CadencierBadge />}
          </div>
        </div>
      ) : (
        <DialogHeader className="pr-8">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="grid min-w-0 gap-1">
              <DialogTitle className="text-lg leading-snug">{title(target, article.data)}</DialogTitle>
              <DialogDescription>{subtitle(target, article.data) || 'Statystyki artykułu'}</DialogDescription>
              {codeFields && <div className="pt-1">{codeFields}</div>}
            </div>
            <div className="flex items-start gap-2">
              {target.kind === 'article' && <ArticleTaskButton target={target} article={article.data ?? null} />}
              {article.data?.cadencier && <CadencierBadge />}
            </div>
          </div>
        </DialogHeader>
      )}

      {target.kind === 'article' && codesOpen && (
        <ArticleCodesCard storeId={storeId} itm8={target.itm8} onClose={() => setCodesOpen(false)} />
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

      {chartHidden ? (
        <div>
          <Button variant="outline" size="sm" onClick={() => setChartHidden(false)}>
            <ChartColumnIcon />
            Pokaż wykres sprzedaży
          </Button>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <SegmentedField label="Co pokazać" options={metrics} value={metric} onChange={setMetric} />
              {target.kind === 'article' && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setChartHidden(true)}
                  title="Schowaj wykres, zostaną dane artykułu, dostawy i protokoły. Wybór zostaje zapamiętany."
                >
                  <EyeOffIcon />
                  Ukryj wykres
                </Button>
              )}
            </div>
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
                // Ten sam koniec okresu zostaje (pozycja jest w dniach).
              }}
            />
          </div>

          <div className="grid min-w-0 gap-4">
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
                  <p className="text-xs text-muted-foreground">z okresu {formatRange(shownRange)}</p>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <div className="flex items-center gap-0.5">
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Wcześniej o ${RANGES[rangeKey].label.toLowerCase()}`}
                      title="Wcześniej (możesz też przeciągnąć wykres w prawo)"
                      onClick={() => moveBy(rangeDays - (rangeDays % rangeStep))}
                    >
                      <ChevronLeft />
                    </Button>
                    <p className="min-w-44 text-center text-sm font-medium tabular-nums">{formatRange(shownRange)}</p>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Później o ${RANGES[rangeKey].label.toLowerCase()}`}
                      title="Później (możesz też przeciągnąć wykres w lewo)"
                      disabled={position === 0}
                      onClick={() => moveBy(-Math.min(position, rangeDays - (rangeDays % rangeStep)))}
                    >
                      <ChevronRight />
                    </Button>
                  </div>
                  {position > 0 && (
                    <Button variant="ghost" size="sm" onClick={() => moveBy(-position)}>
                      Do ostatniego dnia
                    </Button>
                  )}
                </div>
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

            {history.isError && !response ? (
              <p className="py-16 text-center text-sm text-muted-foreground">Nie udało się wczytać sprzedaży.</p>
            ) : !response ? (
              <div aria-hidden className="h-80 loading-shimmer rounded-lg" />
            ) : (
              <BarChart
                scope={`${response.from}:${response.to}`}
                window={zoomed ?? visible}
                panning={drag !== 0}
                loading={history.isPlaceholderData}
                onPan={zoomed ? undefined : pan}
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
        </>
      )}

      {target.kind === 'article' && (
        <>
          <ArticleUpcomingMea storeId={storeId} itm8={target.itm8} />
          <ArticleDeliveries storeId={storeId} itm8={target.itm8} />
          <ArticleProtocols storeId={storeId} itm8={target.itm8} />
          {/* Na stronie (Magazyn, Ustaw promocje) historię cen pokazuje sama strona pod kartą. */}
          {!inline && <ArticlePriceHistory storeId={storeId} itm8={target.itm8} />}
        </>
      )}
    </>
  )
}

const CHART_HIDDEN_KEY = 'intermanage.articleChartHidden'

/** Czy wykres artykułu jest schowany: preferencja przeglądarki (localStorage), wspólna dla kart i okien artykułu. */
function useChartHidden() {
  const [hidden, setHidden] = useState(() => {
    try {
      return window.localStorage.getItem(CHART_HIDDEN_KEY) === '1'
    } catch {
      return false
    }
  })
  const update = (value: boolean) => {
    setHidden(value)
    try {
      if (value) window.localStorage.setItem(CHART_HIDDEN_KEY, '1')
      else window.localStorage.removeItem(CHART_HIDDEN_KEY)
    } catch {
      // Bez dostępu do localStorage wybór działa do zamknięcia karty.
    }
  }
  return [hidden, update] as const
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
  // ITM8 i EAN mają własne pola z kopiowaniem (ArticleCodeFields), tu zostaje marka.
  return article?.brand ?? target.brand ?? ''
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

const shortItm8 = (itm8: string) => itm8.replace(/^0+/, '')

const mergedLabel = (merged: MergedArticle) => `${shortItm8(merged.itm8)}${merged.name ? ` ${merged.name}` : ''}`

/**
 * Połączenie artykułów przez multikod: na artykule głównym, które usunięte artykuły do niego doszły (ich sprzedaż,
 * dostawy i protokoły są na tej karcie), a na usuniętym, z którym artykułem go połączono.
 */
function MergedNote({ article }: { article: SalesArticle }) {
  if (article.mergedInto) {
    return (
      <p className="rounded-lg bg-sky-200 px-4 py-2 text-sm text-sky-950 dark:bg-sky-900/70 dark:text-sky-50">
        Artykuł usunięty z kartoteki. Jego EAN {article.mergedInto.ean} należy teraz do{' '}
        <span className="font-semibold">{mergedLabel(article.mergedInto)}</span> i tam liczy się jego sprzedaż oraz
        dostawy.
      </p>
    )
  }
  if (article.mergedArticles.length === 0) return null
  return (
    <div className="rounded-lg bg-sky-200 px-4 py-2 text-sm text-sky-950 dark:bg-sky-900/70 dark:text-sky-50">
      <span className="font-semibold">Połączone artykuły:</span> sprzedaż, dostawy i protokoły tej karty obejmują też
      <ul className="mt-1 grid gap-0.5">
        {article.mergedArticles.map((merged) => (
          <li key={merged.itm8} className="tabular-nums">
            {mergedLabel(merged)} <span className="opacity-75">(przez EAN {merged.ean})</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Duża plakietka przy artykule z bieżącej listy cadencier: na stronie i w oknie statystyk tak samo. */
function CadencierBadge() {
  return (
    <p
      className="shrink-0 rounded-lg bg-red-600 px-4 py-2 text-base font-bold tracking-wide text-white uppercase shadow-sm dark:bg-red-700"
      title="Artykuł jest na bieżącej liście cadencier sklepu"
    >
      Obecny w cadencier
    </p>
  )
}

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
  if (isPending) return <LoadingPanel className="h-24 rounded-lg" />
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
  // Marża od sprzedaży brutto (jak w Mercalys): cena zwykła i cena zakupu z kartoteki, przy promocji z kasy też na niej.
  const marginAt = (price: number | null) =>
    price != null && price > 0 && article.purchasePrice != null && article.vatPercent != null
      ? marginOf(price, article.vatPercent, article.purchasePrice)
      : null
  const margin = marginAt(article.salePrice)
  const promoMargin = article.promoPrice != null ? marginAt(article.promoPrice) : null

  return (
    <div className="grid gap-3">
      <MergedNote article={article} />
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border bg-border sm:grid-cols-5">
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
          {article.promoPrice != null ? (
            <>
              <span className="text-orange-600 dark:text-orange-400">{formatMoney(article.promoPrice)} zł</span>
              <span className="block text-xs font-normal text-muted-foreground">
                promocja z kasy
                {article.promoTo && ` do ${shortDate.format(parseIsoDate(article.promoTo))}`}
                {article.salePrice != null && ` · regularna ${formatMoney(article.salePrice)} zł`}
              </span>
            </>
          ) : article.salePrice == null ? (
            '—'
          ) : (
            `${formatMoney(article.salePrice)} zł`
          )}
          {article.cadencierPrice != null && article.cadencierPrice > 0 && (
            <span
              className="block text-xs font-normal text-blue-700 dark:text-blue-300"
              title="CENA_CAD: cena sprzedaży narzucona przez centralę, z ostatniego raportu kartoteki"
            >
              CENA_CAD {formatMoney(article.cadencierPrice)} zł
            </span>
          )}
        </Fact>
        <Fact label="Cena zakupu">
          {article.purchasePrice == null ? '—' : `${formatPrice(article.purchasePrice)} zł`}
        </Fact>
        <Fact label="Marża">
          <span
            className={cn(margin != null && margin < 0 && 'text-destructive')}
            title="Od sprzedaży brutto: aktualna cena zwykła i cena zakupu z kartoteki"
          >
            {margin == null ? '—' : formatPercentValue(margin)}
          </span>
          {promoMargin != null && (
            <span
              className={cn(
                'block text-xs font-normal',
                promoMargin < 0 ? 'text-destructive' : 'text-orange-600 dark:text-orange-400',
              )}
            >
              w promocji {formatPercentValue(promoMargin)}
            </span>
          )}
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
        <Detail label="Marka">
          {article.brand?.trim() ? article.brand : <span className="text-muted-foreground">brak w kartotece</span>}
        </Detail>
        {path && <Detail label="Nomenklatura">{path}</Detail>}
        {article.caseSize != null && <Detail label="W kartonie">{formatQuantity(article.caseSize)}</Detail>}
        {article.vatPercent != null && <Detail label="VAT">{article.vatPercent}%</Detail>}
        <Detail label="Cadencier">
          {article.cadencier ? (
            <span className="font-medium">na bieżącej liście cadencier</span>
          ) : (
            <span className="text-muted-foreground">poza cadencier</span>
          )}
        </Detail>
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
const promoDay = new Intl.DateTimeFormat('pl-PL', { day: '2-digit', month: '2-digit' })

/** Gazetka, na którą przyszła dostawa: numer MEA (link do dokumentu), okres promocji, nazwa w podpowiedzi. */
function MeaBadge({ mea, canOpen }: { mea: ArticleDelivery['mea'][number]; canOpen: boolean }) {
  const period =
    mea.promoFrom && mea.promoTo
      ? `${promoDay.format(parseIsoDate(mea.promoFrom))}–${promoDay.format(parseIsoDate(mea.promoTo))}`
      : null
  const title = [`MEA ${mea.number}`, mea.title, period && `promocja ${period}`].filter(Boolean).join(' · ')
  const pill = 'rounded bg-amber-200 px-1.5 font-semibold text-amber-950 dark:bg-amber-900 dark:text-amber-50'
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1 whitespace-nowrap" title={title}>
      {canOpen ? (
        <Link to={meaDocumentPath(mea.number)} className={cn(pill, 'underline-offset-4 hover:underline')}>
          {mea.number}
        </Link>
      ) : (
        <span className={pill}>{mea.number}</span>
      )}
      <CopyButton value={mea.number} label={`Kopiuj kod MEA ${mea.number}`} />
      {period && <span className="text-xs text-muted-foreground">{period}</span>}
    </span>
  )
}

type DeliveryColumn = 'date' | 'number' | 'supplier' | 'quantity' | 'price' | 'value'
type ProtocolColumn = 'date' | 'type' | 'number' | 'quantity'

/** Domyślnie od najnowszej; na te same parametry czeka karta przy wczytywaniu, więc pierwsza strona idzie raz. */
const DELIVERY_SORT: SortState<DeliveryColumn> = { key: 'date', direction: 'desc' }
const PROTOCOL_SORT: SortState<ProtocolColumn> = { key: 'date', direction: 'desc' }
const firstPage = (sort: SortState<string>) => ({ query: '', sort: sort.key, direction: sort.direction })

/** Nagłówek sekcji bez tabeli: wczytywanie, błąd albo brak danych. */
function ListPlaceholder({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid min-w-0 gap-2">
      <h3 className="text-sm font-semibold">{title}</h3>
      {children}
    </section>
  )
}

/**
 * Przyjęcia PZ artykułu po 15, kolejne przy przewijaniu tabeli. Sortowanie kliknięciem w nagłówek i wyszukiwanie
 * (lupa w nagłówku sekcji) liczy serwer, więc obejmują wszystkie dostawy, nie tylko wczytane.
 */
function ArticleDeliveries({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const controls = useListControls(DELIVERY_SORT)
  const { sort, toggleSort } = controls
  const deliveries = useArticleDeliveryRows(storeId, itm8, {
    query: controls.query,
    sort: sort.key,
    direction: sort.direction,
  })
  const scrollRef = useRef<HTMLDivElement>(null)
  const canOpenDocuments = useCan('DOCUMENTS', 'VIEW')
  const canOpenMea = useCan('DATA_ENTRY', 'VIEW')
  const pages = deliveries.data?.pages
  const list = pages?.flatMap((page) => page.items)
  const total = pages?.at(-1)?.total ?? 0
  const searching = controls.query.trim() !== ''

  // Kolumna gazetki tylko, gdy któraś wczytana dostawa przyszła na MEA (towar spoza bazy by jej nie miał).
  const withMea = list?.some((delivery) => delivery.mea.length > 0) ?? false
  const columns = withMea ? 7 : 6

  if (!list) {
    return (
      <ListPlaceholder title="Dostawy">
        {deliveries.isError ? (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">Nie udało się wczytać dostaw.</p>
        ) : (
          <LoadingPanel className="h-32 rounded-lg" />
        )}
      </ListPlaceholder>
    )
  }
  if (total === 0 && !searching) {
    return (
      <ListPlaceholder title="Dostawy">
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
          Brak przyjęć tego artykułu we wgranych raportach dokumentów.
        </p>
      </ListPlaceholder>
    )
  }

  return (
    <ArticleListSection
      title="Dostawy"
      note={
        <span title="Cena zakupu netto po korektach przypiętych do przyjęcia">
          {searching ? `pasuje: ${total}` : `przyjęcia: ${total}`}
        </span>
      }
      searchLabel="Szukaj: bon, dostawca"
      search={controls.text}
      onSearch={controls.setText}
      scrollRef={scrollRef}
      className="max-h-72"
    >
      {/* Stały układ: długa nazwa dostawcy skraca się wielokropkiem zamiast poszerzać okno. Najmniejsza szerokość
          zostawia dostawcy co najmniej 10rem: bez niej kolumna gazetki zjadała mu całe miejsce w wąskim panelu,
          a tabela przewija się wtedy w bok. */}
      <table
        className={cn(
          'w-full table-fixed text-sm tabular-nums transition-opacity',
          withMea ? 'min-w-[57rem]' : 'min-w-[48rem]',
          deliveries.isPlaceholderData && 'opacity-60',
        )}
      >
        <colgroup>
          <col className="w-28" />
          <col className="w-20" />
          {withMea && <col className="w-36" />}
          <col />
          <col className="w-24" />
          <col className="w-52" />
          <col className="w-28" />
        </colgroup>
        <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
          <tr>
            <SortTh column="date" sort={sort} onSort={toggleSort}>
              Data
            </SortTh>
            <SortTh column="number" sort={sort} onSort={toggleSort}>
              Bon
            </SortTh>
            {withMea && (
              <th
                className="px-3 py-2 text-left font-medium"
                title="Towar przyszedł na gazetkę: kod MEA z dostawy z bazy albo ITM8 promocyjny z formularza MEA"
              >
                Gazetka MEA
              </th>
            )}
            <SortTh column="supplier" sort={sort} onSort={toggleSort}>
              Dostawca
            </SortTh>
            <SortTh column="quantity" sort={sort} onSort={toggleSort} align="right">
              Ilość
            </SortTh>
            <SortTh column="price" sort={sort} onSort={toggleSort} align="right" title="Cena zakupu netto po korektach">
              Cena zakupu
            </SortTh>
            <SortTh column="value" sort={sort} onSort={toggleSort} align="right">
              Wartość
            </SortTh>
          </tr>
        </thead>
        <tbody>
          {list.length === 0 && (
            <EmptyListRow colSpan={columns}>Żadna dostawa nie pasuje do wyszukiwania.</EmptyListRow>
          )}
          {list.map((delivery) => (
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
              {withMea && (
                <td className="px-3 py-1">
                  {delivery.mea.length > 0 ? (
                    <div className="grid gap-0.5">
                      {delivery.mea.map((mea) => (
                        <MeaBadge key={mea.number} mea={mea} canOpen={canOpenMea} />
                      ))}
                    </div>
                  ) : (
                    '—'
                  )}
                </td>
              )}
              <td className="truncate px-3 py-1.5" title={delivery.supplierName ?? undefined}>
                {delivery.supplierName ?? delivery.supplierCode ?? '—'}
              </td>
              <td className="px-3 py-1.5 text-right">
                {formatQuantity(delivery.quantity)}
                <BeforeCorrection
                  changed={delivery.originalQuantity != null && delivery.originalQuantity !== delivery.quantity}
                >
                  {delivery.originalQuantity != null && formatQuantity(delivery.originalQuantity)}
                </BeforeCorrection>
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                <PriceChange delivery={delivery} />
                {delivery.purchasePrice == null ? '—' : `${formatPrice(delivery.purchasePrice)} zł`}
                {delivery.originalQuantity != null && (
                  <span
                    className="ml-1.5 rounded bg-amber-200 px-1 py-px text-xs font-medium text-amber-950 dark:bg-amber-900 dark:text-amber-50"
                    title="Cena po korektach przypiętych do przyjęcia"
                  >
                    po korekcie
                  </span>
                )}
                <BeforeCorrection
                  changed={
                    delivery.originalPurchasePrice != null &&
                    (delivery.purchasePrice == null ||
                      Math.abs(delivery.originalPurchasePrice - delivery.purchasePrice) >= 0.00005)
                  }
                >
                  {delivery.originalPurchasePrice != null && `${formatPrice(delivery.originalPurchasePrice)} zł`}
                </BeforeCorrection>
              </td>
              <td className="px-3 py-1.5 text-right whitespace-nowrap">
                {formatZloty(delivery.purchaseValue)}
                <BeforeCorrection
                  changed={
                    delivery.originalPurchaseValue != null &&
                    Math.abs(delivery.originalPurchaseValue - delivery.purchaseValue) >= 0.005
                  }
                >
                  {delivery.originalPurchaseValue != null && formatZloty(delivery.originalPurchaseValue)}
                </BeforeCorrection>
              </td>
            </tr>
          ))}
          <LoadMoreRow
            colSpan={columns}
            hasMore={deliveries.hasNextPage}
            loading={deliveries.isFetchingNextPage}
            loaded={list.length}
            onLoadMore={() => void deliveries.fetchNextPage()}
            root={scrollRef}
          />
        </tbody>
      </table>
    </ArticleListSection>
  )
}

/**
 * Protokoły artykułu inne niż przyjęcia i korekty, po 15 jak dostawy: rodzaj, numer i ilość. Przeniesienie stanów
 * zarejestrowane jako gratis ma plakietkę „Gratis”. Numer prowadzi do dokumentu.
 */
function ArticleProtocols({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const controls = useListControls(PROTOCOL_SORT)
  const { sort, toggleSort } = controls
  const protocols = useArticleProtocolRows(storeId, itm8, {
    query: controls.query,
    sort: sort.key,
    direction: sort.direction,
  })
  const scrollRef = useRef<HTMLDivElement>(null)
  const canOpenDocuments = useCan('DOCUMENTS', 'VIEW')
  const pages = protocols.data?.pages
  const list = pages?.flatMap((page) => page.items)
  const total = pages?.at(-1)?.total ?? 0
  const searching = controls.query.trim() !== ''

  if (!list) {
    return (
      <ListPlaceholder title="Protokoły">
        {protocols.isError ? (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
            Nie udało się wczytać protokołów.
          </p>
        ) : (
          <div aria-hidden className="h-20 loading-shimmer rounded-lg" />
        )}
      </ListPlaceholder>
    )
  }
  if (total === 0 && !searching) {
    return (
      <ListPlaceholder title="Protokoły">
        <p className="rounded-lg bg-muted px-4 py-3 text-sm text-muted-foreground">
          Brak protokołów tego artykułu we wgranych raportach dokumentów.
        </p>
      </ListPlaceholder>
    )
  }

  return (
    <ArticleListSection
      title="Protokoły"
      note={searching ? `pasuje: ${total}` : `dokumenty: ${total}`}
      searchLabel="Szukaj: numer, rodzaj"
      search={controls.text}
      onSearch={controls.setText}
      scrollRef={scrollRef}
      className="max-h-56"
    >
      <table
        className={cn(
          'w-full table-fixed text-sm tabular-nums transition-opacity',
          protocols.isPlaceholderData && 'opacity-60',
        )}
      >
        <colgroup>
          <col className="w-28" />
          <col />
          <col className="w-36" />
          <col className="w-24" />
        </colgroup>
        <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
          <tr>
            <SortTh column="date" sort={sort} onSort={toggleSort}>
              Data
            </SortTh>
            <SortTh column="type" sort={sort} onSort={toggleSort}>
              Protokół
            </SortTh>
            <SortTh column="number" sort={sort} onSort={toggleSort}>
              Numer
            </SortTh>
            <SortTh column="quantity" sort={sort} onSort={toggleSort} align="right">
              Ilość
            </SortTh>
          </tr>
        </thead>
        <tbody>
          {list.length === 0 && <EmptyListRow colSpan={4}>Żaden protokół nie pasuje do wyszukiwania.</EmptyListRow>}
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
          <LoadMoreRow
            colSpan={4}
            hasMore={protocols.hasNextPage}
            loading={protocols.isFetchingNextPage}
            loaded={list.length}
            onLoadMore={() => void protocols.fetchNextPage()}
            root={scrollRef}
          />
        </tbody>
      </table>
    </ArticleListSection>
  )
}

/** Wartość z bonu sprzed korekt, przekreślona pod wartością po korektach; bez zmiany nic. */
function BeforeCorrection({ changed, children }: { changed: boolean; children: ReactNode }) {
  if (!changed) return null
  return (
    <span className="block text-xs text-muted-foreground line-through" title="Z bonu, przed korektą">
      {children}
    </span>
  )
}

/** Zmiana ceny względem poprzedniej (starszej) dostawy, także przy innym sortowaniu; bez zmiany nic. */
function PriceChange({ delivery }: { delivery: ArticleDelivery }) {
  const before = delivery.previousPurchasePrice
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

/** Dni od {@code from} do {@code to} (ujemne, gdy {@code to} jest wcześniej). */
function daysBetween(from: string, to: string) {
  return Math.round((parseIsoDate(to).getTime() - parseIsoDate(from).getTime()) / 86_400_000)
}

/**
 * Widoczna część odpowiedzi (pobieramy też dni przed i po do przeciągania): sumy, straty i dni tylko z okna, żeby
 * kafelki i zapas na półce liczyły się z tego, co widać.
 */
function visibleHistory(response: SalesHistory, window: DayWindow, range: DateRange): SalesHistory {
  const start = Math.max(0, Math.round(window.start))
  const end = Math.min(response.days.length, Math.round(window.end))
  const days = response.days.slice(start, Math.max(start, end))
  return {
    from: days[0]?.date ?? range.from,
    to: days.at(-1)?.date ?? range.to,
    days,
    total: sumFigures(days.map((day) => day.figures)),
    lossValue: days.reduce((sum, day) => sum + day.lossValue, 0),
  }
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
type Scene = { scope: string; key: string; bars: Bar[]; values: number[]; frame: Frame; panning: boolean }
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
  panning = false,
  onPan,
  loading = false,
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
  /** Trwa przeciąganie: okno zmienia się co ruch myszy, bez przelotu kamery. */
  panning?: boolean
  /**
   * Przeciąganie wykresu w bok: przesunięcie od chwytu w dniach (dodatnie = wstecz, w prawo ciągnie się przeszłość),
   * {@code done} po puszczeniu.
   */
  onPan?: (daysBack: number, done: boolean) => void
  /** Dociągają się dni (nowy okres po przeciągnięciu albo strzałce): refleks w miejscu brakujących dni. */
  loading?: boolean
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
  // Chwyt przy przeciąganiu: położenie myszy, szerokość okna w dniach i czy już ruszyło (wtedy klik nie zbliża).
  const grab = useRef<{ x: number; span: number; moved: boolean } | null>(null)
  const justPanned = useRef(false)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const paddingRight = line ? 56 : 12
  // Przy dniach nad słupkami jest wiersz sum tygodni.
  const padTop = step === 1 ? PADDING.top + 28 : PADDING.top
  const values = bars.map((bar) => barSeries.value(bar.figures) ?? 0)
  // Słupki spoza okna (zapas do przeciągania) nie wpływają na skalę, średnią ani podpisy.
  const inView = (bar: Bar) => bar.end > shownDays.start + 0.01 && bar.start < shownDays.end - 0.01
  const viewValues = values.filter((_, index) => inView(bars[index]))
  const ticks = niceTicks(Math.min(0, ...viewValues), Math.max(0, ...viewValues))
  const key = `${shownDays.start}:${shownDays.end}`
  const hovered = hover?.key === key && hover.index < bars.length ? hover.index : null
  const target: Frame = { w0: shownDays.start, w1: shownDays.end, low: ticks[0], high: ticks.at(-1)! }

  // Nowe okno w tym samym okresie: przelot od tego, co było na ekranie (także od połowy przerwanego przelotu).
  // Przed malowaniem, żeby nowe słupki nie mignęły w docelowym miejscu.
  useLayoutEffect(() => {
    const previous = shown.current
    if (!previous) return
    // Nowe dane (np. dociągnięte po przeciągnięciu) mają inne indeksy dni: trwający przelot liczony w starych
    // indeksach zjechałby wykresem z powrotem, więc go kończymy.
    if (previous.scope !== scope) {
      setZoom(null)
      return
    }
    // Przeciąganie i jego puszczenie przesuwają okno ręką, bez przelotu kamery.
    if (previous.key === key || panning || previous.panning || prefersReducedMotion()) return
    const start = performance.now()
    setZoom({ from: previous.frame, outgoing: previous, start })
    setNow(start)
  }, [key, scope, panning])

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
    shown.current = { scope, key, bars, values, frame, panning }
  })

  const plotWidth = Math.max(0, width - PADDING.left - paddingRight)
  const plotHeight = CHART_HEIGHT - padTop - PADDING.bottom
  const plotRight = PADDING.left + plotWidth
  const dayX = (day: number) => PADDING.left + ((day - frame.w0) / (frame.w1 - frame.w0 || 1)) * plotWidth
  const y = (value: number) => padTop + ((frame.high - value) / (frame.high - frame.low || 1)) * plotHeight
  const left = (index: number) => dayX(bars[index].start)
  const bandOf = (bar: Bar) => dayX(bar.end) - dayX(bar.start)
  const center = (index: number) => left(index) + bandOf(bars[index]) / 2
  const average = viewValues.length > 0 ? viewValues.reduce((sum, value) => sum + value, 0) / viewValues.length : 0

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
  const defined = lineValues.filter((value, index): value is number => value != null && inView(bars[index]))
  const lineTicks = defined.length > 0 ? lineRangeTicks(Math.min(...defined), Math.max(...defined)) : []
  const lineLow = lineTicks[0] ?? 0
  const lineHigh = lineTicks.at(-1) ?? 1
  const lineY = (value: number) => padTop + ((lineHigh - value) / (lineHigh - lineLow || 1)) * plotHeight

  // Przy dniach podpisujemy poniedziałki (co któryś, gdy się nie mieszczą), przy zbliżeniu na tydzień każdy dzień,
  // przy tygodniach co któryś słupek.
  const viewCount = bars.filter(inView).length
  const everyDay = step === 1 && viewCount <= 14
  const maxLabels = Math.max(1, Math.floor(plotWidth / 72))
  const mondays = step === 1 ? bars.flatMap((bar, index) => (isMonday(bar.from) ? [index] : [])) : []
  const candidates = (step === 1 && !everyDay ? mondays : bars.map((_, index) => index)).filter((index) =>
    inView(bars[index]),
  )
  const every = Math.max(1, Math.ceil(candidates.length / maxLabels))
  const labelled = new Set(candidates.filter((_, position) => position % every === 0))

  const settled = zoom == null
  const active = hovered == null || !settled ? null : bars[hovered]
  const activeLine = hovered == null ? null : lineValues[hovered]
  const activeTop =
    hovered == null ? 0 : Math.min(y(Math.max(0, values[hovered])), activeLine == null ? Infinity : lineY(activeLine))
  const zoomable = (bar: Bar) => onZoom != null && bar.end - bar.start > 1
  const weeks = step === 1 ? weekTotals(bars, values) : []

  const startPan = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!onPan || event.button !== 0) return
    grab.current = { x: event.clientX, span: shownDays.end - shownDays.start, moved: false }
  }
  const movePan = (event: React.PointerEvent<HTMLDivElement>) => {
    const current = grab.current
    if (!current || !onPan || plotWidth <= 0) return
    const dx = event.clientX - current.x
    if (!current.moved && Math.abs(dx) < 4) return
    if (!current.moved) {
      current.moved = true
      event.currentTarget.setPointerCapture(event.pointerId)
      setHover(null)
    }
    onPan((dx / plotWidth) * current.span, false)
  }
  const endPan = (event: React.PointerEvent<HTMLDivElement>) => {
    const current = grab.current
    grab.current = null
    if (!current?.moved || !onPan) return
    justPanned.current = true
    onPan(((event.clientX - current.x) / plotWidth) * current.span, true)
  }

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
      <div
        ref={ref}
        className={cn(
          'relative min-w-0 overflow-hidden select-none',
          onPan && (panning ? 'cursor-grabbing' : 'cursor-grab'),
        )}
        // Przewijanie strony w pionie zostaje dotykowi, poziomy ruch przesuwa wykres.
        style={onPan ? { touchAction: 'pan-y' } : undefined}
        onMouseLeave={() => setHover(null)}
        onPointerDown={startPan}
        onPointerMove={movePan}
        onPointerUp={endPan}
        onPointerCancel={endPan}
      >
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
              {/* Podpisy, linia i kreski tygodni: tylko w szerokości wykresu (zapas do przeciągania leży obok). */}
              <clipPath id={`${clipId}x`}>
                <rect x={PADDING.left - 2} y={0} width={plotWidth + 4} height={CHART_HEIGHT} />
              </clipPath>
              <clipPath id={`${clipId}ticks`}>
                <rect x={0} y={padTop - 8} width={width} height={plotHeight + 16} />
              </clipPath>
            </defs>

            {settled && (
              <g clipPath={`url(#${clipId}x)`} className="animate-in fade-in duration-200 motion-reduce:animate-none">
                {mondays
                  .filter((index) => index > 0)
                  .map((index) => (
                    <line
                      key={`week${bars[index].start}`}
                      x1={left(index)}
                      x2={left(index)}
                      y1={padTop - 28}
                      y2={CHART_HEIGHT - PADDING.bottom + 6}
                      strokeWidth={2}
                      className="stroke-foreground/20"
                    />
                  ))}
                {weeks.map((week) => {
                  const from = left(week.first)
                  const to = left(week.last) + bandOf(bars[week.last])
                  const room = to - from
                  if (room < 44) return null
                  const up = week.change != null && week.change >= 0
                  return (
                    <text
                      key={`sum${bars[week.first].start}`}
                      x={(from + to) / 2}
                      y={padTop - 10}
                      textAnchor="middle"
                      className="text-sm tabular-nums"
                    >
                      <title>{weekTitle(bars, week, barSeries)}</title>
                      <tspan className="fill-foreground font-semibold">{weekValue(barSeries, week.total)}</tspan>
                      {week.change != null && room >= 96 && (
                        <tspan
                          dx={5}
                          className={cn(
                            'text-xs font-medium',
                            up ? 'fill-emerald-600 dark:fill-emerald-400' : 'fill-red-600 dark:fill-red-400',
                          )}
                        >
                          {up ? '▲' : '▼'}
                          {percentFormat.format(Math.abs(week.change))}%
                        </tspan>
                      )}
                    </text>
                  )
                })}
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
              y={padTop - 12}
              textAnchor="end"
              className="fill-sky-700 text-[0.6875rem] font-medium dark:fill-sky-300"
            >
              {barSeries.unit}
            </text>

            {settled && !panning && hovered != null && (
              <rect
                x={left(hovered)}
                y={padTop}
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
              <g clipPath={`url(#${clipId}x)`} className="animate-in fade-in duration-200 motion-reduce:animate-none">
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
                      y={padTop - 12}
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
                    y={padTop}
                    width={bandOf(bar)}
                    height={plotHeight}
                    fill="transparent"
                    className={cn(zoomable(bar) && 'cursor-zoom-in')}
                    onMouseEnter={() => {
                      if (grab.current?.moved) return
                      setOrigin(ref.current?.getBoundingClientRect() ?? null)
                      setHover({ key, index })
                    }}
                    onClick={
                      zoomable(bar)
                        ? () => {
                            // Puszczenie po przeciągnięciu to nie kliknięcie w tydzień.
                            if (justPanned.current) {
                              justPanned.current = false
                              return
                            }
                            onZoom?.(index)
                          }
                        : undefined
                    }
                  />
                ))}
              </g>
            )}
          </svg>
        )}
        {loading && width > 0 && (
          <LoadingGaps
            gaps={missingDays(bars, shownDays).map(([from, to]) => [
              Math.max(PADDING.left, dayX(from)),
              Math.min(plotRight, dayX(to)),
            ])}
            top={padTop}
            height={plotHeight}
          />
        )}
        {/* Podpowiedź w portalu z position: fixed, więc może wyjść poza okno zamiast przewijać je w bok. */}
        {active &&
          !panning &&
          hovered != null &&
          origin &&
          createPortal(
            <div
              className="pointer-events-none fixed z-100 grid w-max gap-1 rounded-lg glass-float px-3 py-2 text-sm text-popover-foreground"
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
          ? `Słupek = dzień, grube linie oddzielają tygodnie (od poniedziałku), nad tygodniem jego suma i zmiana wobec poprzedniego, kreska na osi = dzień bez sprzedaży${onPan ? ' · przeciągnij wykres w bok, żeby oglądać wcześniejsze dni' : ''}`
          : `Słupek = 7 dni${onZoom ? ' · kliknij słupek, żeby rozbić go na dni' : ''}${onPan ? ' · przeciągnij wykres w bok, żeby oglądać wcześniejsze tygodnie' : ''}`}
      </p>
    </div>
  )
}

/** Części okna bez pobranych dni (przed pierwszym i po ostatnim słupku odpowiedzi), w indeksach dni. */
function missingDays(bars: Bar[], window: DayWindow): [number, number][] {
  if (bars.length === 0) return [[window.start, window.end]]
  const gaps: [number, number][] = []
  if (window.start < bars[0].start) gaps.push([window.start, Math.min(window.end, bars[0].start)])
  if (window.end > bars.at(-1)!.end) gaps.push([Math.max(window.start, bars.at(-1)!.end), window.end])
  return gaps
}

/**
 * Wczytywanie dni wykresu: cienki pasek z refleksem nad wykresem, a w miejscu brakujących dni blok z przesuwającym się
 * refleksem (sam transform, jak `LoadingPanel`) i napisem, gdy jest miejsce. Pojawia się po chwili, żeby szybkie
 * wczytanie nie migało; przy ograniczonym ruchu bez ruchu.
 */
function LoadingGaps({ gaps, top, height }: { gaps: number[][]; top: number; height: number }) {
  return (
    <div
      aria-live="polite"
      className="pointer-events-none absolute inset-0 motion-reduce:!animate-none"
      // Po 150 ms: szybkie wczytanie nie miga.
      style={{ animation: 'loading-label-in 200ms cubic-bezier(0.23, 1, 0.32, 1) 150ms both' }}
    >
      <span className="sr-only">Wczytuję dni wykresu</span>
      <div className="absolute inset-x-0 top-0 h-1 rounded-full loading-shimmer" />
      {gaps.map(([from, to]) =>
        to - from < 4 ? null : (
          <div
            key={from}
            className="absolute flex items-center justify-center rounded-lg loading-shimmer"
            style={{ left: from + 2, width: to - from - 4, top, height }}
          >
            {to - from > 110 && (
              <span className="loading-label flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                <Loader2Icon aria-hidden className="loading-spinner size-3.5" />
                Wczytuję dni…
              </span>
            )}
          </div>
        ),
      )}
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

/** Suma tygodnia nad słupkami: zł skrótem (12,3 tys. zł), ilość skrótem. */
const weekValue = (series: Series, total: number) =>
  series.unit === 'zł' ? `${compactFormat.format(total)} zł` : compactFormat.format(total)

type WeekTotal = { first: number; last: number; total: number; change: number | null; partial: boolean }

function weekTitle(bars: Bar[], week: WeekTotal, series: Series) {
  const range = formatRange({ from: bars[week.first].from, to: bars[week.last].to })
  if (week.change == null) return `Tydzień ${range}: ${series.format(week.total)}`
  const sign = week.change >= 0 ? '+' : '−'
  const against = week.partial ? 'tych samych dni poprzedniego tygodnia' : 'poprzedniego tygodnia'
  return `Tydzień ${range}: ${series.format(week.total)} (${sign}${percentFormat.format(Math.abs(week.change))}% wobec ${against})`
}

/**
 * Tygodnie pon.-niedz. ze słupków dni (indeksy pierwszego i ostatniego słupka), suma serii i zmiana % wobec
 * poprzedniego tygodnia. Niepełny tydzień na końcu (tydzień w toku) porównujemy z tymi samymi dniami tygodnia
 * poprzedniego; niepełny na początku danych i poprzedni bez sprzedaży nie mają zmiany.
 */
function weekTotals(bars: Bar[], values: number[]): WeekTotal[] {
  const weeks: { first: number; last: number; values: number[] }[] = []
  bars.forEach((bar, index) => {
    const current = weeks.at(-1)
    if (!current || isMonday(bar.from)) {
      weeks.push({ first: index, last: index, values: [values[index] ?? 0] })
    } else {
      current.last = index
      current.values.push(values[index] ?? 0)
    }
  })
  const sum = (list: number[]) => list.reduce((total, value) => total + value, 0)
  return weeks.map((week, index) => {
    const previous = weeks[index - 1]
    const total = sum(week.values)
    const full = week.values.length === 7
    const comparable = previous != null && previous.values.length === 7 && (full || index === weeks.length - 1)
    const before = comparable ? sum(previous.values.slice(0, week.values.length)) : 0
    return {
      first: week.first,
      last: week.last,
      total,
      change: comparable && before > 0 ? ((total - before) / before) * 100 : null,
      partial: !full,
    }
  })
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
