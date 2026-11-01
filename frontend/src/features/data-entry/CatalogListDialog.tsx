import { ChartColumnIcon, CheckIcon, PlusIcon, XIcon } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'

import { Form, FormActions, TextField } from '@/components/form'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import type { SalesChartTarget } from '@/features/sales/api'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { nextSort, type SortState } from '@/lib/table-sort'
import type { DataEntryCatalogListItem } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

import { type CatalogListFilters, type CatalogSortKey, useCatalogList } from './api'
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

/** Kolumny z polem filtra nad nagłówkiem. */
const TEXT_COLUMNS: { key: TextFilter & CatalogSortKey; label: string; placeholder: string; className: string }[] = [
  { key: 'itm8', label: 'ITM8', placeholder: 'ITM8', className: 'w-32' },
  { key: 'ean', label: 'EAN', placeholder: 'EAN', className: 'w-36' },
  { key: 'name', label: 'Nazwa', placeholder: 'np. excel*o', className: 'min-w-60' },
  { key: 'brand', label: 'Marka', placeholder: 'Marka', className: 'w-32' },
  { key: 'supplier', label: 'Dostawca', placeholder: 'Dostawca', className: 'w-40' },
  { key: 'nomenclature', label: 'Nomenklatura', placeholder: 'kod albo nazwa', className: 'w-44' },
]

const filterInputClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-background dark:bg-input/30 px-2 text-sm text-foreground transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground/70 focus:border-ring focus:ring-3 focus:ring-ring/30 aria-invalid:border-destructive [&::-webkit-search-cancel-button]:hidden'

const DEFAULT_SORT: SortState<CatalogSortKey> = { key: 'name', direction: 'asc' }

/** Nagłówek z sortowaniem; mniejszy niż w zwykłych tabelach, bo nad nim są pola filtrów. */
const headClass = 'h-auto px-2.5 py-1 text-xs'

/** "250401" -> "25 04 01" jak w kartotece. */
const formatCode = (code: string) => code.replace(/(\d{2})(?=\d)/g, '$1 ')

const NO_NOMENCLATURE_FILTER: string[] = []

export type CatalogAddition = { item: DataEntryCatalogListItem; quantity: number; unitNet: number | null }

