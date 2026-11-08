import { useMemo, useState } from 'react'
import { ShoppingCartIcon } from 'lucide-react'

import { BackLink } from '@/components/BackLink'
import { MultiComboboxField, SegmentedField, SelectField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type SalesChartTarget,
  type StockGapQuery,
  useSalesNomenclatures,
  useSalesReportsOverview,
  useStockGaps,
} from '@/features/sales/api'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { ChartButton, nomenclatureName, StatTile } from '@/features/sales/report-parts'
import { lastCompleteDay, useReportParams } from '@/features/sales/report-period'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { ZeroStockOrderDialog } from '@/features/sales/ZeroStockOrderDialog'
import { nextSort, type SortAccessors, sortRows, type SortState } from '@/lib/table-sort'
import type { StockGapItem, StockGapKind } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const PAGE = 100
const DEFAULT_MIN_WEEKLY = 3
const DEFAULT_MAX_STOCK = 1
const DEFAULT_NO_DELIVERY_DAYS = 14
const ALL_SUPPLIERS = 'all'

/** Zakładki raportu; {@code orderable}: czy z tej zakładki można zamawiać (zamówienie obejmuje tylko cadencier). */
const TABS: { kind: StockGapKind; label: string; param: string | null; hint: string; orderable: boolean }[] = [
  {
    kind: 'MISSING',
    label: 'Brakuje',
    param: null,
    hint: 'Towar stale w ofercie, niski stan, cisza w sprzedaży i brak świeżej dostawy. Tu najpewniej nie ma go na półce. Artykuły z niedawną dostawą pokaże 0 w polu „Bez dostawy od”.',
    orderable: true,
  },
  {
    kind: 'RUNNING_OUT',
    label: 'Kończy się',
    param: 'konczy-sie',
    hint: 'Jeszcze się sprzedaje, ale przy tym tempie stan wystarczy najwyżej na 3 dni.',
    orderable: true,
  },
  {
    kind: 'WRONG_STOCK',
    label: 'Stan do sprawdzenia',
    param: 'stan',
    hint: 'Stan 0 albo ujemny, a artykuł dalej się sprzedaje: towar jest, tylko stan się nie zgadza.',
    orderable: false,
  },
  {
    kind: 'ONE_OFF',
    label: 'Jednorazowe',
    param: 'jednorazowe',
    hint: 'Skończyły się, ale to towar z 1-2 dostaw spoza cadencier, zwykle akcja. Poza cadencier nie ma zamówienia.',
    orderable: false,
  },
]

const CADENCIER = [
  { value: 'all', label: 'Wszystkie', param: null },
  { value: 'with', label: 'W cadencier', param: 'tak' },
  { value: 'without', label: 'Poza', param: 'nie' },
] as const

type SortKey = 'itm8' | 'name' | 'supplier' | 'delivery' | 'stock' | 'typical' | 'silence' | 'cover' | 'margin'

const SORT_PARAMS: Record<SortKey, string> = {
  itm8: 'itm8',
  name: 'nazwa',
  supplier: 'dostawca',
  delivery: 'dostawa',
  stock: 'stan',
  typical: 'tydzien',
  silence: 'cisza',
  cover: 'zapas',
  margin: 'marza',
}

const ACCESSORS: SortAccessors<StockGapItem, SortKey> = {
  itm8: (item) => item.itm8,
  name: (item) => item.name,
  supplier: (item) => item.supplierName,
  delivery: (item) => item.lastDeliveryDate,
  stock: (item) => item.stock,
  typical: (item) => item.typicalWeek,
  silence: (item) => item.silentDays,
  cover: (item) => item.coverDays,
  margin: (item) => item.weeklyMargin,
}

const DEFAULT_SORT: SortState<SortKey> = { key: 'margin', direction: 'desc' }

const decimal = (text: string) => Number(text.replace(',', '.'))
const money = (value: number) => `${formatMoney(value)} zł`
const itm8Label = (itm8: string) => itm8.replace(/^0+/, '')
const daysLabel = (days: number) => (days === 1 ? '1 dzień' : `${formatQuantity(days)} dni`)

