import {
  CircleCheckIcon,
  InfoIcon,
  PackageXIcon,
  SearchCheckIcon,
  TriangleAlertIcon,
  UploadIcon,
  XIcon,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { SegmentedField, SelectField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  bonNumber,
  countFormat,
  documentPaths,
  formatDocumentDate,
  formatMoney,
  formatPercent,
  formatPrice,
  formatQuantity,
} from '@/features/documents/format'
import { usePriceCheck, usePriceCheckCategories } from '@/features/sales/api'
import { IncidentUpload } from '@/features/sales/IncidentUpload'
import { salesPaths } from '@/features/sales/paths'
import {
  ALL_CATEGORIES,
  ALL_SUPPLIERS,
  articlesWord,
  type CategoryKey,
  CategoryTabs,
  FILTER_PARAMS,
  filterOf,
  inCategory,
  MoneyPill,
  OTHER_CATEGORY,
  PERIOD_FILTERS,
  periodStart,
  ReviewedDeliveryRow,
  STATUS_FILTERS,
  STATUS_ORDER,
  STATUS_STYLES,
  type StatusFilter,
  SummaryTile,
  supplierKey,
  useExpanded,
} from '@/features/sales/price-review'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { PriceCheckDelivery, PriceCheckReport as Report } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useSearchParamText } from '@/lib/use-search-param-text'

type PriceFilter = 'all' | 'below-minimum' | 'above-base'
const PRICE_FILTERS: { value: PriceFilter; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'below-minimum', label: 'Poniżej minimum' },
  { value: 'above-base', label: 'Drożej niż baza' },
]
const PRICE_PARAMS: Record<PriceFilter, string | null> = {
  all: null,
  'below-minimum': 'ponizej-minimum',
  'above-base': 'drozej-niz-baza',
}

/** Weryfikacja albo przyjęcia usprawiedliwione incydentem zamówienia do bazy (`?widok=incydenty`). */
type View = 'verify' | 'incidents'
const VIEW_PARAMS: Record<View, string> = { verify: 'weryfikacja', incidents: 'incydenty' }

/**
 * Zakup bazowy poza bazą: przyjęcia z ostatnich 30 dni, w których artykuł z cadencier (formularza zamówień do bazy)
 * przyszedł od innego dostawcy niż baza. Jeden wiersz na przyjęcie, rozwinięty pokazuje te artykuły; linie z marżą
 * na bonie poniżej minimum z Ustawień sklepu są podświetlone. Sprawę wyjaśnia się z dostawcą: status przyjęcia
 * (zgłoszone, wyjaśnione) z notatką, każda zmiana do cofnięcia. Korekta przypięta do przyjęcia od razu zmienia raport.
 */
