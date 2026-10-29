import { useState } from 'react'
import { useForm } from 'react-hook-form'
import {
  ArrowUpRightIcon,
  ChevronRightIcon,
  CircleCheckIcon,
  InfoIcon,
  PackageXIcon,
  SearchCheckIcon,
  SendIcon,
  TriangleAlertIcon,
  Undo2Icon,
  UploadIcon,
  XIcon,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { Form, FormActions, SegmentedField, SelectField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
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
import { usePriceCheck, usePriceCheckCategories, usePriceCheckReviewActions } from '@/features/sales/api'
import { IncidentUpload } from '@/features/sales/IncidentUpload'
import { salesPaths } from '@/features/sales/paths'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import { matchesSearch } from '@/lib/search'
import type {
  PriceCheckCategory,
  PriceCheckDelivery,
  PriceCheckReport as Report,
  PriceCheckReview,
  PriceCheckStatus,
} from '@/lib/types'
import { cn } from '@/lib/utils'

type StatusFilter = 'open' | 'reported' | 'resolved' | 'all'

const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
  { value: 'open', label: 'Do wyjaśnienia' },
  { value: 'reported', label: 'Zgłoszone' },
  { value: 'resolved', label: 'Wyjaśnione' },
  { value: 'all', label: 'Wszystkie' },
]
const FILTER_PARAMS: Record<StatusFilter, string> = {
  open: 'do-wyjasnienia',
  reported: 'zgloszone',
  resolved: 'wyjasnione',
  all: 'wszystkie',
}
const STATUS_LABELS: Record<PriceCheckStatus, string> = {
  REPORTED: 'Zgłoszone dostawcy',
  RESOLVED: 'Wyjaśnione',
}
const STATUS_OPTIONS = (Object.keys(STATUS_LABELS) as PriceCheckStatus[]).map((value) => ({
  value,
  label: STATUS_LABELS[value],
}))

type PeriodFilter = '7' | '14' | '30'
const PERIOD_FILTERS: { value: PeriodFilter; label: string }[] = [
  { value: '7', label: '7 dni' },
  { value: '14', label: '14 dni' },
  { value: '30', label: '30 dni' },
]

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

const ALL_SUPPLIERS = 'wszyscy'

/** Weryfikacja albo przyjęcia usprawiedliwione incydentem zamówienia do bazy (`?widok=incydenty`). */
type View = 'verify' | 'incidents'
const VIEW_PARAMS: Record<View, string> = { verify: 'weryfikacja', incidents: 'incydenty' }

/** Własne kategorie z Ustawień sklepu (`?kategoria=<id>`), do tego wszystkie i przyjęcia spoza kategorii. */
const ALL_CATEGORIES = 'wszystkie'
const OTHER_CATEGORY = 'pozostale'
type CategoryKey = string

/** Przyjęcie należy do kategorii, gdy ma artykuł z jej rejonu; „pozostałe” = z żadnej kategorii. */
function inCategory(delivery: PriceCheckDelivery, key: CategoryKey, categories: PriceCheckCategory[]) {
  if (key === ALL_CATEGORIES) return true
  const regions = (codes: string[]) => delivery.lines.some((line) => line.regionCode && codes.includes(line.regionCode))
  if (key === OTHER_CATEGORY) return !categories.some((category) => regions(category.regionCodes))
  const category = categories.find((item) => item.id === key)
  return category ? regions(category.regionCodes) : true
}

/** Kolor statusu wyjaśniania: bursztyn czeka na sklep, niebieski na dostawcę, zieleń zamknięte. */
const STATUS_STYLES: Record<
  Exclude<StatusFilter, 'all'>,
  { label: string; dot: string; badge: string; tile: string }
> = {
  open: {
    label: 'Do wyjaśnienia',
    dot: 'bg-amber-500',
    badge: 'bg-amber-100 text-amber-900 dark:bg-amber-500/15 dark:text-amber-200',
    tile: 'border-amber-200 bg-amber-50/70 dark:border-amber-500/30 dark:bg-amber-500/10',
  },
  reported: {
    label: 'Zgłoszone dostawcy',
    dot: 'bg-sky-500',
    badge: 'bg-sky-100 text-sky-900 dark:bg-sky-500/15 dark:text-sky-200',
    tile: 'border-sky-200 bg-sky-50/70 dark:border-sky-500/30 dark:bg-sky-500/10',
  },
  resolved: {
    label: 'Wyjaśnione',
    dot: 'bg-emerald-500',
    badge: 'bg-emerald-100 text-emerald-900 dark:bg-emerald-500/15 dark:text-emerald-200',
    tile: 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-500/30 dark:bg-emerald-500/10',
  },
}
const STATUS_ORDER: Exclude<StatusFilter, 'all'>[] = ['open', 'reported', 'resolved']

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

const filterOf = (delivery: PriceCheckDelivery): Exclude<StatusFilter, 'all'> =>
  delivery.review?.status === 'REPORTED' ? 'reported' : delivery.review?.status === 'RESOLVED' ? 'resolved' : 'open'

const supplierKey = (delivery: PriceCheckDelivery) => delivery.supplierCode ?? delivery.supplierName ?? ''

/** Data RRRR-MM-DD o `days - 1` dni przed `to` (okres z dzisiejszym dniem włącznie). */
function periodStart(to: string, days: number) {
  const date = new Date(`${to}T12:00:00`)
  date.setDate(date.getDate() - (days - 1))
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

/** "1 artykuł", "3 artykuły", "5 artykułów". */
function articlesWord(count: number) {
  if (count === 1) return '1 artykuł'
  const lastTwo = count % 100
  const last = count % 10
  return `${count} ${last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'artykuły' : 'artykułów'}`
}

/** Rozwinięte przyjęcia zostają po wejściu w bon i powrocie (pamięć karty przeglądarki). */
function useExpanded(storeId: string) {
  const storageKey = `price-check-expanded:${storeId}`
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(sessionStorage.getItem(storageKey) ?? '[]') as string[])
    } catch {
      return new Set()
    }
  })
  const toggle = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      try {
        sessionStorage.setItem(storageKey, JSON.stringify([...next]))
      } catch {
        // Bez pamięci karty rozwinięcie po prostu nie przetrwa powrotu.
      }
      return next
    })
  return { expanded, toggle }
}

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
  const { expanded, toggle } = useExpanded(storeId)
  const view: View = params.get('widok') === VIEW_PARAMS.incidents ? 'incidents' : 'verify'
  const query = params.get('szukaj') ?? ''
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
        datę. Wartości po korektach: korekta przypięta do przyjęcia od razu zmienia raport.
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
              onChange={(next) => setParam('szukaj', next || null)}
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
                  <Link to={storeSettingsPaths.priceCheck} className="underline underline-offset-4 hover:text-foreground">
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
                        <DeliveryRow
                          key={delivery.id}
                          storeId={storeId}
                          delivery={delivery}
                          hasMinimum={report.data.minMarginPercent != null}
                          open={expanded.has(delivery.id)}
                          onToggle={() => toggle(delivery.id)}
                        />
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

