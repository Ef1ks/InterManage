import { createContext, Fragment, type ReactNode, useContext, useMemo, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, FileSpreadsheetIcon, ListIcon, PrinterIcon } from 'lucide-react'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { LST_FOLDER, lstFile, lstFileName } from '@/features/data-entry/lst-file'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import { MultiComboboxField, SegmentedField, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type PeriodSalesQuery,
  type SalesChartTarget,
  usePeriodSales,
  usePeriodSalesArticles,
  usePeriodSalesPdf,
  useSalesNomenclatures,
  useSalesReportsOverview,
  useTurnoverSuppliers,
} from '@/features/sales/api'
import { barClass, displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { dayCount, daysLabel, formatRange, PERIOD_MODES } from '@/features/sales/periods'
import { ChartButton, formatPercent, nomenclatureName, StatTile } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { ApiError, saveBlob } from '@/lib/api'
import type { PeriodSalesDepth, PeriodSalesFigures, PeriodSalesNode } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const DEPTHS: { value: PeriodSalesDepth; label: string; param: string }[] = [
  { value: 'REGION', label: 'Rejony', param: 'rejony' },
  { value: 'FAMILY', label: 'Rodziny', param: 'rodziny' },
  { value: 'SUBFAMILY', label: 'Podrodziny', param: 'podrodziny' },
  { value: 'ARTICLE', label: 'Artykuły', param: 'artykuly' },
]
/** Klucz rozwiniętej pozycji „Bez nomenklatury”, jak `parent=brak` w API. */
const UNASSIGNED_KEY = 'brak'
const NUMBER_COLUMNS = 7

const money = (value: number) => `${formatMoney(value)} zł`
const keyOf = (node: PeriodSalesNode) => (node.code === '' ? UNASSIGNED_KEY : node.code)
const indent = (depth: number) => `${1 + depth}rem`

/**
 * Sprzedaż za okres bez porównania: cała sprzedaż albo tylko wybrana nomenklatura, marki, dostawcy i wyszukane
 * artykuły, rozwijana od rejonu do artykułów. Wydruk schodzi do wybranego poziomu i ma tylko wyfiltrowaną sprzedaż.
 * Stan raportu jest w adresie.
 */
export function PeriodSalesReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const suppliers = useTurnoverSuppliers(storeId)
  const { params, update } = useReportParams()

  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod({ params, lastDate: lastSaleDate, defaultMode: 'month', customDays: 30 })
  const { mode, customError, range } = period

  const filter = useMemo(() => [...new Set(params.getAll('nom'))].sort(), [params])
  const brands = useMemo(() => [...new Set(params.getAll('marka'))].sort(), [params])
  const supplierCodes = useMemo(() => [...new Set(params.getAll('dostawca'))].sort(), [params])
  const search = useSearchParamText('szukaj')
  const depth = DEPTHS.find((item) => item.param === params.get('wydruk'))?.value ?? 'FAMILY'

  const query: PeriodSalesQuery = {
    range,
    nomenclatures: filter,
    brands,
    suppliers: supplierCodes,
    query: search.debounced,
  }
  const report = usePeriodSales(storeId, query, !customError && !overview.isPending)
  const articleFilters = brands.length > 0 || supplierCodes.length > 0 || search.debounced.trim() !== ''
  const anyFilter = articleFilters || filter.length > 0

  const brandOptions = useMemo(() => {
    const known = report.data?.brands ?? []
    const names = new Set(known.map((brand) => brand.name))
    return [
      ...brands.filter((brand) => !names.has(brand)).map((brand) => ({ value: brand, label: brand })),
      ...known.map((brand) => ({ value: brand.name, label: brand.name, hint: `${brand.articleCount} art.` })),
    ]
  }, [report.data?.brands, brands])

  const pdf = usePeriodSalesPdf(storeId)
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
  const empty = !report.data || report.data.articleCount === 0

  // Plik .lst jak we wprowadzaniu danych: ten sam folder list EAN-ów, a bez niego pobranie.
  const saveLst = async () => {
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(LST_FOLDER.key)
      const eans = await pdf.eans.mutateAsync(query)
      if (eans.length === 0) {
        toast.error('Artykuły raportu nie mają EAN-ów')
        return
      }
      const fileName = lstFileName('SPRZEDAZ', `${query.range.from}_${query.range.to}`)
      if (folderReady) await saveToFolder(LST_FOLDER.key, fileName, lstFile(eans))
      else saveBlob(lstFile(eans), fileName)
      toast.success('Zapisano listę EAN-ów', {
        description: [
          `Kodów: ${eans.length}`,
          !folderReady &&
            'pobrany: folder list EAN-ów nie jest wybrany w tej przeglądarce (ustawienia zapisu we wprowadzaniu danych)',
        ]
          .filter(Boolean)
          .join(', '),
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Nie udało się zapisać listy EAN-ów')
    }
  }

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Sprzedaż za okres</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Okres"
            options={PERIOD_MODES}
            value={mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <PeriodPicker period={period} update={update} />
        </div>

        <div className="grid gap-3 lg:grid-cols-2">
          <MultiComboboxField
            label="Nomenklatura"
            description="Tylko wybrane rejony, rodziny albo podrodziny. Puste = cały sklep."
            options={(nomenclatures.data ?? []).map((item) => ({
              value: item.code,
              label: `${displayCode(item.code)} ${nomenclatureName(item.name)}`,
              hint: LEVEL_STYLES[item.level].label,
            }))}
            value={filter}
            onChange={(codes) => update({ nom: codes })}
            placeholder={
              nomenclatures.data?.length === 0 ? 'Brak wgranej nomenklatury' : 'Szukaj po kodzie albo nazwie…'
            }
          />
          <MultiComboboxField
            label="Marka"
            description="Marka z kartoteki. Ta sama marka bywa zapisana na kilka sposobów, wybierz wszystkie."
            options={brandOptions}
            value={brands}
            onChange={(next) => update({ marka: next })}
            placeholder={report.data ? 'Szukaj marki…' : 'Wczytuję marki…'}
          />
          <MultiComboboxField
            label="Dostawca"
            description="Ostatni dostawca artykułu z przyjęć PZ. Puste = wszyscy."
            options={(suppliers.data ?? []).map((supplier) => ({
              value: supplier.code,
              label: `${supplier.code} ${supplier.name}`,
            }))}
            value={supplierCodes}
            onChange={(next) => update({ dostawca: next })}
            placeholder={suppliers.data?.length === 0 ? 'Brak przyjęć PZ' : 'Szukaj po kodzie albo nazwie…'}
          />
          <TextField
            label="Szukaj artykułów"
            hint="Słowa z nazwy, marki, EAN albo ITM8, wszystkie naraz."
            value={search.text}
            onChange={(event) => search.setText(event.target.value)}
          />
        </div>

        {!customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground"> · {daysLabel(dayCount(range))}</span>
            {anyFilter && <span className="text-muted-foreground"> · tylko wyfiltrowana sprzedaż</span>}
          </p>
        )}
      </Card>

      {customError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć sprzedaży</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <LoadingPanel className="h-72 rounded-xl" />
      ) : (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatTile
              label="Sprzedaż brutto"
              value={money(report.data.total.saleValueGross)}
              detail={`netto ${money(report.data.total.saleValueNet)}`}
              tone="strong"
            />
            <StatTile
              label="Marża"
              value={money(report.data.total.marginValue)}
              detail={formatPercent(report.data.total.marginPercent)}
            />
            <StatTile label="Zakup FIFO" value={money(report.data.total.costValue)} />
            <StatTile
              label="Artykułów ze sprzedażą"
              value={formatQuantity(report.data.articleCount)}
              detail={`ilość ${formatQuantity(report.data.total.quantity)}`}
            />
          </div>

          <Card className="mb-4 flex-row flex-wrap items-end gap-x-4 gap-y-3 p-4">
            <SegmentedField
              label="Wydruk i Excel do poziomu"
              options={DEPTHS}
              value={depth}
              onChange={(next) => update({ wydruk: DEPTHS.find((item) => item.value === next)?.param ?? null })}
            />
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                size="lg"
                loading={pdf.download.isPending}
                disabled={empty}
                onClick={() => pdf.download.mutate({ query, depth }, { onError: onPdfError })}
              >
                <DownloadIcon />
                Pobierz PDF
              </Button>
              <Button
                variant="outline"
                size="lg"
                loading={pdf.excel.isPending}
                disabled={empty}
                onClick={() => pdf.excel.mutate({ query, depth }, { onError: onPdfError })}
              >
                <FileSpreadsheetIcon />
                Excel
              </Button>
              <Button
                variant="outline"
                size="lg"
                loading={pdf.eans.isPending}
                disabled={empty}
                title="Lista EAN-ów wszystkich artykułów raportu (te same filtry), jak we wprowadzaniu danych"
                onClick={() => void saveLst()}
              >
                <ListIcon />
                Plik .lst
              </Button>
              <Button
                variant="outline"
                size="lg"
                loading={pdf.print.isPending}
                disabled={empty}
                onClick={() => pdf.print.mutate({ query, depth }, { onError: onPdfError })}
              >
                <PrinterIcon />
                Drukuj
              </Button>
            </div>
            <p className="basis-full text-xs text-muted-foreground">
              Wydruk i Excel mają ten sam okres, filtry i poziom co tabela, a plik .lst EAN-y wszystkich artykułów z
              tymi filtrami.
              {depth === 'ARTICLE' && report.data.articleCount > 2000 && (
                <span className="text-amber-700 dark:text-amber-300">
                  {' '}
                  {formatQuantity(report.data.articleCount)} artykułów to długi wydruk, zawęź filtry.
                </span>
              )}
            </p>
          </Card>

          <Results
            storeId={storeId}
            query={query}
            regions={report.data.regions}
            total={report.data.total}
            groupCharts={!articleFilters}
            filter={filter}
            lastSaleDate={lastSaleDate}
            refreshing={report.isFetching}
          />
        </>
      )}
    </>
  )
}

