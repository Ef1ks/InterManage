import { useMemo, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, PrinterIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { MultiComboboxField, SegmentedField, SelectField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { bonNumber, documentPaths, formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type SalesChartTarget,
  type SlowMovingQuery,
  type SlowMovingSort,
  usePriceCheckCategories,
  useSalesNomenclatures,
  useSalesReportsOverview,
  useSlowMoving,
  useSlowMovingPdf,
} from '@/features/sales/api'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { dayCount, daysLabel, formatRange, PERIOD_MODES, type PeriodMode } from '@/features/sales/periods'
import {
  ALL_CATEGORIES,
  ALL_SUPPLIERS,
  CategoryTabBar,
  type CategoryKey,
  OTHER_CATEGORY,
} from '@/features/sales/price-review'
import { ChartButton, nomenclatureName, StatTile } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { ApiError } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import { nextSort } from '@/lib/table-sort'
import type { SlowMovingDelivery, SlowMovingItem, SlowMovingReport as Report } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const PAGE = 100
const DEFAULT_MAX_ROTATION = 0.25
/** Cztery pełne tygodnie: krótszy okres łapie towar, który po prostu nie trafił na klienta w tym tygodniu. */
const DEFAULT_PERIOD_DAYS = 28

const PERIODS = PERIOD_MODES.filter((option) => option.value !== 'day')
const MODES = ['week', 'month', 'custom'] as const

const CADENCIER = [
  { value: 'all', label: 'Wszystkie', param: null, api: null },
  { value: 'with', label: 'W cadencier', param: 'tak', api: 'WITH' },
  { value: 'without', label: 'Poza', param: 'nie', api: 'WITHOUT' },
] as const

const DELIVERY_WINDOWS = [
  { value: 'none', label: 'Bez dostaw', param: null, days: null },
  { value: '1', label: '1 dzień', param: '1', days: 1 },
  { value: '7', label: '7 dni', param: '7', days: 7 },
  { value: '30', label: '30 dni', param: '30', days: 30 },
] as const

/** Sortowanie w adresie po polsku. */
const SORT_PARAMS: Record<SlowMovingSort, string> = {
  value: 'wartosc',
  name: 'nazwa',
  itm8: 'itm8',
  supplier: 'dostawca',
  delivery: 'dostawa',
  stock: 'stan',
  sold: 'sprzedano',
  rotation: 'rotacja',
}
const DEFAULT_SORT: SortState<SlowMovingSort> = { key: 'value', direction: 'desc' }

const decimal = (text: string) => Number(text.replace(',', '.'))
const money = (value: number) => `${formatMoney(value)} zł`
const rotationFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const itm8Label = (itm8: string) => itm8.replace(/^0+/, '')

/**
 * Niesprzedające się artykuły: towar na stanie z rotacją tygodniową nie wyższą niż próg w wybranym okresie, od
 * największej wartości stanu. Z wybranym okresem dostaw pokazuje przyjęcia, które zawierały takie artykuły. Stan raportu
 * jest w adresie, a filtry liczy serwer, więc wydruk ma to samo co ekran.
 */
export function SlowMovingReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const categories = usePriceCheckCategories(storeId)
  const { params, update } = useReportParams()

  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod<Exclude<PeriodMode, 'day'>>({
    params,
    lastDate: lastSaleDate,
    defaultMode: 'custom',
    customDays: DEFAULT_PERIOD_DAYS,
    modes: MODES,
  })

  const rotationField = useSearchParamText('rotacja')
  const maxRotation = rotationField.debounced === '' ? DEFAULT_MAX_ROTATION : decimal(rotationField.debounced)
  const rotationError = Number.isFinite(maxRotation) && maxRotation >= 0 ? undefined : 'Podaj rotację.'
  const minValueField = useSearchParamText('min_wartosc')
  const minValue = minValueField.debounced === '' ? null : decimal(minValueField.debounced)
  const minValueError = minValue == null || (Number.isFinite(minValue) && minValue >= 0) ? undefined : 'Podaj kwotę.'
  const searchField = useSearchParamText('szukaj')

  const category: CategoryKey = params.get('kategoria') ?? ALL_CATEGORIES
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const cadencier = CADENCIER.find((option) => option.param === params.get('cadencier')) ?? CADENCIER[0]
  const deliveries = DELIVERY_WINDOWS.find((option) => option.param === params.get('dostawy')) ?? DELIVERY_WINDOWS[0]
  const withoutDeliveries = params.get('bez_dostaw') === 'tak' && deliveries.days == null
  const withRawMaterials = params.get('surowce') === 'tak'
  const sortKey = (Object.keys(SORT_PARAMS) as SlowMovingSort[]).find(
    (key) => SORT_PARAMS[key] === params.get('sortuj'),
  )
  const sort: SortState<SlowMovingSort> = sortKey
    ? { key: sortKey, direction: params.get('kierunek') === 'malejaco' ? 'desc' : 'asc' }
    : DEFAULT_SORT

  const query: SlowMovingQuery = {
    range: period.range,
    maxRotation,
    nomenclatures: [...new Set(params.getAll('nom'))].sort(),
    category: category === ALL_CATEGORIES ? null : category === OTHER_CATEGORY ? 'OTHER' : category,
    supplier: supplier === ALL_SUPPLIERS ? null : supplier,
    cadencier: cadencier.api,
    minStockValue: minValue,
    query: searchField.debounced,
    withoutDeliveries,
    withRawMaterials,
    deliveryDays: deliveries.days,
    sort: sort.key,
    direction: sort.direction,
  }
  const valid = !period.customError && !rotationError && !minValueError
  const report = useSlowMoving(storeId, query, valid && !overview.isPending)
  const onSort = (column: SlowMovingSort) => {
    const next = nextSort(sort, column)
    update({ sortuj: SORT_PARAMS[next.key], kierunek: next.direction === 'desc' ? 'malejaco' : null })
  }

  const counts = report.data
  const countOf = (key: CategoryKey) =>
    !counts
      ? 0
      : key === ALL_CATEGORIES
        ? counts.allCount
        : key === OTHER_CATEGORY
          ? counts.otherCount
          : (counts.categoryCounts.find((item) => item.id === key)?.count ?? 0)

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Niesprzedające się artykuły</h2>
        {report.data && <ReportFreshness generatedAt={report.data.stockReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Aktywne artykuły na stanie, które sprzedają się najwyżej w podanej ilości na tydzień: sprzedana ilość z okresu ×
        7 / liczba dni. Stany i ceny zakupu z raportu kartoteki.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Okres"
            options={PERIODS}
            value={period.mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <PeriodPicker period={period} update={update} />
          <TextField
            label={`Rotacja tygodniowa do (domyślnie ${rotationFormat.format(DEFAULT_MAX_ROTATION)})`}
            inputMode="decimal"
            value={rotationField.text}
            onChange={(event) => rotationField.setText(event.target.value)}
            error={rotationError}
            className="w-full sm:w-80"
          />
        </div>

        <div className="grid gap-3 lg:grid-cols-2">
          <MultiComboboxField
            label="Nomenklatura"
            description="Tylko wybrane rejony, rodziny albo podrodziny. Puste = wszystkie."
            options={(nomenclatures.data ?? []).map((item) => ({
              value: item.code,
              label: `${displayCode(item.code)} ${nomenclatureName(item.name)}`,
              hint: LEVEL_STYLES[item.level].label,
            }))}
            value={query.nomenclatures}
            onChange={(codes) => update({ nom: codes })}
            placeholder={
              nomenclatures.data?.length === 0 ? 'Brak wgranej nomenklatury' : 'Szukaj po kodzie albo nazwie…'
            }
          />
          <div className="flex flex-wrap items-start gap-3">
            <SelectField
              label="Ostatni dostawca"
              options={[
                { value: ALL_SUPPLIERS, label: 'Wszyscy dostawcy' },
                ...(report.data?.suppliers ?? []).map((item) => ({
                  value: item.code,
                  label: `${item.code} ${item.name ?? ''} (${item.count})`,
                })),
              ]}
              value={supplier}
              onChange={(next) => update({ dostawca: next === ALL_SUPPLIERS ? null : next })}
              className="min-w-0 flex-1 basis-64"
            />
            <TextField
              label="Wartość stanu od (zł)"
              inputMode="decimal"
              value={minValueField.text}
              onChange={(event) => minValueField.setText(event.target.value)}
              error={minValueError}
              className="w-full sm:w-52"
            />
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <SegmentedField
            label="Cadencier"
            options={CADENCIER.map(({ value, label }) => ({ value, label }))}
            value={cadencier.value}
            onChange={(next) => update({ cadencier: CADENCIER.find((option) => option.value === next)?.param ?? null })}
          />
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">Pokaż dostawy z takimi artykułami</span>
            <SegmentedField
              label="Dostawy z takimi artykułami"
              options={DELIVERY_WINDOWS.map(({ value, label }) => ({ value, label }))}
              value={deliveries.value}
              onChange={(next) =>
                update({ dostawy: DELIVERY_WINDOWS.find((option) => option.value === next)?.param ?? null })
              }
            />
          </div>
          {deliveries.days == null && (
            <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
              <Switch checked={withoutDeliveries} onCheckedChange={(on) => update({ bez_dostaw: on ? 'tak' : null })} />
              Pomiń artykuły z dostawą w okresie
            </label>
          )}
          {query.nomenclatures.length === 0 && (
            <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
              <Switch checked={withRawMaterials} onCheckedChange={(on) => update({ surowce: on ? 'tak' : null })} />
              Pokaż surowce: mięso (rejon 24) i garmaż (rejon 36)
            </label>
          )}
        </div>
      </Card>

      {(categories.data?.length ?? 0) > 0 && (
        <CategoryTabBar
          categories={categories.data ?? []}
          countOf={countOf}
          value={category}
          onChange={(key) => update({ kategoria: key === ALL_CATEGORIES ? null : key })}
        />
      )}

      <SearchInput
        value={searchField.text}
        onChange={searchField.setText}
        placeholder="Szukaj: nazwa, marka, EAN, ITM8"
        className="mb-4 max-w-md"
      />

      {!valid ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć raportu</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-72 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <Results
            key={JSON.stringify(query)}
            storeId={storeId}
            report={report.data}
            query={query}
            sort={sort}
            onSort={onSort}
            lastSaleDate={lastSaleDate}
          />
        </div>
      )}
    </>
  )
}

function Results({
  storeId,
  report,
  query,
  sort,
  onSort,
  lastSaleDate,
}: {
  storeId: string
  report: Report
  query: SlowMovingQuery
  sort: SortState<SlowMovingSort>
  onSort: (column: SlowMovingSort) => void
  lastSaleDate: string
}) {
  const pdf = useSlowMovingPdf(storeId)
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
  const showsDeliveries = query.deliveryDays != null
  const empty = report.itemCount === 0
  const days = dayCount({ from: report.from, to: report.to })
  const openChart = (item: SlowMovingItem) =>
    setChart({ kind: 'article', itm8: item.itm8, name: item.name, ean: item.ean, brand: item.brand })

  return (
    <>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Artykułów"
          value={formatQuantity(report.itemCount)}
          detail={`${formatRange({ from: report.from, to: report.to })} (${daysLabel(days)})`}
          tone="strong"
        />
        <StatTile label="Bez żadnej sprzedaży" value={formatQuantity(report.unsoldCount)} />
        <StatTile label="Wartość stanu (zakup netto)" value={money(report.stockValue)} />
        {showsDeliveries && (
          <StatTile
            label={`Dostawy z ${daysLabel(query.deliveryDays ?? 0)}`}
            value={formatQuantity(report.deliveries.length)}
          />
        )}
      </div>

      <div className="mb-3 flex flex-wrap justify-end gap-2">
        <Button
          variant="outline"
          size="lg"
          loading={pdf.download.isPending}
          disabled={empty}
          onClick={() => pdf.download.mutate(query, { onError: onPdfError })}
        >
          <DownloadIcon />
          Pobierz PDF
        </Button>
        <Button
          variant="outline"
          size="lg"
          loading={pdf.print.isPending}
          disabled={empty}
          onClick={() => pdf.print.mutate(query, { onError: onPdfError })}
        >
          <PrinterIcon />
          Drukuj
        </Button>
      </div>

      {empty ? (
        <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          {showsDeliveries
            ? 'W dostawach z tego okresu nie ma artykułów spełniających filtry.'
            : 'Żaden artykuł nie spełnia tych filtrów.'}
        </p>
      ) : showsDeliveries ? (
        <DeliveriesList report={report} onChart={openChart} />
      ) : (
        <ArticlesTable items={report.items} sort={sort} onSort={onSort} onChart={openChart} />
      )}

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={[]}
        basis="gross"
        lastSaleDate={lastSaleDate}
        onClose={() => setChart(null)}
      />
    </>
  )
}

function ArticleName({ item, onChart }: { item: SlowMovingItem; onChart: (item: SlowMovingItem) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className="min-w-0">
        {item.name}
        {item.brand && <span className="ml-1.5 text-muted-foreground">{item.brand}</span>}
      </span>
      {item.cadencier && (
        <span className="shrink-0 rounded-md bg-blue-200 px-1.5 text-xs font-medium text-blue-950 dark:bg-blue-500/30 dark:text-blue-100">
          cadencier
        </span>
      )}
      <ChartButton onClick={() => onChart(item)} />
    </div>
  )
}

function ArticlesTable({
  items,
  sort,
  onSort,
  onChart,
}: {
  items: SlowMovingItem[]
  sort: SortState<SlowMovingSort>
  onSort: (column: SlowMovingSort) => void
  onChart: (item: SlowMovingItem) => void
}) {
  const [shown, setShown] = useState(PAGE)
  const loadMore = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < items.length, shown)
  const sorting = { sort, onSort }

  return (
    <Card className="overflow-clip p-0">
      <div className="overflow-x-auto lg:overflow-x-visible">
        <table className="w-full caption-bottom text-sm">
          <TableHeader className="bg-card lg:sticky lg:top-14 lg:z-10">
            <TableRow>
              <SortableHead column="itm8" {...sorting}>
                ITM8
              </SortableHead>
              <TableHead>EAN</TableHead>
              <SortableHead column="name" {...sorting} className="min-w-72">
                Nazwa
              </SortableHead>
              <SortableHead column="supplier" {...sorting}>
                Ostatni dostawca
              </SortableHead>
              <SortableHead column="delivery" {...sorting}>
                Ostatnia dostawa
              </SortableHead>
              <SortableHead column="stock" align="right" {...sorting}>
                Stan
              </SortableHead>
              <SortableHead column="value" align="right" {...sorting} className="whitespace-nowrap">
                Wartość stanu
              </SortableHead>
              <SortableHead column="sold" align="right" {...sorting}>
                Sprzedano
              </SortableHead>
              <SortableHead column="rotation" align="right" {...sorting} className="whitespace-nowrap">
                Rotacja / tydz.
              </SortableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.slice(0, shown).map((item) => (
              <TableRow key={item.itm8}>
                <TableCell className="tabular-nums">{itm8Label(item.itm8)}</TableCell>
                <TableCell className="tabular-nums">{item.ean}</TableCell>
                <TableCell className="whitespace-normal">
                  <ArticleName item={item} onChart={onChart} />
                </TableCell>
                <TableCell className="max-w-56 truncate text-muted-foreground" title={item.supplierName ?? undefined}>
                  {item.supplierName ?? '–'}
                </TableCell>
                <TableCell className="text-muted-foreground tabular-nums">
                  {item.lastDeliveryDate ? formatDocumentDate(item.lastDeliveryDate) : 'brak'}
                </TableCell>
                <TableCell className="text-right tabular-nums">{formatQuantity(item.stock)}</TableCell>
                <TableCell className="text-right font-medium tabular-nums">
                  {item.stockValue == null ? '–' : money(item.stockValue)}
                </TableCell>
                <TableCell className="text-right tabular-nums">{formatQuantity(item.soldQuantity)}</TableCell>
                <TableCell className="text-right tabular-nums">{rotationFormat.format(item.weeklyRotation)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </table>
      </div>
      <div ref={loadMore} />
    </Card>
  )
}

function DeliveriesList({ report, onChart }: { report: Report; onChart: (item: SlowMovingItem) => void }) {
  const [shown, setShown] = useState(PAGE)
  const loadMore = useLoadMore<HTMLDivElement>(
    () => setShown((count) => count + PAGE),
    shown < report.deliveries.length,
    shown,
  )
  const items = useMemo(() => new Map(report.items.map((item) => [item.itm8, item])), [report.items])

  return (
    <div className="grid gap-2">
      {report.deliveries.slice(0, shown).map((delivery) => (
        <DeliveryCard key={delivery.documentId} delivery={delivery} items={items} onChart={onChart} />
      ))}
      <div ref={loadMore} />
    </div>
  )
}

function DeliveryCard({
  delivery,
  items,
  onChart,
}: {
  delivery: SlowMovingDelivery
  items: Map<string, SlowMovingItem>
  onChart: (item: SlowMovingItem) => void
}) {
  const [open, setOpen] = useState(false)
  const canOpenDocuments = useCan('DOCUMENTS', 'VIEW')

  return (
    <Card className="gap-0 overflow-clip p-0">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 text-sm">
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          className="flex min-w-0 flex-1 items-center gap-2 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <ChevronRightIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-150',
              open && 'rotate-90',
            )}
          />
          <span className="text-muted-foreground tabular-nums">{formatDocumentDate(delivery.documentDate)}</span>
          <span className="min-w-0 truncate font-medium">{delivery.supplierName ?? 'Brak dostawcy'}</span>
        </button>
        {canOpenDocuments ? (
          <Link
            to={documentPaths.document('PZ', delivery.documentId)}
            title={`Przyjęcie ${delivery.documentNumber}`}
            className="tabular-nums underline underline-offset-4 hover:text-foreground"
          >
            bon {bonNumber(delivery.documentNumber)}
          </Link>
        ) : (
          <span className="tabular-nums">bon {bonNumber(delivery.documentNumber)}</span>
        )}
        <span className="text-muted-foreground tabular-nums">artykułów: {delivery.items.length}</span>
        <span className="font-medium tabular-nums">{money(delivery.purchaseValue)}</span>
      </div>
      {open && (
        <div className="overflow-x-auto border-t">
          <table className="w-full text-sm">
            <TableHeader>
              <TableRow>
                <TableHead>ITM8</TableHead>
                <TableHead>EAN</TableHead>
                <TableHead className="min-w-72">Nazwa</TableHead>
                <TableHead className="text-right">Dostarczono</TableHead>
                <TableHead className="text-right whitespace-nowrap">Wartość dostawy</TableHead>
                <TableHead className="text-right">Stan</TableHead>
                <TableHead className="text-right">Sprzedano</TableHead>
                <TableHead className="text-right whitespace-nowrap">Rotacja / tydz.</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {delivery.items.map((line) => {
                const item = items.get(line.itm8)
                return (
                  item && (
                    <TableRow key={line.itm8}>
                      <TableCell className="tabular-nums">{itm8Label(item.itm8)}</TableCell>
                      <TableCell className="tabular-nums">{item.ean}</TableCell>
                      <TableCell className="whitespace-normal">
                        <ArticleName item={item} onChart={onChart} />
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatQuantity(line.quantity)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(line.purchaseValue)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatQuantity(item.stock)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatQuantity(item.soldQuantity)}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {rotationFormat.format(item.weeklyRotation)}
                      </TableCell>
                    </TableRow>
                  )
                )
              })}
            </TableBody>
          </table>
        </div>
      )}
    </Card>
  )
}
