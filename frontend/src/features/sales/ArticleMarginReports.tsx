import { type ReactNode, useMemo, useState } from 'react'
import { CircleCheckIcon, TriangleAlertIcon, Undo2Icon, XIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import type { UseQueryResult } from '@tanstack/react-query'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { SegmentedField, SelectField, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { InsertIntoDataEntry } from '@/features/data-entry/InsertIntoDataEntry'
import { emptyRow, withScannedProduct } from '@/features/data-entry/rows'
import { formatDocumentDate, formatPercent, formatPrice, formatQuantity } from '@/features/documents/format'
import {
  useArticleMarginVerifications,
  useArticleMargins,
  useCurrentPromotions,
  usePriceCheckCategories,
} from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { salesPaths } from '@/features/sales/paths'
import {
  ALL_CATEGORIES,
  ALL_SUPPLIERS,
  articlesWord,
  CategoryTabBar,
  OTHER_CATEGORY,
  regionInCategory,
} from '@/features/sales/price-review'
import { StatTile } from '@/features/sales/report-parts'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import { type SortAccessors, useTableSort } from '@/lib/table-sort'
import type { ArticleMarginItem, ArticleMarginReport } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

type Mode = 'low' | 'promo'

const PAGE = 100
const CADENCIER_VIEW = 'cadencier'
const VERIFIED_VIEW = 'zweryfikowane'

/** Zakładki raportu: do kontroli, sprawdzone (ceny bez zmian od weryfikacji) i cadencier (marża z centrali). */
type View = 'open' | 'verified' | 'cadencier'
const VIEWS: { view: View; label: string; param: string | null }[] = [
  { view: 'open', label: 'Do kontroli', param: null },
  { view: 'verified', label: 'Zweryfikowane', param: VERIFIED_VIEW },
  { view: 'cadencier', label: 'Cadencier', param: CADENCIER_VIEW },
]

const pill = 'inline-block rounded-md px-1.5 py-0.5 text-xs font-medium whitespace-nowrap'
const RED = 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'
const ORANGE = 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100'
const AMBER = 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100'
const GREEN = 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100'
const INDIGO = 'bg-indigo-200 text-indigo-950 dark:bg-indigo-900/70 dark:text-indigo-100'

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

/** Dzień RRRR-MM-DD jako DD.MM. */
const formatDay = (day: string) => `${day.slice(8, 10)}.${day.slice(5, 7)}`

/** Progi raportu artykułów (`?marza=`): marża poniżej tylu procent; domyślnie 20%. */
const MAX_MARGINS = [5, 10, 15, 20, 25, 30]
const DEFAULT_MAX_MARGIN = 20

/** Filtr marży w promocji (`?marza=`). */
const PROMO_FILTERS: { value: string; label: string; matches: (item: ArticleMarginItem) => boolean }[] = [
  { value: 'wszystkie', label: 'Wszystkie', matches: () => true },
  { value: 'minus', label: 'Na minusie', matches: (item) => (item.promoMargin ?? 0) < 0 },
  { value: '5', label: 'Poniżej 5%', matches: (item) => (item.promoMargin ?? 0) < 5 },
  { value: '10', label: 'Poniżej 10%', matches: (item) => (item.promoMargin ?? 0) < 10 },
  {
    value: 'wymagana',
    label: 'Poniżej wymaganej',
    matches: (item) => item.requiredMargin != null && (item.promoMargin ?? 0) < item.requiredMargin,
  },
]

type SortKey = 'name' | 'sold' | 'purchase' | 'sale' | 'margin' | 'required' | 'promoMargin' | 'promoTo'

const SORT_ACCESSORS: SortAccessors<ArticleMarginItem, SortKey> = {
  name: (item) => item.name,
  sold: (item) => item.soldQuantity,
  purchase: (item) => item.purchasePrice,
  sale: (item) => item.salePrice,
  margin: (item) => item.marginPercent,
  required: (item) => item.requiredMargin,
  promoMargin: (item) => item.promoMargin,
  promoTo: (item) => item.promoTo,
}

/**
 * Artykuły z za niską marżą, nie tylko z przyjęć: marża z aktualnej ceny zwykłej na kasie i ostatniej ceny zakupu
 * poniżej wybranego procentu (25, 20, 15%…). Marża wymagana na nomenklaturze jest tylko do porównania. Artykuły aktywne
 * w cadencier z CENA_CAD są w osobnej zakładce, bo marżę ustala im centrala.
 */
export function ArticleMarginsReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const maxMargin = MAX_MARGINS.find((value) => String(value) === params.get('marza')) ?? DEFAULT_MAX_MARGIN
  const query = useArticleMargins(storeId, maxMargin)
  return (
    <ArticleMarginPage
      storeId={storeId}
      mode="low"
      query={query}
      title="Artykuły z za niską marżą"
      intro={`Artykuły, które żyją: sprzedane albo przyjęte na PZ w ostatnich 3 miesiącach (aktywność i stan z kartoteki nic tu nie znaczą), z marżą poniżej ${maxMargin}%. Marża jest liczona od sprzedaży brutto, z aktualnej ceny zwykłej na kasie i ceny zakupu z ostatniego przyjęcia PZ z ostatniego roku (bez niego z kartoteki). Marża wymagana na nomenklaturze jest obok do porównania. Sprawdzony artykuł oznacz jako zweryfikowany: przechodzi do osobnej zakładki i wraca do kontroli przy zmianie ceny sprzedaży albo zakupu i przy kolejnej dostawie. Artykuły aktywne w cadencier z ceną cadencier są w osobnej zakładce, bo marżę ustala im centrala.`}
      filterParams={['marza']}
      filters={
        <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          Marża poniżej
          <SegmentedField
            label="Marża poniżej"
            options={MAX_MARGINS.map((value) => ({ value: String(value), label: `${value}%` }))}
            value={String(maxMargin)}
            onChange={(next) =>
              setParams(
                (old) => {
                  const updated = new URLSearchParams(old)
                  if (Number(next) === DEFAULT_MAX_MARGIN) updated.delete('marza')
                  else updated.set('marza', next)
                  return updated
                },
                { replace: true },
              )
            }
          />
        </span>
      }
    />
  )
}

/** Promocje trwające dziś z marżą na cenie promocyjnej, bez gazetki (utrzymanie cen z centrali). */
export function CurrentPromotionsReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const filter = PROMO_FILTERS.find((option) => option.value === params.get('marza')) ?? PROMO_FILTERS[0]
  const query = useCurrentPromotions(storeId)
  return (
    <ArticleMarginPage
      storeId={storeId}
      mode="promo"
      query={query}
      title="Aktualne promocje"
      intro="Promocje trwające dziś (z listy promocji i z plików kasy) z marżą na cenie promocyjnej, żeby wyłapać te, które są za tanie. Cena zakupu z ostatniego przyjęcia PZ z ostatniego roku, bez niego z kartoteki; marża od sprzedaży brutto. Raport pomija artykuły z gazetki (utrzymanie cen z centrali), bo na te promocje sklep nie ma wpływu, a artykuły aktywne w cadencier są w osobnej zakładce."
      itemFilter={filter.matches}
      filterParams={['marza']}
      filters={
        <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          Marża w promocji
          <SegmentedField
            label="Marża w promocji"
            options={PROMO_FILTERS}
            value={filter.value}
            onChange={(next) =>
              setParams(
                (old) => {
                  const updated = new URLSearchParams(old)
                  if (next === PROMO_FILTERS[0].value) updated.delete('marza')
                  else updated.set('marza', next)
                  return updated
                },
                { replace: true },
              )
            }
          />
        </span>
      }
    />
  )
}

