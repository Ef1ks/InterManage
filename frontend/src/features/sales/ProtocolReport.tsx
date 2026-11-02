import { Fragment, useMemo, useState } from 'react'
import { ChartColumnIcon, ChevronLeftIcon, ChevronRightIcon, TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { DateField, SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type ProtocolReportQuery,
  type SalesChartTarget,
  useProtocolReport,
  useProtocolReportsOverview,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { displayCode } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import {
  COMPARE_LABELS,
  type CompareMode,
  compareModes,
  comparePeriods,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  lastCompleteWeekEnd,
  MAX_PERIOD_DAYS,
  PERIOD_MODES,
  type PeriodMode,
  resolveAnchor,
  resolveCompareMode,
  shiftAnchor,
} from '@/features/sales/periods'
import { addDays, todayIso } from '@/lib/dates'
import type {
  ProtocolFigures,
  ProtocolKind,
  ProtocolReport as ProtocolReportData,
  ProtocolReportArticle,
  ProtocolReportGroup,
  ProtocolReportGrouping,
} from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

/** Stan raportu w adresie po polsku, żeby link otwierał ten sam widok. */
const PROTOCOL_PARAMS: Record<ProtocolKind, string> = {
  LIQUIDATION: 'likwidacje',
  STORE_NEEDS: 'potrzeby-sklepu',
  ADVERTISING: 'reklama',
  DONATION: 'darowizny',
  REPRESENTATION: 'reprezentacja',
  TRANSFER: 'mm',
}
/** W interfejsie pełne nazwy, nie kody (MM). */
const PROTOCOL_LABELS: Record<ProtocolKind, string> = {
  LIQUIDATION: 'Likwidacje',
  STORE_NEEDS: 'Potrzeby sklepu',
  ADVERTISING: 'Reklama',
  DONATION: 'Darowizny',
  REPRESENTATION: 'Reprezentacja',
  TRANSFER: 'Ruchy międzymagazynowe',
}
const GROUPINGS: { value: ProtocolReportGrouping; label: string; param: string; column: string }[] = [
  { value: 'ARTICLE', label: 'Artykuł', param: 'artykul', column: 'Artykuł' },
  { value: 'BRAND', label: 'Marka', param: 'marka', column: 'Marka' },
  { value: 'SUPPLIER', label: 'Ostatni dostawca', param: 'dostawca', column: 'Ostatni dostawca' },
  { value: 'NOMENCLATURE', label: 'Nomenklatura', param: 'nomenklatura', column: 'Nomenklatura' },
]
type Level = ProtocolReportQuery['level']
const LEVELS: { value: Level; label: string; param: string }[] = [
  { value: 'REGION', label: 'Rejon', param: 'rejon' },
  { value: 'FAMILY', label: 'Rodzina', param: 'rodzina' },
  { value: 'SUBFAMILY', label: 'Podrodzina', param: 'podrodzina' },
]
const MODE_PARAMS: Record<PeriodMode, string> = {
  day: 'dzien',
  week: 'tydzien',
  month: 'miesiac',
  custom: 'wlasny',
}
type Compare = CompareMode | 'none'
const COMPARE_PARAMS: Record<Compare, string> = {
  none: 'brak',
  year: 'rok',
  previous: 'poprzedni',
  week: 'tydzien',
  custom: 'wlasny',
}
const ANCHOR_LABELS: Record<Exclude<PeriodMode, 'custom'>, string> = {
  day: 'Dzień',
  week: 'Tydzień z dniem',
  month: 'Miesiąc z dniem',
}
const STEP_LABELS: Record<Exclude<PeriodMode, 'custom'>, [string, string]> = {
  day: ['Poprzedni dzień', 'Następny dzień'],
  week: ['Poprzedni tydzień', 'Następny tydzień'],
  month: ['Poprzedni miesiąc', 'Następny miesiąc'],
}

type Metric = keyof ProtocolFigures
const METRICS: { key: Metric; label: string; format: (value: number) => string }[] = [
  { key: 'purchaseValue', label: 'Zakup netto', format: (value) => `${formatMoney(value)} zł` },
  { key: 'saleValueGross', label: 'Sprzedaż brutto', format: (value) => `${formatMoney(value)} zł` },
  { key: 'quantity', label: 'Ilość', format: formatQuantity },
]

type Sort = 'value' | 'change' | 'name'

const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const signedPercent = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
  signDisplay: 'exceptZero',
})