/**
 * Druga linia zakładek: własne kategorie z Ustawień sklepu (rejony nomenklatury) w bieżącej zakładce, z liczbą
 * przyjęć. Bez kategorii pokazuje tylko odnośnik do ustawień.
 */
function CategoryTabs({
  categories,
  deliveries,
  value,
  onChange,
}: {
  categories: PriceCheckCategory[]
  deliveries: PriceCheckDelivery[]
  value: CategoryKey
  onChange: (key: CategoryKey) => void
}) {
  const canEdit = useCan('SALES', 'EDIT')
  if (categories.length === 0) {
    return canEdit ? (
      <p className="mb-4 px-1 text-sm text-muted-foreground">
        Własne kategorie (np. napoje, chemia) dodasz w{' '}
        <Link to={storeSettingsPaths.priceCheck} className="underline underline-offset-4 hover:text-foreground">
          Ustawieniach sklepu
        </Link>
        .
      </p>
    ) : (
      <div className="mb-4" />
    )
  }
  const tabs = [
    { key: ALL_CATEGORIES, label: 'Wszystkie' },
    ...categories.map((category) => ({ key: category.id, label: category.name })),
    { key: OTHER_CATEGORY, label: 'Pozostałe' },
  ]
  return (
    <div role="tablist" aria-label="Kategorie własne" className="mb-4 flex flex-wrap gap-1.5">
      {tabs.map((tab) => {
        const selected = tab.key === value
        const count = deliveries.filter((delivery) => inCategory(delivery, tab.key, categories)).length
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(tab.key)}
            className={cn(
              'inline-flex h-8 items-center gap-2 rounded-full border px-3 text-sm transition-[background-color,border-color,color,transform] duration-150 ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-[0.97] motion-reduce:active:scale-100',
              selected
                ? 'border-foreground bg-foreground font-medium text-background'
                : 'bg-card text-muted-foreground hover:border-foreground/30 hover:text-foreground',
              tab.key === OTHER_CATEGORY && !selected && 'border-dashed',
            )}
          >
            {tab.label}
            <span
              className={cn(
                'min-w-5 rounded-full px-1.5 text-xs tabular-nums',
                selected ? 'bg-background/20' : 'bg-muted',
              )}
            >
              {count}
            </span>
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

/** Kafelek statusu: w kolorze statusu, gdy coś w nim jest, wyciszony przy zerze. */
function SummaryTile({
  status,
  value,
  detail,
}: {
  status: Exclude<StatusFilter, 'all'>
  value: number
  detail: string
}) {
  const style = STATUS_STYLES[status]
  return (
    <Card className={cn('gap-1 p-4', value > 0 && style.tile)}>
      <p className="flex items-center gap-2 text-sm font-medium">
        <span aria-hidden className={cn('size-2.5 rounded-full', value > 0 ? style.dot : 'bg-muted-foreground/30')} />
        {style.label}
      </p>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      <p className="text-sm text-muted-foreground tabular-nums">{detail}</p>
    </Card>
  )
}

function DeliveryRow({
  storeId,
  delivery,
  hasMinimum,
  open,
  onToggle,
}: {
  storeId: string
  delivery: PriceCheckDelivery
  hasMinimum: boolean
  open: boolean
  onToggle: () => void
}) {
  const canEdit = useCan('SALES', 'EDIT')
  const [dialog, setDialog] = useState<PriceCheckStatus | null>(null)
  const { review, remove, restore } = usePriceCheckReviewActions(storeId)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const undo = (entry: PriceCheckReview) =>
    remove.mutate(entry.id, {
      onSuccess: () =>
        toast.success('Cofnięto zmianę statusu', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(entry.id, { onError }) },
        }),
      onError,
    })

  const status = delivery.review?.status ?? null
  const supplier = delivery.supplierName ?? delivery.supplierCode ?? 'Nieznany dostawca'
  const panelId = `przyjecie-${delivery.id}`

  return (
    <div>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={onToggle}
        className={cn(
          'grid w-full grid-cols-[auto_minmax(0,1fr)] items-start gap-x-3 gap-y-2 px-4 py-3 text-left transition-colors duration-150 outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-inset md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-center',
          open && 'bg-muted/60 hover:bg-muted/70',
        )}
      >
        <ChevronRightIcon
          aria-hidden
          className={cn(
            'mt-0.5 size-4 text-muted-foreground transition-transform duration-200 ease-out md:mt-0',
            open && 'rotate-90',
          )}
        />
        <span className="min-w-0">
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1 font-medium">
            {supplier}
            {delivery.supplierCode && delivery.supplierName && (
              <span className="text-sm font-normal text-muted-foreground tabular-nums">{delivery.supplierCode}</span>
            )}
            <StatusBadge status={status} />
          </span>
          <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground tabular-nums">
            <span className="rounded bg-muted px-1.5 font-medium text-foreground">Bon {bonNumber(delivery.number)}</span>
            {formatDocumentDate(delivery.documentDate)}
            {delivery.supplierDocumentNumber && <span>· faktura {delivery.supplierDocumentNumber}</span>}
            {delivery.correctionCount > 0 && (
              <span>· {delivery.correctionCount === 1 ? '1 korekta' : `korekty: ${delivery.correctionCount}`}</span>
            )}
          </span>
        </span>
        <span className="col-start-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm tabular-nums md:col-start-3 md:justify-end md:text-right">
          <MoneyPill label="z cadencier" value={articlesWord(delivery.lines.length)} tone="neutral" />
          {hasMinimum && delivery.belowMinimumCount > 0 && (
            <MoneyPill label="poniżej minimum" value={String(delivery.belowMinimumCount)} tone="loss" />
          )}
          <MoneyPill label="zakup" value={`${formatMoney(delivery.linesPurchaseValue)} zł`} tone="neutral" />
          {delivery.baseDifferenceValue > 0 && (
            <MoneyPill
              label="drożej niż baza"
              value={`${formatMoney(delivery.baseDifferenceValue)} zł`}
              tone="loss"
            />
          )}
        </span>
      </button>

      {open && (
        <div id={panelId} className="border-t bg-muted/40 px-3 pt-1 pb-3 sm:px-4">
          <div className="flex flex-wrap items-center justify-between gap-3 py-2.5">
            <Link
              to={documentPaths.document('PZ', delivery.id)}
              className="inline-flex items-center gap-1 text-sm font-medium underline-offset-4 hover:underline"
              title={`Numer wewnętrzny ${delivery.number}`}
            >
              Otwórz bon {bonNumber(delivery.number)}
              <ArrowUpRightIcon aria-hidden className="size-3.5" />
            </Link>
            {canEdit && (
              <div className="flex flex-wrap gap-2">
                {status !== 'REPORTED' && status !== 'RESOLVED' && (
                  <Button variant="outline" onClick={() => setDialog('REPORTED')}>
                    <SendIcon />
                    Zgłoszone dostawcy
                  </Button>
                )}
                {status !== 'RESOLVED' && (
                  <Button variant="outline" onClick={() => setDialog('RESOLVED')}>
                    <CircleCheckIcon />
                    Wyjaśnione
                  </Button>
                )}
              </div>
            )}
          </div>

          {delivery.lines.length === 0 ? (
            <p className="rounded-lg border bg-background px-4 py-3 text-sm text-muted-foreground">
              Po korektach w tym przyjęciu nie ma już artykułów z cadencier od innego dostawcy.
            </p>
          ) : (
            <LinesTable delivery={delivery} hasMinimum={hasMinimum} />
          )}

          {delivery.history.length > 0 && (
            <ul className="mt-3 grid gap-1 rounded-lg border bg-background px-4 py-3 text-sm">
              {delivery.history.map((entry, index) => (
                <li key={entry.id} className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium">{STATUS_LABELS[entry.status]}</span>
                  <span className="text-muted-foreground tabular-nums">
                    {[entry.createdBy, dateTimeFormat.format(new Date(entry.createdAt))].filter(Boolean).join(', ')}
                  </span>
                  {entry.note && <span className="min-w-0 wrap-break-word">„{entry.note}”</span>}
                  {canEdit && index === 0 && (
                    <Button
                      variant="link"
                      className="h-auto p-0 text-xs"
                      disabled={remove.isPending}
                      onClick={() => undo(entry)}
                    >
                      <Undo2Icon />
                      Cofnij
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <Dialog open={dialog !== null} onOpenChange={(isOpen) => !isOpen && setDialog(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-md">
          {dialog && (
            <ReviewForm
              delivery={delivery}
              initialStatus={dialog}
              onSubmit={(status, note) => review.mutateAsync({ documentId: delivery.id, status, note })}
              onDone={(history) => {
                setDialog(null)
                const added = history[0]
                toast.success(`Zapisano: ${STATUS_LABELS[added.status].toLowerCase()}`, {
                  action: { label: 'Cofnij', onClick: () => remove.mutate(added.id, { onError }) },
                })
              }}
              onCancel={() => setDialog(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function StatusBadge({ status }: { status: PriceCheckStatus | null }) {
  const style = STATUS_STYLES[status === 'RESOLVED' ? 'resolved' : status === 'REPORTED' ? 'reported' : 'open']
  return (
    <Badge className={cn('gap-1.5 border-transparent font-medium', style.badge)}>
      <span aria-hidden className={cn('size-1.5 rounded-full', style.dot)} />
      {style.label}
    </Badge>
  )
}

/** Kwota w pigułce: czerwona, gdy oznacza stratę sklepu (drożej niż baza, poniżej minimum). */
function MoneyPill({ label, value, tone }: { label: string; value: string; tone: 'loss' | 'neutral' }) {
  return (
    <span
      className={cn(
        'inline-flex items-baseline gap-1.5 rounded-md px-2 py-0.5',
        tone === 'loss' ? 'bg-red-50 text-red-900 dark:bg-red-500/10 dark:text-red-200' : '',
      )}
    >
      <span className={tone === 'loss' ? 'text-red-800/80 dark:text-red-200/80' : 'text-muted-foreground'}>
        {label}
      </span>
      <span className="font-semibold">{value}</span>
    </span>
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
                  <p className="text-xs text-muted-foreground">w cadencier jako ITM8 {line.cadencierItm8} (ten sam EAN)</p>
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
                  <span className="block text-xs text-muted-foreground">CENA_CAD {formatPrice(line.cadencierPrice)}</span>
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

function ReviewForm({
  delivery,
  initialStatus,
  onSubmit,
  onDone,
  onCancel,
}: {
  delivery: PriceCheckDelivery
  initialStatus: PriceCheckStatus
  onSubmit: (status: PriceCheckStatus, note: string) => Promise<PriceCheckReview[]>
  onDone: (history: PriceCheckReview[]) => void
  onCancel: () => void
}) {
  const [status, setStatus] = useState(initialStatus)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<{ note: string }>({ defaultValues: { note: '' } })

  const submit = handleSubmit(async ({ note }) => {
    setFormError(null)
    try {
      onDone(await onSubmit(status, note.trim()))
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['note']))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Status wyjaśnienia</DialogTitle>
        <DialogDescription>
          Bon {bonNumber(delivery.number)} z {formatDocumentDate(delivery.documentDate)},{' '}
          {delivery.supplierName ?? delivery.supplierCode}.
        </DialogDescription>
      </DialogHeader>
      <SegmentedField label="Status" options={STATUS_OPTIONS} value={status} onChange={setStatus} className="w-fit" />
      <TextField
        label={status === 'REPORTED' ? 'Notatka, np. komu i jak zgłoszono' : 'Notatka, np. korekta albo ustalenia'}
        autoComplete="off"
        autoFocus
        error={errors.note?.message}
        {...register('note', { maxLength: { value: 1000, message: 'Maksymalnie 1000 znaków' } })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