function matches(item: StockGapItem, words: string[]) {
  const text = [item.name, item.brand, item.ean, item.itm8].join(' ').toLowerCase()
  return words.every((word) => text.includes(word))
}

export function StockGapReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const { params, update } = useReportParams()

  const minWeeklyField = useSearchParamText('tydzien')
  const maxStockField = useSearchParamText('stan')
  const noDeliveryField = useSearchParamText('bez_dostawy')
  const searchField = useSearchParamText('szukaj')

  const minWeekly = minWeeklyField.debounced === '' ? DEFAULT_MIN_WEEKLY : decimal(minWeeklyField.debounced)
  const maxStock = maxStockField.debounced === '' ? DEFAULT_MAX_STOCK : decimal(maxStockField.debounced)
  const noDeliveryDays = noDeliveryField.debounced === '' ? DEFAULT_NO_DELIVERY_DAYS : Number(noDeliveryField.debounced)
  const minWeeklyError = Number.isFinite(minWeekly) && minWeekly > 0 ? undefined : 'Podaj ilość większą od 0.'
  const maxStockError = Number.isFinite(maxStock) && maxStock >= 0 ? undefined : 'Podaj stan.'
  const noDeliveryError = Number.isInteger(noDeliveryDays) && noDeliveryDays >= 0 ? undefined : 'Podaj liczbę dni.'

  const query: StockGapQuery = {
    minWeekly,
    maxStock,
    noDeliveryDays,
    nomenclatures: [...new Set(params.getAll('nom'))].sort(),
  }
  const valid = !minWeeklyError && !maxStockError && !noDeliveryError
  const report = useStockGaps(storeId, query, valid)
  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)

  const tab = TABS.find((option) => option.param === params.get('zakladka')) ?? TABS[0]
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const cadencier = CADENCIER.find((option) => option.param === params.get('cadencier')) ?? CADENCIER[0]
  const sortKey = (Object.keys(SORT_PARAMS) as SortKey[]).find((key) => SORT_PARAMS[key] === params.get('sortuj'))
  const direction = params.get('kierunek') === 'malejaco' ? 'desc' : 'asc'
  const sort = useMemo<SortState<SortKey>>(
    () => (sortKey ? { key: sortKey, direction } : DEFAULT_SORT),
    [sortKey, direction],
  )
  const onSort = (column: SortKey) => {
    const next = nextSort(sort, column)
    update({ sortuj: SORT_PARAMS[next.key], kierunek: next.direction === 'desc' ? 'malejaco' : null })
  }

  // Wyszukiwarka, dostawca i cadencier zawężają wszystkie zakładki, więc liczniki przy zakładkach też.
  const filtered = useMemo(() => {
    const words = searchField.debounced.toLowerCase().split(/\s+/).filter(Boolean)
    return (report.data?.items ?? []).filter(
      (item) =>
        (supplier === ALL_SUPPLIERS || item.supplierCode === supplier) &&
        (cadencier.value === 'all' || item.cadencier === (cadencier.value === 'with')) &&
        matches(item, words),
    )
  }, [report.data, searchField.debounced, supplier, cadencier.value])
  const items = useMemo(
    () =>
      sortRows(
        filtered.filter((item) => item.kind === tab.kind),
        ACCESSORS,
        sort,
      ),
    [filtered, tab.kind, sort],
  )
  const suppliers = useMemo(() => {
    const found = new Map<string, { name: string | null; count: number }>()
    for (const item of report.data?.items ?? []) {
      if (item.kind === tab.kind && item.supplierCode) {
        const known = found.get(item.supplierCode)
        found.set(item.supplierCode, { name: item.supplierName, count: (known?.count ?? 0) + 1 })
      }
    }
    return [...found.entries()].sort(([, a], [, b]) => (a.name ?? '').localeCompare(b.name ?? '', 'pl'))
  }, [report.data, tab.kind])

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Braki towaru</h2>
        {report.data && <ReportFreshness generatedAt={report.data.stockReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Artykuły, które regularnie się sprzedają, a skończyły się albo zaraz skończą. Typowy tydzień to mediana tygodni
        ze sprzedażą z ostatnich 8, więc tydzień bez towaru ani skok z gazetki go nie zmieniają. Bez produkcji, mięsa i
        garmażu.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-3">
          <TextField
            label={`Typowy tydzień od (domyślnie ${DEFAULT_MIN_WEEKLY} szt.)`}
            inputMode="decimal"
            value={minWeeklyField.text}
            onChange={(event) => minWeeklyField.setText(event.target.value)}
            error={minWeeklyError}
            className="w-full sm:w-72"
          />
          <TextField
            label={`Brak przy stanie do (domyślnie ${DEFAULT_MAX_STOCK})`}
            inputMode="decimal"
            value={maxStockField.text}
            onChange={(event) => maxStockField.setText(event.target.value)}
            error={maxStockError}
            className="w-full sm:w-72"
          />
          <TextField
            label={`Bez dostawy od (domyślnie ${DEFAULT_NO_DELIVERY_DAYS} dni)`}
            inputMode="numeric"
            value={noDeliveryField.text}
            onChange={(event) => noDeliveryField.setText(event.target.value)}
            error={noDeliveryError}
            className="w-full sm:w-72"
          />
          <SegmentedField
            label="Cadencier"
            options={CADENCIER.map(({ value, label }) => ({ value, label }))}
            value={cadencier.value}
            onChange={(next) => update({ cadencier: CADENCIER.find((option) => option.value === next)?.param ?? null })}
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
          <SelectField
            label="Ostatni dostawca"
            options={[
              { value: ALL_SUPPLIERS, label: 'Wszyscy dostawcy' },
              ...suppliers.map(([code, { name, count }]) => ({
                value: code,
                label: `${code} ${name ?? ''} (${count})`,
              })),
            ]}
            value={supplier}
            onChange={(next) => update({ dostawca: next === ALL_SUPPLIERS ? null : next })}
          />
        </div>
      </Card>

      <div role="tablist" aria-label="Rodzaj braku" className="mb-2 flex flex-wrap gap-1.5">
        {TABS.map((option) => {
          const selected = option === tab
          return (
            <button
              key={option.kind}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => update({ zakladka: option.param, dostawca: null })}
              className={cn(
                'rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                selected ? 'border-foreground bg-foreground text-background' : 'bg-card hover:border-foreground/20',
              )}
            >
              {option.label}
              <span className={cn('ml-1.5 tabular-nums', selected ? 'text-background/70' : 'text-muted-foreground')}>
                {filtered.filter((item) => item.kind === option.kind).length}
              </span>
            </button>
          )
        })}
      </div>
      <p className="mb-4 text-sm text-muted-foreground">{tab.hint}</p>

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
            key={`${tab.kind}:${JSON.stringify(query)}`}
            storeId={storeId}
            items={items}
            kind={tab.kind}
            orderable={tab.orderable}
            sort={sort}
            onSort={onSort}
            lastSaleDate={lastSaleDate}
            dataUntil={report.data.lastSaleDate}
          />
        </div>
      )}
    </>
  )
}

