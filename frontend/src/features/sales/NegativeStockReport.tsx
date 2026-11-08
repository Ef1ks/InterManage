import { useMemo, useState } from 'react'

import { BackLink } from '@/components/BackLink'
import { DateField, MultiComboboxField, SegmentedField, SelectField, SwitchField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableHeader, TableRow } from '@/components/ui/table'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import { type NegativeStockQuery, useNegativeStock, useSalesNomenclatures } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { nomenclatureName, StatTile } from '@/features/sales/report-parts'
import { rangeError, useReportParams } from '@/features/sales/report-period'
import { addDays, todayIso } from '@/lib/dates'
import { nextSort, type SortAccessors, sortRows, type SortState } from '@/lib/table-sort'
import type { NegativeStockDelivery, NegativeStockItem } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const PAGE = 100
const DEFAULT_DAYS = 14
const DEFAULT_PERIOD_DAYS = 30
const ALL_SUPPLIERS = 'all'
const NO_SUPPLIER = 'none'

const DELIVERY: { value: NegativeStockDelivery; label: string; param: string | null; hint: string }[] = [
  {
    value: 'NO_DELIVERY_SINCE',
    label: 'Bez dostawy od',
    param: null,
    hint: 'Stan na minusie, a dostawy nie było od podanej liczby dni (albo nigdy). Jeśli towar się sprzedaje, przyjechał najpewniej pod innym kodem.',
  },
  {
    value: 'NEVER',
    label: 'Nigdy bez dostawy',
    param: 'nigdy',
    hint: 'Artykuły, do których nigdy nic nie przyszło, a stan mają na minusie: sprzedają się pod kodem, pod którym towar nie jest przyjmowany.',
  },
  {
    value: 'DELIVERED_IN',
    label: 'Z dostawą w okresie',
    param: 'w-okresie',
    hint: 'Była dostawa w wybranym okresie, a stan i tak jest na minusie: przyjęto za mało albo sprzedaż idzie pod innym kodem.',
  },
  { value: 'ANY', label: 'Wszystkie', param: 'wszystkie', hint: 'Wszystkie artykuły ze stanem na minusie.' },
]

const CADENCIER = [
  { value: 'all', label: 'Wszystkie', param: null },
  { value: 'with', label: 'W cadencier', param: 'tak' },
  { value: 'without', label: 'Poza', param: 'nie' },
] as const

type SortKey =
  'itm8' | 'ean' | 'name' | 'supplier' | 'delivery' | 'deliveries' | 'stock' | 'value' | 'sold' | 'gross' | 'lastSale'

const SORT_PARAMS: Record<SortKey, string> = {
  itm8: 'itm8',
  ean: 'ean',
  name: 'nazwa',
  supplier: 'dostawca',
  delivery: 'dostawa',
  deliveries: 'dostaw',
  stock: 'stan',
  value: 'wartosc',
  sold: 'sprzedaz',
  gross: 'brutto',
  lastSale: 'ostatnia',
}

const DEFAULT_SORT: SortState<SortKey> = { key: 'value', direction: 'asc' }

const decimal = (text: string) => Number(text.replace(',', '.'))
const money = (value: number) => `${formatMoney(value)} zł`
const itm8Label = (itm8: string) => itm8.replace(/^0+/, '')
const daysAgo = (days: number) => (days === 0 ? 'dziś' : days === 1 ? 'wczoraj' : `${formatQuantity(days)} dni temu`)

function matches(item: NegativeStockItem, words: string[]) {
  const text = [item.name, item.brand, item.ean, item.itm8, item.supplierName, item.supplierCode]
    .join(' ')
    .toLowerCase()
  return words.every((word) => text.includes(word))
}