export function PriceCheckReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const filter =
    (Object.keys(FILTER_PARAMS) as StatusFilter[]).find((key) => FILTER_PARAMS[key] === params.get('status')) ?? 'open'
  const { expanded, toggle } = useExpanded(`price-check-expanded:${storeId}`)
  const view: View = params.get('widok') === VIEW_PARAMS.incidents ? 'incidents' : 'verify'
  const { text: query, setText: setQuery } = useSearchParamText('szukaj')
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const period = PERIOD_FILTERS.find((option) => option.value === params.get('okres'))?.value ?? '30'
  const priceFilter =
    (Object.keys(PRICE_PARAMS) as PriceFilter[]).find((key) => PRICE_PARAMS[key] === params.get('ceny')) ?? 'all'
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

  const report = usePriceCheck(storeId)
  const categories = usePriceCheckCategories(storeId).data ?? []
  const all = report.data?.deliveries ?? []
  const suppliers = [...new Map(all.map((delivery) => [supplierKey(delivery), delivery])).values()]
    .map((delivery) => ({
      value: supplierKey(delivery),
      label: [delivery.supplierName, delivery.supplierCode].filter(Boolean).join(' · ') || 'Nieznany dostawca',
    }))
    .sort((a, b) => a.label.localeCompare(b.label, 'pl'))
  const from = report.data && period !== '30' ? periodStart(report.data.to, Number(period)) : null
  // Przyjęcie ze statusem wyjaśniania zostaje bez względu na datę, więc okres zawęża tylko te do wyjaśnienia.
  const filtered = all.filter(
    (delivery) =>
      (supplier === ALL_SUPPLIERS || supplierKey(delivery) === supplier) &&
      (!from || delivery.review != null || delivery.documentDate.slice(0, 10) >= from) &&
      (priceFilter !== 'below-minimum' || delivery.belowMinimumCount > 0) &&
      (priceFilter !== 'above-base' || delivery.baseDifferenceValue > 0) &&
      (!query ||
        matchesSearch(
          [
            delivery.supplierName,
            delivery.supplierCode,
            delivery.number,
            bonNumber(delivery.number),
            delivery.supplierDocumentNumber,
            ...delivery.lines.flatMap((line) => [line.name, line.brand, line.ean, line.itm8, line.cadencierItm8]),
          ],
          query,
        )),
  )
  const filtersActive = query !== '' || supplier !== ALL_SUPPLIERS || period !== '30' || priceFilter !== 'all'
  const viewCounts = { verify: 0, incidents: 0 }
  filtered.forEach((delivery) => viewCounts[delivery.afterIncident ? 'incidents' : 'verify']++)
  const inView = filtered.filter((delivery) => delivery.afterIncident === (view === 'incidents'))
  const categoryParam = params.get('kategoria')
  const category: CategoryKey =
    categories.find((item) => item.id === categoryParam)?.id ??
    (categoryParam === OTHER_CATEGORY && categories.length > 0 ? OTHER_CATEGORY : ALL_CATEGORIES)
  const deliveries = inView.filter((delivery) => inCategory(delivery, category, categories))
  const counts = { open: 0, reported: 0, resolved: 0 }
  deliveries.forEach((delivery) => counts[filterOf(delivery)]++)
  const open = deliveries.filter((delivery) => filterOf(delivery) === 'open')
  const openValue = open.reduce((sum, delivery) => sum + delivery.linesPurchaseValue, 0)
  const openExcess = open.reduce((sum, delivery) => sum + delivery.excessValue, 0)
  const shown = filter === 'all' ? deliveries : deliveries.filter((delivery) => filterOf(delivery) === filter)

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Zakup bazowy poza bazą</h2>
        {report.data && <ReportFreshness generatedAt={report.data.documentsReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Przyjęcia z ostatnich 30 dni, w których artykuł z cadencier (formularza zamówień do bazy) przyszedł od innego
        dostawcy niż baza, rozpoznany po ITM8 albo EAN-ie. Przyjęcia ze statusem wyjaśniania zostają bez względu na
        datę. Wartości po korektach: korekta przypięta do przyjęcia od razu zmienia raport. Raport pomija prasę od
        Kolportera.
      </p>

      {report.isError ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">
            {report.error instanceof ApiError ? report.error.message : 'Nie udało się policzyć raportu'}
          </p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-48 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : report.data.cadencierCount === 0 ? (
        <MissingCadencier />
      ) : (
        <>
          <ViewTabs
            view={view}
            counts={viewCounts}
            incidentDays={report.data.incidentDays}
            onChange={(next) => setParam('widok', next === 'incidents' ? VIEW_PARAMS.incidents : null)}
          />

          <CategoryTabs
            categories={categories}
            deliveries={inView}
            value={category}
            onChange={(next) => setParam('kategoria', next === ALL_CATEGORIES ? null : next)}
          />

          <ReportNotices report={report.data} />

          {view === 'incidents' && <IncidentUpload storeId={storeId} incidentDays={report.data.incidentDays} />}

          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <SummaryTile
              status="open"
              value={counts.open}
              detail={
                counts.open
                  ? `zakup ${formatMoney(openValue)} zł${openExcess > 0 ? ` · za drogo o ${formatMoney(openExcess)} zł` : ''}`
                  : 'nic nie czeka'
              }
            />
            <SummaryTile status="reported" value={counts.reported} detail="czekają na odpowiedź" />
            <SummaryTile status="resolved" value={counts.resolved} detail="także starsze niż 30 dni" />
          </div>

          <div className="mb-3 grid gap-3 rounded-xl border bg-card p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]">
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder="Szukaj: dostawca, bon, faktura, artykuł, EAN, ITM8"
            />
            <SelectField
              label="Dostawca"
              options={[{ value: ALL_SUPPLIERS, label: 'Wszyscy dostawcy' }, ...suppliers]}
              value={supplier}
              onChange={(next) => setParam('dostawca', next && next !== ALL_SUPPLIERS ? next : null)}
            />
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 md:col-span-2">
              <span className="flex items-center gap-2 text-sm text-muted-foreground">
                Przyjęte w ostatnich
                <SegmentedField
                  label="Okres"
                  options={PERIOD_FILTERS}
                  value={period}
                  onChange={(next) => setParam('okres', next === '30' ? null : next)}
                />
              </span>
              <span className="flex items-center gap-2 text-sm text-muted-foreground">
                Ceny
                <SegmentedField
                  label="Ceny"
                  options={PRICE_FILTERS}
                  value={priceFilter}
                  onChange={(next) => setParam('ceny', PRICE_PARAMS[next])}
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
                        for (const key of ['szukaj', 'dostawca', 'okres', 'ceny']) updated.delete(key)
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

          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <SegmentedField
              label="Status"
              options={STATUS_FILTERS}
              value={filter}
              onChange={(next) => setParam('status', FILTER_PARAMS[next])}
            />
            <p className="text-sm text-muted-foreground tabular-nums">
              {report.data.minMarginPercent == null ? (
                <>
                  Bez minimalnej marży linie nie są oceniane.{' '}
                  <Link
                    to={storeSettingsPaths.priceCheck}
                    className="underline underline-offset-4 hover:text-foreground"
                  >
                    Ustaw ją
                  </Link>
                </>
              ) : (
                <>
                  <span
                    aria-hidden
                    className="mr-1.5 inline-block size-2.5 rounded-sm bg-red-200 align-middle dark:bg-red-500/40"
                  />
                  marża poniżej {formatPercent(report.data.minMarginPercent)} od sprzedaży netto
                </>
              )}
            </p>
          </div>

          {shown.length === 0 ? (
            <Card className="items-center gap-1 px-6 py-10 text-center">
              <CircleCheckIcon aria-hidden className="mb-1 size-6 text-muted-foreground" />
              <p className="font-medium">
                {filtersActive
                  ? 'Brak przyjęć dla wybranych filtrów'
                  : filter === 'open'
                    ? 'Nic nie czeka na wyjaśnienie'
                    : 'Brak przyjęć z tym statusem'}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? 'Zmień albo wyczyść filtry nad listą.'
                  : view === 'incidents'
                    ? `Żadne przyjęcie od innego dostawcy nie przyszło do ${report.data.incidentDays} dni po incydencie z bazy.`
                    : 'W ostatnich 30 dniach artykuły z cadencier przychodziły tylko z bazy.'}
              </p>
            </Card>
          ) : (
            <div className="grid gap-6">
              {STATUS_ORDER.map((status) => {
                const group = shown.filter((delivery) => filterOf(delivery) === status)
                if (group.length === 0) return null
                const style = STATUS_STYLES[status]
                const excess = group.reduce((sum, delivery) => sum + delivery.baseDifferenceValue, 0)
                return (
                  <section key={status} aria-label={style.label}>
                    <h3 className="mb-2 flex flex-wrap items-baseline gap-x-2 px-1 text-sm">
                      <span aria-hidden className={cn('size-2.5 self-center rounded-full', style.dot)} />
                      <span className="font-semibold">{style.label}</span>
                      <span className="text-muted-foreground tabular-nums">
                        {group.length} {group.length === 1 ? 'przyjęcie' : 'przyjęć'}
                        {excess > 0 && ` · drożej niż baza o ${formatMoney(excess)} zł`}
                      </span>
                    </h3>
                    <Card className="gap-0 divide-y py-0">
                      {group.map((delivery) => (
                        <ReviewedDeliveryRow
                          key={delivery.id}
                          storeId={storeId}
                          report="BASE_PURCHASE"
                          delivery={delivery}
                          emptyText="Po korektach w tym przyjęciu nie ma już artykułów z cadencier od innego dostawcy."
                          open={expanded.has(delivery.id)}
                          onToggle={() => toggle(delivery.id)}
                          pills={
                            <>
                              <MoneyPill
                                label="z cadencier"
                                value={articlesWord(delivery.lines.length)}
                                tone="neutral"
                              />
                              {report.data.minMarginPercent != null && delivery.belowMinimumCount > 0 && (
                                <MoneyPill
                                  label="poniżej minimum"
                                  value={String(delivery.belowMinimumCount)}
                                  tone="loss"
                                />
                              )}
                              <MoneyPill
                                label="zakup"
                                value={`${formatMoney(delivery.linesPurchaseValue)} zł`}
                                tone="neutral"
                              />
                              {delivery.baseDifferenceValue > 0 && (
                                <MoneyPill
                                  label="drożej niż baza"
                                  value={`${formatMoney(delivery.baseDifferenceValue)} zł`}
                                  tone="loss"
                                />
                              )}
                            </>
                          }
                        >
                          <LinesTable delivery={delivery} hasMinimum={report.data.minMarginPercent != null} />
                        </ReviewedDeliveryRow>
                      ))}
                    </Card>
                  </section>
                )
              })}
            </div>
          )}
        </>
      )}
    </>
  )
}