function Results({
  storeId,
  items,
  kind,
  orderable,
  sort,
  onSort,
  lastSaleDate,
  dataUntil,
}: {
  storeId: string
  items: StockGapItem[]
  kind: StockGapKind
  orderable: boolean
  sort: SortState<SortKey>
  onSort: (column: SortKey) => void
  lastSaleDate: string
  dataUntil: string | null
}) {
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const [ordering, setOrdering] = useState(false)
  const [shown, setShown] = useState(PAGE)
  const loadMore = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < items.length, shown)
  const sorting = { sort, onSort }
  const usesCover = kind === 'RUNNING_OUT'
  const empty = items.length === 0
  const cadencierItems = items.filter((item) => item.cadencier)

  return (
    <>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <StatTile
          label="Artykułów"
          value={formatQuantity(items.length)}
          detail={dataUntil ? `Sprzedaż do ${formatDocumentDate(dataUntil)}` : 'Brak wgranej sprzedaży'}
          tone="strong"
        />
        <StatTile
          label="Marża na tydzień"
          value={money(items.reduce((sum, item) => sum + item.weeklyMargin, 0))}
          detail="Ile te artykuły zarabiają w typowym tygodniu"
        />
        {orderable && (
          <div className="flex flex-col items-end justify-end gap-1.5">
            <p className="text-sm text-muted-foreground tabular-nums">
              Tylko z cadencier: {formatQuantity(cadencierItems.length)} z {formatQuantity(items.length)}
            </p>
            <Button size="lg" disabled={cadencierItems.length === 0} onClick={() => setOrdering(true)}>
              <ShoppingCartIcon />
              Stwórz zamówienie
            </Button>
          </div>
        )}
      </div>

      {empty ? (
        <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          Żaden artykuł nie spełnia tych filtrów.
        </p>
      ) : (
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
                  <SortableHead column="typical" align="right" {...sorting} className="whitespace-nowrap">
                    Typowy tydzień
                  </SortableHead>
                  {usesCover ? (
                    <SortableHead column="cover" align="right" {...sorting} className="whitespace-nowrap">
                      Zapas na
                    </SortableHead>
                  ) : (
                    <SortableHead column="silence" align="right" {...sorting} className="whitespace-nowrap">
                      Bez sprzedaży
                    </SortableHead>
                  )}
                  <SortableHead column="margin" align="right" {...sorting} className="whitespace-nowrap">
                    Marża / tydz.
                  </SortableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.slice(0, shown).map((item) => (
                  <TableRow key={item.itm8}>
                    <TableCell className="tabular-nums">{itm8Label(item.itm8)}</TableCell>
                    <TableCell className="tabular-nums">{item.ean}</TableCell>
                    <TableCell className="whitespace-normal">
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
                        <ChartButton
                          onClick={() =>
                            setChart({
                              kind: 'article',
                              itm8: item.itm8,
                              name: item.name,
                              ean: item.ean,
                              brand: item.brand,
                            })
                          }
                        />
                      </div>
                    </TableCell>
                    <TableCell
                      className="max-w-56 truncate text-muted-foreground"
                      title={item.supplierName ?? undefined}
                    >
                      {item.supplierName ?? '–'}
                    </TableCell>
                    <TableCell className="text-muted-foreground tabular-nums">
                      {item.lastDeliveryDate ? formatDocumentDate(item.lastDeliveryDate) : 'brak'}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(item.stock)}</TableCell>
                    <TableCell className="text-right tabular-nums" title={`Sprzedaż w ${item.weeksSold} z 8 tygodni`}>
                      {formatQuantity(item.typicalWeek)}
                    </TableCell>
                    {usesCover ? (
                      <TableCell className="text-right tabular-nums">
                        {item.coverDays == null ? '–' : `${formatQuantity(item.coverDays)} dni`}
                      </TableCell>
                    ) : (
                      <TableCell
                        className="text-right tabular-nums"
                        title={`Ostatnia sprzedaż ${formatDocumentDate(item.lastSale)}, w tym czasie zeszłoby ok. ${formatQuantity(item.missedQuantity)}`}
                      >
                        {item.silentDays === 0 ? 'sprzedaje się' : daysLabel(item.silentDays)}
                      </TableCell>
                    )}
                    <TableCell className="text-right font-medium tabular-nums">{money(item.weeklyMargin)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </table>
          </div>
          <div ref={loadMore} />
        </Card>
      )}

      <ZeroStockOrderDialog storeId={storeId} items={cadencierItems} open={ordering} onOpenChange={setOrdering} />
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
