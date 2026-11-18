import { type FormEvent, type ReactElement, useMemo, useState } from 'react'
import { CheckIcon, FolderInputIcon, PencilIcon, PlusIcon, SettingsIcon, Trash2Icon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import {
  Form,
  FormActions,
  MultiComboboxField,
  SearchableSelectField,
  SegmentedField,
  TextField,
} from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type MissingAssortmentChange,
  type MissingAssortmentQuery,
  type SalesChartTarget,
  useChangeMissingAssortment,
  useMissingAssortment,
  useSalesNomenclatures,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { ChartButton, nomenclatureName, StatTile } from '@/features/sales/report-parts'
import { lastCompleteDay, useReportParams } from '@/features/sales/report-period'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { ApiError } from '@/lib/api'
import { compareSupplierCodes } from '@/lib/supplier-order'
import { nextSort, type SortAccessors, sortRows, type SortState } from '@/lib/table-sort'
import type { MissingAssortmentCategory, MissingAssortmentItem } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'
import { LoadingPanel } from '@/components/LoadingPanel'

const PAGE = 100
const DEFAULT_MONTHS = '3'
const DEFAULT_SILENT_DAYS = 7
const ALL_SUPPLIERS = 'all'
/** Zakładka artykułów bez kategorii (domyślna) i wszystkich artykułów; pozostałe to id kategorii. */
const TO_CHECK = null
const ALL = 'wszystkie'

const MONTHS = [
  { value: '1', label: 'Miesiąc' },
  { value: '3', label: '3 miesiące' },
  { value: '6', label: 'Pół roku' },
  { value: '12', label: 'Rok' },
]

type SortKey = 'itm8' | 'name' | 'supplier' | 'purchase' | 'stock' | 'lastSale' | 'weekly' | 'value'

const SORT_PARAMS: Record<SortKey, string> = {
  itm8: 'itm8',
  name: 'nazwa',
  supplier: 'dostawca',
  purchase: 'zakup',
  stock: 'stan',
  lastSale: 'sprzedaz',
  weekly: 'tydzien',
  value: 'wartosc',
}

const ACCESSORS: SortAccessors<MissingAssortmentItem, SortKey> = {
  itm8: (item) => item.itm8,
  name: (item) => item.name,
  supplier: (item) => item.supplierName,
  purchase: (item) => item.lastPurchaseDate,
  stock: (item) => item.stock,
  lastSale: (item) => item.lastSale,
  weekly: (item) => item.weeklyInStock,
  value: (item) => item.weeklyValue,
}

const DEFAULT_SORT: SortState<SortKey> = { key: 'value', direction: 'desc' }

const money = (value: number) => `${formatMoney(value)} zł`
const itm8Label = (itm8: string) => itm8.replace(/^0+/, '')
const daysLabel = (days: number) => (days === 1 ? '1 dzień' : `${formatQuantity(days)} dni`)
/**
 * Średnia z mniej niż tygodnia na stanie (cała dostawa zeszła w 1-3 dni, zwykle akcja) mówi niewiele: 50 szt. w dniu
 * dostawy to 350 na tydzień. Liczbę zostawiamy, ale przygaszoną i z liczbą dni.
 */
const SHORT_STOCK_DAYS = 7
const shortStock = (item: MissingAssortmentItem) => item.daysInStock != null && item.daysInStock < SHORT_STOCK_DAYS
const errorText = (error: unknown) => (error instanceof ApiError ? error.message : 'Nie udało się zmienić kategorii')

function matches(item: MissingAssortmentItem, words: string[]) {
  const text = [item.name, item.brand, item.ean, item.itm8, item.supplierName, item.supplierCode]
    .join(' ')
    .toLowerCase()
  return words.every((word) => text.includes(word))
}

function categoryLabel(category: MissingAssortmentCategory) {
  return category.own ? category.name : `${category.name} (sklep ${category.storeNumber})`
}

export function MissingAssortmentReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const canEdit = useCan('SALES', 'EDIT')
  const { params, update } = useReportParams()

  const silentField = useSearchParamText('cisza')
  const searchField = useSearchParamText('szukaj')
  const silentDays = silentField.debounced === '' ? DEFAULT_SILENT_DAYS : Number(silentField.debounced)
  const silentError =
    Number.isInteger(silentDays) && silentDays >= 0 && silentDays <= 365 ? undefined : 'Podaj liczbę dni.'
  const months = MONTHS.find((option) => option.value === params.get('okres'))?.value ?? DEFAULT_MONTHS

  const query: MissingAssortmentQuery = {
    months: Number(months),
    silentDays,
    nomenclatures: [...new Set(params.getAll('nom'))].sort(),
  }
  const report = useMissingAssortment(storeId, query, !silentError)
  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const categories = useMemo(() => report.data?.categories ?? [], [report.data])

  const tabParam = params.get('kategoria')
  const tab = tabParam === ALL || categories.some((category) => category.id === tabParam) ? tabParam : TO_CHECK
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
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

  // Dostawca i wyszukiwarka zawężają wszystkie zakładki, więc liczniki przy zakładkach też.
  const filtered = useMemo(() => {
    const words = searchField.debounced.toLowerCase().split(/\s+/).filter(Boolean)
    return (report.data?.items ?? []).filter(
      (item) => (supplier === ALL_SUPPLIERS || item.supplierCode === supplier) && matches(item, words),
    )
  }, [report.data, searchField.debounced, supplier])
  const items = useMemo(
    () =>
      sortRows(
        filtered.filter((item) => tab === ALL || item.categoryId === tab),
        ACCESSORS,
        sort,
      ),
    [filtered, tab, sort],
  )
  const suppliers = useMemo(() => {
    const found = new Map<string, { name: string | null; count: number }>()
    for (const item of report.data?.items ?? []) {
      if ((tab === ALL || item.categoryId === tab) && item.supplierCode) {
        const known = found.get(item.supplierCode)
        found.set(item.supplierCode, { name: item.supplierName, count: (known?.count ?? 0) + 1 })
      }
    }
    return [...found.entries()].sort(([a], [b]) => compareSupplierCodes(a, b))
  }, [report.data, tab])

  const tabs: { key: string | null; label: string; hint?: string }[] = [
    { key: TO_CHECK, label: 'Do sprawdzenia' },
    ...categories.map((category) => ({
      key: category.id,
      label: category.name,
      hint: category.own ? undefined : `sklep ${category.storeNumber}`,
    })),
    { key: ALL, label: 'Wszystkie' },
  ]
  const count = (key: string | null) =>
    key === ALL ? filtered.length : filtered.filter((item) => item.categoryId === key).length

  const [managing, setManaging] = useState(false)
  const baseCodes = report.data?.baseSupplierCodes.join(' i ') ?? '538 i 88110'

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Brakujący asortyment</h2>
        {report.data && <ReportFreshness generatedAt={report.data.stockReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Artykuły kupione w wybranym okresie od dostawców spoza bazy ({baseCodes} pomijamy), których nie ma na stanie i
        które się nie sprzedają. Sprzedaż na tydzień liczy się tylko z dni, w których towar był: od dostawy do ostatniej
        sprzedaży przed kolejną. Bez produkcji, mięsa i garmażu.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-3">
          <div className="grid gap-1.5">
            <span className="text-sm font-medium">Zakupy z ostatnich</span>
            <SegmentedField
              label="Zakupy z ostatnich"
              options={MONTHS}
              value={months}
              onChange={(next) => update({ okres: next === DEFAULT_MONTHS ? null : next })}
            />
          </div>
          <TextField
            label={`Bez sprzedaży od (domyślnie ${DEFAULT_SILENT_DAYS} dni)`}
            inputMode="numeric"
            value={silentField.text}
            onChange={(event) => silentField.setText(event.target.value)}
            error={silentError}
            className="w-full sm:w-72"
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
          <SearchableSelectField
            label="Dostawca (ostatni zakup)"
            options={[
              { value: ALL_SUPPLIERS, label: 'Wszyscy dostawcy' },
              ...suppliers.map(([code, { name, count: itemCount }]) => ({
                value: code,
                label: `${code} ${name ?? ''} (${itemCount})`,
              })),
            ]}
            value={supplier}
            onChange={(next) => update({ dostawca: next === ALL_SUPPLIERS ? null : next })}
          />
        </div>
      </Card>

      <div className="mb-4 flex flex-wrap items-center gap-1.5">
        <div role="tablist" aria-label="Kategoria" className="flex flex-wrap gap-1.5">
          {tabs.map((option) => {
            const selected = option.key === tab
            return (
              <button
                key={option.key ?? 'do-sprawdzenia'}
                type="button"
                role="tab"
                aria-selected={selected}
                onClick={() => update({ kategoria: option.key })}
                className={cn(
                  'rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                  selected ? 'border-foreground bg-foreground text-background' : 'bg-card hover:border-foreground/20',
                )}
              >
                {option.label}
                {option.hint && (
                  <span className={cn('ml-1 text-xs', selected ? 'text-background/70' : 'text-muted-foreground')}>
                    {option.hint}
                  </span>
                )}
                <span className={cn('ml-1.5 tabular-nums', selected ? 'text-background/70' : 'text-muted-foreground')}>
                  {count(option.key)}
                </span>
              </button>
            )
          })}
        </div>
        {canEdit && (
          <Button variant="ghost" size="sm" onClick={() => setManaging(true)}>
            <SettingsIcon />
            Kategorie
          </Button>
        )}
      </div>

      <SearchInput
        value={searchField.text}
        onChange={searchField.setText}
        placeholder="Szukaj: nazwa, marka, EAN, ITM8, dostawca"
        className="mb-4 max-w-md"
      />

      {silentError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć raportu</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <LoadingPanel className="h-72 rounded-xl" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <Results
            key={`${tab}:${JSON.stringify(query)}:${supplier}`}
            storeId={storeId}
            items={items}
            categories={categories}
            canEdit={canEdit}
            sort={sort}
            onSort={onSort}
            lastSaleDate={lastSaleDate}
            dataUntil={report.data.lastSaleDate}
            since={report.data.since}
          />
        </div>
      )}

      <CategoriesDialog storeId={storeId} categories={categories} open={managing} onOpenChange={setManaging} />
    </>
  )
}