function ArticleMarginPage({
  storeId,
  mode,
  query,
  title,
  intro,
  filters,
  filterParams,
  itemFilter = () => true,
}: {
  storeId: string
  mode: Mode
  query: UseQueryResult<ArticleMarginReport>
  title: string
  intro: string
  filters: ReactNode
  filterParams: string[]
  itemFilter?: (item: ArticleMarginItem) => boolean
}) {
  const [params, setParams] = useSearchParams()
  const { text: search, setText: setSearch } = useSearchParamText('szukaj')
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const onlySold = params.get('sprzedaz') === 'tak'
  const viewParam = params.get('widok')
  const view: View =
    viewParam === CADENCIER_VIEW ? 'cadencier' : viewParam === VERIFIED_VIEW && mode === 'low' ? 'verified' : 'open'
  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current)
        if (value) updated.set(key, value)
        else updated.delete(key)
        return updated
      },
      { replace: true },
    )

  const categories = usePriceCheckCategories(storeId).data ?? []
  const all = useMemo(() => query.data?.items ?? [], [query.data])
  const suppliers = useMemo(
    () =>
      [
        ...new Map(
          all
            .filter((item) => item.supplierCode)
            .map((item) => [item.supplierCode!, [item.supplierName, item.supplierCode].filter(Boolean).join(' · ')]),
        ),
      ]
        .map(([value, label]) => ({ value, label }))
        .sort((a, b) => a.label.localeCompare(b.label, 'pl')),
    [all],
  )

  const filtered = all.filter(
    (item) =>
      itemFilter(item) &&
      (supplier === ALL_SUPPLIERS || item.supplierCode === supplier) &&
      (!onlySold || (item.soldQuantity ?? 0) > 0) &&
      (!search ||
        matchesSearch([item.name, item.brand, item.ean, item.itm8, item.supplierName, item.supplierCode], search)),
  )
  const viewOf = (item: ArticleMarginItem): View =>
    item.cadencier ? 'cadencier' : item.verification ? 'verified' : 'open'
  const viewCounts = { open: 0, verified: 0, cadencier: 0 }
  filtered.forEach((item) => viewCounts[viewOf(item)]++)
  const inView = filtered.filter((item) => viewOf(item) === view)
  const categoryParam = params.get('kategoria')
  const category =
    categories.find((item) => item.id === categoryParam)?.id ??
    (categoryParam === OTHER_CATEGORY && categories.length > 0 ? OTHER_CATEGORY : ALL_CATEGORIES)
  const items = inView.filter((item) => regionInCategory(item.regionCode, category, categories))

  const filtersActive =
    search !== '' || supplier !== ALL_SUPPLIERS || onlySold || filterParams.some((key) => params.has(key))

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>
      <h2 className="mb-1 text-xl font-semibold tracking-tight">{title}</h2>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">{intro}</p>

      {query.isError ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">
            {query.error instanceof ApiError ? query.error.message : 'Nie udało się policzyć raportu'}
          </p>
          <Button variant="outline" onClick={() => query.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !query.data ? (
        <div aria-hidden className="h-48 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <>
          <div role="tablist" aria-label="Widok" className="mb-3 flex flex-wrap gap-1.5">
            {VIEWS.filter((tab) => mode === 'low' || tab.view !== 'verified').map((tab) => {
              const selected = tab.view === view
              return (
                <button
                  key={tab.label}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  onClick={() => setParam('widok', tab.param)}
                  className={cn(
                    'inline-flex h-9 items-center gap-2 rounded-lg border px-3.5 text-sm font-medium transition-[background-color,border-color,color,transform] duration-150 ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-[0.97] motion-reduce:active:scale-100',
                    selected
                      ? 'border-foreground bg-foreground text-background'
                      : 'bg-card text-muted-foreground hover:border-foreground/30 hover:text-foreground',
                  )}
                >
                  {tab.label}
                  <span
                    className={cn(
                      'min-w-5 rounded-full px-1.5 text-xs tabular-nums',
                      selected ? 'bg-background/20' : 'bg-muted',
                    )}
                  >
                    {viewCounts[tab.view]}
                  </span>
                </button>
              )
            })}
          </div>

          <CategoryTabBar
            categories={categories}
            countOf={(key) => inView.filter((item) => regionInCategory(item.regionCode, key, categories)).length}
            value={category}
            onChange={(next) => setParam('kategoria', next === ALL_CATEGORIES ? null : next)}
          />

          <Notices report={query.data} mode={mode} />

          {view !== 'verified' && <Tiles mode={mode} items={items} report={query.data} />}

          <div className="mb-3 grid gap-3 rounded-xl border bg-card p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]">
            <SearchInput
              value={search}
              onChange={setSearch}
              placeholder="Szukaj: artykuł, marka, EAN, ITM8, dostawca"
            />
            <SelectField
              label="Dostawca ostatniej dostawy"
              options={[{ value: ALL_SUPPLIERS, label: 'Wszyscy dostawcy' }, ...suppliers]}
              value={supplier}
              onChange={(next) => setParam('dostawca', next && next !== ALL_SUPPLIERS ? next : null)}
            />
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 md:col-span-2">
              {filters}
              <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                Sprzedaż
                <SegmentedField
                  label="Sprzedaż"
                  options={[
                    { value: 'wszystkie', label: 'Wszystkie' },
                    { value: 'tak', label: 'Sprzedające się (3 mies.)' },
                  ]}
                  value={onlySold ? 'tak' : 'wszystkie'}
                  onChange={(next) => setParam('sprzedaz', next === 'tak' ? 'tak' : null)}
                />
              </span>
              {filtersActive && (
                <Button
                  variant="ghost"
                  className="ml-auto"
                  onClick={() =>
                    setParams(
                      (current) => {
                        const updated = new URLSearchParams(current)
                        for (const key of ['szukaj', 'dostawca', 'sprzedaz', ...filterParams]) updated.delete(key)
                        return updated
                      },
                      { replace: true },
                    )
                  }
                >
                  <XIcon />
                  Wyczyść filtry
                </Button>
              )}
            </div>
          </div>

          {items.length === 0 ? (
            <Card className="items-center gap-1 px-6 py-10 text-center">
              <p className="font-medium">
                {filtersActive ? 'Brak artykułów dla wybranych filtrów' : 'Nic do pokazania'}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? 'Zmień albo wyczyść filtry nad listą.'
                  : view === 'verified'
                    ? 'Nie ma zweryfikowanych artykułów z marżą poniżej wybranego procentu.'
                    : mode === 'low'
                      ? 'Żaden artykuł w tej zakładce nie ma marży poniżej wybranego procentu.'
                      : 'W tej zakładce nie ma trwających promocji.'}
              </p>
            </Card>
          ) : (
            <ItemsTable key={`${view}:${category}`} storeId={storeId} mode={mode} view={view} items={items} />
          )}
        </>
      )}
    </>
  )
}