/** Wiersze rysujemy po 100: przy grupowaniu po artykule bywa ich kilka tysięcy. */
const PAGE = 100

const find = <T extends { param: string }>(options: T[], param: string | null) =>
  options.find((option) => option.param === param) ?? options[0]

/**
 * Raport protokołów: linie jednego z sześciu protokołów (likwidacje, potrzeby sklepu, reklama, darowizny,
 * reprezentacja, ruchy międzymagazynowe) z raportu Wejścia/Wyjścia za okres, opcjonalnie z porównaniem, po artykule,
 * marce, ostatnim dostawcy artykułu albo nomenklaturze. Każdy protokół liczy się tak samo. Protokoły wyłączone
 * w Ustawieniach sklepu nie są do wyboru.
 */
export function ProtocolReport({ storeId }: { storeId: string }) {
  const overview = useProtocolReportsOverview(storeId)
  const sales = useSalesReportsOverview(storeId)
  const [params, setParams] = useSearchParams()

  const today = todayIso()
  const coveredUntil = overview.data?.documentsCoveredUntil ?? null
  // Okres domyślnie kończy się na ostatnim dniu z wgranych dokumentów.
  const lastDate = coveredUntil && coveredUntil < today ? coveredUntil : addDays(today, -1)
  const protocols = overview.data?.protocols ?? []
  const protocol =
    protocols.find((item) => PROTOCOL_PARAMS[item.kind] === params.get('protokol')) ?? protocols[0] ?? null
  const grouping = find(GROUPINGS, params.get('grupuj'))
  const level = find(LEVELS, params.get('poziom'))
  const mode =
    (Object.keys(MODE_PARAMS) as PeriodMode[]).find((key) => MODE_PARAMS[key] === params.get('okres')) ?? 'month'
  const anchor = resolveAnchor(mode, params.get('data') ?? lastDate, lastDate)
  const custom: DateRange = { from: params.get('od') ?? addDays(lastDate, -29), to: params.get('do') ?? lastDate }
  const requestedCompare =
    (Object.keys(COMPARE_PARAMS) as Compare[]).find((key) => COMPARE_PARAMS[key] === params.get('porownaj')) ?? 'none'
  const compare: Compare = requestedCompare === 'none' ? 'none' : resolveCompareMode(mode, requestedCompare)
  const yearAgo = comparePeriods(mode, anchor, custom, lastDate, 'year', custom).previous
  const compareCustom: DateRange = {
    from: params.get('por_od') ?? yearAgo.from,
    to: params.get('por_do') ?? yearAgo.to,
  }
  const periods = comparePeriods(mode, anchor, custom, lastDate, compare === 'none' ? 'year' : compare, compareCustom)
  const customError = mode === 'custom' ? rangeError(custom) : null
  const compareError = compare === 'custom' ? rangeError(compareCustom) : null
  const valid = !customError && !compareError
  const query = params.get('szukaj') ?? ''

  const update = (changes: Record<string, string | null>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => (value == null ? next.delete(key) : next.set(key, value)))
        return next
      },
      { replace: true },
    )

  const report = useProtocolReport(
    storeId,
    {
      protocol: protocol?.kind ?? 'LIQUIDATION',
      grouping: grouping.value,
      level: level.value,
      range: periods.current,
      previous: compare === 'none' ? null : periods.previous,
    },
    valid && protocol != null && protocol.documentType != null,
  )

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Raport protokołów</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.documentsReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Protokoły z raportu Wejścia/Wyjścia wg daty dokumentu: wartość zakupu netto, wartość w cenach sprzedaży brutto i
        ilość. Protokoły wyłączone w Ustawieniach sklepu nie są do wyboru.
      </p>

      {overview.isPending ? (
        <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : !protocol ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Wszystkie protokoły są wyłączone</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Włącz je w{' '}
            <Link
              to="/ustawienia-sklepu/pliki-proto"
              className="font-medium text-foreground underline underline-offset-4"
            >
              Ustawieniach sklepu → Protokoły i zmiana cen
            </Link>
            .
          </p>
        </Card>
      ) : (
        <>
          <Card className="mb-4 gap-4 p-4">
            <div className="-mx-1 overflow-x-auto px-1 pb-0.5">
              <SegmentedField
                label="Protokół"
                options={protocols.map((item) => ({ value: item.kind, label: PROTOCOL_LABELS[item.kind] }))}
                value={protocol.kind}
                onChange={(next) => update({ protokol: PROTOCOL_PARAMS[next] })}
              />
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <SegmentedField
                label="Grupuj po"
                options={GROUPINGS}
                value={grouping.value}
                onChange={(next) => update({ grupuj: GROUPINGS.find((item) => item.value === next)!.param })}
              />
              {grouping.value === 'NOMENCLATURE' && (
                <SegmentedField
                  label="Poziom nomenklatury"
                  options={LEVELS}
                  value={level.value}
                  onChange={(next) => update({ poziom: LEVELS.find((item) => item.value === next)!.param })}
                />
              )}
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <SegmentedField
                label="Okres"
                options={PERIOD_MODES}
                value={mode}
                onChange={(next) => update({ okres: MODE_PARAMS[next] })}
              />
              <SegmentedField
                label="Porównaj z"
                options={[{ value: 'none' as Compare, label: 'Bez porównania' }, ...compareModes(mode)]}
                value={compare}
                onChange={(next) =>
                  update(
                    next === 'custom' && compare !== 'custom'
                      ? { porownaj: COMPARE_PARAMS[next], por_od: periods.previous.from, por_do: periods.previous.to }
                      : { porownaj: COMPARE_PARAMS[next] },
                  )
                }
              />
            </div>

            <div className="flex flex-wrap items-start gap-3">
              {mode === 'custom' ? (
                <>
                  <DateField
                    label="Od"
                    value={custom.from}
                    onChange={(from) => update({ od: from, do: custom.to })}
                    max={today}
                    shortcuts={[]}
                    className="w-full sm:w-64"
                  />
                  <DateField
                    label="Do"
                    value={custom.to}
                    onChange={(to) => update({ od: custom.from, do: to })}
                    max={today}
                    shortcuts={[{ label: 'Ostatni dzień z dokumentów', value: lastDate }]}
                    error={customError ?? undefined}
                    className="w-full sm:w-64"
                  />
                </>
              ) : (
                <div className="flex w-full items-center gap-2 sm:w-auto">
                  <Button
                    variant="outline"
                    size="icon-lg"
                    aria-label={STEP_LABELS[mode][0]}
                    title={STEP_LABELS[mode][0]}
                    onClick={() => update({ data: shiftAnchor(mode, anchor, -1) })}
                  >
                    <ChevronLeftIcon />
                  </Button>
                  <DateField
                    label={ANCHOR_LABELS[mode]}
                    value={anchor}
                    onChange={(data) => update({ data })}
                    max={today}
                    shortcuts={
                      mode === 'week'
                        ? [{ label: 'Ostatni pełny tydzień', value: lastCompleteWeekEnd(lastDate) }]
                        : [{ label: 'Ostatni dzień z dokumentów', value: lastDate }]
                    }
                    className="min-w-0 flex-1 sm:w-72 sm:flex-none"
                  />
                  <Button
                    variant="outline"
                    size="icon-lg"
                    aria-label={STEP_LABELS[mode][1]}
                    title={STEP_LABELS[mode][1]}
                    disabled={shiftAnchor(mode, anchor, 1) > lastDate}
                    onClick={() => update({ data: shiftAnchor(mode, anchor, 1) })}
                  >
                    <ChevronRightIcon />
                  </Button>
                </div>
              )}
              {compare === 'custom' && (
                <>
                  <DateField
                    label="Porównaj od"
                    value={compareCustom.from}
                    onChange={(from) => update({ por_od: from, por_do: compareCustom.to })}
                    max={today}
                    shortcuts={[]}
                    className="w-full sm:w-64"
                  />
                  <DateField
                    label="Porównaj do"
                    value={compareCustom.to}
                    onChange={(to) => update({ por_od: compareCustom.from, por_do: to })}
                    max={today}
                    shortcuts={[]}
                    error={compareError ?? undefined}
                    className="w-full sm:w-64"
                  />
                </>
              )}
            </div>

            {valid && (
              <p className="border-t pt-4 text-sm tabular-nums">
                <span className="font-medium">{formatRange(periods.current)}</span>
                <span className="text-muted-foreground"> · {daysLabel(dayCount(periods.current))}</span>
                {compare !== 'none' && (
                  <span className="text-muted-foreground">
                    {' '}
                    · porównanie z {formatRange(periods.previous)} ({daysLabel(dayCount(periods.previous))})
                  </span>
                )}
              </p>
            )}
          </Card>

          {protocol.documentType == null ? (
            <Card className="items-center px-6 py-10 text-center">
              <p className="font-medium">Brak dokumentów: {PROTOCOL_LABELS[protocol.kind].toLowerCase()}</p>
              <p className="max-w-md text-sm text-muted-foreground">
                W raportach Wejścia/Wyjścia nie było jeszcze takiego protokołu, więc nie wiemy, jakim kodem zapisuje go
                system sklepu. Raport pojawi się, gdy go dopiszemy.
              </p>
            </Card>
          ) : !valid ? null : report.isError && !report.data ? (
            <Card className="items-center px-6 py-10 text-center">
              <p className="font-medium">Nie udało się policzyć raportu</p>
              <Button variant="outline" onClick={() => report.refetch()}>
                Spróbuj ponownie
              </Button>
            </Card>
          ) : !report.data ? (
            <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
          ) : (
            <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
              <Report
                storeId={storeId}
                report={report.data}
                compareLabel={compare === 'none' ? null : COMPARE_LABELS[compare].column}
                query={query}
                onQuery={(next) => update({ szukaj: next || null })}
                lastSaleDate={sales.data?.lastSaleDate ?? lastDate}
              />
            </div>
          )}
        </>
      )}
    </>
  )
}

