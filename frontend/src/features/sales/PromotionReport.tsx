import { useDeferredValue, useMemo, useState } from 'react'
import { ChartColumnIcon, ChevronLeftIcon, ChevronRightIcon, InfoIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { DateField, SegmentedField, SelectField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney } from '@/features/documents/format'
import { type SalesChartTarget, usePromotionReport, useSalesReportsOverview } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import {
  canShiftForward,
  currentPeriod,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  lastCompleteWeekEnd,
  PERIOD_MODES,
  type PeriodMode,
  resolveAnchor,
  shiftAnchor,
} from '@/features/sales/periods'
import { ApiError } from '@/lib/api'
import { addDays, parseIsoDate, todayIso } from '@/lib/dates'
import { matchesSearch } from '@/lib/search'
import { type SortAccessors, useTableSort } from '@/lib/table-sort'
import type { PromotionReport as Report, PromotionReportItem, PromotionVerdict } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

/** Jak PromotionReportService.MAX_PERIOD_DAYS: rozpoznawanie promocji ze sprzedaży liczy się ok. 2 s na miesiąc. */
const MAX_DAYS = 92
const PAGE_SIZE = 100
const NO_FILTER: string[] = []

type Mode = Exclude<PeriodMode, 'day'>
const MODES = PERIOD_MODES.filter((option): option is { value: Mode; label: string } => option.value !== 'day')
const MODE_PARAMS: Record<Mode, string> = { week: 'tydzien', month: 'miesiac', custom: 'wlasny' }
const ANCHOR_LABELS: Record<Exclude<Mode, 'custom'>, string> = { week: 'Tydzień z dniem', month: 'Miesiąc z dniem' }
const STEP_LABELS: Record<Exclude<Mode, 'custom'>, [string, string]> = {
  week: ['Poprzedni tydzień', 'Następny tydzień'],
  month: ['Poprzedni miesiąc', 'Następny miesiąc'],
}

type VerdictFilter = PromotionVerdict | 'ALL'
const VERDICT_PARAMS: Record<PromotionVerdict, string> = {
  BELOW_COST: 'ponizej-kosztu',
  NO_REACTION: 'brak-reakcji',
  TOO_DEEP: 'za-gleboka',
  PROFITABLE: 'oplacalne',
  NO_BASELINE: 'bez-porownania',
}
/** Od najgorszych; kolejność zakładek i plakietek. */
const VERDICT_ORDER: PromotionVerdict[] = ['BELOW_COST', 'NO_REACTION', 'TOO_DEEP', 'PROFITABLE', 'NO_BASELINE']
const VERDICTS: Record<PromotionVerdict, { label: string; detail: string; badge: string; dot: string }> = {
  BELOW_COST: {
    label: 'Poniżej kosztu',
    detail: 'cena promocyjna niższa niż zakup FIFO: każda sztuka to strata',
    badge: 'bg-red-300 text-red-950 dark:bg-red-500/45 dark:text-red-50',
    dot: 'bg-red-600',
  },
  NO_REACTION: {
    label: 'Brak reakcji',
    detail: 'sprzedaż prawie nie wzrosła: ci sami klienci kupili to samo taniej',
    badge: 'bg-amber-300 text-amber-950 dark:bg-amber-500/45 dark:text-amber-50',
    dot: 'bg-amber-600',
  },
  TOO_DEEP: {
    label: 'Za głęboka obniżka',
    detail: 'sprzedaż wzrosła, ale nie do progu opłacalności',
    badge: 'bg-orange-300 text-orange-950 dark:bg-orange-500/45 dark:text-orange-50',
    dot: 'bg-orange-600',
  },
  PROFITABLE: {
    label: 'Opłacalna',
    detail: 'marża zł co najmniej taka, jak bez promocji',
    badge: 'bg-emerald-300 text-emerald-950 dark:bg-emerald-500/45 dark:text-emerald-50',
    dot: 'bg-emerald-600',
  },
  NO_BASELINE: {
    label: 'Bez porównania',
    detail: 'artykuł nie sprzedawał się przed promocją albo przed nią też były promocje',
    badge: 'bg-zinc-300 text-zinc-950 dark:bg-zinc-500/45 dark:text-zinc-50',
    dot: 'bg-zinc-500',
  },
}
const LOSING: PromotionVerdict[] = ['BELOW_COST', 'NO_REACTION', 'TOO_DEEP']

type Source = 'all' | 'list' | 'sales'
const SOURCE_OPTIONS: { value: Source; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'list', label: 'Z listy promocji' },
  { value: 'sales', label: 'Rozpoznane' },
]
const SOURCE_PARAMS: Record<Source, string | null> = { all: null, list: 'lista', sales: 'sprzedaz' }
const ALL_REGIONS = 'wszystkie'