function Notices({ report, mode }: { report: ArticleMarginReport; mode: Mode }) {
  return (
    <div className="mb-4 grid gap-2 text-sm">
      {!report.marginsConfigured && (
        <p className="flex items-start gap-2 text-amber-700 dark:text-amber-400">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          <span>
            Sklep nie ma ustawionej marży wymaganej
            {mode === 'low' ? ', więc raport nie ma z czym porównać' : ''}. Ustawisz ją w{' '}
            <Link to={storeSettingsPaths.margins} className="underline underline-offset-4">
              Ustawieniach sklepu
            </Link>
            .
          </span>
        </p>
      )}
      {report.excludedSuppliers.length > 0 && (
        <p className="text-muted-foreground">
          Pomijani dostawcy bez marży wymaganej (jak w{' '}
          <Link to={salesPaths.lowMargins} className="underline underline-offset-4">
            weryfikacji przyjęć
          </Link>
          ): {report.excludedSuppliers.map((supplier) => supplier.name ?? supplier.code).join(', ')}.
        </p>
      )}
    </div>
  )
}

function Tiles({ mode, items, report }: { mode: Mode; items: ArticleMarginItem[]; report: ArticleMarginReport }) {
  if (mode === 'low') {
    const selling = items.filter((item) => (item.soldQuantity ?? 0) > 0).length
    const fromCatalog = items.filter((item) => item.purchaseSource === 'CATALOG').length
    const inPromo = items.filter((item) => item.promoPrice != null).length
    return (
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Artykuły" value={String(items.length)} tone={items.length > 0 ? 'bad' : 'good'} />
        <StatTile label="Sprzedające się" value={String(selling)} detail="w ostatnich 3 miesiącach" />
        <StatTile label="Teraz w promocji" value={String(inPromo)} detail="marża w promocji przy cenie" />
        <StatTile label="Cena zakupu z kartoteki" value={String(fromCatalog)} detail="bez przyjęcia w ostatnim roku" />
      </div>
    )
  }
  const negative = items.filter((item) => (item.promoMargin ?? 0) < 0).length
  const belowRequired = items.filter(
    (item) => item.requiredMargin != null && (item.promoMargin ?? 0) < item.requiredMargin,
  ).length
  return (
    <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <StatTile label="Promocje" value={String(items.length)} />
      <StatTile label="Na minusie" value={String(negative)} tone={negative > 0 ? 'bad' : 'good'} />
      <StatTile label="Poniżej marży wymaganej" value={String(belowRequired)} />
      <StatTile label="Pominięte z gazetki" value={String(report.leafletSkipped)} detail="utrzymanie cen z centrali" />
    </div>
  )
}