function rangeError(range: DateRange) {
  if (range.from > range.to) return 'Data „od” jest późniejsza niż „do”.'
  if (dayCount(range) > MAX_PERIOD_DAYS) return `Okres może mieć najwyżej ${MAX_PERIOD_DAYS} dni.`
  return null
}

function Report({
  storeId,
  report,
  compareLabel,
  query,
  onQuery,
  lastSaleDate,
}: {
  storeId: string
  report: ProtocolReportData
  compareLabel: string | null
  query: string
  onQuery: (query: string) => void
  lastSaleDate: string
}) {
  const compare = report.previousTotal != null && compareLabel != null
  const byArticle = report.grouping === 'ARTICLE'
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const [sort, setSort] = useState<Sort>('value')
  const [shown, setShown] = useState(PAGE)
  const [chart, setChart] = useState<SalesChartTarget | null>(null)

  const groups = useMemo(() => {
    const words = normalize(query).split(/\s+/).filter(Boolean)
    const matches = (text: string) => words.every((word) => text.includes(word))
    const filtered =
      words.length === 0
        ? report.groups.map((group) => ({ group, articles: group.articles, viaArticles: false }))
        : report.groups.flatMap((group) => {
            if (matches(normalize(`${group.code ?? ''} ${group.name ?? ''}`))) {
              return [{ group, articles: group.articles, viaArticles: false }]
            }
            const articles = group.articles.filter((article) => matches(articleText(article)))
            return articles.length > 0 ? [{ group, articles, viaArticles: true }] : []
          })
    const key = (figures: ProtocolReportGroup) => {
      if (sort === 'change') return figures.current.purchaseValue - (figures.previous?.purchaseValue ?? 0)
      return figures.current.purchaseValue
    }
    return sort === 'name'
      ? [...filtered].sort((a, b) => (a.group.name ?? '').localeCompare(b.group.name ?? '', 'pl'))
      : sort === 'change'
        ? [...filtered].sort((a, b) => Math.abs(key(b.group)) - Math.abs(key(a.group)))
        : filtered
  }, [report.groups, query, sort])

  const loadMore = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < groups.length, shown)
  const share = (value: number) =>
    report.total.purchaseValue === 0 ? '–' : `${percentFormat.format((value / report.total.purchaseValue) * 100)}%`
  const toggle = (key: string) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  const groupColumn =
    report.grouping === 'NOMENCLATURE'
      ? (LEVELS.find((level) => level.value === report.level)?.label ?? 'Nomenklatura')
      : GROUPINGS.find((item) => item.value === report.grouping)!.column
  const openChart = (article: ProtocolReportArticle) =>
    setChart({ kind: 'article', itm8: article.itm8, name: article.name, ean: article.ean, brand: article.brand })

  const warning =
    (!report.documentsCoveredUntil || report.to > report.documentsCoveredUntil) &&
    `Dokumenty są wgrane ${report.documentsCoveredUntil ? `do ${formatRange({ from: report.documentsCoveredUntil, to: report.documentsCoveredUntil })}` : 'w ogóle nie są wgrane'}, okres jest niepełny.`

  return (
    <>
      {warning && (
        <div className="mb-4 flex gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          <p>{warning}</p>
        </div>
      )}

      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {METRICS.map((metric) => (
          <Tile
            key={metric.key}
            label={metric.label}
            value={metric.format(report.total[metric.key])}
            detail={
              compare
                ? `${compareLabel}: ${metric.format(report.previousTotal![metric.key])} · ${change(report.total[metric.key], report.previousTotal![metric.key])}`
                : null
            }
          />
        ))}
        <Tile
          label="Artykuły"
          value={formatQuantity(report.groups.reduce((sum, group) => sum + group.articles.length, 0))}
          detail={byArticle ? null : `${formatQuantity(report.groups.length)} × ${groupColumn.toLowerCase()}`}
        />
      </div>

      {report.groups.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak protokołów w tym okresie</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Wybierz inny okres albo wgraj raport dokumentów w Imporcie danych.
          </p>
        </Card>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-3">
            <SearchInput
              value={query}
              onChange={(next) => {
                onQuery(next)
                setShown(PAGE)
              }}
              placeholder={
                byArticle
                  ? 'Szukaj: nazwa, ITM8, EAN, marka'
                  : `Szukaj: ${groupColumn.toLowerCase()} albo artykuł (nazwa, ITM8, EAN)`
              }
              className="min-w-64 flex-1"
            />
            <SegmentedField
              label="Sortuj"
              options={[
                { value: 'value' as Sort, label: 'Wartość' },
                ...(compare ? [{ value: 'change' as Sort, label: 'Największa zmiana' }] : []),
                { value: 'name' as Sort, label: 'Nazwa' },
              ]}
              value={sort === 'change' && !compare ? 'value' : sort}
              onChange={setSort}
            />
          </div>

          <Card className="overflow-clip py-0">
            <div className="max-lg:overflow-x-auto">
              <table className="w-full text-sm tabular-nums">
                <TableHeader className="sticky top-0 z-20 bg-card shadow-[0_1px_0_var(--border)] lg:top-14">
                  {compare && (
                    <TableRow className="hover:bg-transparent">
                      <TableHead className="sticky left-0 z-10 bg-card" />
                      {METRICS.map((metric) => (
                        <TableHead key={metric.key} colSpan={3} className="border-l text-center">
                          {metric.label}
                        </TableHead>
                      ))}
                      <TableHead className="border-l" />
                    </TableRow>
                  )}
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="sticky left-0 z-10 min-w-64 bg-card pl-4">{groupColumn}</TableHead>
                    {METRICS.map((metric) =>
                      compare ? (
                        <Fragment key={metric.key}>
                          <NumberHead className="border-l">{compareLabel}</NumberHead>
                          <NumberHead>Aktualny</NumberHead>
                          <NumberHead>Zmiana</NumberHead>
                        </Fragment>
                      ) : (
                        <NumberHead key={metric.key}>{metric.label}</NumberHead>
                      ),
                    )}
                    <NumberHead className={cn('pr-4', compare && 'border-l')} hint="w zakupie netto protokołu">
                      Udział
                    </NumberHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {groups.slice(0, shown).map(({ group, articles, viaArticles }) => {
                    const open = !byArticle && (expanded.has(group.key) || viaArticles)
                    const article = byArticle ? group.articles[0] : null
                    return (
                      <Fragment key={group.key}>
                        <TableRow
                          className={cn(!byArticle && 'cursor-pointer', open && 'bg-muted/60')}
                          onClick={byArticle ? undefined : () => toggle(group.key)}
                        >
                          <TableCell className={cn('sticky left-0 z-10 max-w-96 bg-card pl-4', open && 'bg-muted')}>
                            {article ? (
                              <ArticleName article={article} onChart={() => openChart(article)} />
                            ) : (
                              <button
                                type="button"
                                aria-expanded={open}
                                className="flex w-full items-center gap-2 text-left outline-none focus-visible:underline"
                                onClick={(event) => {
                                  event.stopPropagation()
                                  toggle(group.key)
                                }}
                              >
                                <ChevronRightIcon
                                  aria-hidden
                                  className={cn(
                                    'size-4 shrink-0 text-muted-foreground transition-transform duration-150',
                                    open && 'rotate-90',
                                  )}
                                />
                                <span className="min-w-0">
                                  <span className="block truncate font-medium">
                                    {group.code && (
                                      <span className="mr-1.5 text-muted-foreground">
                                        {report.grouping === 'NOMENCLATURE' ? displayCode(group.code) : group.code}
                                      </span>
                                    )}
                                    {groupName(group, report.grouping)}
                                  </span>
                                  <span className="block text-xs text-muted-foreground">
                                    {articlesLabel(group.articles.length)}
                                  </span>
                                </span>
                              </button>
                            )}
                          </TableCell>
                          <Figures
                            current={group.current}
                            previous={group.previous}
                            compare={compare}
                            strong={!byArticle}
                          />
                          <TableCell className={cn('pr-4 text-right text-muted-foreground', compare && 'border-l')}>
                            {share(group.current.purchaseValue)}
                          </TableCell>
                        </TableRow>
                        {open &&
                          articles.map((item) => (
                            <TableRow key={item.itm8}>
                              <TableCell className="sticky left-0 z-10 max-w-96 bg-card pl-10">
                                <ArticleName article={item} onChart={() => openChart(item)} />
                              </TableCell>
                              <Figures current={item.current} previous={item.previous} compare={compare} />
                              <TableCell className={cn('pr-4 text-right text-muted-foreground', compare && 'border-l')}>
                                {share(item.current.purchaseValue)}
                              </TableCell>
                            </TableRow>
                          ))}
                      </Fragment>
                    )
                  })}
                </TableBody>
                <TableFooter className="sticky bottom-0 z-20 bg-card">
                  <TableRow className="hover:bg-transparent">
                    <TableCell className="sticky left-0 z-10 bg-card pl-4 font-semibold">Razem</TableCell>
                    <Figures current={report.total} previous={report.previousTotal} compare={compare} strong />
                    <TableCell className={cn('pr-4 text-right font-semibold', compare && 'border-l')}>100%</TableCell>
                  </TableRow>
                </TableFooter>
              </table>
            </div>
            {groups.length === 0 && (
              <p className="px-4 py-8 text-center text-sm text-muted-foreground">Nic nie pasuje do wyszukiwania.</p>
            )}
            <div ref={loadMore} />
          </Card>
        </>
      )}

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={NO_FILTER}
        basis="gross"
        lastSaleDate={lastSaleDate}
        onClose={() => setChart(null)}
      />
    </>
  )
}