type SortKey = 'article' | 'date' | 'discount' | 'uplift' | 'margin' | 'extra'
const SORT_BY: SortAccessors<PromotionReportItem, SortKey> = {
  article: (row) => row.name ?? row.itm8,
  date: (row) => row.endDate,
  discount: (row) => row.discountPercent,
  uplift: (row) => row.uplift,
  margin: (row) => row.marginValue,
  extra: (row) => row.extraMargin,
}

const quantityFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 })
const ratioFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const percentFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 })
const shortDate = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'short' })
const fullDate = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long', year: 'numeric' })

const ratio = (value: number) => `${ratioFormat.format(value)}×`
const signedMoney = (value: number) => `${value > 0 ? '+' : value < 0 ? '−' : ''}${formatMoney(Math.abs(value))} zł`
const dateRange = (from: string, to: string) =>
  from === to
    ? shortDate.format(parseIsoDate(from))
    : `${shortDate.format(parseIsoDate(from))} – ${shortDate.format(parseIsoDate(to))}`

/** Spadek sprzedaży po promocji, który pokazujemy: klienci zrobili zapas. */
const AFTER_DIP = 0.8
/** Przy wolno rotujących artykułach spadek po promocji to przypadek: liczymy go od tylu sztuk spodziewanych po niej. */
const AFTER_MIN_UNITS = 5

/** Straty w trakcie i po promocji wyraźnie większe niż zwykle (o połowę i co najmniej 20 zł). */
function highLosses(row: PromotionReportItem) {
  const usual = row.usualLossValue ?? 0
  return row.lossValue >= 20 && row.lossValue - usual >= 20 && row.lossValue > usual * 1.5
}

/**
 * Skuteczność promocji: czy promocja dała więcej marży zł, niż artykuł dałby bez niej. Każda promocja ma swój próg
 * opłacalności (ile razy więcej trzeba sprzedać, żeby niższa marża na sztuce się zwróciła), a raport porównuje go
 * ze wzrostem sprzedaży względem 28 dni przed promocją. Promocje z listy promocji i rozpoznane ze spadku ceny.
 */