function Results({
  storeId,
  items,
  categories,
  canEdit,
  sort,
  onSort,
  lastSaleDate,
  dataUntil,
  since,
}: {
  storeId: string
  items: MissingAssortmentItem[]
  categories: MissingAssortmentCategory[]
  canEdit: boolean
  sort: SortState<SortKey>
  onSort: (column: SortKey) => void
  lastSaleDate: string
  dataUntil: string | null
  since: string
}) {
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const [shown, setShown] = useState(PAGE)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const loadMore = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < items.length, shown)
  const sorting = { sort, onSort }
  const chosen = items.filter((item) => selected.has(item.itm8))
  const supplierCount = new Set(items.map((item) => item.supplierCode)).size
  const toggle = (itm8: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(itm8)) next.delete(itm8)
      else next.add(itm8)
      return next
    })

  return (
    <>
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile
          label="Artykułów"
          value={formatQuantity(items.length)}
          detail={`Zakupy od ${formatDocumentDate(since)}`}
          tone="strong"
        />
        <StatTile
          label="Dostawców"
          value={formatQuantity(supplierCount)}
          detail={dataUntil ? `Sprzedaż do ${formatDocumentDate(dataUntil)}` : 'Brak wgranej sprzedaży'}
        />
        <StatTile
          label="Sprzedaż na tydzień, gdy były"
          value={money(items.reduce((sum, item) => sum + (item.weeklyValue ?? 0), 0))}
          detail="W cenach z kartoteki: tyle mniej więcej ucieka co tydzień"
        />
      </div>

      {items.length === 0 ? (
        <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          Żaden artykuł nie spełnia tych filtrów.
        </p>
      ) : (
        <Card className="overflow-clip p-0">
          <div className="overflow-x-auto lg:overflow-x-visible">
            <table className="w-full caption-bottom text-sm">
              <TableHeader className="bg-card lg:sticky lg:top-14 lg:z-10">
                <TableRow>
                  {canEdit && (
                    <TableHead className="w-10 pl-4">
                      <input
                        type="checkbox"
                        aria-label="Zaznacz wszystkie"
                        className="size-4 accent-foreground"
                        checked={chosen.length > 0 && chosen.length === items.length}
                        ref={(element) => {
                          if (element) element.indeterminate = chosen.length > 0 && chosen.length < items.length
                        }}
                        onChange={(event) =>
                          setSelected(event.target.checked ? new Set(items.map((item) => item.itm8)) : new Set())
                        }
                      />
                    </TableHead>
                  )}
                  <SortableHead column="itm8" {...sorting}>
                    ITM8
                  </SortableHead>
                  <SortableHead column="name" {...sorting} className="min-w-72">
                    Nazwa
                  </SortableHead>
                  <SortableHead column="supplier" {...sorting}>
                    Dostawca
                  </SortableHead>
                  <SortableHead column="purchase" {...sorting} className="whitespace-nowrap">
                    Ostatni zakup
                  </SortableHead>
                  <SortableHead column="stock" align="right" {...sorting}>
                    Stan
                  </SortableHead>
                  <SortableHead column="lastSale" {...sorting} className="whitespace-nowrap">
                    Ostatnia sprzedaż
                  </SortableHead>
                  <SortableHead column="weekly" align="right" {...sorting} className="whitespace-nowrap">
                    Szt. / tydz. na stanie
                  </SortableHead>
                  <SortableHead column="value" align="right" {...sorting} className="whitespace-nowrap">
                    Sprzedaż / tydz.
                  </SortableHead>
                  <TableHead>Kategoria</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.slice(0, shown).map((item) => {
                  const category = categories.find((option) => option.id === item.categoryId)
                  return (
                    <TableRow key={item.itm8} data-state={selected.has(item.itm8) ? 'selected' : undefined}>
                      {canEdit && (
                        <TableCell className="w-10 pl-4">
                          <input
                            type="checkbox"
                            aria-label={`Zaznacz ${item.name ?? item.itm8}`}
                            className="size-4 accent-foreground"
                            checked={selected.has(item.itm8)}
                            onChange={() => toggle(item.itm8)}
                          />
                        </TableCell>
                      )}
                      <TableCell className="tabular-nums">
                        {itm8Label(item.itm8)}
                        {item.ean && <div className="text-xs text-muted-foreground">{item.ean}</div>}
                      </TableCell>
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
                      <TableCell className="max-w-56 truncate" title={item.supplierName ?? undefined}>
                        <span className="text-muted-foreground tabular-nums">{item.supplierCode}</span>{' '}
                        {item.supplierName ?? '–'}
                      </TableCell>
                      <TableCell className="tabular-nums" title={`Przyjęć w okresie: ${item.purchases}`}>
                        {formatDocumentDate(item.lastPurchaseDate)}
                        {item.lastPurchaseQuantity != null && (
                          <div className="text-xs text-muted-foreground">
                            {formatQuantity(item.lastPurchaseQuantity)} szt.
                            {item.lastPurchasePrice != null && ` × ${money(item.lastPurchasePrice)}`}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatQuantity(item.stock)}</TableCell>
                      <TableCell className="tabular-nums">
                        {item.lastSale ? (
                          <>
                            {formatDocumentDate(item.lastSale)}
                            {item.silentDays != null && (
                              <div className="text-xs text-muted-foreground">{daysLabel(item.silentDays)} temu</div>
                            )}
                          </>
                        ) : (
                          <span className="text-muted-foreground">nigdy</span>
                        )}
                      </TableCell>
                      <TableCell
                        className="text-right tabular-nums"
                        title={
                          item.daysInStock != null && item.soldInStock != null
                            ? `${formatQuantity(item.soldInStock)} szt. w ${daysLabel(item.daysInStock)} na stanie`
                            : 'Po żadnej dostawie nic się nie sprzedało'
                        }
                      >
                        {item.weeklyInStock == null ? (
                          '–'
                        ) : (
                          <>
                            <span className={cn(shortStock(item) && 'text-muted-foreground')}>
                              {formatQuantity(item.weeklyInStock)}
                            </span>
                            <div
                              className={cn(
                                'text-xs',
                                shortStock(item)
                                  ? 'font-medium text-amber-700 dark:text-amber-400'
                                  : 'text-muted-foreground',
                              )}
                            >
                              {shortStock(item) ? 'tylko ' : 'z '}
                              {daysLabel(item.daysInStock ?? 0)} na stanie
                            </div>
                          </>
                        )}
                      </TableCell>
                      <TableCell
                        className={cn(
                          'text-right font-medium tabular-nums',
                          shortStock(item) && 'text-muted-foreground',
                        )}
                      >
                        {item.weeklyValue == null ? '–' : money(item.weeklyValue)}
                      </TableCell>
                      <TableCell>
                        {canEdit ? (
                          <MoveMenu
                            storeId={storeId}
                            categories={categories}
                            items={[item]}
                            trigger={
                              <Button variant="ghost" size="sm" className="max-w-48 justify-start">
                                <FolderInputIcon />
                                <span className="truncate">{category ? categoryLabel(category) : 'Przenieś'}</span>
                              </Button>
                            }
                          />
                        ) : (
                          <span className="text-muted-foreground">{category ? categoryLabel(category) : '–'}</span>
                        )}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </table>
          </div>
          <div ref={loadMore} />
          {canEdit && chosen.length > 0 && (
            <div className="sticky bottom-0 z-10 flex flex-wrap items-center gap-2 border-t bg-card px-4 py-2.5">
              <span className="text-sm font-medium tabular-nums">Zaznaczone: {formatQuantity(chosen.length)}</span>
              <MoveMenu
                storeId={storeId}
                categories={categories}
                items={chosen}
                onDone={() => setSelected(new Set())}
                trigger={
                  <Button size="sm">
                    <FolderInputIcon />
                    Przenieś do kategorii
                  </Button>
                }
              />
              <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
                <XIcon />
                Odznacz wszystko
              </Button>
            </div>
          )}
        </Card>
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

/** Przeniesienie artykułów do kategorii albo z powrotem do „Do sprawdzenia”, z „Cofnij” w komunikacie. */
function MoveMenu({
  storeId,
  categories,
  items,
  trigger,
  onDone,
}: {
  storeId: string
  categories: MissingAssortmentCategory[]
  items: MissingAssortmentItem[]
  trigger: ReactElement
  onDone?: () => void
}) {
  const change = useChangeMissingAssortment(storeId)
  const [creating, setCreating] = useState(false)
  const move = (target: MissingAssortmentCategory | null) => {
    const affected = items.filter((item) => item.categoryId !== (target?.id ?? null))
    if (affected.length === 0) return
    const undo: MissingAssortmentChange = {
      action: 'assign',
      items: affected.map((item) => ({ itm8: item.itm8, categoryId: item.categoryId })),
    }
    change.mutate(
      { action: 'assign', items: affected.map((item) => ({ itm8: item.itm8, categoryId: target?.id ?? null })) },
      {
        onError: (error) => toast.error(errorText(error)),
        onSuccess: () => {
          onDone?.()
          const what = affected.length === 1 ? 'Artykuł' : `${affected.length} art.`
          toast.success(target ? `${what} w kategorii: ${target.name}` : `${what} wraca do sprawdzenia`, {
            action: {
              label: 'Cofnij',
              onClick: () => change.mutate(undo, { onError: (e) => toast.error(errorText(e)) }),
            },
          })
        },
      },
    )
  }
  const own = categories.filter((category) => category.own)
  const shared = categories.filter((category) => !category.own)
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger render={trigger} />
        <DropdownMenuContent align="end" className="min-w-60">
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={() => move(null)}>Do sprawdzenia (bez kategorii)</DropdownMenuItem>
          </DropdownMenuGroup>
          {own.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>Kategorie sklepu</DropdownMenuLabel>
                {own.map((category) => (
                  <DropdownMenuItem key={category.id} onClick={() => move(category)}>
                    {category.name}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuGroup>
            </>
          )}
          {shared.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>Sklepów zaprzyjaźnionych</DropdownMenuLabel>
                {shared.map((category) => (
                  <DropdownMenuItem key={category.id} onClick={() => move(category)}>
                    {category.name}
                    <span className="ml-auto text-xs text-muted-foreground">sklep {category.storeNumber}</span>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuGroup>
            </>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={() => setCreating(true)}>
              <PlusIcon />
              Nowa kategoria…
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent className="sm:max-w-md">
          <NewCategoryForm
            storeId={storeId}
            onCreated={(category) => {
              setCreating(false)
              if (category) move(category)
            }}
            onCancel={() => setCreating(false)}
            description={`Od razu trafi do niej ${items.length === 1 ? 'wybrany artykuł' : `${items.length} wybranych artykułów`}.`}
          />
        </DialogContent>
      </Dialog>
    </>
  )
}

function NewCategoryForm({
  storeId,
  onCreated,
  onCancel,
  description,
}: {
  storeId: string
  onCreated: (category: MissingAssortmentCategory | null) => void
  onCancel: () => void
  description: string
}) {
  const change = useChangeMissingAssortment(storeId)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const clean = name.trim().replace(/\s+/g, ' ')
    if (clean === '') {
      setError('Podaj nazwę kategorii')
      return
    }
    try {
      const result = await change.mutateAsync({ action: 'create', name: clean })
      toast.success(`Dodano kategorię: ${clean}`)
      onCreated(result.find((category) => category.own && category.name.toLowerCase() === clean.toLowerCase()) ?? null)
    } catch (caught) {
      setError(errorText(caught))
    }
  }
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Nowa kategoria</DialogTitle>
        <DialogDescription>{description}</DialogDescription>
      </DialogHeader>
      <Form onSubmit={submit} error={error}>
        <TextField
          label="Nazwa, np. Wycofane z produkcji"
          value={name}
          onChange={(event) => setName(event.target.value)}
          autoFocus
          maxLength={60}
        />
        <FormActions>
          <Button type="button" variant="outline" onClick={onCancel}>
            Anuluj
          </Button>
          <Button type="submit" loading={change.isPending}>
            Dodaj kategorię
          </Button>
        </FormActions>
      </Form>
    </>
  )
}

/** Kategorie sklepu (zmiana nazwy, usunięcie z „Przywróć”) i kategorie sklepów zaprzyjaźnionych do odczytu. */
function CategoriesDialog({
  storeId,
  categories,
  open,
  onOpenChange,
}: {
  storeId: string
  categories: MissingAssortmentCategory[]
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const change = useChangeMissingAssortment(storeId)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null)
  const own = categories.filter((category) => category.own)
  const shared = categories.filter((category) => !category.own)

  const remove = (category: MissingAssortmentCategory) =>
    change.mutate(
      { action: 'delete', id: category.id },
      {
        onError: (error) => toast.error(errorText(error)),
        onSuccess: () =>
          toast.success(`Usunięto kategorię: ${category.name}`, {
            description: category.articleCount > 0 ? 'Jej artykuły wracają do sprawdzenia.' : undefined,
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate({ action: 'restore', id: category.id }, { onError: (e) => toast.error(errorText(e)) }),
            },
          }),
      },
    )
  const rename = (event: FormEvent) => {
    event.preventDefault()
    if (!editing || editing.name.trim() === '') return
    change.mutate(
      { action: 'rename', id: editing.id, name: editing.name.trim() },
      { onError: (error) => toast.error(errorText(error)), onSuccess: () => setEditing(null) },
    )
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next)
        if (!next) {
          setAdding(false)
          setEditing(null)
        }
      }}
    >
      <DialogContent className="sm:max-w-lg">
        {adding ? (
          <NewCategoryForm
            storeId={storeId}
            onCreated={() => setAdding(false)}
            onCancel={() => setAdding(false)}
            description="Kategoria widoczna też w sklepach zaprzyjaźnionych; artykuły każdy sklep przypisuje u siebie."
          />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle className="text-lg">Kategorie brakującego asortymentu</DialogTitle>
              <DialogDescription>
                Np. „wycofane z produkcji” albo „aktualnie brak u dostawcy”. Artykuł z kategorią znika z „Do
                sprawdzenia”. Sklepy zaprzyjaźnione widzą kategorie tego sklepu, a artykuły przypisują u siebie.
              </DialogDescription>
            </DialogHeader>
            <div className="grid gap-4">
              <ul className="grid gap-1">
                {own.length === 0 && <li className="text-sm text-muted-foreground">Sklep nie ma jeszcze kategorii.</li>}
                {own.map((category) =>
                  editing?.id === category.id ? (
                    <li key={category.id}>
                      <form onSubmit={rename} className="flex items-center gap-2">
                        <input
                          aria-label="Nazwa kategorii"
                          value={editing.name}
                          maxLength={60}
                          autoFocus
                          onChange={(event) => setEditing({ id: category.id, name: event.target.value })}
                          className="h-9 min-w-0 flex-1 rounded-lg border border-input bg-background px-3 text-sm outline-none focus:border-ring focus:ring-3 focus:ring-ring/30 dark:bg-input/30"
                        />
                        <Button type="submit" size="icon" aria-label="Zapisz nazwę" loading={change.isPending}>
                          <CheckIcon />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          aria-label="Anuluj"
                          onClick={() => setEditing(null)}
                        >
                          <XIcon />
                        </Button>
                      </form>
                    </li>
                  ) : (
                    <li key={category.id} className="flex items-center gap-2 rounded-lg px-2 py-1.5 hover:bg-muted">
                      <span className="min-w-0 flex-1 truncate">{category.name}</span>
                      <span className="text-sm text-muted-foreground tabular-nums">
                        {formatQuantity(category.articleCount)} art.
                      </span>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Zmień nazwę: ${category.name}`}
                        onClick={() => setEditing({ id: category.id, name: category.name })}
                      >
                        <PencilIcon />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Usuń kategorię: ${category.name}`}
                        onClick={() => remove(category)}
                      >
                        <Trash2Icon />
                      </Button>
                    </li>
                  ),
                )}
              </ul>
              {shared.length > 0 && (
                <div className="grid gap-1">
                  <p className="text-sm font-medium">Kategorie sklepów zaprzyjaźnionych</p>
                  <ul className="grid gap-1">
                    {shared.map((category) => (
                      <li key={category.id} className="flex items-center gap-2 px-2 py-1.5 text-sm">
                        <span className="min-w-0 flex-1 truncate">{category.name}</span>
                        <span className="text-muted-foreground">sklep {category.storeNumber}</span>
                        <span className="text-muted-foreground tabular-nums">
                          {formatQuantity(category.articleCount)} art.
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="flex justify-end">
                <Button onClick={() => setAdding(true)}>
                  <PlusIcon />
                  Nowa kategoria
                </Button>
              </div>
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