const NO_FILTER: string[] = []

function ArticleName({ article, onChart }: { article: ProtocolReportArticle; onChart: () => void }) {
  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">
        <span className="block truncate">{article.name ?? article.itm8}</span>
        <span className="block truncate text-xs text-muted-foreground">
          {[article.itm8.replace(/^0+/, ''), article.ean, article.brand].filter(Boolean).join(' · ')}
        </span>
      </div>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label="Wykres sprzedaży artykułu"
        title="Wykres sprzedaży artykułu"
        onClick={(event) => {
          event.stopPropagation()
          onChart()
        }}
      >
        <ChartColumnIcon />
      </Button>
    </div>
  )
}

function Figures({
  current,
  previous,
  compare,
  strong,
}: {
  current: ProtocolFigures
  previous: ProtocolFigures | null
  compare: boolean
  strong?: boolean
}) {
  return METRICS.map((metric) =>
    compare && previous ? (
      <Fragment key={metric.key}>
        <TableCell className="border-l text-right text-muted-foreground">
          {metric.format(previous[metric.key])}
        </TableCell>
        <TableCell className={cn('text-right', strong && 'font-medium')}>
          {metric.format(current[metric.key])}
        </TableCell>
        <TableCell className="text-right text-muted-foreground">
          {change(current[metric.key], previous[metric.key])}
        </TableCell>
      </Fragment>
    ) : (
      <TableCell
        key={metric.key}
        className={cn('text-right', strong && metric.key === 'purchaseValue' && 'font-medium')}
      >
        {metric.format(current[metric.key])}
      </TableCell>
    ),
  )
}