export function NegativeStockReport({ storeId }: { storeId: string }) {
  const nomenclatures = useSalesNomenclatures(storeId)
  const { params, update } = useReportParams()

  const daysField = useSearchParamText('dni')
  const searchField = useSearchParamText('szukaj')

  const delivery = DELIVERY.find((option) => option.param === params.get('dostawy')) ?? DELIVERY[0]
  const days = daysField.debounced === '' ? DEFAULT_DAYS : decimal(daysField.debounced)
  const daysError =
    delivery.value !== 'NO_DELIVERY_SINCE' || (Number.isInteger(days) && days >= 1)
      ? undefined
      : 'Podaj liczbę dni od 1.'
  const today = todayIso()
  const from = params.get('od') ?? addDays(today, -(DEFAULT_PERIOD_DAYS - 1))
  const to = params.get('do') ?? today
  const periodError = (delivery.value === 'DELIVERED_IN' && rangeError({ from, to })) || undefined

  const query: NegativeStockQuery = {
    delivery: delivery.value,
    days: delivery.value === 'NO_DELIVERY_SINCE' ? days : DEFAULT_DAYS,
    from: delivery.value === 'DELIVERED_IN' ? from : null,
    to: delivery.value === 'DELIVERED_IN' ? to : null,
    withProduction: params.get('produkcja') === 'tak',
    nomenclatures: [...new Set(params.getAll('nom'))].sort(),
  }
  const valid = !daysError && !periodError
  const report = useNegativeStock(storeId, query, valid)

  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const cadencier = CADENCIER.find((option) => option.param === params.get('cadencier')) ?? CADENCIER[0]
  const onlySelling = params.get('sprzedaje') === 'tak'
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

  const inPeriod = report.data?.delivery === 'DELIVERED_IN'
  const accessors = useMemo<SortAccessors<NegativeStockItem, SortKey>>(
    () => ({
      itm8: (item) => item.itm8,
      ean: (item) => item.ean,
      name: (item) => item.name,
      supplier: (item) => item.supplierName,
      delivery: (item) => item.lastDeliveryDate,
      deliveries: (item) => (inPeriod ? item.periodDeliveries : item.deliveryDays),
      stock: (item) => item.stock,
      value: (item) => item.stockValue,
      sold: (item) => item.soldQuantity,
      gross: (item) => item.soldGross,
      lastSale: (item) => item.lastSale,
    }),
    [inPeriod],
  )

  const items = useMemo(() => {
    const words = searchField.debounced.toLowerCase().split(/\s+/).filter(Boolean)
    const filtered = (report.data?.items ?? []).filter(
      (item) =>
        (supplier === ALL_SUPPLIERS ||
          (supplier === NO_SUPPLIER ? item.supplierCode == null : item.supplierCode === supplier)) &&
        (cadencier.value === 'all' || item.cadencier === (cadencier.value === 'with')) &&
        (!onlySelling || item.soldQuantity > 0) &&
        matches(item, words),
    )
    return sortRows(filtered, accessors, sort)
  }, [report.data, searchField.debounced, supplier, cadencier.value, onlySelling, accessors, sort])

  const suppliers = useMemo(() => {
    const found = new Map<string, { name: string | null; count: number }>()
    let without = 0
    for (const item of report.data?.items ?? []) {
      if (!item.supplierCode) {
        without++
        continue
      }
      const known = found.get(item.supplierCode)
      found.set(item.supplierCode, { name: item.supplierName, count: (known?.count ?? 0) + 1 })
    }
    return {
      without,
      list: [...found.entries()].sort(([, a], [, b]) => (a.name ?? '').localeCompare(b.name ?? '', 'pl')),
    }
  }, [report.data])

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Ujemne stany</h2>
        {report.data && <ReportFreshness generatedAt={report.data.stockReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Artykuły ze stanem na minusie. Stan schodzi poniżej zera, gdy towar się sprzedaje, a nie przyjechał pod tym
        kodem: dostawa weszła pod innym artykułem albo kasa sprzedaje pod złym EAN-em. Dostawa to przyjęcie PZ albo
        towar od innego sklepu. Bez produkcji, mięsa i garmażu, chyba że je włączysz.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-3">
          <SegmentedField
            label="Dostawy"
            options={DELIVERY.map(({ value, label }) => ({ value, label }))}
            value={delivery.value}
            onChange={(next) => update({ dostawy: DELIVERY.find((option) => option.value === next)?.param ?? null })}
          />
          {delivery.value === 'NO_DELIVERY_SINCE' && (
            <TextField
              label={`Dni bez dostawy (domyślnie ${DEFAULT_DAYS})`}
              inputMode="numeric"
              value={daysField.text}
              onChange={(event) => daysField.setText(event.target.value)}
              error={daysError}
              className="w-full sm:w-64"
            />
          )}
          {delivery.value === 'DELIVERED_IN' && (
            <>
              <DateField
                label="Dostawa od"
                value={from}
                max={to}
                onChange={(next) => update({ od: next || null })}
                error={periodError}
                shortcuts={[
                  { label: 'Tydzień temu', value: addDays(today, -6) },
                  { label: '30 dni temu', value: addDays(today, -29) },
                  { label: '90 dni temu', value: addDays(today, -89) },
                ]}
                className="w-full sm:w-56"
              />
              <DateField
                label="Dostawa do"
                value={to}
                min={from}
                max={today}
                onChange={(next) => update({ do: next || null })}
                shortcuts={[{ label: 'Dziś', value: today }]}
                className="w-full sm:w-56"
              />
            </>
          )}
          <SegmentedField
            label="Cadencier"
            options={CADENCIER.map(({ value, label }) => ({ value, label }))}
            value={cadencier.value}
            onChange={(next) => update({ cadencier: CADENCIER.find((option) => option.value === next)?.param ?? null })}
          />
        </div>
        <p className="text-sm text-muted-foreground">{delivery.hint}</p>
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
              ...(suppliers.without > 0
                ? [{ value: NO_SUPPLIER, label: `Bez przyjęcia PZ (${suppliers.without})` }]
                : []),
              ...suppliers.list.map(([code, { name, count }]) => ({
                value: code,
                label: `${code} ${name ?? ''} (${count})`,
              })),
            ]}
            value={supplier}
            onChange={(next) => update({ dostawca: next === ALL_SUPPLIERS ? null : next })}
          />
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          <SwitchField
            label="Tylko sprzedające się"
            description="Artykuły ze sprzedażą w ostatnich 28 dniach: tu najczęściej towar przyjechał pod innym kodem."
            checked={onlySelling}
            onCheckedChange={(checked) => update({ sprzedaje: checked ? 'tak' : null })}
          />
          <SwitchField
            label="Z produkcją, mięsem i garmażem"
            description="Rejony 24, 36, 46: stany schodzą tam na minus przez produkcję, nie przez złe kody."
            checked={query.withProduction}
            onCheckedChange={(checked) => update({ produkcja: checked ? 'tak' : null })}
          />
        </div>
      </Card>

      <SearchInput
        value={searchField.text}
        onChange={searchField.setText}
        placeholder="Szukaj: nazwa, marka, EAN, ITM8, dostawca"
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
            items={items}
            inPeriod={inPeriod}
            sort={sort}
            onSort={onSort}
            salesFrom={report.data.salesFrom}
            salesTo={report.data.lastSaleDate}
          />
        </div>
      )}
    </>
  )
}