export function PromotionReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const [params, setParams] = useSearchParams()

  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday
  const mode = (Object.keys(MODE_PARAMS) as Mode[]).find((key) => MODE_PARAMS[key] === params.get('okres')) ?? 'month'
  const anchor = resolveAnchor(mode, params.get('data') ?? lastSaleDate, lastSaleDate)
  const custom: DateRange = {
    from: params.get('od') ?? addDays(lastSaleDate, -29),
    to: params.get('do') ?? lastSaleDate,
  }
  const customError =
    mode !== 'custom'
      ? null
      : custom.from > custom.to
        ? 'Data „od” jest późniejsza niż „do”.'
        : dayCount(custom) > MAX_DAYS
          ? `Okres może mieć najwyżej ${MAX_DAYS} dni.`
          : null
  const range = currentPeriod(mode, anchor, custom, lastSaleDate)

  const setParam = (changes: Record<string, string | null>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => (value == null ? next.delete(key) : next.set(key, value)))
        return next
      },
      { replace: true },
    )

  const report = usePromotionReport(storeId, range, !customError && !overview.isPending)

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <h2 className="mb-1 text-xl font-semibold tracking-tight">Skuteczność promocji</h2>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Promocje kończące się w okresie. Każdą porównujemy ze sprzedażą tego artykułu w 28 dniach przed nią: czy dała
        więcej marży zł, niż artykuł zarobiłby bez promocji. Marża liczy się z kosztu FIFO, więc tani zakup pod promocję
        jest w niej uwzględniony.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <SegmentedField
          label="Okres"
          options={MODES}
          value={mode}
          onChange={(next) => setParam({ okres: MODE_PARAMS[next] })}
        />

        {mode === 'custom' ? (
          <div className="flex flex-wrap items-start gap-3">
            <DateField
              label="Od"
              value={custom.from}
              onChange={(from) => setParam({ od: from, do: custom.to })}
              max={lastSaleDate}
              shortcuts={[]}
              className="w-full sm:w-64"
            />
            <DateField
              label="Do"
              value={custom.to}
              onChange={(to) => setParam({ od: custom.from, do: to })}
              max={lastSaleDate}
              shortcuts={[{ label: 'Ostatni dzień sprzedaży', value: lastSaleDate }]}
              error={customError ?? undefined}
              className="w-full sm:w-64"
            />
          </div>
        ) : (
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <Button
              variant="outline"
              size="icon-lg"
              aria-label={STEP_LABELS[mode][0]}
              title={STEP_LABELS[mode][0]}
              onClick={() => setParam({ data: shiftAnchor(mode, anchor, -1) })}
            >
              <ChevronLeftIcon />
            </Button>
            <DateField
              label={ANCHOR_LABELS[mode]}
              value={anchor}
              onChange={(data) => setParam({ data })}
              max={lastSaleDate}
              shortcuts={
                mode === 'week'
                  ? [{ label: 'Ostatni pełny tydzień', value: lastCompleteWeekEnd(lastSaleDate) }]
                  : [{ label: 'Ostatni dzień sprzedaży', value: lastSaleDate }]
              }
              className="min-w-0 flex-1 sm:w-72 sm:flex-none"
            />
            <Button
              variant="outline"
              size="icon-lg"
              aria-label={STEP_LABELS[mode][1]}
              title={STEP_LABELS[mode][1]}
              disabled={!canShiftForward(mode, anchor, lastSaleDate)}
              onClick={() => setParam({ data: shiftAnchor(mode, anchor, 1) })}
            >
              <ChevronRightIcon />
            </Button>
          </div>
        )}

        {!customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground"> · promocje kończące się w tych {daysLabel(dayCount(range))}</span>
          </p>
        )}
      </Card>

      {customError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">
            {report.error instanceof ApiError ? report.error.message : 'Nie udało się policzyć raportu'}
          </p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <Promotions storeId={storeId} report={report.data} params={params} setParam={setParam} />
        </div>
      )}
    </>
  )
}