type Tree = {
  storeId: string
  query: PeriodSalesQuery
  totalGross: number
  expanded: ReadonlySet<string>
  toggle: (key: string) => void
  openChart?: (target: SalesChartTarget) => void
  /** Wykres grupy liczy całą sprzedaż nomenklatury, więc przy filtrze marki, dostawcy albo wyszukiwania go nie ma. */
  groupCharts: boolean
}

const TreeContext = createContext<Tree | null>(null)

function useTree() {
  const tree = useContext(TreeContext)
  if (!tree) throw new Error('Wiersze sprzedaży poza tabelą')
  return tree
}

function Results({
  storeId,
  query,
  regions,
  total,
  groupCharts,
  filter,
  lastSaleDate,
  refreshing,
}: {
  storeId: string
  query: PeriodSalesQuery
  regions: PeriodSalesNode[]
  total: PeriodSalesFigures
  groupCharts: boolean
  filter: string[]
  lastSaleDate: string
  refreshing: boolean
}) {
  // Jeden rejon (np. wybrana nomenklatura) rozwija się od razu.
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() =>
    regions.length === 1 ? new Set([keyOf(regions[0])]) : new Set(),
  )
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const toggle = (key: string) =>
    setExpanded((previous) => {
      const next = new Set(previous)
      if (!next.delete(key)) next.add(key)
      return next
    })
  const expandTo = (level: 'NONE' | 'REGION' | 'FAMILY') => {
    const keys = new Set<string>()
    if (level !== 'NONE') {
      for (const region of regions) {
        if (region.code === '') continue
        keys.add(region.code)
        if (level === 'FAMILY') region.children.forEach((family) => keys.add(family.code))
      }
    }
    setExpanded(keys)
  }

  if (regions.length === 0) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">Brak sprzedaży</p>
        <p className="text-sm text-muted-foreground">W tym okresie żaden artykuł nie spełnia filtrów.</p>
      </Card>
    )
  }

  const tree: Tree = {
    storeId,
    query,
    totalGross: total.saleValueGross,
    expanded,
    toggle,
    openChart: setChart,
    groupCharts,
  }

  return (
    <div className={cn('transition-opacity duration-150', refreshing && 'opacity-60')}>
      <div className="mb-3 flex flex-wrap items-center justify-end gap-2">
        <span className="text-sm text-muted-foreground">Rozwiń:</span>
        <Button variant="outline" size="sm" onClick={() => expandTo('NONE')}>
          Same rejony
        </Button>
        <Button variant="outline" size="sm" onClick={() => expandTo('REGION')}>
          Rodziny
        </Button>
        <Button variant="outline" size="sm" onClick={() => expandTo('FAMILY')}>
          Podrodziny
        </Button>
      </div>

      {/* overflow-clip zamiast hidden, żeby nagłówek i „Razem” kleiły się do okna. */}
      <Card className="overflow-clip py-0">
        <div className="max-lg:overflow-x-auto">
          <table className="w-full text-sm tabular-nums">
            <TableHeader className="sticky top-0 z-20 bg-card shadow-[0_1px_0_var(--border)] lg:top-14">
              <TableRow className="hover:bg-transparent">
                <TableHead className="sticky left-0 z-10 min-w-64 bg-card pl-4">Pozycja</TableHead>
                <NumberHead>Ilość</NumberHead>
                <NumberHead>Zakup FIFO</NumberHead>
                <NumberHead>Sprzedaż netto</NumberHead>
                <NumberHead>Sprzedaż brutto</NumberHead>
                <NumberHead>Marża</NumberHead>
                <NumberHead>Marża %</NumberHead>
                <NumberHead className="pr-4">Udział</NumberHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TreeContext.Provider value={tree}>
                {regions.map((region) => (
                  <NodeRow key={keyOf(region)} node={region} depth={0} />
                ))}
              </TreeContext.Provider>
            </TableBody>
            <TableFooter className="sticky bottom-0 z-20 bg-foreground text-background shadow-[0_-1px_0_var(--border)]">
              <TableRow className="hover:bg-foreground">
                <TableCell className="sticky left-0 z-10 bg-foreground py-3 pl-4 font-semibold">Razem</TableCell>
                <FigureCells figures={total} totalGross={total.saleValueGross} strong />
              </TableRow>
            </TableFooter>
          </table>
        </div>
      </Card>

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={filter}
        basis="gross"
        lastSaleDate={lastSaleDate}
        onClose={() => setChart(null)}
      />
    </div>
  )
}