function Results({
  items,
  inPeriod,
  sort,
  onSort,
  salesFrom,
  salesTo,
}: {
  items: NegativeStockItem[]
  inPeriod: boolean
  sort: SortState<SortKey>
  onSort: (column: SortKey) => void
  salesFrom: string
  salesTo: string | null
}) {
  const [shown, setShown] = useState(PAGE)
  const loadMore = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < items.length, shown)
  const sorting = { sort, onSort }
  const selling = items.filter((item) => item.soldQuantity > 0).length
  const salesPeriod = salesTo
    ? `${formatDocumentDate(salesFrom)} – ${formatDocumentDate(salesTo)}`
    : 'Brak wgranej sprzedaży'

  return (
    <>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <StatTile
          label="Artykułów"
          value={formatQuantity(items.length)}
          detail={`W tym sprzedających się: ${formatQuantity(selling)}`}
          tone="strong"
        />
        <StatTile
          label="Wartość stanu na minusie"
          value={money(items.reduce((sum, item) => sum + item.stockValue, 0))}
          detail="Stan × cena zakupu z kartoteki"
        />
        <StatTile
          label="Sprzedaż brutto (28 dni)"
          value={money(items.reduce((sum, item) => sum + item.soldGross, 0))}
          detail={salesPeriod}
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
                  <SortableHead column="itm8" {...sorting}>
                    ITM8
                  </SortableHead>
                  <SortableHead column="ean" {...sorting}>
                    EAN
                  </SortableHead>
                  <SortableHead column="name" {...sorting} className="min-w-72">
                    Nazwa
                  </SortableHead>
                  <SortableHead column="supplier" {...sorting}>
                    Ostatni dostawca
                  </SortableHead>
                  <SortableHead column="delivery" {...sorting} className="whitespace-nowrap">
                    Ostatnia dostawa
                  </SortableHead>
                  <SortableHead column="deliveries" align="right" {...sorting} className="whitespace-nowrap">
                    {inPeriod ? 'Dostaw w okresie' : 'Dni z dostawą'}
                  </SortableHead>
                  <SortableHead column="stock" align="right" {...sorting}>
                    Stan
                  </SortableHead>
                  <SortableHead column="value" align="right" {...sorting} className="whitespace-nowrap">
                    Wartość stanu
                  </SortableHead>
                  <SortableHead column="sold" align="right" {...sorting} className="whitespace-nowrap">
                    Sprzedaż 28 dni
                  </SortableHead>
                  <SortableHead column="gross" align="right" {...sorting} className="whitespace-nowrap">
                    Brutto 28 dni
                  </SortableHead>
                  <SortableHead column="lastSale" {...sorting} className="whitespace-nowrap">
                    Ostatnia sprzedaż
                  </SortableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.slice(0, shown).map((item) => (
                  <TableRow key={item.itm8}>
                    <TableCell className="tabular-nums">{itm8Label(item.itm8)}</TableCell>
                    <TableCell className="tabular-nums">{item.ean ?? '–'}</TableCell>
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
                        {!item.active && (
                          <span className="shrink-0 rounded-md bg-zinc-200 px-1.5 text-xs font-medium text-zinc-900 dark:bg-zinc-600/40 dark:text-zinc-100">
                            nieaktywny
                          </span>
                        )}
                        <ArticleStatsButton
                          article={{ itm8: item.itm8, name: item.name, ean: item.ean, brand: item.brand }}
                        />
                      </div>
                    </TableCell>
                    <TableCell
                      className="max-w-56 truncate text-muted-foreground"
                      title={item.supplierName ?? undefined}
                    >
                      {item.supplierCode ? `${item.supplierCode} ${item.supplierName ?? ''}` : '–'}
                    </TableCell>
                    <TableCell
                      className="whitespace-nowrap text-muted-foreground tabular-nums"
                      title={item.daysSinceDelivery != null ? daysAgo(item.daysSinceDelivery) : undefined}
                    >
                      {item.lastDeliveryDate ? (
                        formatDocumentDate(item.lastDeliveryDate)
                      ) : (
                        <span className="rounded-md bg-amber-200 px-1.5 text-xs font-medium text-amber-950 dark:bg-amber-500/30 dark:text-amber-100">
                          nigdy
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(inPeriod ? item.periodDeliveries : item.deliveryDays)}
                    </TableCell>
                    <TableCell className="text-right font-medium text-red-700 tabular-nums dark:text-red-400">
                      {formatQuantity(item.stock)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{money(item.stockValue)}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {item.soldQuantity > 0 ? formatQuantity(item.soldQuantity) : '–'}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {item.soldGross !== 0 ? money(item.soldGross) : '–'}
                    </TableCell>
                    <TableCell className="text-muted-foreground tabular-nums">
                      {item.lastSale ? formatDocumentDate(item.lastSale) : 'nigdy'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </table>
          </div>
          <div ref={loadMore} />
        </Card>
      )}
    </>
  )
}
