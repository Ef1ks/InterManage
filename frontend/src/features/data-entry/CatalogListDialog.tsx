import { ChartColumnIcon, CheckIcon, EyeIcon, EyeOffIcon, ListPlusIcon, PlusIcon, XIcon } from 'lucide-react'
import { Fragment, type ReactNode, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { TableHead } from '@/components/ui/table'
import { formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import type { SalesChartTarget } from '@/features/sales/api'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { formatShortDate } from '@/lib/dates'
import { nextSort, type SortState } from '@/lib/table-sort'
import type { DataEntryCatalogListItem } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

import { type CatalogListFilters, type CatalogSortKey, fetchCatalogPage, useCatalogList } from './api'
import { parseNumber } from './grid-values'

const NO_FILTERS: CatalogListFilters = {
  itm8: '',
  ean: '',
  name: '',
  brand: '',
  supplier: '',
  nomenclature: '',
  purchaseFrom: '',
  purchaseTo: '',
  saleFrom: '',
  saleTo: '',
}

type TextFilter = 'itm8' | 'ean' | 'name' | 'brand' | 'supplier' | 'nomenclature'
type ColumnKey = TextFilter | 'purchase' | 'sale' | 'stock' | 'rotation'

/**
 * Kolumny listy w kolejności. Dostawca nie jest brany z kartoteki (bywa błędny): filtr szuka w przyjęciach PZ
 * z 6 miesięcy, a kolumna pokazuje ostatnią dostawę, więc nie ma po niej sortowania. Każdą kolumnę poza nazwą da się
 * ukryć; ukrycie czyści jej filtr i sortowanie.
 */
const COLUMNS: {
  key: ColumnKey
  label: string
  sort?: CatalogSortKey
  /** Tekstowe pole filtra nad nagłówkiem. */
  text?: { placeholder: string; title?: string }
  /** Przedział od-do nad nagłówkiem. */
  range?: ['purchaseFrom' | 'saleFrom', 'purchaseTo' | 'saleTo']
  align?: 'right'
  className?: string
}[] = [
  { key: 'itm8', label: 'ITM8', sort: 'itm8', text: { placeholder: 'ITM8' }, className: 'w-32' },
  { key: 'ean', label: 'EAN', sort: 'ean', text: { placeholder: 'EAN' }, className: 'w-36' },
  { key: 'name', label: 'Nazwa', sort: 'name', text: { placeholder: 'np. excel*o' }, className: 'min-w-60' },
  { key: 'brand', label: 'Marka', sort: 'brand', text: { placeholder: 'Marka' }, className: 'w-32' },
  {
    key: 'supplier',
    label: 'Ostatnia dostawa',
    text: {
      placeholder: 'Dostawca (6 mies.)',
      title: 'Kod albo nazwa dostawcy: artykuły, które przyjechały od niego w przyjęciach PZ z ostatnich 6 miesięcy',
    },
    className: 'w-48',
  },
  {
    key: 'nomenclature',
    label: 'Nomenklatura',
    sort: 'nomenclature',
    text: { placeholder: 'kod albo nazwa' },
    className: 'w-44',
  },
  { key: 'purchase', label: 'Zakup netto', sort: 'purchase', range: ['purchaseFrom', 'purchaseTo'], align: 'right' },
  { key: 'sale', label: 'Sprzedaż brutto', sort: 'sale', range: ['saleFrom', 'saleTo'], align: 'right' },
  { key: 'stock', label: 'Stan', sort: 'stock', align: 'right' },
  { key: 'rotation', label: 'Rotacja', sort: 'rotation', align: 'right' },
]

const COLUMN_LABELS = new Map(COLUMNS.map((column) => [column.key, column.label]))

const HIDDEN_COLUMNS_KEY = 'intermanage.catalogListHiddenColumns'

function storedHiddenColumns(): ColumnKey[] {
  try {
    const value = JSON.parse(localStorage.getItem(HIDDEN_COLUMNS_KEY) ?? '[]') as unknown
    return Array.isArray(value) ? value.filter((key): key is ColumnKey => COLUMN_LABELS.has(key) && key !== 'name') : []
  } catch {
    return []
  }
}

function storeHiddenColumns(columns: ColumnKey[]) {
  try {
    localStorage.setItem(HIDDEN_COLUMNS_KEY, JSON.stringify(columns))
  } catch {
    // Ukryte kolumny to tylko wygoda; bez pamięci przeglądarki działają do zamknięcia okna.
  }
}

/** Najwięcej artykułów dodawanych naraz przez „Dodaj wszystkie wyfiltrowane”. */
const BULK_LIMIT = 500

const filterInputClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-background dark:bg-input/30 px-2 text-sm text-foreground transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground/70 focus:border-ring focus:ring-3 focus:ring-ring/30 aria-invalid:border-destructive [&::-webkit-search-cancel-button]:hidden'

const DEFAULT_SORT: SortState<CatalogSortKey> = { key: 'name', direction: 'asc' }

/** Nagłówek z sortowaniem; mniejszy niż w zwykłych tabelach, bo nad nim są pola filtrów. */
const headClass = 'group/head h-auto px-2.5 py-1 text-xs'

/** "250401" -> "25 04 01" jak w kartotece. */
const formatCode = (code: string) => code.replace(/(\d{2})(?=\d)/g, '$1 ')

const NO_NOMENCLATURE_FILTER: string[] = []

const withoutZeros = (ean: string) => ean.replace(/^0+/, '')

export type CatalogAddition = { item: DataEntryCatalogListItem; quantity: number; unitNet: number | null }

/** Artykuły do dodania naraz i ile pominięto (już w tabeli albo bez EAN-u). */
type BulkAddition = { items: DataEntryCatalogListItem[]; present: number; withoutEan: number }

/**
 * Lista towarów z kartoteki sklepu: filtr nad każdą kolumną (fragment, `*` = dowolny ciąg znaków, `?` = jeden znak),
 * nomenklatura po kodzie albo nazwie, przedziały cen zakupu i sprzedaży, sortowanie po każdej kolumnie (w bazie). Plus przy wierszu pyta
 * o ilość i cenę zakupu i dopisuje artykuł do tabeli; „Dodaj wszystkie wyfiltrowane” dopisuje naraz wszystkie pasujące
 * do filtrów (ilość 1, cena z kartoteki). Okno zostaje otwarte, żeby dodać kilka artykułów pod rząd. Oczko przy
 * nagłówku chowa kolumnę (zapamiętane w przeglądarce). Ikona wykresu (z dostępem do raportów sprzedaży,
 * {@code lastSaleDate}) otwiera statystyki artykułu jak w raportach.
 */
export function CatalogListDialog({
  storeId,
  open,
  onOpenChange,
  inTable,
  onAdd,
  onPick,
  initialName = '',
  title = 'Lista towarów',
  lastSaleDate,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** EAN-y (bez zer wiodących) artykułów, które już są w tabeli. */
  inTable: Set<string>
  /** Dodaje artykuły do tabeli: jeden z plusa (z ilością i ceną z okna) albo wszystkie wyfiltrowane. */
  onAdd?: (additions: CatalogAddition[]) => Promise<void>
  /** Zamiast dodawania: plus od razu wybiera artykuł (przypisanie pozycji z faktury). */
  onPick?: (item: DataEntryCatalogListItem) => void
  /** Początkowy filtr nazwy (np. „szy*kru” z nazwy z faktury). */
  initialName?: string
  title?: string
  /** Ostatni dzień ze sprzedażą; {@code null} = bez dostępu do raportów sprzedaży, bez statystyk. */
  lastSaleDate: string | null
}) {
  const [chartTarget, setChartTarget] = useState<SalesChartTarget | null>(null)
  const [filters, setFilters] = useState(() => ({ ...NO_FILTERS, name: initialName }))
  const [sort, setSort] = useState(DEFAULT_SORT)
  const onSort = (key: CatalogSortKey) => setSort((current) => nextSort(current, key))
  const [hidden, setHidden] = useState(storedHiddenColumns)
  const [adding, setAdding] = useState<DataEntryCatalogListItem | null>(null)
  const [bulk, setBulk] = useState<BulkAddition | null>(null)
  const [collecting, setCollecting] = useState(false)
  const [bulkAdding, setBulkAdding] = useState(false)
  const debounced = useDebouncedValue(filters, 300)
  const list = useCatalogList(storeId, debounced, sort, open)
  const items = list.data?.pages.flatMap((page) => page.items) ?? []
  const loadMoreRef = useLoadMore<HTMLTableRowElement>(
    () => list.fetchNextPage(),
    Boolean(list.hasNextPage) && !list.isFetchingNextPage,
    items.length,
  )
  const filtered = Object.values(filters).some((value) => value.trim() !== '')
  const setFilter = (key: keyof CatalogListFilters, value: string) =>
    setFilters((current) => ({ ...current, [key]: value }))
  const columns = COLUMNS.filter((column) => !hidden.includes(column.key))

  const changeHidden = (next: ColumnKey[]) => {
    setHidden(next)
    storeHiddenColumns(next)
  }

  const hideColumn = (key: ColumnKey) => {
    const column = COLUMNS.find((candidate) => candidate.key === key)
    // Ukryta kolumna nie może dalej zawężać ani układać listy, bo nie widać, dlaczego.
    if (column?.text) setFilter(column.key as TextFilter, '')
    column?.range?.forEach((filter) => setFilter(filter, ''))
    if (column?.sort && sort.key === column.sort) setSort(DEFAULT_SORT)
    changeHidden([...hidden, key])
  }

  /** Pobiera wszystkie artykuły pasujące do filtrów (strona po stronie) i pyta, czy je dodać. */
  const collectFiltered = async () => {
    setCollecting(true)
    try {
      const found: DataEntryCatalogListItem[] = []
      for (let page = 0; ; page++) {
        const result = await fetchCatalogPage(storeId, filters, sort, page)
        found.push(...result.items)
        if (found.length > BULK_LIMIT) {
          toast.error(`Pasuje ponad ${BULK_LIMIT} artykułów. Zawęź filtry, żeby dodać je naraz.`)
          return
        }
        if (!result.hasMore) break
      }
      const withEan = found.filter((item) => item.ean)
      const toAdd = withEan.filter((item) => !inTable.has(withoutZeros(item.ean)))
      const result = { items: toAdd, present: withEan.length - toAdd.length, withoutEan: found.length - withEan.length }
      if (toAdd.length === 0) {
        toast.info(
          found.length === 0
            ? 'Żaden artykuł nie pasuje do filtrów.'
            : 'Wszystkie wyfiltrowane artykuły z EAN-em są już w tabeli.',
        )
        return
      }
      setBulk(result)
    } catch {
      toast.error('Nie udało się pobrać wyfiltrowanych artykułów.')
    } finally {
      setCollecting(false)
    }
  }

  const addBulk = async () => {
    if (!bulk || !onAdd) return
    setBulkAdding(true)
    try {
      await onAdd(bulk.items.map((item) => ({ item, quantity: 1, unitNet: item.purchasePrice })))
      setBulk(null)
    } catch {
      toast.error('Nie udało się dodać artykułów do tabeli.')
    } finally {
      setBulkAdding(false)
    }
  }

  const hideButton = (key: ColumnKey, label: string) =>
    key === 'name' ? null : (
      <button
        type="button"
        onClick={() => hideColumn(key)}
        aria-label={`Ukryj kolumnę: ${label}`}
        title="Ukryj kolumnę"
        className="ml-0.5 inline-flex size-5 items-center justify-center rounded align-middle text-muted-foreground opacity-0 transition-opacity duration-150 outline-none group-hover/head:opacity-100 hover:bg-background hover:text-foreground focus-visible:opacity-100 focus-visible:ring-3 focus-visible:ring-ring/30"
      >
        <EyeOffIcon className="size-3.5" />
      </button>
    )

  const priceRange = (from: 'purchaseFrom' | 'saleFrom', to: 'purchaseTo' | 'saleTo', label: string) => (
    <div className="flex justify-end gap-1">
      {([from, to] as const).map((key) => (
        <input
          key={key}
          type="search"
          inputMode="decimal"
          value={filters[key]}
          onChange={(event) => setFilter(key, event.target.value)}
          placeholder={key === from ? 'od' : 'do'}
          aria-label={`${label} ${key === from ? 'od' : 'do'}`}
          aria-invalid={parseNumber(filters[key]) === undefined || undefined}
          className={cn(filterInputClass, 'w-16 text-right')}
        />
      ))}
    </div>
  )

  const cell = (key: ColumnKey, item: DataEntryCatalogListItem): ReactNode => {
    switch (key) {
      case 'itm8':
        return <td className="px-2.5 py-1 whitespace-nowrap text-muted-foreground">{item.itm8}</td>
      case 'ean':
        return <td className="px-2.5 py-1 whitespace-nowrap">{item.ean}</td>
      case 'name':
        return (
          <td className="px-2.5 py-1">
            <span className="flex items-center gap-2">
              <span>{item.name}</span>
              {lastSaleDate && (
                <button
                  type="button"
                  onClick={() =>
                    setChartTarget({
                      kind: 'article',
                      itm8: item.itm8,
                      name: item.name ?? item.itm8,
                      ean: item.ean,
                      brand: item.brand,
                    })
                  }
                  aria-label={`Statystyki: ${item.name ?? item.itm8}`}
                  title="Sprzedaż, stan i dostawy artykułu"
                  className="flex size-6 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors duration-150 outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/30"
                >
                  <ChartColumnIcon className="size-4" />
                </button>
              )}
              {inTable.has(withoutZeros(item.ean)) && (
                <span className="inline-flex shrink-0 items-center gap-0.5 rounded-md bg-emerald-200 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50">
                  <CheckIcon className="size-3" aria-hidden />w tabeli
                </span>
              )}
            </span>
          </td>
        )
      case 'brand':
        return <td className="px-2.5 py-1">{item.brand}</td>
      case 'supplier':
        return (
          <td className="max-w-56 px-2.5 py-1">
            {item.lastDelivery && (
              <span
                className="flex items-baseline gap-1.5"
                title={[item.lastDelivery.supplierCode, item.lastDelivery.supplierName].filter(Boolean).join(' · ')}
              >
                <span className="truncate">{item.lastDelivery.supplierName ?? item.lastDelivery.supplierCode}</span>
                <span className="shrink-0 text-xs text-muted-foreground">
                  {formatShortDate(item.lastDelivery.date)}
                </span>
              </span>
            )}
          </td>
        )
      case 'nomenclature': {
        const nomenclature = item.subfamilyCode
          ? `${formatCode(item.subfamilyCode)} ${item.subfamilyName ?? ''}`.trim()
          : undefined
        return (
          <td className="max-w-52 truncate px-2.5 py-1" title={nomenclature}>
            {item.subfamilyCode && (
              <>
                <span className="text-muted-foreground">{formatCode(item.subfamilyCode)}</span> {item.subfamilyName}
              </>
            )}
          </td>
        )
      }
      case 'purchase':
        return (
          <td className="px-2.5 py-1 text-right whitespace-nowrap">
            {item.purchasePrice !== null && `${formatPrice(item.purchasePrice)} zł`}
          </td>
        )
      case 'sale':
        return (
          <td className="px-2.5 py-1 text-right whitespace-nowrap">
            {item.salePrice !== null && `${formatMoney(item.salePrice)} zł`}
          </td>
        )
      case 'stock':
        return (
          <td className="px-2.5 py-1 text-right whitespace-nowrap">
            {item.stock !== null && formatQuantity(item.stock)}
          </td>
        )
      case 'rotation':
        return (
          <td className="px-2.5 py-1 text-right whitespace-nowrap">
            {item.rotation !== null && formatQuantity(item.rotation)}
          </td>
        )
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-[96rem]">
        <DialogHeader>
          <DialogTitle className="text-lg">{title}</DialogTitle>
          <DialogDescription>
            Artykuły z kartoteki sklepu. Wpisz fragment nad dowolną kolumną; gwiazdka zastępuje dowolny ciąg znaków
            (excel*o znajdzie „EXCELLENT MLEKO”). Dostawca: kod albo nazwa, pokazuje artykuły przyjęte od niego na PZ
            w ostatnich 6 miesiącach. Nomenklatura: kod (np. 25 44) albo nazwa rejonu, rodziny lub podrodziny.{' '}
            {onPick ? 'Plus wybiera artykuł.' : 'Plus dodaje artykuł do tabeli.'} Oczko przy nagłówku chowa kolumnę.
          </DialogDescription>
        </DialogHeader>

        {(hidden.length > 0 || (onAdd && !onPick)) && (
          <div className="-mb-1 flex min-h-8 flex-wrap items-center gap-1.5 text-sm">
            {hidden.length > 0 && (
              <>
                <span className="text-muted-foreground">Ukryte kolumny:</span>
                {COLUMNS.filter((column) => hidden.includes(column.key)).map((column) => (
                  <button
                    key={column.key}
                    type="button"
                    onClick={() => changeHidden(hidden.filter((key) => key !== column.key))}
                    title="Pokaż kolumnę"
                    className="inline-flex h-7 items-center gap-1 rounded-md border bg-background px-2 text-xs transition-colors duration-150 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/30 dark:bg-input/30"
                  >
                    <EyeIcon className="size-3.5 text-muted-foreground" aria-hidden />
                    {column.label}
                  </button>
                ))}
                {hidden.length > 1 && (
                  <Button variant="ghost" size="sm" onClick={() => changeHidden([])}>
                    Pokaż wszystkie
                  </Button>
                )}
              </>
            )}
            {onAdd && !onPick && (
              <Button
                variant="outline"
                size="sm"
                className="ml-auto"
                onClick={collectFiltered}
                loading={collecting}
                disabled={!filtered || list.isPending || items.length === 0}
                title={
                  filtered
                    ? 'Dodaj do tabeli wszystkie artykuły pasujące do filtrów (ilość 1, cena z kartoteki)'
                    : 'Najpierw zawęź listę filtrami'
                }
              >
                <ListPlusIcon />
                Dodaj wszystkie wyfiltrowane
              </Button>
            )}
          </div>
        )}

        <div
          className={cn(
            'h-[62svh] overflow-auto rounded-xl border transition-opacity duration-150',
            list.isPlaceholderData && 'opacity-60',
          )}
        >
          <table className="w-full text-sm tabular-nums">
            <thead className="sticky top-0 z-10 bg-muted text-xs text-muted-foreground">
              <tr>
                <th className="w-10 px-2 pt-2 align-middle">
                  {filtered && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => setFilters(NO_FILTERS)}
                      aria-label="Wyczyść filtry"
                      title="Wyczyść filtry"
                    >
                      <XIcon />
                    </Button>
                  )}
                </th>
                {columns.map((column) => (
                  <th key={column.key} className={cn('px-1.5 pt-2 font-normal', column.className)}>
                    {column.text && (
                      <input
                        type="search"
                        value={filters[column.key as TextFilter]}
                        onChange={(event) => setFilter(column.key as TextFilter, event.target.value)}
                        placeholder={column.text.placeholder}
                        title={column.text.title}
                        aria-label={column.text.title ?? `Filtr: ${column.label}`}
                        autoFocus={column.key === 'name'}
                        className={filterInputClass}
                      />
                    )}
                    {column.range && priceRange(column.range[0], column.range[1], column.label)}
                  </th>
                ))}
              </tr>
              <tr>
                <th className="px-2 py-2" />
                {columns.map((column) =>
                  column.sort ? (
                    <SortableHead
                      key={column.key}
                      column={column.sort}
                      sort={sort}
                      onSort={onSort}
                      align={column.align}
                      className={headClass}
                      actions={hideButton(column.key, column.label)}
                    >
                      {column.label}
                    </SortableHead>
                  ) : (
                    <TableHead key={column.key} className={cn(headClass, 'font-medium text-muted-foreground')}>
                      {column.label}
                      {hideButton(column.key, column.label)}
                    </TableHead>
                  ),
                )}
              </tr>
            </thead>
            <tbody className="divide-y">
              {list.isError ? (
                <MessageRow span={columns.length + 1} className="text-destructive">
                  Nie udało się wczytać kartoteki.
                </MessageRow>
              ) : list.isPending ? (
                <MessageRow span={columns.length + 1}>
                  <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
                </MessageRow>
              ) : items.length === 0 ? (
                <MessageRow span={columns.length + 1}>Nie znaleziono artykułu.</MessageRow>
              ) : (
                items.map((item) => (
                  <tr key={item.itm8} className="hover:bg-muted/50">
                    <td className="px-2 py-1">
                      <Button
                        variant="outline"
                        size="icon-sm"
                        onClick={() => (onPick ? onPick(item) : setAdding(item))}
                        disabled={!item.ean}
                        aria-label={`${onPick ? 'Wybierz' : 'Dodaj do tabeli'}: ${item.name ?? item.itm8}`}
                        title={item.ean ? (onPick ? 'Wybierz ten artykuł' : 'Dodaj do tabeli') : 'Artykuł bez EAN-u'}
                      >
                        <PlusIcon />
                      </Button>
                    </td>
                    {columns.map((column) => (
                      <Fragment key={column.key}>{cell(column.key, item)}</Fragment>
                    ))}
                  </tr>
                ))
              )}
              <tr ref={loadMoreRef} aria-hidden />
            </tbody>
          </table>
        </div>
      </DialogContent>

      <Dialog open={adding !== null} onOpenChange={(next) => !next && setAdding(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-sm">
          {adding && (
            <AddForm
              key={adding.itm8}
              item={adding}
              onCancel={() => setAdding(null)}
              onAdd={async (quantity, unitNet) => {
                await onAdd?.([{ item: adding, quantity, unitNet }])
                setAdding(null)
              }}
            />
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={bulk !== null} onOpenChange={(next) => !next && !bulkAdding && setBulk(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-md">
          {bulk && (
            <>
              <DialogHeader>
                <DialogTitle className="text-lg">
                  Dodać {bulk.items.length} {articlesLabel(bulk.items.length)} do tabeli?
                </DialogTitle>
                <DialogDescription>
                  Wszystkie artykuły pasujące do filtrów, z ilością 1 i ceną zakupu z kartoteki. Ilości i ceny poprawisz
                  w tabeli.
                  {bulk.present > 0 && ` Pominięte, bo już są w tabeli: ${bulk.present}.`}
                  {bulk.withoutEan > 0 && ` Pominięte, bo nie mają EAN-u: ${bulk.withoutEan}.`}
                </DialogDescription>
              </DialogHeader>
              <FormActions>
                <Button variant="outline" size="lg" onClick={() => setBulk(null)} disabled={bulkAdding}>
                  Anuluj
                </Button>
                <Button size="lg" onClick={addBulk} loading={bulkAdding}>
                  Dodaj {bulk.items.length}
                </Button>
              </FormActions>
            </>
          )}
        </DialogContent>
      </Dialog>

      {lastSaleDate && (
        <SalesChartDialog
          storeId={storeId}
          kind="sales"
          target={chartTarget}
          filter={NO_NOMENCLATURE_FILTER}
          basis="gross"
          lastSaleDate={lastSaleDate}
          onClose={() => setChartTarget(null)}
        />
      )}
    </Dialog>
  )
}

/** "1 artykuł", "3 artykuły", "5 artykułów". */
function articlesLabel(count: number) {
  if (count === 1) return 'artykuł'
  const tens = count % 100
  const ones = count % 10
  return ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? 'artykuły' : 'artykułów'
}

function MessageRow({ span, className, children }: { span: number; className?: string; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={span} className={cn('px-4 py-6 text-center text-sm text-muted-foreground', className)}>
        {children}
      </td>
    </tr>
  )
}

const decimal = (value: number | null) => (value === null ? '' : String(value).replace('.', ','))

/** Ilość i cena zakupu netto artykułu dodawanego z listy; cena domyślnie z kartoteki. */
function AddForm({
  item,
  onCancel,
  onAdd,
}: {
  item: DataEntryCatalogListItem
  onCancel: () => void
  onAdd: (quantity: number, unitNet: number | null) => Promise<void>
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<{ quantity: string; unitNet: string }>({
    defaultValues: { quantity: '1', unitNet: decimal(item.purchasePrice) },
  })

  const onSubmit = handleSubmit(async ({ quantity, unitNet }) => {
    setFormError(null)
    try {
      await onAdd(parseNumber(quantity) ?? 0, parseNumber(unitNet) ?? null)
    } catch {
      setFormError('Nie udało się dodać artykułu do tabeli')
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Dodaj do tabeli</DialogTitle>
        <DialogDescription>
          {item.name} · EAN {item.ean}
        </DialogDescription>
      </DialogHeader>
      <TextField
        label="Ilość"
        inputMode="decimal"
        autoComplete="off"
        autoFocus
        onFocus={(event) => event.currentTarget.select()}
        error={errors.quantity?.message}
        {...register('quantity', {
          validate: (value) => {
            const parsed = parseNumber(value)
            if (parsed === null) return 'Podaj ilość'
            if (parsed === undefined) return 'Ilość to liczba, np. 2 albo 1,5'
            return parsed !== 0 || 'Ilość nie może być 0'
          },
        })}
      />
      <TextField
        label="Cena zakupu netto (zł)"
        inputMode="decimal"
        autoComplete="off"
        onFocus={(event) => event.currentTarget.select()}
        error={errors.unitNet?.message}
        {...register('unitNet', {
          validate: (value) => {
            const parsed = parseNumber(value)
            if (parsed === undefined) return 'Cena to liczba, np. 3,49'
            return parsed === null || parsed >= 0 || 'Cena nie może być ujemna'
          },
        })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Dodaj
        </Button>
      </FormActions>
    </Form>
  )
}