/** Dwie kategorie raportu u góry: do weryfikacji i po incydencie z bazy, z liczbą przyjęć po filtrach. */
function ViewTabs({
  view,
  counts,
  incidentDays,
  onChange,
}: {
  view: View
  counts: Record<View, number>
  incidentDays: number
  onChange: (view: View) => void
}) {
  const tabs: { value: View; label: string; detail: string; icon: typeof SearchCheckIcon; tone: string }[] = [
    {
      value: 'verify',
      label: 'Do weryfikacji',
      detail: 'zakup u innego dostawcy bez powodu z bazy',
      icon: SearchCheckIcon,
      tone: 'border-amber-300 bg-amber-50 text-amber-950 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100 [&_[data-icon]]:bg-amber-500 [&_[data-icon]]:text-white',
    },
    {
      value: 'incidents',
      label: 'Po incydencie z bazy',
      detail: `baza nie dowiozła: przyjęcie do ${incidentDays} dni po incydencie`,
      icon: PackageXIcon,
      tone: 'border-sky-300 bg-sky-50 text-sky-950 dark:border-sky-500/40 dark:bg-sky-500/10 dark:text-sky-100 [&_[data-icon]]:bg-sky-600 [&_[data-icon]]:text-white',
    },
  ]
  return (
    <div role="tablist" aria-label="Kategoria" className="mb-2 grid gap-2 sm:grid-cols-2">
      {tabs.map((tab) => {
        const selected = tab.value === view
        const Icon = tab.icon
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(tab.value)}
            className={cn(
              'flex items-center gap-3 rounded-xl border px-4 py-3 text-left transition-[background-color,border-color,box-shadow,transform] duration-150 ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-[0.99] motion-reduce:active:scale-100',
              selected
                ? cn('shadow-sm', tab.tone)
                : 'bg-card text-foreground hover:bg-muted/60 [&_[data-icon]]:bg-muted [&_[data-icon]]:text-muted-foreground',
            )}
          >
            <span
              data-icon
              aria-hidden
              className="flex size-9 shrink-0 items-center justify-center rounded-lg transition-colors duration-150"
            >
              <Icon className="size-4.5" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block font-semibold">{tab.label}</span>
              <span className={cn('block text-sm', selected ? 'opacity-80' : 'text-muted-foreground')}>
                {tab.detail}
              </span>
            </span>
            <span className="text-2xl font-semibold tabular-nums">{counts[tab.value]}</span>
          </button>
        )
      })}
    </div>
  )
}