function Promotions({
  storeId,
  report,
  params,
  setParam,
}: {
  storeId: string
  report: Report
  params: URLSearchParams
  setParam: (changes: Record<string, string | null>) => void
}) {
  const verdict: VerdictFilter = VERDICT_ORDER.find((key) => VERDICT_PARAMS[key] === params.get('ocena')) ?? 'ALL'
  const source =
    (Object.keys(SOURCE_PARAMS) as Source[]).find((key) => SOURCE_PARAMS[key] === params.get('zrodlo')) ?? 'all'
  const query = params.get('szukaj') ?? ''
  const deferredQuery = useDeferredValue(query)
  const region = params.get('rejon') ?? ALL_REGIONS
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const [limit, setLimit] = useState(PAGE_SIZE)

  const regions = useMemo(
    () =>
      [...new Map(report.promotions.filter((row) => row.regionCode).map((row) => [row.regionCode!, row])).values()]
        .map((row) => ({ value: row.regionCode!, label: row.regionName ?? row.regionCode! }))
        .sort((a, b) => a.value.localeCompare(b.value)),
    [report.promotions],
  )
  // Zakładki ocen liczą promocje po pozostałych filtrach, a lista dodatkowo po ocenie.
  const matching = useMemo(
    () =>
      report.promotions.filter(
        (row) =>
          (source === 'all' || row.listed === (source === 'list')) &&
          (region === ALL_REGIONS || row.regionCode === region) &&
          matchesSearch([row.name, row.brand, row.ean, row.itm8, row.regionName], deferredQuery),
      ),
    [report.promotions, source, region, deferredQuery],
  )
  const shown = useMemo(
    () => (verdict === 'ALL' ? matching : matching.filter((row) => row.verdict === verdict)),
    [matching, verdict],
  )
  const { sorted, sort, toggle } = useTableSort(shown, SORT_BY, { key: 'extra', direction: 'asc' })
  const visible = sorted.slice(0, limit)
  const loadMoreRef = useLoadMore<HTMLDivElement>(
    () => setLimit((current) => current + PAGE_SIZE),
    sorted.length > limit,
    visible.length,
  )

  const counts = Object.fromEntries(VERDICT_ORDER.map((key) => [key, 0])) as Record<PromotionVerdict, number>
  matching.forEach((row) => counts[row.verdict]++)
  const listed = matching.filter((row) => row.listed).length
  const margin = matching.reduce((sum, row) => sum + row.marginValue, 0)
  const gross = matching.reduce((sum, row) => sum + row.saleValueGross, 0)
  const extra = matching.reduce((sum, row) => sum + (row.extraMargin ?? 0), 0)
  const losing = matching.filter((row) => LOSING.includes(row.verdict))
  const lost = losing.reduce((sum, row) => sum + (row.extraMargin ?? 0), 0)
  const filtersActive = query !== '' || region !== ALL_REGIONS || source !== 'all'

  return (
    <>
      <ListNotice report={report} />

      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Tile
          label="Promocje"
          value={String(matching.length)}
          detail={`z listy ${listed} · rozpoznane ${matching.length - listed}`}
        />
        <Tile
          label="Marża w promocjach"
          value={`${formatMoney(margin)} zł`}
          detail={`sprzedaż brutto ${formatMoney(gross)} zł`}
        />
        <Tile
          label="Wynik promocji"
          value={signedMoney(extra)}
          detail={extra >= 0 ? 'więcej marży niż bez promocji' : 'mniej marży niż bez promocji'}
          tone={extra >= 0 ? 'good' : 'bad'}
        />
        <Tile
          label="Nieopłacalne"
          value={String(losing.length)}
          detail={losing.length ? `zabrały ${formatMoney(Math.abs(lost))} zł marży` : 'brak'}
          tone={losing.length ? 'bad' : undefined}
        />
      </div>

      <div role="tablist" aria-label="Ocena" className="mb-3 flex flex-wrap gap-2">
        <VerdictTab
          selected={verdict === 'ALL'}
          label="Wszystkie"
          count={matching.length}
          onClick={() => setParam({ ocena: null })}
        />
        {VERDICT_ORDER.map((key) => (
          <VerdictTab
            key={key}
            selected={verdict === key}
            label={VERDICTS[key].label}
            title={VERDICTS[key].detail}
            dot={VERDICTS[key].dot}
            count={counts[key]}
            onClick={() => setParam({ ocena: VERDICT_PARAMS[key] })}
          />
        ))}
      </div>

      <div className="mb-3 grid gap-3 rounded-xl border bg-card p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]">
        <SearchInput
          value={query}
          onChange={(next) => setParam({ szukaj: next || null })}
          placeholder="Szukaj: artykuł, marka, EAN, ITM8"
        />
        <SelectField
          label="Rejon"
          options={[{ value: ALL_REGIONS, label: 'Wszystkie rejony' }, ...regions]}
          value={region}
          onChange={(next) => setParam({ rejon: next && next !== ALL_REGIONS ? next : null })}
        />
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 md:col-span-2">
          <span className="flex items-center gap-2 text-sm text-muted-foreground">
            Promocje
            <SegmentedField
              label="Skąd promocje"
              options={SOURCE_OPTIONS}
              value={source}
              onChange={(next) => setParam({ zrodlo: SOURCE_PARAMS[next] })}
            />
          </span>
          {filtersActive && (
            <Button
              variant="ghost"
              className="ml-auto"
              onClick={() => setParam({ szukaj: null, rejon: null, zrodlo: null })}
            >
              Wyczyść filtry
            </Button>
          )}
        </div>
      </div>

      {sorted.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">
            {report.promotions.length === 0
              ? 'Brak zakończonych promocji w tym okresie'
              : 'Żadna promocja nie pasuje do filtrów'}
          </p>
        </Card>
      ) : (
        <Card className="gap-0 overflow-clip p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <SortableHead column="article" sort={sort} onSort={toggle} className="min-w-64">
                  Artykuł
                </SortableHead>
                <SortableHead column="date" sort={sort} onSort={toggle}>
                  Termin
                </SortableHead>
                <SortableHead column="discount" sort={sort} onSort={toggle} align="right">
                  Cena
                </SortableHead>
                <TableHead className="text-right">Sprzedaż dziennie</TableHead>
                <SortableHead column="uplift" sort={sort} onSort={toggle} align="right">
                  Wzrost / próg
                </SortableHead>
                <SortableHead column="margin" sort={sort} onSort={toggle} align="right">
                  Marża w promocji
                </SortableHead>
                <SortableHead column="extra" sort={sort} onSort={toggle} align="right">
                  Wynik
                </SortableHead>
                <TableHead>Ocena</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((row) => (
                <PromotionRow
                  key={`${row.itm8}:${row.startDate}:${row.endDate}`}
                  row={row}
                  onChart={() =>
                    setChart({ kind: 'article', itm8: row.itm8, name: row.name, ean: row.ean, brand: row.brand })
                  }
                />
              ))}
            </TableBody>
          </Table>
          <div ref={loadMoreRef} />
        </Card>
      )}

      <Explanation report={report} />

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={NO_FILTER}
        basis="gross"
        lastSaleDate={report.lastSaleDate ?? report.to}
        onClose={() => setChart(null)}
      />
    </>
  )
}

