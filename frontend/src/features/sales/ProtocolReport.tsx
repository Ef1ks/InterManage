import { Fragment, type ReactNode, useMemo, useState } from 'react'
import { ChartColumnIcon, ChevronRightIcon, DownloadIcon, PrinterIcon, TagIcon, TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField, SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type ProtocolReportQuery,
  type SalesChartTarget,
  useProtocolReport,
  useProtocolReportPdf,
  useProtocolReportsOverview,
  useLossControlGroups,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { displayCode } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { StatTile } from '@/features/sales/report-parts'
import { MODE_PARAMS, PeriodPicker, rangeError, useReportParams, useReportPeriod } from '@/features/sales/report-period'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import {
  ControlDots,
  ControlFilterBar,
  ControlGroupButton,
  ControlGroupEditor,
  ControlGroupMenu,
  type EditorState,
  useControlGroupsByItm8,
} from '@/features/sales/LossControlGroups'
import { useCan } from '@/features/auth/api'
import {
  COMPARE_LABELS,
  type CompareMode,
  compareModes,
  comparePeriods,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  PERIOD_MODES,
  resolveCompareMode,
} from '@/features/sales/periods'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import { nextSort, type SortAccessors, type SortState, sortRows } from '@/lib/table-sort'
import type {
  ProtocolFigures,
  ProtocolKind,
  ProtocolReport as ProtocolReportData,
  ProtocolReportArticle,
  ProtocolReportGroup,
  ProtocolReportGrouping,
  LossControlGroup,
} from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'
import { useSearchParamText } from '@/lib/use-search-param-text'

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
type Compare = CompareMode | 'none'
const COMPARE_PARAMS: Record<Compare, string> = {
  none: 'brak',
  year: 'rok',
  previous: 'poprzedni',
  week: 'tydzien',
  custom: 'wlasny',
}

type Metric = keyof ProtocolFigures
const METRICS: { key: Metric; label: string; format: (value: number) => string }[] = [
  { key: 'purchaseValue', label: 'Zakup netto', format: (value) => `${formatMoney(value)} zł` },
  { key: 'saleValueGross', label: 'Sprzedaż brutto', format: (value) => `${formatMoney(value)} zł` },
  { key: 'quantity', label: 'Ilość', format: formatQuantity },
]

/** Wiersz tabeli: grupa albo jej artykuł. */
type SortableRow = { name: string | null; current: ProtocolFigures; previous: ProtocolFigures | null }
type SortKey = 'name' | 'share' | `${Metric}:${'previous' | 'current' | 'change'}`

const relativeChange = (current: number, previous: number) =>
  previous === 0 ? null : ((current - previous) / Math.abs(previous)) * 100

/** Co pokazuje kolumna, po tym sortuje: zmiana to procent, udział idzie za wartością zakupu. */
const SORT_ACCESSORS = {
  name: (row) => row.name,
  share: (row) => row.current.purchaseValue,
  ...Object.fromEntries(
    METRICS.flatMap(({ key }) => [
      [`${key}:previous`, (row: SortableRow) => row.previous?.[key]],
      [`${key}:current`, (row: SortableRow) => row.current[key]],
      [
        `${key}:change`,
        (row: SortableRow) => (row.previous ? relativeChange(row.current[key], row.previous[key]) : null),
      ],
    ]),
  ),
} as SortAccessors<SortableRow, SortKey>

const DEFAULT_SORT: SortState<SortKey> = { key: 'purchaseValue:current', direction: 'desc' }

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
  const { params, update } = useReportParams()

  const today = todayIso()
  const coveredUntil = overview.data?.documentsCoveredUntil ?? null
  // Okres domyślnie kończy się na ostatnim dniu z wgranych dokumentów.
  const lastDate = coveredUntil && coveredUntil < today ? coveredUntil : addDays(today, -1)
  const protocols = overview.data?.protocols ?? []
  const protocol =
    protocols.find((item) => PROTOCOL_PARAMS[item.kind] === params.get('protokol')) ?? protocols[0] ?? null
  const grouping = find(GROUPINGS, params.get('grupuj'))
  const level = find(LEVELS, params.get('poziom'))
  const period = useReportPeriod({ params, lastDate, defaultMode: 'month', customDays: 30 })
  const { mode, anchor, custom, customError } = period
  const requestedCompare =
    (Object.keys(COMPARE_PARAMS) as Compare[]).find((key) => COMPARE_PARAMS[key] === params.get('porownaj')) ?? 'none'
  const compare: Compare = requestedCompare === 'none' ? 'none' : resolveCompareMode(mode, requestedCompare)
  const yearAgo = comparePeriods(mode, anchor, custom, lastDate, 'year', custom).previous
  const compareCustom: DateRange = {
    from: params.get('por_od') ?? yearAgo.from,
    to: params.get('por_do') ?? yearAgo.to,
  }
  const periods = comparePeriods(mode, anchor, custom, lastDate, compare === 'none' ? 'year' : compare, compareCustom)
  const compareError = compare === 'custom' ? rangeError(compareCustom) : null
  const valid = !customError && !compareError
  const { text: query, setText: setQuery } = useSearchParamText('szukaj')

  const reportQuery: ProtocolReportQuery = {
    protocol: protocol?.kind ?? 'LIQUIDATION',
    grouping: grouping.value,
    level: level.value,
    range: periods.current,
    previous: compare === 'none' ? null : periods.previous,
  }
  const report = useProtocolReport(storeId, reportQuery, valid && protocol != null && protocol.documentType != null)

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

            <PeriodPicker period={period} update={update} max={today} lastDateLabel="Ostatni dzień z dokumentów">
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
            </PeriodPicker>

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
                // Zaznaczenie dotyczy grup jednego protokołu i grupowania.
                key={`${reportQuery.protocol}:${reportQuery.grouping}:${reportQuery.level}`}
                storeId={storeId}
                report={report.data}
                reportQuery={reportQuery}
                compareLabel={compare === 'none' ? null : COMPARE_LABELS[compare].column}
                query={query}
                onQuery={setQuery}
                lastSaleDate={sales.data?.lastSaleDate ?? lastDate}
              />
            </div>
          )}
        </>
      )}
    </>
  )
}