function NodeRow({ node, depth }: { node: PeriodSalesNode; depth: number }) {
  const { expanded, toggle, openChart, groupCharts, totalGross } = useTree()
  const key = keyOf(node)
  const open = expanded.has(key)
  const style = LEVEL_STYLES[node.level]
  const unassigned = node.code === ''
  const name = unassigned ? 'Bez nomenklatury' : node.name ? nomenclatureName(node.name) : 'Brak nazwy w nomenklaturze'
  const leaf = unassigned || node.level === 'SUBFAMILY'
  return (
    <>
      <TableRow className={cn('cursor-pointer', open ? style.open : style.row)} onClick={() => toggle(key)}>
        <TableCell
          className={cn('sticky left-0 z-10 bg-inherit py-2.5 pr-4 whitespace-normal', barClass, style.bar)}
          style={{ paddingLeft: indent(depth) }}
        >
          <div className="flex items-start gap-1">
            <ExpandButton open={open} onToggle={() => toggle(key)}>
              <span className="flex min-w-0 items-baseline gap-2">
                {!unassigned && (
                  <span className="shrink-0 text-xs text-muted-foreground">{displayCode(node.code)}</span>
                )}
                <span
                  className={cn(node.level !== 'SUBFAMILY' && 'font-medium', !node.name && 'text-muted-foreground')}
                >
                  {name}
                </span>
                <span className="shrink-0 text-xs text-muted-foreground">{formatQuantity(node.articleCount)} art.</span>
              </span>
            </ExpandButton>
            {groupCharts && openChart && (
              <ChartButton
                onClick={() =>
                  openChart({
                    kind: 'group',
                    code: node.code,
                    unassigned,
                    name,
                    levelLabel: unassigned ? 'Artykuły spoza kartoteki' : `${style.label} ${displayCode(node.code)}`,
                  })
                }
              />
            )}
          </div>
        </TableCell>
        <FigureCells figures={node.figures} totalGross={totalGross} strong={node.level === 'REGION'} />
      </TableRow>
      {open &&
        (leaf ? (
          <ArticleRows parent={key} depth={depth + 1} />
        ) : (
          node.children.map((child) => <NodeRow key={keyOf(child)} node={child} depth={depth + 1} />)
        ))}
    </>
  )
}