function PromotionRow({ row, onChart }: { row: PromotionReportItem; onChart: () => void }) {
  const style = VERDICTS[row.verdict]
  const reachesBreakEven = row.uplift != null && row.breakEvenUplift != null && row.uplift >= row.breakEvenUplift
  const dip =
    row.afterRatio != null &&
    row.afterRatio < AFTER_DIP &&
    (row.baseQuantityPerDay ?? 0) * row.afterDays >= AFTER_MIN_UNITS
  return (
    <TableRow className="align-top">
      <TableCell className="whitespace-normal">
        <div className="flex items-start gap-1.5">
          <div className="min-w-0 flex-1">
            <p className="font-medium">{row.name ?? 'Artykuł spoza kartoteki'}</p>
            <p className="text-xs text-muted-foreground tabular-nums">
              {[row.brand, row.itm8, row.regionName].filter(Boolean).join(' · ')}
            </p>
          </div>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Wykres sprzedaży"
            title="Wykres sprzedaży"
            onClick={onChart}
          >
            <ChartColumnIcon />
          </Button>
        </div>
      </TableCell>
      <TableCell className="tabular-nums">
        <p>{dateRange(row.startDate, row.endDate)}</p>
        <p className="text-xs text-muted-foreground">
          {daysLabel(row.days)}
          {!row.listed && (
            <span title="Promocji nie ma na liście promocji: rozpoznana ze spadku ceny w sprzedaży, daty przybliżone">
              {' '}
              · rozpoznana
            </span>
          )}
        </p>
      </TableCell>
      <TableCell className="text-right tabular-nums">
        <p className="font-medium">{row.promoPrice != null ? `${formatMoney(row.promoPrice)} zł` : '–'}</p>
        {row.regularPrice != null && (
          <p className="text-xs text-muted-foreground">
            zwykle {formatMoney(row.regularPrice)}
            {row.discountPercent != null && ` · −${percentFormat.format(row.discountPercent)}%`}
          </p>
        )}
      </TableCell>
      <TableCell className="text-right tabular-nums">
        <p className="font-medium">{quantityFormat.format(row.quantityPerDay)}</p>
        {row.baseQuantityPerDay != null && (
          <p className="text-xs text-muted-foreground">zwykle {quantityFormat.format(row.baseQuantityPerDay)}</p>
        )}
      </TableCell>
      <TableCell className="text-right tabular-nums">
        <p
          className={cn(
            'font-medium',
            row.uplift != null && row.breakEvenUplift != null && !reachesBreakEven && 'text-red-700 dark:text-red-300',
          )}
        >
          {row.uplift != null ? ratio(row.uplift) : '–'}
        </p>
        <p className="text-xs text-muted-foreground">
          {row.breakEvenUplift != null
            ? `próg ${ratio(row.breakEvenUplift)}`
            : row.verdict === 'BELOW_COST'
              ? 'nie do osiągnięcia'
              : ''}
        </p>
      </TableCell>
      <TableCell className="text-right tabular-nums">
        <p className={cn('font-medium', row.marginValue < 0 && 'text-red-700 dark:text-red-300')}>
          {formatMoney(row.marginValue)} zł
        </p>
        <p className="text-xs text-muted-foreground">
          {row.marginPercent != null && `${percentFormat.format(row.marginPercent)}%`}
          {row.baseMarginPercent != null && ` · zwykle ${percentFormat.format(row.baseMarginPercent)}%`}
        </p>
      </TableCell>
      <TableCell className="text-right tabular-nums">
        {row.extraMargin != null ? (
          <span
            className={cn(
              'inline-block rounded-md px-2 py-0.5 font-semibold',
              row.extraMargin >= 0
                ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-500/30 dark:text-emerald-50'
                : 'bg-red-200 text-red-950 dark:bg-red-500/30 dark:text-red-50',
            )}
          >
            {signedMoney(row.extraMargin)}
          </span>
        ) : (
          '–'
        )}
      </TableCell>
      <TableCell className="whitespace-normal">
        <Badge className={cn('gap-1.5 border-transparent font-medium', style.badge)} title={style.detail}>
          {style.label}
        </Badge>
        {dip && (
          <p
            className="mt-1 text-xs text-muted-foreground"
            title={`Sprzedaż dzienna w ${row.afterDays} dniach po promocji względem bazy`}
          >
            po promocji −{percentFormat.format((1 - row.afterRatio!) * 100)}% sprzedaży
          </p>
        )}
        {highLosses(row) && (
          <p
            className="mt-1 text-xs text-red-700 dark:text-red-300"
            title="Likwidacje w trakcie promocji i do 14 dni po niej, w wartości zakupu netto"
          >
            straty {formatMoney(row.lossValue)} zł
            {row.usualLossValue != null && ` (zwykle ${formatMoney(row.usualLossValue)})`}
          </p>
        )}
      </TableCell>
    </TableRow>
  )
}

