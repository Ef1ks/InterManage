import { Fragment } from 'react'
import { DownloadIcon, FileSpreadsheetIcon, PrinterIcon, RotateCcwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField, MultiComboboxField, SearchableSelectField, SegmentedField, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { TableBody, TableCell, TableHeader, TableRow } from '@/components/ui/table'
import { formatDocumentDate, formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type ArticleListQuery,
  useArticleList,
  useArticleListFiles,
  usePriceCheckCategories,
  useSalesNomenclatures,
} from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { nomenclatureName, StatTile } from '@/features/sales/report-parts'
import { useReportParams } from '@/features/sales/report-period'
import { ApiError } from '@/lib/api'
import { nextSort, type SortState } from '@/lib/table-sort'
import type { ArticleListGroup, ArticleListItem } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

type ColumnKey =
  | 'ITM8'
  | 'EAN'
  | 'NAME'
  | 'BRAND'
  | 'NOMENCLATURE'
  | 'REGION'
  | 'FAMILY'
  | 'SUBFAMILY'
  | 'CATALOG_SUPPLIER'
  | 'LAST_SUPPLIER'
  | 'LAST_DELIVERY'
  | 'STOCK'
  | 'ROTATION'
  | 'PURCHASE_PRICE'
  | 'SALE_PRICE'
  | 'VAT'
  | 'MARGIN'
  | 'STOCK_VALUE'
  | 'STOCK_SALE_VALUE'
  | 'UNIT'
  | 'NET_CONTENT'
  | 'CASE_SIZE'
  | 'KIND'
  | 'CADENCIER'
  | 'CADENCIER_PRICE'
  | 'PROMO_PRICE'
  | 'PROMO_TO'
  | 'ACTIVE'
  | 'DEPOSIT'
  | 'DLV'
  | 'CN'

type ColumnDef = { key: ColumnKey; label: string; right?: boolean; value: (item: ArticleListItem) => string }

const percent = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const num = (value: number | null) => (value == null ? '' : formatQuantity(value))
const money = (value: number | null) => (value == null ? '' : formatMoney(value))
const date = (value: string | null) => (value ? formatDocumentDate(value) : '')
const own = (code: string | null, name: string | null) =>
  code == null ? '' : `${code.slice(-2)}${name ? ` ${nomenclatureName(name)}` : ''}`

/** Te same kolumny i w tej samej kolejności co {@code ArticleListColumn} na serwerze. */
const COLUMNS: ColumnDef[] = [
  { key: 'ITM8', label: 'ITM8', value: (item) => item.itm8.replace(/^0+/, '') },
  { key: 'EAN', label: 'EAN', value: (item) => item.ean ?? '' },
  { key: 'NAME', label: 'Nazwa', value: (item) => item.name ?? '' },
  { key: 'BRAND', label: 'Marka', value: (item) => item.brand ?? '' },
  {
    key: 'NOMENCLATURE',
    label: 'Nomenklatura',
    value: (item) => (item.subfamilyCode ? displayCode(item.subfamilyCode) : ''),
  },
  { key: 'REGION', label: 'Rejon', value: (item) => own(item.regionCode, item.regionName) },
  { key: 'FAMILY', label: 'Rodzina', value: (item) => own(item.familyCode, item.familyName) },
  { key: 'SUBFAMILY', label: 'Podrodzina', value: (item) => own(item.subfamilyCode, item.subfamilyName) },
  { key: 'CATALOG_SUPPLIER', label: 'Dostawca (kartoteka)', value: (item) => item.catalogSupplier ?? '' },
  {
    key: 'LAST_SUPPLIER',
    label: 'Ostatni dostawca',
    value: (item) => [item.lastSupplierCode, item.lastSupplierName].filter(Boolean).join(' '),
  },
  { key: 'LAST_DELIVERY', label: 'Ostatnia dostawa', value: (item) => date(item.lastDeliveryDate) },
  { key: 'STOCK', label: 'Stan', right: true, value: (item) => num(item.stock) },
  { key: 'ROTATION', label: 'Rotacja', right: true, value: (item) => num(item.rotation) },
  { key: 'PURCHASE_PRICE', label: 'Cena zakupu netto', right: true, value: (item) => money(item.purchasePrice) },
  { key: 'SALE_PRICE', label: 'Cena sprzedaży brutto', right: true, value: (item) => money(item.salePrice) },
  { key: 'VAT', label: 'VAT %', right: true, value: (item) => num(item.vatRate) },
  {
    key: 'MARGIN',
    label: 'Marża %',
    right: true,
    value: (item) => (item.marginPercent == null ? '' : percent.format(item.marginPercent)),
  },
  { key: 'STOCK_VALUE', label: 'Wartość stanu (zakup)', right: true, value: (item) => money(item.stockValue) },
  {
    key: 'STOCK_SALE_VALUE',
    label: 'Wartość stanu (sprzedaż)',
    right: true,
    value: (item) => money(item.stockSaleValue),
  },
  { key: 'UNIT', label: 'Jedn.', value: (item) => item.unit ?? '' },
  { key: 'NET_CONTENT', label: 'Zawartość', right: true, value: (item) => num(item.netContent) },
  { key: 'CASE_SIZE', label: 'Karton', right: true, value: (item) => num(item.caseSize) },
  { key: 'KIND', label: 'Rodzaj', value: (item) => item.kind ?? '' },
  { key: 'CADENCIER', label: 'Cadencier', value: (item) => (item.cadencier ? 'tak' : '') },
  { key: 'CADENCIER_PRICE', label: 'CENA_CAD', right: true, value: (item) => money(item.cadencierPrice) },
  { key: 'PROMO_PRICE', label: 'Promocja z kasy', right: true, value: (item) => money(item.promoPrice) },
  { key: 'PROMO_TO', label: 'Promocja do', value: (item) => date(item.promoTo) },
  { key: 'ACTIVE', label: 'Aktywny', value: (item) => (item.active ? 'tak' : 'nie') },
  {
    key: 'DEPOSIT',
    label: 'Kaucja',
    value: (item) =>
      item.depositCode == null || item.depositCode === 0 || item.depositCode === 99 ? '' : String(item.depositCode),
  },
  { key: 'DLV', label: 'DLV', right: true, value: (item) => num(item.dlv) },
  { key: 'CN', label: 'Kod CN', value: (item) => item.cnCode ?? '' },
]

const DEFAULT_COLUMNS: ColumnKey[] = [
  'ITM8',
  'EAN',
  'NAME',
  'BRAND',
  'STOCK',
  'PURCHASE_PRICE',
  'SALE_PRICE',
  'MARGIN',
  'STOCK_VALUE',
  'LAST_SUPPLIER',
]

/** Wybór z trzech: wszystkie / z / bez; {@code param} w adresie, {@code api} dla serwera. */
type Choice = { value: string; label: string; param: string | null; api: string | null }
const choices = (withLabel: string, withoutLabel: string, apiWith = 'WITH', apiWithout = 'WITHOUT'): Choice[] => [
  { value: 'all', label: 'Wszystkie', param: null, api: null },
  { value: 'with', label: withLabel, param: 'tak', api: apiWith },
  { value: 'without', label: withoutLabel, param: 'nie', api: apiWithout },
]

const STOCK: Choice[] = [
  { value: 'all', label: 'Wszystkie', param: null, api: null },
  { value: 'plus', label: 'Na plusie', param: 'plus', api: 'POSITIVE' },
  { value: 'zero', label: 'Zero', param: 'zero', api: 'ZERO' },
  { value: 'minus', label: 'Ujemny', param: 'minus', api: 'NEGATIVE' },
]

/** Filtry tak / nie: parametr w adresie, nazwa w API, etykieta i wybory. */
const FLAGS = [
  { param: 'cadencier', api: 'cadencier', label: 'Cadencier', options: choices('W cadencier', 'Poza') },
  { param: 'promocja', api: 'promotion', label: 'Promocja z kasy', options: choices('Teraz w promocji', 'Bez') },
  { param: 'kaucja', api: 'deposit', label: 'Kaucja', options: choices('Z kaucją', 'Bez') },
  {
    param: 'aktywny',
    api: 'active',
    label: 'Aktywny',
    options: choices('Aktywne', 'Nieaktywne', 'ACTIVE', 'INACTIVE'),
  },
  { param: 'dostawy', api: 'delivery', label: 'Dostawy', options: choices('Była dostawa', 'Nigdy') },
] as const

/** Zakresy od-do: parametry w adresie i nazwy w API. */
const RANGES = [
  { label: 'Stan', from: 'stan_od', to: 'stan_do', apiFrom: 'stockFrom', apiTo: 'stockTo' },
  { label: 'Rotacja', from: 'rot_od', to: 'rot_do', apiFrom: 'rotationFrom', apiTo: 'rotationTo' },
  { label: 'Cena zakupu (zł)', from: 'zakup_od', to: 'zakup_do', apiFrom: 'purchaseFrom', apiTo: 'purchaseTo' },
  { label: 'Cena sprzedaży (zł)', from: 'cena_od', to: 'cena_do', apiFrom: 'saleFrom', apiTo: 'saleTo' },
  { label: 'Marża %', from: 'marza_od', to: 'marza_do', apiFrom: 'marginFrom', apiTo: 'marginTo' },
  { label: 'Wartość stanu (zł)', from: 'wartosc_od', to: 'wartosc_do', apiFrom: 'valueFrom', apiTo: 'valueTo' },
] as const

const GROUPINGS = [
  { value: 'NONE', label: 'Bez podziału', param: null, depth: 0 },
  { value: 'REGION', label: 'Rejony', param: 'rejony', depth: 1 },
  { value: 'FAMILY', label: 'Rodziny', param: 'rodziny', depth: 2 },
  { value: 'SUBFAMILY', label: 'Podrodziny', param: 'podrodziny', depth: 3 },
] as const

const ALL = 'wszystkie'
const OTHER = 'pozostale'

/** Wszystkie parametry adresu z filtrami, do „Wyczyść filtry”. */
const FILTER_PARAMS = [
  'szukaj',
  'nom',
  'bez_nom',
  'kategoria',
  'marka',
  'dostawca',
  'dost_kart',
  'jedn',
  'rodzaj',
  'vat',
  'stan',
  'dost_od',
  'dost_do',
  'wszystkie',
  'nie_towar',
  ...FLAGS.map((flag) => flag.param),
  ...RANGES.flatMap((range) => [range.from, range.to]),
]

const decimal = (text: string | null) => {
  if (!text) return null
  const value = Number(text.replace(',', '.').replace(/\s/g, ''))
  return Number.isFinite(value) ? String(value) : null
}

/**
 * Lista artykułów kartoteki z filtrami po każdym polu, podziałem na rejony, rodziny i podrodziny, wyborem kolumn,
 * wydrukiem i Excelem. Stan w adresie; filtry liczy serwer, więc wydruk ma to samo co podgląd.
 */
export function ArticleListReport({ storeId }: { storeId: string }) {
  const nomenclatures = useSalesNomenclatures(storeId)
  const categories = usePriceCheckCategories(storeId)
  const { params, update } = useReportParams()

  const grouping = GROUPINGS.find((option) => option.param === params.get('podzial')) ?? GROUPINGS[0]
  const columnKeys = (() => {
    const chosen = new Set(params.getAll('kol'))
    const keys = COLUMNS.filter((column) => chosen.has(column.key)).map((column) => column.key)
    return keys.length > 0 ? keys : DEFAULT_COLUMNS
  })()
  const columns = COLUMNS.filter((column) => columnKeys.includes(column.key))
  const sortKey = COLUMNS.find((column) => column.key === params.get('sortuj'))?.key ?? 'NAME'
  const sort: SortState<ColumnKey> = { key: sortKey, direction: params.get('kierunek') === 'malejaco' ? 'desc' : 'asc' }
  const withArticles = params.get('artykuly') !== 'nie'
  const category = params.get('kategoria') ?? ALL
  const stock = STOCK.find((option) => option.param === params.get('stan')) ?? STOCK[0]

  const query: ArticleListQuery = {
    query: params.get('szukaj'),
    nomenclatures: [...new Set(params.getAll('nom'))].sort(),
    withoutNomenclature: params.get('bez_nom') === 'tak',
    category: category === ALL ? null : category === OTHER ? 'OTHER' : category,
    brands: params.getAll('marka'),
    suppliers: params.getAll('dostawca'),
    catalogSupplier: params.get('dost_kart'),
    units: params.getAll('jedn'),
    kinds: params.getAll('rodzaj'),
    vatRates: params.getAll('vat'),
    stock: stock.api,
    deliveredFrom: params.get('dost_od'),
    deliveredTo: params.get('dost_do'),
    allArticles: params.get('wszystkie') === 'tak',
    withNotGoods: params.get('nie_towar') === 'tak',
    groupBy: grouping.value,
    sort: sort.key,
    direction: sort.direction,
  }
  FLAGS.forEach((flag) => {
    query[flag.api] = flag.options.find((option) => option.param === params.get(flag.param))?.api ?? null
  })
  RANGES.forEach((range) => {
    query[range.apiFrom] = decimal(params.get(range.from))
    query[range.apiTo] = decimal(params.get(range.to))
  })
  const fileQuery: ArticleListQuery = { ...query, columns: columnKeys, articles: withArticles ? null : 'false' }

  const report = useArticleList(storeId, query)
  const files = useArticleListFiles(storeId)
  const options = report.data?.options
  const activeFilters = FILTER_PARAMS.filter((key) => params.has(key)).length
  const onFileError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować pliku')
  const onSort = (column: ColumnKey) => {
    const next = nextSort(sort, column)
    update({
      sortuj: next.key === 'NAME' && next.direction === 'asc' ? null : next.key,
      kierunek: next.direction === 'desc' ? 'malejaco' : null,
    })
  }
  const toggleColumn = (key: ColumnKey) => {
    const next = columnKeys.includes(key) ? columnKeys.filter((item) => item !== key) : [...columnKeys, key]
    update({
      kol: next.length === 0 ? null : COLUMNS.map((column) => column.key).filter((item) => next.includes(item)),
    })
  }
  const clearFilters = () => update(Object.fromEntries(FILTER_PARAMS.map((key) => [key, null])))
  const empty = !report.data || report.data.itemCount === 0

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Lista artykułów</h2>
        {report.data && <ReportFreshness generatedAt={report.data.stockReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Dowolna lista artykułów z kartoteki: wybierz filtry, podział na nomenklaturę i kolumny, potem wydrukuj albo
        pobierz Excel. Stany i ceny z raportu kartoteki, ostatni dostawca z przyjęć PZ.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="font-semibold">Filtry</h3>
          {activeFilters > 0 && (
            <Button variant="ghost" size="sm" onClick={clearFilters}>
              <RotateCcwIcon />
              Wyczyść filtry ({activeFilters})
            </Button>
          )}
        </div>

        <div className="grid gap-3 lg:grid-cols-2">
          <SearchField />
          <MultiComboboxField
            label="Nomenklatura"
            description="Rejony, rodziny albo podrodziny. Puste = wszystkie."
            options={(nomenclatures.data ?? []).map((item) => ({
              value: item.code,
              label: `${displayCode(item.code)} ${nomenclatureName(item.name)}`,
              hint: LEVEL_STYLES[item.level].label,
            }))}
            value={query.nomenclatures as string[]}
            onChange={(codes) => update({ nom: codes, bez_nom: null })}
            placeholder="Szukaj po kodzie albo nazwie…"
          />
          <MultiComboboxField
            label="Marka"
            options={(options?.brands ?? []).map((option) => ({
              value: option.value,
              label: option.label,
              hint: String(option.count),
            }))}
            value={params.getAll('marka')}
            onChange={(values) => update({ marka: values })}
            placeholder="Szukaj marki…"
          />
          <MultiComboboxField
            label="Ostatni dostawca (z przyjęć PZ)"
            options={(options?.suppliers ?? []).map((option) => ({
              value: option.value,
              label: option.label,
              hint: String(option.count),
            }))}
            value={params.getAll('dostawca')}
            onChange={(values) => update({ dostawca: values })}
            placeholder="Szukaj dostawcy…"
          />
          <ParamTextField param="dost_kart" label="Dostawca w kartotece (fragment nazwy)" />
          <div className="grid gap-3 sm:grid-cols-3">
            <MultiComboboxField
              label="VAT"
              options={(options?.vatRates ?? []).map((option) => ({ ...option, hint: String(option.count) }))}
              value={params.getAll('vat')}
              onChange={(values) => update({ vat: values })}
            />
            <MultiComboboxField
              label="Jednostka"
              options={(options?.units ?? []).map((option) => ({ ...option, hint: String(option.count) }))}
              value={params.getAll('jedn')}
              onChange={(values) => update({ jedn: values })}
            />
            <MultiComboboxField
              label="Rodzaj"
              options={(options?.kinds ?? []).map((option) => ({ ...option, hint: String(option.count) }))}
              value={params.getAll('rodzaj')}
              onChange={(values) => update({ rodzaj: values })}
            />
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {RANGES.map((range) => (
            <div key={range.from} className="grid grid-cols-2 gap-2">
              <ParamTextField param={range.from} label={`${range.label} od`} numeric />
              <ParamTextField param={range.to} label={`${range.label} do`} numeric />
            </div>
          ))}
          <div className="grid grid-cols-2 gap-2">
            <DateField
              label="Ostatnia dostawa od"
              value={params.get('dost_od') ?? ''}
              onChange={(value) => update({ dost_od: value || null })}
            />
            <DateField
              label="Ostatnia dostawa do"
              value={params.get('dost_do') ?? ''}
              onChange={(value) => update({ dost_do: value || null })}
            />
          </div>
          {(categories.data?.length ?? 0) > 0 && (
            <SearchableSelectField
              label="Kategoria sklepu"
              options={[
                { value: ALL, label: 'Wszystkie kategorie' },
                ...(categories.data ?? []).map((item) => ({ value: item.id, label: item.name })),
                { value: OTHER, label: 'Pozostałe (poza kategoriami)' },
              ]}
              value={category}
              onChange={(next) => update({ kategoria: next === ALL ? null : next })}
            />
          )}
        </div>

        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Stan"
            options={STOCK.map(({ value, label }) => ({ value, label }))}
            value={stock.value}
            onChange={(next) => update({ stan: STOCK.find((option) => option.value === next)?.param ?? null })}
          />
          {FLAGS.map((flag) => (
            <SegmentedField
              key={flag.param}
              label={flag.label}
              options={flag.options.map(({ value, label }) => ({ value, label }))}
              value={flag.options.find((option) => option.param === params.get(flag.param))?.value ?? 'all'}
              onChange={(next) =>
                update({ [flag.param]: flag.options.find((option) => option.value === next)?.param ?? null })
              }
            />
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <ParamSwitch
            param="bez_nom"
            label="Tylko artykuły bez nomenklatury"
            onChange={(on) => on && update({ nom: null })}
          />
          <ParamSwitch param="wszystkie" label="Także artykuły spoza najnowszego raportu kartoteki" />
          <ParamSwitch param="nie_towar" label="Także rejony, które nie są towarem (torby, opakowania zwrotne)" />
        </div>
      </Card>

      <Card className="mb-4 gap-4 p-4">
        <h3 className="font-semibold">Wydruk</h3>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">Podział</span>
            <SegmentedField
              label="Podział na nomenklaturę"
              options={GROUPINGS.map(({ value, label }) => ({ value, label }))}
              value={grouping.value}
              onChange={(next) =>
                update({
                  podzial: GROUPINGS.find((option) => option.value === next)?.param ?? null,
                  artykuly: next === 'NONE' ? null : params.get('artykuly'),
                })
              }
            />
          </div>
          {grouping.depth > 0 && (
            <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
              <Switch checked={withArticles} onCheckedChange={(on) => update({ artykuly: on ? null : 'nie' })} />Z
              artykułami (wyłączone = same podsumowania grup)
            </label>
          )}
        </div>
        <div>
          <div className="mb-2 flex flex-wrap items-center gap-2 text-sm">
            <span className="text-muted-foreground">Kolumny ({columnKeys.length})</span>
            <Button variant="ghost" size="xs" onClick={() => update({ kol: null })}>
              Domyślne
            </Button>
            <Button variant="ghost" size="xs" onClick={() => update({ kol: COLUMNS.map((column) => column.key) })}>
              Wszystkie
            </Button>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {COLUMNS.map((column) => {
              const on = columnKeys.includes(column.key)
              return (
                <button
                  key={column.key}
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggleColumn(column.key)}
                  className={cn(
                    'rounded-md border px-2 py-1 text-xs transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                    on
                      ? 'border-foreground bg-foreground text-background'
                      : 'text-muted-foreground hover:border-foreground/30 hover:text-foreground',
                  )}
                >
                  {column.label}
                </button>
              )
            })}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            size="lg"
            loading={files.print.isPending}
            disabled={empty}
            onClick={() => files.print.mutate(fileQuery, { onError: onFileError })}
          >
            <PrinterIcon />
            Drukuj
          </Button>
          <Button
            variant="outline"
            size="lg"
            loading={files.pdf.isPending}
            disabled={empty}
            onClick={() => files.pdf.mutate(fileQuery, { onError: onFileError })}
          >
            <DownloadIcon />
            Pobierz PDF
          </Button>
          <Button
            variant="outline"
            size="lg"
            loading={files.xlsx.isPending}
            disabled={empty}
            onClick={() => files.xlsx.mutate(fileQuery, { onError: onFileError })}
          >
            <FileSpreadsheetIcon />
            Excel
          </Button>
        </div>
      </Card>

      {report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">
            {report.error instanceof ApiError ? report.error.message : 'Nie udało się przygotować listy'}
          </p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <LoadingPanel className="h-72 rounded-xl" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <StatTile label="Artykułów" value={formatQuantity(report.data.itemCount)} tone="strong" />
            <StatTile label="Wartość stanu (zakup netto)" value={`${formatMoney(report.data.stockValue)} zł`} />
            <StatTile label="Wartość stanu (sprzedaż brutto)" value={`${formatMoney(report.data.stockSaleValue)} zł`} />
          </div>
          {report.data.itemCount === 0 ? (
            <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
              Żaden artykuł nie spełnia tych filtrów.
            </p>
          ) : grouping.depth > 0 && !withArticles ? (
            <GroupSummary groups={report.data.groups} depth={grouping.depth} />
          ) : (
            <Preview
              items={report.data.items}
              groups={report.data.groups}
              depth={grouping.depth}
              columns={columns}
              sort={sort}
              onSort={onSort}
              total={report.data.itemCount}
              limit={report.data.shownLimit}
            />
          )}
        </div>
      )}
    </>
  )
}

/** Wyszukiwarka z własnym stanem pola; do adresu (i do zapytania) trafia po chwili. */
function SearchField() {
  const field = useSearchParamText('szukaj')
  return <SearchInput value={field.text} onChange={field.setText} placeholder="Szukaj: nazwa, marka, EAN, ITM8" />
}

function ParamTextField({ param, label, numeric }: { param: string; label: string; numeric?: boolean }) {
  const field = useSearchParamText(param)
  const error = numeric && field.text.trim() !== '' && decimal(field.text) == null ? 'Podaj liczbę.' : undefined
  return (
    <TextField
      label={label}
      inputMode={numeric ? 'decimal' : undefined}
      value={field.text}
      onChange={(event) => field.setText(event.target.value)}
      error={error}
    />
  )
}

function ParamSwitch({ param, label, onChange }: { param: string; label: string; onChange?: (on: boolean) => void }) {
  const { params, update } = useReportParams()
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
      <Switch
        checked={params.get(param) === 'tak'}
        onCheckedChange={(on) => {
          update({ [param]: on ? 'tak' : null })
          onChange?.(on)
        }}
      />
      {label}
    </label>
  )
}

const LEVELS = ['REGION', 'FAMILY', 'SUBFAMILY'] as const

const groupTitle = (group: ArticleListGroup) =>
  group.code === ''
    ? 'Bez nomenklatury'
    : `${displayCode(group.code)}${group.name ? ` ${nomenclatureName(group.name)}` : ''}`

const codeAt = (item: ArticleListItem, level: number) =>
  item.subfamilyCode && item.subfamilyCode.length >= 6 ? item.subfamilyCode.slice(0, level * 2) : ''

/** Podgląd jak wydruk: belki grup nad ich artykułami, najwyżej pierwsze {@code limit} artykułów. */
function Preview({
  items,
  groups,
  depth,
  columns,
  sort,
  onSort,
  total,
  limit,
}: {
  items: ArticleListItem[]
  groups: ArticleListGroup[]
  depth: number
  columns: ColumnDef[]
  sort: SortState<ColumnKey>
  onSort: (column: ColumnKey) => void
  total: number
  limit: number
}) {
  const byKey = new Map(groups.map((group) => [`${group.level}:${group.code}`, group]))
  const current: (string | null)[] = Array(depth).fill(null)
  const nameColumn = columns.findIndex((column) => column.key === 'NAME')

  return (
    <>
      {total > items.length && (
        <p className="mb-2 text-sm text-muted-foreground">
          Podgląd pierwszych {formatQuantity(Math.min(limit, items.length))} z {formatQuantity(total)} artykułów. Wydruk
          i Excel mają całą listę.
        </p>
      )}
      <Card className="overflow-clip p-0">
        <div className="overflow-x-auto">
          <table className="w-full caption-bottom text-sm">
            <TableHeader className="bg-card">
              <TableRow>
                {columns.map((column) => (
                  <SortableHead
                    key={column.key}
                    column={column.key}
                    sort={sort}
                    onSort={onSort}
                    align={column.right ? 'right' : 'left'}
                    className={cn('whitespace-nowrap', column.key === 'NAME' && 'min-w-64')}
                  >
                    {column.label}
                  </SortableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((item) => {
                const headers: ArticleListGroup[] = []
                for (let level = 1; level <= depth; level++) {
                  const key = `${level}:${codeAt(item, level)}`
                  if (key !== current[level - 1]) {
                    current[level - 1] = key
                    current.fill(null, level)
                    const group = byKey.get(key)
                    if (group) headers.push(group)
                  }
                }
                return (
                  <Fragment key={item.itm8}>
                    {headers.map((group) => (
                      <TableRow
                        key={`${group.level}:${group.code}`}
                        className={LEVEL_STYLES[LEVELS[group.level - 1]].row}
                      >
                        <TableCell colSpan={columns.length} className="font-semibold">
                          <span style={{ paddingLeft: `${(group.level - 1) * 1}rem` }}>{groupTitle(group)}</span>
                          <span className="ml-3 font-normal text-muted-foreground tabular-nums">
                            artykułów: {formatQuantity(group.count)} · stan: {formatMoney(group.stockValue)} zł
                          </span>
                        </TableCell>
                      </TableRow>
                    ))}
                    <TableRow>
                      {columns.map((column, index) => (
                        <TableCell
                          key={column.key}
                          className={cn(
                            column.right && 'text-right tabular-nums',
                            column.key === 'NAME' && 'whitespace-normal',
                          )}
                        >
                          {index === nameColumn ? (
                            <span className="flex items-center gap-1.5">
                              <span className="min-w-0">{column.value(item)}</span>
                              {!item.inLastReport && (
                                <span className="shrink-0 rounded-md bg-pink-200 px-1.5 text-xs font-medium text-pink-950 dark:bg-pink-500/30 dark:text-pink-100">
                                  poza raportem
                                </span>
                              )}
                              <ArticleStatsButton
                                article={{ itm8: item.itm8, name: item.name, ean: item.ean, brand: item.brand }}
                              />
                            </span>
                          ) : (
                            column.value(item)
                          )}
                        </TableCell>
                      ))}
                    </TableRow>
                  </Fragment>
                )
              })}
            </TableBody>
          </table>
        </div>
      </Card>
    </>
  )
}

/** Same podsumowania: każda grupa do wybranego poziomu z liczbą artykułów i wartością stanu. */
function GroupSummary({ groups, depth }: { groups: ArticleListGroup[]; depth: number }) {
  const shown = groups.filter((group) => group.level <= depth)
  return (
    <Card className="overflow-clip p-0">
      <table className="w-full text-sm">
        <TableHeader>
          <TableRow>
            <th className="px-2 py-2 text-left font-medium">Nomenklatura</th>
            <th className="px-2 py-2 text-right font-medium">Artykułów</th>
            <th className="px-2 py-2 text-right font-medium">Wartość stanu (zakup)</th>
            <th className="px-2 py-2 text-right font-medium">Wartość stanu (sprzedaż)</th>
          </TableRow>
        </TableHeader>
        <TableBody>
          {shown.map((group) => (
            <TableRow key={`${group.level}:${group.code}`} className={LEVEL_STYLES[LEVELS[group.level - 1]].row}>
              <TableCell className={cn(group.level === 1 && 'font-semibold')}>
                <span style={{ paddingLeft: `${group.level - 1}rem` }}>{groupTitle(group)}</span>
              </TableCell>
              <TableCell className="text-right tabular-nums">{formatQuantity(group.count)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatMoney(group.stockValue)} zł</TableCell>
              <TableCell className="text-right tabular-nums">{formatMoney(group.stockSaleValue)} zł</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </table>
    </Card>
  )
}