/** Skąd raport ma dane: lista cadencier z ustawień i dokumenty; ostrzega, gdy dokumenty nie sięgają do dziś. */
function ReportNotices({ report }: { report: Report }) {
  const documentsBehind = !report.documentsCoveredUntil || report.documentsCoveredUntil < report.to
  return (
    <div className="mb-4 grid gap-2 text-sm">
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-muted-foreground tabular-nums">
        <span>
          Przyjęcia {formatDocumentDate(report.from)} – {formatDocumentDate(report.to)} · cadencier:{' '}
          {countFormat.format(report.cadencierCount)} artykułów · baza: dostawcy {report.baseSupplierCodes.join(' i ')}
        </span>
        <ReportFreshness generatedAt={report.cadencierGeneratedAt ?? report.cadencierImportedAt} />
      </p>
      {documentsBehind && (
        <p className="flex items-start gap-2 text-amber-700 dark:text-amber-400">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          {report.documentsCoveredUntil
            ? `Dokumenty są wgrane do ${formatDocumentDate(report.documentsCoveredUntil)}, więc późniejszych przyjęć w raporcie nie ma.`
            : 'Brak wgranych dokumentów. Wgraj raport Wejścia/Wyjścia w Imporcie danych.'}
        </p>
      )}
    </div>
  )
}