function Tile({ label, value, detail, tone }: { label: string; value: string; detail: string; tone?: 'good' | 'bad' }) {
  return (
    <Card
      className={cn(
        'gap-1 p-4',
        tone === 'good' &&
          'border-emerald-300 bg-emerald-200/70 text-emerald-950 dark:border-emerald-500/40 dark:bg-emerald-500/20 dark:text-emerald-50',
        tone === 'bad' &&
          'border-red-300 bg-red-200/70 text-red-950 dark:border-red-500/40 dark:bg-red-500/20 dark:text-red-50',
      )}
    >
      <p className="text-sm font-medium">{label}</p>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      <p className={cn('text-sm tabular-nums', tone ? 'opacity-80' : 'text-muted-foreground')}>{detail}</p>
    </Card>
  )
}

function VerdictTab({
  selected,
  label,
  title,
  dot,
  count,
  onClick,
}: {
  selected: boolean
  label: string
  title?: string
  dot?: string
  count: number
  onClick: () => void
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      title={title}
      onClick={onClick}
      className={cn(
        'inline-flex h-9 items-center gap-2 rounded-lg border px-3 text-sm font-medium transition-[background-color,border-color] duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        selected ? 'border-foreground bg-foreground text-background' : 'bg-card hover:bg-muted',
      )}
    >
      {dot && <span aria-hidden className={cn('size-2 rounded-full', dot)} />}
      {label}
      <span className={cn('tabular-nums', selected ? 'opacity-80' : 'text-muted-foreground')}>{count}</span>
    </button>
  )
}