function ArticleRows({ parent, depth }: { parent: string; depth: number }) {
  const { storeId, query, totalGross, openChart } = useTree()
  const { data, isPending, isError, refetch } = usePeriodSalesArticles(storeId, query, parent)

  if (isPending) {
    return Array.from({ length: 2 }, (_, row) => (
      <TableRow key={row} className="hover:bg-transparent" aria-hidden>
        <TableCell className="py-3.5" style={{ paddingLeft: indent(depth) }}>
          <div className="h-3.5 w-40 loading-shimmer rounded-full" />
        </TableCell>
        {Array.from({ length: NUMBER_COLUMNS }, (_, column) => (
          <TableCell key={column}>
            <div className="ml-auto h-3.5 w-14 loading-shimmer rounded-full" />
          </TableCell>
        ))}
      </TableRow>
    ))
  }
  if (isError || !data) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell
          colSpan={1 + NUMBER_COLUMNS}
          className="py-3 text-sm text-muted-foreground"
          style={{ paddingLeft: indent(depth) }}
        >
          Nie udało się wczytać artykułów.{' '}
          <button
            type="button"
            className="font-medium text-foreground underline underline-offset-4"
            onClick={() => refetch()}
          >
            Spróbuj ponownie
          </button>
        </TableCell>
      </TableRow>
    )
  }
  return data.map((article, index) => (
    <Fragment key={article.itm8}>
      <TableRow className={cn('bg-card hover:bg-muted', index % 2 === 1 && 'bg-muted/40')}>
        <TableCell
          className="sticky left-0 z-10 bg-inherit py-2 pr-4 whitespace-normal"
          style={{ paddingLeft: indent(depth) }}
        >
          <div className="flex items-start gap-1 pl-5.5">
            <span className="min-w-0 flex-1">
              <span className={cn('block', !article.name && 'text-muted-foreground')}>
                {article.name ?? 'Brak w kartotece'}
              </span>
              <span className="block text-xs text-muted-foreground">
                {[`ITM8 ${article.itm8}`, article.ean && `EAN ${article.ean}`, article.brand]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </span>
            {openChart && (
              <ChartButton
                onClick={() =>
                  openChart({
                    kind: 'article',
                    itm8: article.itm8,
                    name: article.name,
                    ean: article.ean,
                    brand: article.brand,
                  })
                }
              />
            )}
          </div>
        </TableCell>
        <FigureCells figures={article.figures} totalGross={totalGross} />
      </TableRow>
    </Fragment>
  ))
}

