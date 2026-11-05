import { CircleCheckIcon, TriangleAlertIcon, XIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { SegmentedField, SelectField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  bonNumber,
  documentPaths,
  formatDocumentDate,
  formatMoney,
  formatPercent,
  formatPrice,
  formatQuantity,
} from '@/features/documents/format'
import { useOverpayments, usePriceCheckCategories } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import {
  ALL_CATEGORIES,
  ALL_SUPPLIERS,
  articlesWord,
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
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { OverpaymentDelivery, OverpaymentReport as Report } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useSearchParamText } from '@/lib/use-search-param-text'

/**
 * Przepłacone artykuły: przyjęcia z ostatnich 30 dni, w których artykuł kosztował o ponad 5% więcej niż w jednej
 * z trzech poprzednich dostaw z normalną ilością. Jeden wiersz na przyjęcie, rozwinięty pokazuje te artykuły i ceny,
 * z którymi je porównano. Sprawę wyjaśnia się z dostawcą jak w zakupie bazowym poza bazą, ale statusy są osobne.
 */
export function OverpaymentReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const filter =
    (Object.keys(FILTER_PARAMS) as StatusFilter[]).find((key) => FILTER_PARAMS[key] === params.get('status')) ?? 'open'
  const { expanded, toggle } = useExpanded(`overpayment-expanded:${storeId}`)
  const { text: query, setText: setQuery } = useSearchParamText('szukaj')
  const supplier = params.get('dostawca') ?? ALL_SUPPLIERS
  const period = PERIOD_FILTERS.find((option) => option.value === params.get('okres'))?.value ?? '30'
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

  const report = useOverpayments(storeId)
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
      (!query ||
        matchesSearch(
          [
            delivery.supplierName,
            delivery.supplierCode,
            delivery.number,
            bonNumber(delivery.number),
            delivery.supplierDocumentNumber,
            ...delivery.lines.flatMap((line) => [line.name, line.brand, line.ean, line.itm8]),
          ],
          query,
        )),
  )
  const filtersActive = query !== '' || supplier !== ALL_SUPPLIERS || period !== '30'
  const categoryParam = params.get('kategoria')
  const category =
    categories.find((item) => item.id === categoryParam)?.id ??
    (categoryParam === OTHER_CATEGORY && categories.length > 0 ? OTHER_CATEGORY : ALL_CATEGORIES)
  const deliveries = filtered.filter((delivery) => inCategory(delivery, category, categories))
  const counts = { open: 0, reported: 0, resolved: 0 }
  deliveries.forEach((delivery) => counts[filterOf(delivery)]++)
  const openExcess = deliveries
    .filter((delivery) => filterOf(delivery) === 'open')
    .reduce((sum, delivery) => sum + delivery.excessValue, 0)
  const shown = filter === 'all' ? deliveries : deliveries.filter((delivery) => filterOf(delivery) === filter)

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Przepłacone artykuły</h2>
        {report.data && <ReportFreshness generatedAt={report.data.documentsReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Przyjęcia z ostatnich 30 dni, w których artykuł kosztował o ponad 5% więcej niż w jednej z trzech poprzednich
        dostaw, od dowolnego dostawcy. Dostawy z ilością ponad 5 razy większą niż zwykle nie biorą udziału w porównaniu,
        bo to zwykle krótki termin. Poprzednie ceny są z bonu, sprzed korekt, bo baza zwraca promocję korektą.
        Sprawdzane przyjęcie liczy się po korektach. Raport pomija mięso, drób, garmażerkę, owoce i warzywa, bo wahania
        cen są tam normalne, oraz papierosy, kupowane z jednego miejsca.
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
      ) : (
        <>
          <CategoryTabs
            categories={categories}
            deliveries={filtered}
            value={category}
            onChange={(next) => setParam('kategoria', next === ALL_CATEGORIES ? null : next)}
          />

          <ReportNotices report={report.data} />

          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <SummaryTile
              status="open"
              value={counts.open}
              detail={counts.open ? `przepłacone ${formatMoney(openExcess)} zł` : 'nic nie czeka'}
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
              {filtersActive && (
                <Button
                  variant="ghost"
                  className="ml-auto"
                  onClick={() =>
                    setParams(
                      (current) => {
                        const updated = new URLSearchParams(current)
                        for (const key of ['szukaj', 'dostawca', 'okres']) updated.delete(key)
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

          <div className="mb-3">
            <SegmentedField
              label="Status"
              options={STATUS_FILTERS}
              value={filter}
              onChange={(next) => setParam('status', FILTER_PARAMS[next])}
            />
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
                  : 'W ostatnich 30 dniach żaden artykuł nie był wyraźnie droższy niż w poprzednich dostawach.'}
              </p>
            </Card>
          ) : (
            <div className="grid gap-6">
              {STATUS_ORDER.map((status) => {
                const group = shown.filter((delivery) => filterOf(delivery) === status)
                if (group.length === 0) return null
                const style = STATUS_STYLES[status]
                const excess = group.reduce((sum, delivery) => sum + delivery.excessValue, 0)
                return (
                  <section key={status} aria-label={style.label}>
                    <h3 className="mb-2 flex flex-wrap items-baseline gap-x-2 px-1 text-sm">
                      <span aria-hidden className={cn('size-2.5 self-center rounded-full', style.dot)} />
                      <span className="font-semibold">{style.label}</span>
                      <span className="text-muted-foreground tabular-nums">
                        {group.length} {group.length === 1 ? 'przyjęcie' : 'przyjęć'}
                        {excess > 0 && ` · przepłacone ${formatMoney(excess)} zł`}
                      </span>
                    </h3>
                    <Card className="gap-0 divide-y py-0">
                      {group.map((delivery) => (
                        <ReviewedDeliveryRow
                          key={delivery.id}
                          storeId={storeId}
                          report="OVERPAYMENT"
                          delivery={delivery}
                          emptyText="W tym przyjęciu nie ma już przepłaconych artykułów."
                          open={expanded.has(delivery.id)}
                          onToggle={() => toggle(delivery.id)}
                          pills={
                            <>
                              <MoneyPill label="droższe" value={articlesWord(delivery.lines.length)} tone="neutral" />
                              <MoneyPill
                                label="zakup"
                                value={`${formatMoney(delivery.linesPurchaseValue)} zł`}
                                tone="neutral"
                              />
                              {delivery.excessValue > 0 && (
                                <MoneyPill
                                  label="przepłacone"
                                  value={`${formatMoney(delivery.excessValue)} zł`}
                                  tone="loss"
                                />
                              )}
                            </>
                          }
                        >
                          <LinesTable delivery={delivery} />
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

/** Skąd raport ma dane; ostrzega, gdy dokumenty nie sięgają do dziś. */
function ReportNotices({ report }: { report: Report }) {
  const behind = !report.documentsCoveredUntil || report.documentsCoveredUntil < report.to
  return (
    <div className="mb-4 grid gap-2 text-sm">
      <p className="text-muted-foreground tabular-nums">
        Przyjęcia {formatDocumentDate(report.from)} – {formatDocumentDate(report.to)}
      </p>
      {behind && (
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

function LinesTable({ delivery }: { delivery: OverpaymentDelivery }) {
  return (
    <div className="overflow-x-auto rounded-lg border bg-background shadow-xs">
      <Table className="tabular-nums">
        <TableHeader className="bg-muted/60">
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-4">Artykuł</TableHead>
            <TableHead className="text-right">Ilość</TableHead>
            <TableHead className="text-right">
              Cena zakupu<span className="block text-xs font-normal text-muted-foreground">netto, po korektach</span>
            </TableHead>
            <TableHead className="text-right">
              Najniższa<span className="block text-xs font-normal text-muted-foreground">z poprzednich dostaw</span>
            </TableHead>
            <TableHead className="text-right">Drożej</TableHead>
            <TableHead className="text-right">Przepłacone zł</TableHead>
            <TableHead className="pr-4">Poprzednie dostawy (cena z bonu)</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {delivery.lines.map((line) => (
            <TableRow key={line.itm8}>
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
              </TableCell>
              <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
              <TableCell className="text-right font-medium">{formatPrice(line.purchasePrice)}</TableCell>
              <TableCell className="text-right">{formatPrice(line.referencePrice)}</TableCell>
              <TableCell className="text-right font-medium text-destructive">
                {formatPercent(line.overpaidPercent)}
              </TableCell>
              <TableCell className="text-right font-semibold">{formatMoney(line.excessValue)}</TableCell>
              <TableCell className="pr-4 text-xs whitespace-normal">
                <ul className="grid gap-0.5">
                  {line.references.map((reference) => (
                    <li key={reference.documentId} className="flex flex-wrap gap-x-1.5">
                      <span
                        className={cn(
                          'font-medium',
                          reference.purchasePrice === line.referencePrice && 'text-emerald-700 dark:text-emerald-300',
                        )}
                      >
                        {formatPrice(reference.purchasePrice)}
                      </span>
                      <Link
                        to={documentPaths.document('PZ', reference.documentId)}
                        className="text-muted-foreground underline-offset-4 hover:underline"
                      >
                        bon {bonNumber(reference.documentNumber)}
                      </Link>
                      <span className="text-muted-foreground">
                        {formatDocumentDate(reference.documentDate)} · {formatQuantity(reference.quantity)} szt.
                        {reference.supplierCode && reference.supplierCode !== line.supplierCode
                          ? ` · dostawca ${reference.supplierCode}`
                          : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