function ItemsTable({
  storeId,
  mode,
  view,
  items,
}: {
  storeId: string
  mode: Mode
  view: View
  items: ArticleMarginItem[]
}) {
  const canEdit = useCan('SALES', 'EDIT')
  const verifying = mode === 'low' && canEdit
  const [selected, setSelected] = useState<Set<string>>(() => new Set())
  const [asking, setAsking] = useState(false)
  const { verify, cancel, restore } = useArticleMarginVerifications(storeId)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać weryfikacji')
  const toggleOne = (itm8: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (!next.delete(itm8)) next.add(itm8)
      return next
    })
  const chosen = items.filter((item) => selected.has(item.itm8))
  const markVerified = (note: string) =>
    verify.mutate(
      {
        items: chosen.map((item) => ({
          itm8: item.itm8,
          salePrice: item.salePrice,
          purchasePrice: item.purchasePrice,
          marginPercent: item.marginPercent,
          lastPurchaseDate: item.lastPurchaseDate,
        })),
        note,
      },
      {
        onSuccess: (ids) => {
          setAsking(false)
          setSelected(new Set())
          toast.success(`Zweryfikowano: ${articlesWord(ids.length)}`, {
            description: 'Wrócą do kontroli przy zmianie ceny albo kolejnej dostawie.',
            action: { label: 'Cofnij', onClick: () => cancel.mutate(ids, { onError }) },
          })
        },
        onError,
      },
    )
  const unverify = (ids: string[]) =>
    cancel.mutate(ids, {
      onSuccess: () => {
        setSelected(new Set())
        toast.success(`Cofnięto weryfikację: ${articlesWord(ids.length)}`, {
          action: { label: 'Przywróć', onClick: () => restore.mutate(ids, { onError }) },
        })
      },
      onError,
    })

  const { sorted, sort, toggle } = useTableSort(items, SORT_ACCESSORS, {
    key: mode === 'low' ? 'margin' : 'promoMargin',
    direction: 'asc',
  })
  const [shown, setShown] = useState(PAGE)
  const more = useLoadMore<HTMLDivElement>(() => setShown((count) => count + PAGE), shown < sorted.length, shown)
  const insertable = sorted.filter((item) => item.ean).slice(0, 500)

  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center justify-end gap-2">
        <span className="mr-auto text-sm text-muted-foreground tabular-nums">
          {articlesWord(sorted.length)}
          {chosen.length > 0 && ` · zaznaczone: ${chosen.length}`}
        </span>
        {verifying && view !== 'verified' && (
          <Button size="sm" disabled={chosen.length === 0} onClick={() => setAsking(true)}>
            <CircleCheckIcon />
            Oznacz jako zweryfikowane{chosen.length > 0 && ` (${chosen.length})`}
          </Button>
        )}
        {verifying && view === 'verified' && (
          <Button
            size="sm"
            variant="outline"
            disabled={chosen.length === 0}
            loading={cancel.isPending}
            onClick={() => unverify(chosen.map((item) => item.verification!.id))}
          >
            <Undo2Icon />
            Cofnij weryfikację{chosen.length > 0 && ` (${chosen.length})`}
          </Button>
        )}
        {mode === 'low' && (
          <InsertIntoDataEntry
            storeId={storeId}
            eans={insertable.map((item) => item.ean!)}
            buildRows={(byEan) =>
              insertable.map((item) => ({
                ...withScannedProduct(
                  { ...emptyRow(), ean: item.ean! },
                  byEan.get(item.ean!.replace(/^0+/, '')) ?? null,
                ),
                quantity: 1,
                unitNet: item.purchasePrice,
              }))
            }
            label={`Do wprowadzania danych (${insertable.length})`}
            title="Artykuły z listy (najwyżej 500) trafią do tabeli wprowadzania danych, gdzie poprawisz ceny"
            successTitle="Artykuły w tabeli"
            successDescription={(count) =>
              `Artykuły: ${count}. Popraw ceny sprzedaży, wskaż dostawcę i kliknij „Zmień ceny”, żeby wystawić plik.`
            }
            askDescription="Zastąpić je artykułami z tego raportu czy dopisać je na końcu?"
            size="sm"
          />
        )}
      </div>
      <div className="overflow-x-auto rounded-lg border bg-background shadow-xs">
        <Table className="tabular-nums">
          <TableHeader className="bg-muted/60">
            <TableRow className="hover:bg-transparent">
              {verifying && (
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
              <SortableHead column="name" sort={sort} onSort={toggle} className={verifying ? undefined : 'pl-4'}>
                Artykuł
              </SortableHead>
              <SortableHead column="sold" sort={sort} onSort={toggle} align="right">
                Sprzedaż 3 mies.
              </SortableHead>
              <SortableHead column="purchase" sort={sort} onSort={toggle} align="right">
                Cena zakupu
              </SortableHead>
              <SortableHead column="sale" sort={sort} onSort={toggle} align="right">
                Cena zwykła
              </SortableHead>
              {mode === 'promo' && (
                <SortableHead column="promoTo" sort={sort} onSort={toggle} align="right">
                  Promocja
                </SortableHead>
              )}
              {mode === 'promo' && (
                <SortableHead column="promoMargin" sort={sort} onSort={toggle} align="right">
                  Marża w promocji
                </SortableHead>
              )}
              <SortableHead column="margin" sort={sort} onSort={toggle} align="right">
                {mode === 'promo' ? 'Marża zwykła' : 'Marża'}
              </SortableHead>
              <SortableHead column="required" sort={sort} onSort={toggle} align="right">
                Wymagana
              </SortableHead>
              {mode === 'low' && (
                <TableHead className="pr-4 text-right">
                  Dla wymaganej
                  <span className="block text-xs font-normal text-muted-foreground">cena sprzedaży / zakupu</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {sorted.slice(0, shown).map((item) => (
              <ItemRow
                key={item.itm8}
                item={item}
                mode={mode}
                selectable={verifying}
                selected={selected.has(item.itm8)}
                onToggle={() => toggleOne(item.itm8)}
              />
            ))}
          </TableBody>
        </Table>
      </div>
      {shown < sorted.length && <div ref={more} aria-hidden className="h-1" />}
      <VerifyDialog
        open={asking}
        count={chosen.length}
        pending={verify.isPending}
        onCancel={() => setAsking(false)}
        onConfirm={markVerified}
      />
    </div>
  )
}

/** Potwierdzenie weryfikacji z notatką (np. dlaczego marża może być taka). */
function VerifyDialog({
  open,
  count,
  pending,
  onCancel,
  onConfirm,
}: {
  open: boolean
  count: number
  pending: boolean
  onCancel: () => void
  onConfirm: (note: string) => void
}) {
  const [note, setNote] = useState('')
  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        if (!isOpen) onCancel()
        else setNote('')
      }}
    >
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="text-lg">Oznacz jako zweryfikowane</DialogTitle>
          <DialogDescription>
            {articlesWord(count)} przejdzie do zakładki „Zweryfikowane”. Wróci do kontroli przy zmianie ceny sprzedaży
            albo zakupu i przy kolejnej dostawie.
          </DialogDescription>
        </DialogHeader>
        <TextField
          label="Notatka (nieobowiązkowa)"
          autoComplete="off"
          autoFocus
          maxLength={500}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') onConfirm(note.trim())
          }}
        />
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onCancel}>
            Anuluj
          </Button>
          <Button loading={pending} onClick={() => onConfirm(note.trim())}>
            Zweryfikowane
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function ItemRow({
  item,
  mode,
  selectable,
  selected,
  onToggle,
}: {
  item: ArticleMarginItem
  mode: Mode
  selectable: boolean
  selected: boolean
  onToggle: () => void
}) {
  const below = item.requiredMargin != null && item.marginPercent < item.requiredMargin
  const promoStyle =
    item.promoMargin == null
      ? ''
      : item.promoMargin < 0
        ? RED
        : item.requiredMargin != null && item.promoMargin < item.requiredMargin
          ? ORANGE
          : GREEN
  return (
    <TableRow data-state={selected ? 'selected' : undefined}>
      {selectable && (
        <TableCell className="w-10 pl-4">
          <input
            type="checkbox"
            aria-label={`Zaznacz ${item.name ?? item.itm8}`}
            className="size-4 accent-foreground"
            checked={selected}
            onChange={onToggle}
          />
        </TableCell>
      )}
      <TableCell className={cn('max-w-80 whitespace-normal', !selectable && 'pl-4')}>
        <p className="font-medium">
          {item.name ?? 'Bez nazwy'}
          <ArticleStatsButton article={item} className="ml-1" />
          {item.cadencier && (
            <span className={cn(pill, INDIGO, 'ml-1')} title="Aktywny w cadencier, marżę ustala centrala">
              cadencier{item.cadencierPrice != null && ` ${formatPrice(item.cadencierPrice)}`}
            </span>
          )}
        </p>
        <p className="text-xs text-muted-foreground">
          {[item.brand, item.ean && `EAN ${item.ean}`, `ITM8 ${item.itm8}`].filter(Boolean).join(' · ')}
        </p>
        {item.supplierCode && (
          <p className="text-xs text-muted-foreground">
            {[item.supplierName, item.supplierCode].filter(Boolean).join(' · ')}
          </p>
        )}
        {item.verification && (
          <p className={cn(pill, GREEN, 'mt-1 whitespace-normal')}>
            zweryfikowany{' '}
            {[item.verification.verifiedBy, dateTimeFormat.format(new Date(item.verification.verifiedAt))]
              .filter(Boolean)
              .join(', ')}
            {item.verification.note && ` · „${item.verification.note}”`}
          </p>
        )}
      </TableCell>
      <TableCell className="text-right">
        {item.soldQuantity == null ? '–' : formatQuantity(item.soldQuantity)}
      </TableCell>
      <TableCell className="text-right">
        <span className="font-medium">{formatPrice(item.purchasePrice)}</span>
        <span className="block text-xs text-muted-foreground">
          {item.purchaseSource === 'DELIVERY' && item.lastPurchaseDate
            ? `PZ ${formatDocumentDate(item.lastPurchaseDate)}`
            : 'z kartoteki'}
        </span>
      </TableCell>
      <TableCell className="text-right whitespace-normal">
        {formatPrice(item.salePrice)}
        {mode === 'low' && item.promoPrice != null && (
          <span
            className={cn(pill, 'mt-0.5 ml-auto block w-fit', promoStyle)}
            title="Artykuł jest teraz w promocji; marża na cenie promocyjnej przy tej samej cenie zakupu"
          >
            w promocji {formatPrice(item.promoPrice)}
            {item.promoTo && ` do ${formatDay(item.promoTo)}`} · {formatPercent(item.promoMargin)}
          </span>
        )}
      </TableCell>
      {mode === 'promo' && (
        <TableCell className="text-right">
          <span className="font-semibold">{formatPrice(item.promoPrice)}</span>
          <span className="block text-xs text-muted-foreground">
            {item.promoFrom && formatDay(item.promoFrom)}
            {item.promoTo && ` – ${formatDay(item.promoTo)}`}
            {item.promoSource && ` · ${item.promoSource === 'LIST' ? 'lista' : 'kasa'}`}
          </span>
        </TableCell>
      )}
      {mode === 'promo' && (
        <TableCell className="text-right">
          <span className={cn(pill, 'text-sm', promoStyle)}>{formatPercent(item.promoMargin)}</span>
        </TableCell>
      )}
      <TableCell className="text-right">
        <span className={cn(pill, 'text-sm', mode === 'low' ? (below ? RED : AMBER) : below ? AMBER : '')}>
          {formatPercent(item.marginPercent)}
        </span>
      </TableCell>
      <TableCell className="text-right">
        {item.requiredMargin == null ? '–' : formatPercent(item.requiredMargin)}
      </TableCell>
      {mode === 'low' && (
        <TableCell className="pr-4 text-right">
          {item.targetSalePrice == null ? '–' : formatPrice(item.targetSalePrice)}
          {item.targetPurchasePrice != null && (
            <span className="block text-xs text-muted-foreground">zakup {formatPrice(item.targetPurchasePrice)}</span>
          )}
        </TableCell>
      )}
    </TableRow>
  )
}