function Report({
  storeId,
  report,
  reportQuery,
  compareLabel,
  query,
  onQuery,
  lastSaleDate,
}: {
  storeId: string
  report: ProtocolReportData
  reportQuery: ProtocolReportQuery
  compareLabel: string | null
  query: string
  onQuery: (query: string) => void
  lastSaleDate: string
}) {
  const compare = report.previousTotal != null && compareLabel != null
  const byArticle = report.grouping === 'ARTICLE'
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const [requestedSort, setSort] = useState(DEFAULT_SORT)
  // Po wyłączeniu porównania nie ma już kolumn okresu porównywanego.
  const sort =
    compare || requestedSort.key.endsWith(':current') || !requestedSort.key.includes(':') ? requestedSort : DEFAULT_SORT
  const [shown, setShown] = useState(PAGE)
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  // Zaznaczone artykuły: klucz grupy + ITM8. Grupa jest zaznaczona, gdy zaznaczone są wszystkie jej artykuły.
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set())
  const [groupQueries, setGroupQueries] = useState<Readonly<Record<string, string>>>({})
  const [withArticles, setWithArticles] = useState(true)
  const pdf = useProtocolReportPdf(storeId)
  const canEdit = useCan('SALES', 'EDIT')
  const controlGroups = useLossControlGroups(storeId).data ?? NO_CONTROL_GROUPS
  const controlByItm8 = useControlGroupsByItm8(controlGroups)
  const [editor, setEditor] = useState<EditorState>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const activeControl = searchParams.getAll('kontrola').filter((id) => controlGroups.some((group) => group.id === id))
  const setActiveControl = (ids: string[]) =>
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('kontrola')
        for (const id of ids) next.append('kontrola', id)
        return next
      },
      { replace: true },
    )
  const activeControlKey = activeControl.join(',')

  const groups = useMemo(() => {
    // Filtr dostawców do kontroli: tylko ich artykuły, a pozycja liczy się z nich.
    const controlIds = new Set(activeControlKey ? activeControlKey.split(',') : [])
    const controlled = new Set(
      controlGroups.filter((group) => controlIds.has(group.id)).flatMap((group) => group.itm8s),
    )
    const source: ProtocolReportGroup[] =
      controlIds.size === 0
        ? report.groups
        : report.groups.flatMap((group) => {
            const articles = group.articles.filter((article) => controlled.has(article.itm8))
            if (articles.length === 0) return []
            return [
              {
                ...group,
                articles,
                current: sumFigures(articles.map((article) => article.current)),
                previous: group.previous ? sumFigures(articles.map((article) => article.previous!)) : null,
              },
            ]
          })
    const words = normalize(query).split(/\s+/).filter(Boolean)
    const matches = (text: string) => words.every((word) => text.includes(word))
    const filtered =
      words.length === 0
        ? source.map((group) => ({ group, articles: group.articles, viaArticles: false }))
        : source.flatMap((group) => {
            if (matches(normalize(`${group.code ?? ''} ${group.name ?? ''}`))) {
              return [{ group, articles: group.articles, viaArticles: false }]
            }
            const articles = group.articles.filter((article) => matches(articleText(article)))
            return articles.length > 0 ? [{ group, articles, viaArticles: true }] : []
          })
    const byGroup: SortAccessors<(typeof filtered)[number], SortKey> = Object.fromEntries(
      Object.entries(SORT_ACCESSORS).map(([key, accessor]) => [
        key,
        (row: (typeof filtered)[number]) => accessor(row.group),
      ]),
    ) as SortAccessors<(typeof filtered)[number], SortKey>
    return sortRows(filtered, byGroup, sort).map((row) => {
      const articles = sortRows(row.articles, SORT_ACCESSORS, sort)
      // Wyszukiwarka pod rozwiniętą grupą zawęża jej artykuły (po wyszukiwaniu ogólnym).
      const groupWords = normalize(groupQueries[row.group.key] ?? '')
        .split(/\s+/)
        .filter(Boolean)
      return {
        ...row,
        articles,
        shownArticles:
          groupWords.length === 0
            ? articles
            : articles.filter((article) => groupWords.every((word) => articleText(article).includes(word))),
      }
    })
  }, [report.groups, query, sort, groupQueries, controlGroups, activeControlKey])
  const controlFiltered = activeControl.length > 0
  const controlTotal = sumFigures(groups.map((row) => row.group.current))
  const controlPrevious = compare ? sumFigures(groups.map((row) => row.group.previous!)) : null

  // Zaznaczone artykuły, które są w raporcie (po zmianie okresu część może zniknąć), w kolejności raportu.
  const selection = report.groups.flatMap((group) => {
    const articles = group.articles.filter((article) => selected.has(selectionKey(group, article)))
    return articles.length > 0 ? [{ group, articles, whole: articles.length === group.articles.length }] : []
  })
  const selectedArticles = selection.flatMap((item) => item.articles)
  const selectedTotal = sumFigures(selectedArticles.map((article) => article.current))
  const selectedPrevious = compare ? sumFigures(selectedArticles.map((article) => article.previous!)) : null
  const selectedLabel =
    byArticle || selection.every((item) => item.whole)
      ? `${selection.length}`
      : `${selection.length} · ${articlesLabel(selectedArticles.length)}`
  const groupState = (group: ProtocolReportGroup) => {
    const count = group.articles.filter((article) => selected.has(selectionKey(group, article))).length
    return count === 0 ? 'none' : count === group.articles.length ? 'all' : 'some'
  }
  const setArticles = (group: ProtocolReportGroup, articles: ProtocolReportArticle[], on: boolean) =>
    setSelected((current) => {
      const next = new Set(current)
      for (const article of articles) {
        if (on) next.add(selectionKey(group, article))
        else next.delete(selectionKey(group, article))
      }
      return next
    })
  const toggleGroup = (group: ProtocolReportGroup) => setArticles(group, group.articles, groupState(group) !== 'all')
  const toggleArticle = (group: ProtocolReportGroup, article: ProtocolReportArticle) =>
    setArticles(group, [article], !selected.has(selectionKey(group, article)))
  const unselectedIn = (group: ProtocolReportGroup, articles: ProtocolReportArticle[]) =>
    articles.filter((article) => !selected.has(selectionKey(group, article)))
  // Wyszukane po artykule zaznaczają tylko znalezione artykuły, wyszukane po pozycji całą pozycję.
  const filteredUnselected = groups.filter(({ group, articles }) => unselectedIn(group, articles).length > 0).length
  // Bez zaznaczenia, ale z filtrem dostawców, wydruk obejmuje artykuły z filtra.
  const printSelection =
    selection.length > 0 || !controlFiltered
      ? selection
      : groups.map((row) => ({
          group: row.group,
          articles: row.group.articles,
          whole:
            row.group.articles.length ===
            (report.groups.find((group) => group.key === row.group.key)?.articles.length ?? 0),
        }))
  const printedItm8s = new Set(printSelection.flatMap((item) => item.articles.map((article) => article.itm8)))
  const printRequest = {
    ...reportQuery,
    previous: compare ? reportQuery.previous : null,
    groups: printSelection.map((item) => item.group.key),
    articles: !byArticle && withArticles,
    groupArticles: Object.fromEntries(
      printSelection
        .filter((item) => !item.whole)
        .map((item) => [item.group.key, item.articles.map((article) => article.itm8)]),
    ),
    // Dostawcy do kontroli w nagłówku wydruku: z filtra, a bez niego ci, do których należą drukowane artykuły.
    controlGroups: controlFiltered
      ? activeControl
      : controlGroups.filter((group) => group.itm8s.some((itm8) => printedItm8s.has(itm8))).map((group) => group.id),
  }
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
  const printLabel =
    selection.length > 0 ? ` zaznaczone (${selectedLabel})` : controlFiltered ? ' dostawców z filtra' : ' wszystko'
  const assignProps = {
    storeId,
    groups: controlGroups,
    onCreate: (itm8s: string[]) => setEditor({ group: null, itm8s }),
  }
  const columnCount = 2 + METRICS.length * (compare ? 3 : 1)

  const sorting = { sort, onSort: (column: SortKey) => setSort(nextSort(sort, column)) }
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
          <StatTile
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
        <StatTile
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
          <ControlFilterBar
            storeId={storeId}
            groups={controlGroups}
            active={activeControl}
            onActive={(ids) => {
              setActiveControl(ids)
              setShown(PAGE)
            }}
            canEdit={canEdit}
            onEdit={setEditor}
          />
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
            {query.trim() !== '' && groups.length > 0 && filteredUnselected > 0 && (
              <Button
                variant="outline"
                onClick={() =>
                  setSelected(
                    (current) =>
                      new Set([
                        ...current,
                        ...groups.flatMap(({ group, articles }) =>
                          articles.map((article) => selectionKey(group, article)),
                        ),
                      ]),
                  )
                }
              >
                Zaznacz wyszukane ({filteredUnselected})
              </Button>
            )}
            {canEdit && selectedArticles.length > 0 && (
              <ControlGroupMenu
                {...assignProps}
                itm8s={[...new Set(selectedArticles.map((article) => article.itm8))]}
                trigger={
                  <Button variant="outline">
                    <TagIcon />
                    Przypisz zaznaczone ({selectedArticles.length})
                  </Button>
                }
              />
            )}
            {selection.length > 0 && (
              <Button variant="ghost" onClick={() => setSelected(new Set())}>
                Wyczyść zaznaczenie
              </Button>
            )}
          </div>

          <div className="mb-3 flex flex-wrap items-center gap-3">
            <p className="text-sm text-muted-foreground">
              {selection.length > 0
                ? 'Wydruk obejmie tylko zaznaczone pozycje i artykuły, z ich sumą na dole.'
                : byArticle
                  ? 'Zaznacz artykuły, żeby je zsumować i wydrukować, np. dla dostawcy.'
                  : 'Zaznacz pozycje (np. kilka zapisów tej samej marki) albo pojedyncze artykuły w rozwiniętej pozycji, żeby je zsumować i wydrukować, np. dla dostawcy.'}
            </p>
            <div className="ml-auto flex flex-wrap items-center gap-3">
              {!byArticle && (
                <label className="flex cursor-pointer items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    className="size-4 accent-foreground"
                    checked={withArticles}
                    onChange={(event) => setWithArticles(event.target.checked)}
                  />
                  Z artykułami
                </label>
              )}
              <Button
                variant="outline"
                loading={pdf.download.isPending}
                onClick={() => pdf.download.mutate(printRequest, { onError: onPdfError })}
              >
                <DownloadIcon />
                Pobierz PDF{printLabel}
              </Button>
              <Button
                variant="outline"
                loading={pdf.print.isPending}
                onClick={() => pdf.print.mutate(printRequest, { onError: onPdfError })}
              >
                <PrinterIcon />
                Drukuj{printLabel}
              </Button>
            </div>
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
                    <SortableHead column="name" {...sorting} className="sticky left-0 z-10 min-w-64 bg-card pl-4">
                      {groupColumn}
                    </SortableHead>
                    {METRICS.map((metric) =>
                      compare ? (
                        <Fragment key={metric.key}>
                          <NumberHead column={`${metric.key}:previous`} {...sorting} className="border-l">
                            {compareLabel}
                          </NumberHead>
                          <NumberHead column={`${metric.key}:current`} {...sorting}>
                            Aktualny
                          </NumberHead>
                          <NumberHead column={`${metric.key}:change`} {...sorting}>
                            Zmiana
                          </NumberHead>
                        </Fragment>
                      ) : (
                        <NumberHead key={metric.key} column={`${metric.key}:current`} {...sorting}>
                          {metric.label}
                        </NumberHead>
                      ),
                    )}
                    <NumberHead
                      column="share"
                      {...sorting}
                      className={cn('pr-4', compare && 'border-l')}
                      hint="w zakupie netto protokołu"
                    >
                      Udział
                    </NumberHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {groups.slice(0, shown).map(({ group, articles, shownArticles, viaArticles }) => {
                    const open = !byArticle && (expanded.has(group.key) || viaArticles || controlFiltered)
                    const article = byArticle ? group.articles[0] : null
                    const state = groupState(group)
                    const groupQuery = groupQueries[group.key] ?? ''
                    const toSelect = unselectedIn(group, shownArticles)
                    return (
                      <Fragment key={group.key}>
                        <TableRow
                          className={cn(!byArticle && 'cursor-pointer', open && 'bg-muted/60')}
                          onClick={byArticle ? undefined : () => toggle(group.key)}
                        >
                          <TableCell className={cn('sticky left-0 z-10 max-w-96 bg-card pl-4', open && 'bg-muted')}>
                            <div className="flex items-center gap-2">
                              <SelectBox
                                label={`Zaznacz do sumy i wydruku: ${group.name ?? group.key}`}
                                checked={state === 'all'}
                                indeterminate={state === 'some'}
                                onChange={() => toggleGroup(group)}
                              />
                              {article ? (
                                <div className="min-w-0 flex-1">
                                  <ArticleName
                                    article={article}
                                    onChart={() => openChart(article)}
                                    controls={controlByItm8.get(article.itm8)}
                                    assign={canEdit && <ControlGroupButton {...assignProps} itm8s={[article.itm8]} />}
                                  />
                                </div>
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
                            </div>
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
                        {open && (
                          <TableRow className="hover:bg-transparent">
                            <TableCell colSpan={columnCount} className="py-1.5">
                              <div className="sticky left-0 flex w-fit max-w-full flex-wrap items-center gap-2 pl-6">
                                <SearchInput
                                  value={groupQuery}
                                  onChange={(next) => setGroupQueries((current) => ({ ...current, [group.key]: next }))}
                                  placeholder={`Szukaj w: ${groupName(group, report.grouping) || 'pozycji'} (nazwa, ITM8, EAN, marka)`}
                                  className="w-96 max-w-full"
                                />
                                {groupQuery.trim() !== '' && toSelect.length > 0 && (
                                  <Button variant="outline" onClick={() => setArticles(group, toSelect, true)}>
                                    Zaznacz wyszukane ({toSelect.length})
                                  </Button>
                                )}
                                {groupQuery.trim() !== '' && (
                                  <span className="text-xs text-muted-foreground">
                                    {shownArticles.length} z {articles.length}
                                  </span>
                                )}
                              </div>
                            </TableCell>
                          </TableRow>
                        )}
                        {open && shownArticles.length === 0 && (
                          <TableRow className="hover:bg-transparent">
                            <TableCell colSpan={columnCount} className="py-3 pl-10 text-sm text-muted-foreground">
                              Żaden artykuł tej pozycji nie pasuje do wyszukiwania.
                            </TableCell>
                          </TableRow>
                        )}
                        {open &&
                          shownArticles.map((item) => (
                            <TableRow
                              key={item.itm8}
                              className="cursor-pointer"
                              onClick={() => toggleArticle(group, item)}
                            >
                              <TableCell className="sticky left-0 z-10 max-w-96 bg-card pl-10">
                                <div className="flex items-center gap-2">
                                  <SelectBox
                                    label={`Zaznacz artykuł do sumy i wydruku: ${item.name ?? item.itm8}`}
                                    checked={selected.has(selectionKey(group, item))}
                                    onChange={() => toggleArticle(group, item)}
                                  />
                                  <div className="min-w-0 flex-1">
                                    <ArticleName
                                      article={item}
                                      onChart={() => openChart(item)}
                                      controls={controlByItm8.get(item.itm8)}
                                      assign={canEdit && <ControlGroupButton {...assignProps} itm8s={[item.itm8]} />}
                                    />
                                  </div>
                                </div>
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
                  {controlFiltered && (
                    <TableRow className="hover:bg-transparent">
                      <TableCell className="sticky left-0 z-10 bg-card pl-4 font-semibold">
                        Dostawcy z filtra (
                        {controlGroups
                          .filter((group) => activeControl.includes(group.id))
                          .map((group) => group.name)
                          .join(', ')}
                        )
                      </TableCell>
                      <Figures current={controlTotal} previous={controlPrevious} compare={compare} strong />
                      <TableCell className={cn('pr-4 text-right font-semibold', compare && 'border-l')}>
                        {share(controlTotal.purchaseValue)}
                      </TableCell>
                    </TableRow>
                  )}
                  {selection.length > 0 && (
                    <TableRow className="bg-muted/60 hover:bg-muted/60">
                      <TableCell className="sticky left-0 z-10 bg-muted pl-4 font-semibold">
                        Zaznaczone ({selectedLabel})
                      </TableCell>
                      <Figures current={selectedTotal} previous={selectedPrevious} compare={compare} strong />
                      <TableCell className={cn('pr-4 text-right font-semibold', compare && 'border-l')}>
                        {share(selectedTotal.purchaseValue)}
                      </TableCell>
                    </TableRow>
                  )}
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

      <ControlGroupEditor storeId={storeId} groups={controlGroups} state={editor} onClose={() => setEditor(null)} />
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
const NO_CONTROL_GROUPS: LossControlGroup[] = []

function selectionKey(group: ProtocolReportGroup, article: ProtocolReportArticle) {
  return `${group.key}\u0000${article.itm8}`
}

function SelectBox({
  label,
  checked,
  indeterminate = false,
  onChange,
}: {
  label: string
  checked: boolean
  indeterminate?: boolean
  onChange: () => void
}) {
  return (
    <input
      type="checkbox"
      aria-label={label}
      className="size-4 shrink-0 accent-foreground"
      checked={checked}
      ref={(element) => {
        if (element) element.indeterminate = indeterminate
      }}
      onClick={(event) => event.stopPropagation()}
      onChange={onChange}
    />
  )
}

function ArticleName({
  article,
  onChart,
  controls,
  assign,
}: {
  article: ProtocolReportArticle
  onChart: () => void
  controls?: LossControlGroup[]
  assign?: ReactNode
}) {
  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5">
          <ControlDots groups={controls} />
          <span className="truncate">{article.name ?? article.itm8}</span>
        </span>
        <span className="block truncate text-xs text-muted-foreground">
          {[article.itm8.replace(/^0+/, ''), article.ean, article.brand].filter(Boolean).join(' · ')}
        </span>
      </div>
      {assign}
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

function sumFigures(figures: ProtocolFigures[]): ProtocolFigures {
  return figures.reduce(
    (sum, item) => ({
      quantity: sum.quantity + item.quantity,
      purchaseValue: sum.purchaseValue + item.purchaseValue,
      saleValueGross: sum.saleValueGross + item.saleValueGross,
    }),
    { quantity: 0, purchaseValue: 0, saleValueGross: 0 },
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

function NumberHead({
  column,
  sort,
  onSort,
  children,
  hint,
  className,
}: {
  column: SortKey
  sort: SortState<SortKey>
  onSort: (column: SortKey) => void
  children: string | null
  hint?: string
  className?: string
}) {
  return (
    <SortableHead
      column={column}
      sort={sort}
      onSort={onSort}
      align="right"
      className={cn('whitespace-nowrap', className)}
    >
      <span title={hint}>{children}</span>
    </SortableHead>
  )
}