function ExpandButton({ open, onToggle, children }: { open: boolean; onToggle: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-expanded={open}
      onClick={(event) => {
        event.stopPropagation()
        onToggle()
      }}
      className="flex min-w-0 flex-1 items-start gap-1.5 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <ChevronRightIcon
        aria-hidden
        className={cn(
          'mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out motion-reduce:transition-none',
          open && 'rotate-90 text-foreground',
        )}
      />
      {children}
    </button>
  )
}

function FigureCells({
  figures,
  totalGross,
  strong,
}: {
  figures: PeriodSalesFigures
  totalGross: number
  strong?: boolean
}) {
  const share = totalGross ? (figures.saleValueGross * 100) / totalGross : null
  const cell = cn('px-3 py-2.5 text-right whitespace-nowrap', strong && 'font-semibold')
  return (
    <>
      <td className={cell}>{formatQuantity(figures.quantity)}</td>
      <td className={cell}>{money(figures.costValue)}</td>
      <td className={cell}>{money(figures.saleValueNet)}</td>
      <td className={cell}>{money(figures.saleValueGross)}</td>
      <td className={cell}>{money(figures.marginValue)}</td>
      <td className={cell}>{formatPercent(figures.marginPercent)}</td>
      <td className={cn(cell, 'pr-4')}>{formatPercent(share)}</td>
    </>
  )
}

function NumberHead({ children, className }: { children: string; className?: string }) {
  return <TableHead className={cn('text-right whitespace-nowrap', className)}>{children}</TableHead>
}