function NumberHead({ children, hint, className }: { children: string | null; hint?: string; className?: string }) {
  return (
    <TableHead className={cn('text-right whitespace-nowrap', className)} title={hint}>
      {children}
    </TableHead>
  )
}

function Tile({ label, value, detail }: { label: string; value: string; detail: string | null }) {
  return (
    <div className="rounded-xl border bg-card p-4">
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-semibold tracking-tight tabular-nums">{value}</p>
      {detail && <p className="mt-0.5 text-sm text-muted-foreground tabular-nums">{detail}</p>}
    </div>
  )
}

/** Zmiana % względem okresu porównywanego; bez niego „nowe”, bez obu „–”. */
function change(current: number, previous: number) {
  if (previous === 0) return current === 0 ? '–' : 'nowe'
  return `${signedPercent.format(((current - previous) / Math.abs(previous)) * 100)}%`
}

function groupName(group: ProtocolReportGroup, grouping: ProtocolReportGrouping) {
  const name = group.name ?? ''
  return grouping === 'NOMENCLATURE' ? name.replace(/^\d+_/, '') : name
}

function articlesLabel(count: number) {
  if (count === 1) return '1 artykuł'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? `${count} artykuły` : `${count} artykułów`
}

function articleText(article: ProtocolReportArticle) {
  return normalize(`${article.name ?? ''} ${article.itm8} ${article.ean ?? ''} ${article.brand ?? ''}`)
}

/** Bez wielkości liter i polskich znaków. */
function normalize(text: string) {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .replace(/ł/g, 'l')
}