/** Skąd są promocje: lista zna tylko bieżące i przyszłe, więc starsze raport rozpoznaje ze sprzedaży. */
function ListNotice({ report }: { report: Report }) {
  const text = !report.listSince
    ? 'Listy promocji jeszcze nie wgrano, więc wszystkie promocje są rozpoznane ze sprzedaży: daty są przybliżone, a przecena z etykiety może wyglądać jak promocja.'
    : report.listSince > report.from
      ? `Lista promocji jest wgrywana od ${fullDate.format(parseIsoDate(report.listSince))}; wcześniejsze promocje są rozpoznane ze sprzedaży.`
      : null
  if (!text) return null
  return (
    <div className="mb-4 flex items-start gap-3 rounded-xl border border-sky-300 bg-sky-100 p-3 text-sm text-sky-950 dark:border-sky-500/40 dark:bg-sky-500/15 dark:text-sky-50">
      <InfoIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <p>
        {text} Żeby raport znał dokładne daty i ceny, wgrywaj „Listę bieżących i przyszłych promocji” w{' '}
        <Link to="/import" className="font-medium underline underline-offset-2">
          Imporcie danych
        </Link>{' '}
        co najmniej raz w tygodniu.
      </p>
    </div>
  )
}

function Explanation({ report }: { report: Report }) {
  return (
    <div className="mt-4 max-w-3xl space-y-2 text-sm text-muted-foreground">
      <p>
        <span className="font-medium text-foreground">Próg opłacalności</span> to ile razy więcej trzeba sprzedawać
        dziennie, żeby niższa marża na sztuce dała tyle marży zł, co bez promocji: marża na sztuce zwykle ÷ marża na
        sztuce w promocji. Przykład: zwykle 2 zł na sztuce, w promocji 1 zł, więc próg to 2×. Sprzedaż 4× to promocja
        opłacalna, 1,5× nieopłacalna. Każda promocja ma swój próg: płytka obniżka zwraca się szybko, głęboka wymaga dużo
        większej sprzedaży.
      </p>
      <p>
        <span className="font-medium text-foreground">Wynik</span> to marża w promocji minus marża, którą artykuł dałby
        w tych dniach, sprzedając się jak w bazie (dni bez promocji w {report.baseDays} dniach przed nią). Nieopłacalna
        promocja ze wzrostem poniżej {ratio(report.noReactionUplift)} to brak reakcji klientów (artykuł nie nadaje się
        do promocji), powyżej to za głęboka obniżka (wystarczyłaby mniejsza).
      </p>
      <p>
        Pod oceną: spadek sprzedaży w {report.afterDays} dniach po promocji (klienci zrobili zapas) i likwidacje w
        trakcie i po promocji, gdy są wyraźnie większe niż zwykle. Promocje rozpoznane ze sprzedaży to co najmniej 3 dni
        z ceną niższą o ponad 15% od zwykłej, najwyżej 31 dni.
      </p>
    </div>
  )
}