function MissingCadencier() {
  const canEdit = useCan('SALES', 'EDIT')
  return (
    <Card className="items-center gap-2 px-6 py-10 text-center">
      <InfoIcon aria-hidden className="size-6 text-muted-foreground" />
      <p className="font-medium">Wgraj listę artykułów z cadencier</p>
      <p className="max-w-md text-sm text-muted-foreground">
        Raport sprawdza artykuły z listy „OBECNE W CADENCIER” kupione u innego dostawcy niż baza.
        {!canEdit && ' Wgrywa ją osoba z uprawnieniem do edycji sprzedaży.'}
      </p>
      {canEdit && (
        <Button render={<Link to={storeSettingsPaths.priceCheck} />} variant="outline" size="lg">
          <UploadIcon />
          Przejdź do ustawień
        </Button>
      )}
    </Card>
  )
}

function LinesTable({ delivery, hasMinimum }: { delivery: PriceCheckDelivery; hasMinimum: boolean }) {
  return (
    <div className="overflow-x-auto rounded-lg border bg-background shadow-xs">
      <Table className="tabular-nums">
        <TableHeader className="bg-muted/60">
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-4">Artykuł</TableHead>
            <TableHead className="text-right">Ilość</TableHead>
            <TableHead className="text-right">
              Cena zakupu<span className="block text-xs font-normal text-muted-foreground">netto, za szt.</span>
            </TableHead>
            <TableHead className="text-right">Zakup zł</TableHead>
            <TableHead className="text-right">
              Na bonie<span className="block text-xs font-normal text-muted-foreground">sprzedaż brutto</span>
            </TableHead>
            <TableHead className="text-right">Marża</TableHead>
            <TableHead className="text-right">
              Cena z bazy
              <span className="block text-xs font-normal text-muted-foreground">ostatnia dostawa, do roku</span>
            </TableHead>
            {hasMinimum && (
              <>
                <TableHead className="text-right">
                  Max cena zakupu<span className="block text-xs font-normal text-muted-foreground">dla minimum</span>
                </TableHead>
                <TableHead className="pr-4 text-right">Za drogo zł</TableHead>
              </>
            )}
          </TableRow>
        </TableHeader>
        <TableBody>
          {delivery.lines.map((line) => (
            <TableRow
              key={line.itm8}
              className={cn(
                line.belowMinimum && 'bg-red-50 hover:bg-red-100/70 dark:bg-red-500/10 dark:hover:bg-red-500/15',
              )}
            >
              <TableCell className="max-w-80 pl-4 whitespace-normal">
                <p className="font-medium">
                  {line.name ?? 'Bez nazwy'}
                  {line.corrected && (
                    <span className="ml-1 text-muted-foreground" title="Po korekcie">
                      *
                    </span>
                  )}
                </p>
                <p className="text-xs text-muted-foreground">
                  {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`].filter(Boolean).join(' · ')}
                </p>
                {line.cadencierItm8 && (
                  <p className="text-xs text-muted-foreground">
                    w cadencier jako ITM8 {line.cadencierItm8} (ten sam EAN)
                  </p>
                )}
                {line.incidentDate && (
                  <p
                    className="mt-1 inline-flex items-center gap-1 rounded bg-sky-50 px-1.5 py-0.5 text-xs font-medium text-sky-900 dark:bg-sky-500/10 dark:text-sky-200"
                    title={line.incidentComment ?? undefined}
                  >
                    <PackageXIcon aria-hidden className="size-3.5 shrink-0" />
                    Incydent z bazy {formatDocumentDate(line.incidentDate)}
                    {line.incidentReason && `: ${line.incidentReason}`}
                  </p>
                )}
              </TableCell>
              <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
              <TableCell className="text-right font-medium">{formatPrice(line.purchasePrice)}</TableCell>
              <TableCell className="text-right">{formatMoney(line.purchaseValue)}</TableCell>
              <TableCell className="text-right">
                {formatPrice(line.salePriceGross) || '–'}
                {line.cadencierPrice != null && line.cadencierPrice !== line.salePriceGross && (
                  <span className="block text-xs text-muted-foreground">
                    CENA_CAD {formatPrice(line.cadencierPrice)}
                  </span>
                )}
              </TableCell>
              <TableCell className={cn('text-right font-medium', line.belowMinimum && 'text-destructive')}>
                {formatPercent(line.marginPercent) || '–'}
              </TableCell>
              <TableCell className="text-right">
                {line.basePrice == null ? (
                  <span className="text-xs text-muted-foreground">brak w bazie od roku</span>
                ) : (
                  <>
                    {formatPrice(line.basePrice)}
                    <BaseDifference purchasePrice={line.purchasePrice} basePrice={line.basePrice} />
                    <span className="block text-xs text-muted-foreground">
                      {line.baseDocumentId && line.baseDocumentNumber ? (
                        <Link
                          to={documentPaths.document('PZ', line.baseDocumentId)}
                          className="underline-offset-4 hover:underline"
                        >
                          bon {bonNumber(line.baseDocumentNumber)}
                        </Link>
                      ) : null}
                      {line.baseDate && `, ${formatDocumentDate(line.baseDate)}`}
                    </span>
                  </>
                )}
              </TableCell>
              {hasMinimum && (
                <>
                  <TableCell className="text-right">{formatPrice(line.targetPurchasePrice) || '–'}</TableCell>
                  <TableCell className="pr-4 text-right font-semibold">
                    {line.belowMinimum ? formatMoney(line.excessValue) : '–'}
                  </TableCell>
                </>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

/** O ile cena zakupu od dostawcy różni się od ostatniej ceny z bazy (za sztukę i w %). */
function BaseDifference({ purchasePrice, basePrice }: { purchasePrice: number; basePrice: number }) {
  const difference = purchasePrice - basePrice
  if (Math.abs(difference) < 0.005) {
    return <span className="mt-0.5 block text-xs text-muted-foreground">tyle samo</span>
  }
  const percent = basePrice > 0 ? (difference / basePrice) * 100 : null
  return (
    <span
      className={cn(
        'mt-0.5 inline-block rounded px-1.5 text-xs font-medium',
        difference > 0
          ? 'bg-red-50 text-red-800 dark:bg-red-500/10 dark:text-red-300'
          : 'bg-emerald-50 text-emerald-800 dark:bg-emerald-500/10 dark:text-emerald-300',
      )}
    >
      {difference > 0 ? 'drożej o ' : 'taniej o '}
      {formatPrice(Math.abs(difference))} zł
      {percent != null && ` (${formatPercent(Math.abs(Math.round(percent * 10) / 10))})`}
    </span>
  )
}