/**
 * Lista towarów z kartoteki sklepu: filtr nad każdą kolumną (fragment, `*` = dowolny ciąg znaków, `?` = jeden znak),
 * nomenklatura po kodzie albo nazwie, przedziały cen zakupu i sprzedaży, sortowanie po każdej kolumnie (w bazie). Plus przy wierszu pyta
 * o ilość i cenę zakupu i dopisuje artykuł do tabeli. Okno zostaje otwarte, żeby dodać kilka artykułów pod rząd.
 * Ikona wykresu (z dostępem do raportów sprzedaży, {@code lastSaleDate}) otwiera statystyki artykułu jak w raportach.
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
  /** Plus pyta o ilość i cenę i dodaje artykuł do tabeli. */
  onAdd?: (addition: CatalogAddition) => Promise<void>
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
  const [adding, setAdding] = useState<DataEntryCatalogListItem | null>(null)
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

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-[96rem]">
        <DialogHeader>
          <DialogTitle className="text-lg">{title}</DialogTitle>
          <DialogDescription>
            Artykuły z kartoteki sklepu. Wpisz fragment nad dowolną kolumną; gwiazdka zastępuje dowolny ciąg znaków
            (excel*o znajdzie „EXCELLENT MLEKO”). Nomenklatura: kod (np. 25 44) albo nazwa rejonu, rodziny lub
            podrodziny. {onPick ? 'Plus wybiera artykuł.' : 'Plus dodaje artykuł do tabeli.'}
          </DialogDescription>
        </DialogHeader>

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
                {TEXT_COLUMNS.map((column) => (
                  <th key={column.key} className={cn('px-1.5 pt-2 font-normal', column.className)}>
                    <input
                      type="search"
                      value={filters[column.key]}
                      onChange={(event) => setFilter(column.key, event.target.value)}
                      placeholder={column.placeholder}
                      aria-label={`Filtr: ${column.label}`}
                      autoFocus={column.key === 'name'}
                      className={filterInputClass}
                    />
                  </th>
                ))}
                <th className="px-1.5 pt-2 font-normal">{priceRange('purchaseFrom', 'purchaseTo', 'Zakup netto')}</th>
                <th className="px-1.5 pt-2 font-normal">{priceRange('saleFrom', 'saleTo', 'Sprzedaż brutto')}</th>
                <th className="px-2 pt-2" />
              </tr>
              <tr>
                <th className="px-2 py-2" />
                {TEXT_COLUMNS.map((column) => (
                  <SortableHead key={column.key} column={column.key} sort={sort} onSort={onSort} className={headClass}>
                    {column.label}
                  </SortableHead>
                ))}
                <SortableHead column="purchase" sort={sort} onSort={onSort} align="right" className={headClass}>
                  Zakup netto
                </SortableHead>
                <SortableHead column="sale" sort={sort} onSort={onSort} align="right" className={headClass}>
                  Sprzedaż brutto
                </SortableHead>
                <SortableHead column="stock" sort={sort} onSort={onSort} align="right" className={headClass}>
                  Stan
                </SortableHead>
              </tr>
            </thead>
            <tbody className="divide-y">
              {list.isError ? (
                <MessageRow className="text-destructive">Nie udało się wczytać kartoteki.</MessageRow>
              ) : list.isPending ? (
                <MessageRow>
                  <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
                </MessageRow>
              ) : items.length === 0 ? (
                <MessageRow>Nie znaleziono artykułu.</MessageRow>
              ) : (
                items.map((item) => {
                  const present = inTable.has(item.ean.replace(/^0+/, ''))
                  const nomenclature = item.subfamilyCode
                    ? `${formatCode(item.subfamilyCode)} ${item.subfamilyName ?? ''}`.trim()
                    : undefined
                  return (
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
                      <td className="px-2.5 py-1 whitespace-nowrap text-muted-foreground">{item.itm8}</td>
                      <td className="px-2.5 py-1 whitespace-nowrap">{item.ean}</td>
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
                          {present && (
                            <span className="inline-flex shrink-0 items-center gap-0.5 rounded-md bg-emerald-200 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50">
                              <CheckIcon className="size-3" aria-hidden />w tabeli
                            </span>
                          )}
                        </span>
                      </td>
                      <td className="px-2.5 py-1">{item.brand}</td>
                      <td className="max-w-48 truncate px-2.5 py-1" title={item.supplier ?? undefined}>
                        {item.supplier}
                      </td>
                      <td className="max-w-52 truncate px-2.5 py-1" title={nomenclature}>
                        {item.subfamilyCode && (
                          <>
                            <span className="text-muted-foreground">{formatCode(item.subfamilyCode)}</span>{' '}
                            {item.subfamilyName}
                          </>
                        )}
                      </td>
                      <td className="px-2.5 py-1 text-right whitespace-nowrap">
                        {item.purchasePrice !== null && `${formatPrice(item.purchasePrice)} zł`}
                      </td>
                      <td className="px-2.5 py-1 text-right whitespace-nowrap">
                        {item.salePrice !== null && `${formatMoney(item.salePrice)} zł`}
                      </td>
                      <td className="px-2.5 py-1 text-right whitespace-nowrap">
                        {item.stock !== null && formatQuantity(item.stock)}
                      </td>
                    </tr>
                  )
                })
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
                await onAdd?.({ item: adding, quantity, unitNet })
                setAdding(null)
              }}
            />
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

function MessageRow({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <tr>
      <td colSpan={10} className={cn('px-4 py-6 text-center text-sm text-muted-foreground', className)}>
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
