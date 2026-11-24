import { type FormEvent, Fragment, useMemo, useState } from 'react'
import { TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FormNote, SegmentedField, SwitchField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
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
import { useCan } from '@/features/auth/api'
import { InsertIntoDataEntry } from '@/features/data-entry/InsertIntoDataEntry'
import { InvoicePrintButton } from '@/features/ksef/InvoicePrintButton'
import { emptyRow, withScannedProduct } from '@/features/data-entry/rows'
import { useLowMarginBon, useLowMargins, useSetLowMarginGaps } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { CadencierDifference, MoneyPill } from '@/features/sales/price-review'
import { ReportExclusions } from '@/features/sales/ReportExclusions'
import { ReviewedReportPage } from '@/features/sales/ReviewedReportPage'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import type {
  DeliveryAnomaly,
  DeliveryReviewLine,
  LowMarginDelivery,
  LowMarginDeviation,
  LowMarginPromotion,
} from '@/lib/types'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'

const PROMOTION_LABELS: Record<LowMarginPromotion, string> = {
  LIST: 'z listy promocji',
  TILL: 'z plików kasy',
  LEAFLET: 'z gazetki',
  SALES: 'rozpoznana ze sprzedaży',
}

/** Okresy raportu; krótsze niż 30 dni zawęża przeglądarka, dłuższe pobiera serwer. */
const PERIODS = [
  { value: '7', label: '7 dni' },
  { value: '14', label: '14 dni' },
  { value: '30', label: '30 dni' },
  { value: '60', label: '60 dni' },
  { value: '90', label: '90 dni' },
  { value: '180', label: 'pół roku' },
  { value: '365', label: 'rok' },
]

/**
 * Rodzaje anomalii: nazwa, kolor plakietki (mocne odcienie, czytelne w obu motywach) i licznik przyjęcia. Kolejność
 * jest kolejnością plakietek i filtra.
 */
const ANOMALIES: {
  kind: DeliveryAnomaly
  label: string
  short: string
  param: string
  style: string
  count: (delivery: LowMarginDelivery) => number
}[] = [
  {
    kind: 'LOW_MARGIN',
    label: 'Za niska marża',
    short: 'za niska marża',
    param: 'niska-marza',
    style: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
    count: (delivery) => delivery.lowMarginCount,
  },
  {
    kind: 'OVERPAID',
    label: 'Drożej niż wcześniej',
    short: 'drożej niż wcześniej',
    param: 'drozej',
    style: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
    count: (delivery) => delivery.overpaidCount,
  },
  {
    kind: 'OUTSIDE_BASE',
    label: 'Poza bazą',
    short: 'poza bazą',
    param: 'poza-baza',
    style: 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
    count: (delivery) => delivery.outsideBaseCount,
  },
  {
    kind: 'HIGH_MARGIN',
    label: 'Za wysoka marża',
    short: 'za wysoka marża',
    param: 'wysoka-marza',
    style: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
    count: (delivery) => delivery.highMarginCount,
  },
]
const ANOMALY_BY_KIND = Object.fromEntries(ANOMALIES.map((anomaly) => [anomaly.kind, anomaly])) as Record<
  DeliveryAnomaly,
  (typeof ANOMALIES)[number]
>

const ANOMALY_PARAM = 'anomalia'
const ALL_ANOMALIES = 'wszystkie'

const DEVIATION_STYLES: Record<LowMarginDeviation, string> = {
  LOW: ANOMALY_BY_KIND.LOW_MARGIN.style,
  HIGH: ANOMALY_BY_KIND.HIGH_MARGIN.style,
}

const pill = 'inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-medium whitespace-nowrap'

const anomaliesWord = (count: number) => {
  if (count === 1) return '1 anomalia'
  const lastTwo = count % 100
  const last = count % 10
  return `${count} ${last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'anomalie' : 'anomalii'}`
}

/**
 * Weryfikacja przyjęć: przyjęcia z wybranego okresu z anomaliami, każde z całym bonem. Anomalie liczą się tą samą
 * logiką co osobne raporty: marża z aktualnej ceny na kasie poza normą wobec wymaganej, zakup drożej niż
 * w poprzednich dostawach i artykuł z cadencier kupiony poza bazą. Nagłówek przyjęcia pokazuje liczbę anomalii
 * każdego rodzaju, rozwinięty bon ma je na górze.
 */
export function LowMarginReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const period = PERIODS.find((option) => option.value === params.get('okres'))?.value ?? '30'
  const anomaly = ANOMALIES.find((option) => option.param === params.get(ANOMALY_PARAM)) ?? null
  const raw = useLowMargins(storeId, Math.max(30, Number(period)))
  const data = useMemo(() => {
    if (!raw.data || !anomaly) return raw.data
    return { ...raw.data, deliveries: raw.data.deliveries.filter((delivery) => anomaly.count(delivery) > 0) }
  }, [raw.data, anomaly])
  const query = { ...raw, data } as typeof raw

  return (
    <ReviewedReportPage
      storeId={storeId}
      report="LOW_MARGIN"
      query={query}
      title="Weryfikacja przyjęć"
      actions={
        <ReportExclusions
          storeId={storeId}
          report="LOW_MARGIN"
          description="Artykuły z tych nomenklatur i od tych dostawców zostają na bonie, ale nie są anomalią żadnego rodzaju. Listy obowiązują w całym sklepie."
        >
          {raw.data && (
            <GapSettings
              key={`${raw.data.lowGap}:${raw.data.highGap}`}
              storeId={storeId}
              lowGap={raw.data.lowGap}
              highGap={raw.data.highGap}
            />
          )}
        </ReportExclusions>
      }
      notice={
        raw.data?.marginsConfigured === false && (
          <p className="flex items-start gap-2 text-amber-700 dark:text-amber-400">
            <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>
              Sklep nie ma ustawionej marży wymaganej, więc raport nie ocenia marż. Ustawisz ją w{' '}
              <Link to={storeSettingsPaths.margins} className="underline underline-offset-4">
                Ustawieniach sklepu
              </Link>
              .
            </span>
          </p>
        )
      }
      periods={PERIODS}
      filterParams={[ANOMALY_PARAM]}
      filters={
        <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          Anomalia
          <SegmentedField
            label="Anomalia"
            options={[
              { value: ALL_ANOMALIES, label: 'Wszystkie' },
              ...ANOMALIES.map((option) => ({ value: option.param, label: option.label })),
            ]}
            value={anomaly?.param ?? ALL_ANOMALIES}
            onChange={(next) =>
              setParams(
                (current) => {
                  const updated = new URLSearchParams(current)
                  if (next === ALL_ANOMALIES) updated.delete(ANOMALY_PARAM)
                  else updated.set(ANOMALY_PARAM, next)
                  return updated
                },
                { replace: true },
              )
            }
          />
        </span>
      }
      linesLabel="artykuły"
      excessLabel="możliwa strata"
      emptyDeliveryText="W tym przyjęciu nie ma już anomalii."
      nothingFoundText="W wybranym okresie żadne przyjęcie nie ma anomalii."
      renderLines={(delivery) => <LinesTable storeId={storeId} delivery={delivery} />}
      countsExcess={(line) => line.anomalies.length > 0}
      lineExcess={(line) => line.lossValue}
      renderPills={(delivery) => <DeliveryPills delivery={delivery} />}
    />
  )
}

/** Nagłówek przyjęcia: liczba anomalii, ich rodzaje, zakup bonu i możliwa strata. */
function DeliveryPills({ delivery }: { delivery: LowMarginDelivery }) {
  return (
    <>
      <span className="font-semibold tabular-nums">{anomaliesWord(delivery.anomalyCount)}</span>
      {ANOMALIES.map((anomaly) => {
        const count = anomaly.count(delivery)
        return count > 0 ? (
          <span key={anomaly.kind} className={cn(pill, anomaly.style, 'tabular-nums')}>
            {count} {anomaly.short}
          </span>
        ) : null
      })}
      <MoneyPill
        label="bon"
        value={`${delivery.lineCount} art. · ${formatMoney(delivery.linesPurchaseValue)} zł`}
        tone="neutral"
      />
      {delivery.excessValue > 0 && (
        <MoneyPill label="możliwa strata" value={`${formatMoney(delivery.excessValue)} zł`} tone="loss" />
      )}
    </>
  )
}

/** Progi marży sklepu: o ile punktów procentowych marża może odbiegać od wymaganej. */
function GapSettings({ storeId, lowGap, highGap }: { storeId: string; lowGap: number; highGap: number }) {
  const canEdit = useCan('SALES', 'EDIT')
  const save = useSetLowMarginGaps(storeId)
  const [low, setLow] = useState(String(lowGap).replace('.', ','))
  const [high, setHigh] = useState(String(highGap).replace('.', ','))
  if (!canEdit) {
    return (
      <FormNote>
        Za niska marża: o {formatPercent(lowGap).replace('%', '')} pkt i więcej poniżej wymaganej, za wysoka: o{' '}
        {formatPercent(highGap).replace('%', '')} pkt i więcej powyżej.
      </FormNote>
    )
  }
  const parse = (text: string) => Number(text.replace(',', '.').trim())
  const lowValue = parse(low)
  const highValue = parse(high)
  const valid = (value: number, text: string) =>
    text.trim() !== '' && Number.isFinite(value) && value >= 0 && value <= 100
  const changed = lowValue !== lowGap || highValue !== highGap
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate(
      { lowGap: lowValue, highGap: highValue },
      {
        onSuccess: () => toast.success('Zapisano progi marży'),
        onError: (failure) =>
          toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zapisać progów marży'),
      },
    )
  }
  return (
    <form onSubmit={submit} className="grid gap-3">
      <p className="text-sm font-medium">Progi marży (punkty procentowe od marży wymaganej)</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="Za niska: o ile niżej"
          inputMode="decimal"
          value={low}
          onChange={(event) => setLow(event.target.value)}
          error={valid(lowValue, low) ? undefined : 'Od 0 do 100'}
          hint="np. 5: przy wymaganych 30% od 25% w dół"
        />
        <TextField
          label="Za wysoka: o ile wyżej"
          inputMode="decimal"
          value={high}
          onChange={(event) => setHigh(event.target.value)}
          error={valid(highValue, high) ? undefined : 'Od 0 do 100'}
          hint="np. 10: przy wymaganych 30% od 40% w górę"
        />
      </div>
      <div className="flex justify-end">
        <Button
          type="submit"
          loading={save.isPending}
          disabled={!changed || !valid(lowValue, low) || !valid(highValue, high)}
        >
          Zapisz progi
        </Button>
      </div>
    </form>
  )
}

/** Dzień RRRR-MM-DD jako DD.MM. */
const formatDay = (day: string) => `${day.slice(8, 10)}.${day.slice(5, 7)}`

/**
 * Artykuły z anomalią do tabeli wprowadzania danych, żeby poprawić cenę sprzedaży („Zmień ceny”) albo artykuł: dane
 * z kartoteki jak po wpisaniu EAN-u, ilość i cena zakupu z przyjęcia po korektach.
 */
function InsertLinesIntoDataEntry({ storeId, delivery }: { storeId: string; delivery: LowMarginDelivery }) {
  const lines = delivery.lines.filter((line) => line.ean && line.anomalies.length > 0)
  if (lines.length === 0) return null
  return (
    <InsertIntoDataEntry
      storeId={storeId}
      eans={lines.map((line) => line.ean!)}
      buildRows={(byEan) =>
        lines.map((line) => ({
          ...withScannedProduct({ ...emptyRow(), ean: line.ean! }, byEan.get(line.ean!.replace(/^0+/, '')) ?? null),
          quantity: line.quantity,
          unitNet: line.purchasePrice,
        }))
      }
      label={`Do wprowadzania danych (${lines.length})`}
      title="Artykuły z anomalią trafią do tabeli wprowadzania danych, gdzie poprawisz ceny"
      successTitle={`Bon ${bonNumber(delivery.number)} w tabeli`}
      successDescription={(count) =>
        `Artykuły: ${count}. Popraw ceny sprzedaży, wskaż dostawcę i kliknij „Zmień ceny”, żeby wystawić plik.`
      }
      askDescription="Zastąpić je artykułami z tego przyjęcia czy dopisać je na końcu?"
      size="sm"
    />
  )
}

function LinesTable({ storeId, delivery }: { storeId: string; delivery: LowMarginDelivery }) {
  const canSeeInvoices = useCan('SALES', 'VIEW')
  const [onlyAnomalies, setOnlyAnomalies] = useState(false)
  const flagged = delivery.lines
  // Reszta bonu przychodzi dopiero po rozwinięciu przyjęcia (i tylko, gdy ktoś chce ją zobaczyć).
  const bon = useLowMarginBon(storeId, delivery.id)
  const flaggedItm8s = new Set(flagged.map((line) => line.itm8))
  const normal = (bon.data ?? [])
    .filter((line) => !flaggedItm8s.has(line.itm8))
    .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '', 'pl'))
  const normalCount = Math.max(0, delivery.lineCount - flagged.length)
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center justify-end gap-2">
        <SwitchField
          label={`Tylko anomalie (${flagged.length} z ${delivery.lineCount})`}
          checked={onlyAnomalies}
          onCheckedChange={setOnlyAnomalies}
          className="mr-auto w-auto border-0 bg-transparent px-0 py-1 hover:bg-transparent dark:bg-transparent dark:hover:bg-transparent"
        />
        {canSeeInvoices &&
          delivery.ksefInvoices.map((invoice) => (
            <InvoicePrintButton
              key={invoice.id}
              storeId={storeId}
              invoiceId={invoice.id}
              invoiceNumber={invoice.invoiceNumber}
              label={`${invoice.correction ? 'Korekta' : 'Faktura'} ${invoice.invoiceNumber}`}
            />
          ))}
        <InsertLinesIntoDataEntry storeId={storeId} delivery={delivery} />
      </div>
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
                Cena sprzedaży
                <span className="block text-xs font-normal text-muted-foreground">aktualna na kasie, brutto</span>
              </TableHead>
              <TableHead className="text-right">
                Marża<span className="block text-xs font-normal text-muted-foreground">wobec wymaganej</span>
              </TableHead>
              <TableHead className="text-right">
                Poprzednie dostawy
                <span className="block text-xs font-normal text-muted-foreground">najniższa cena</span>
              </TableHead>
              <TableHead className="text-right">
                Cadencier
                <span className="block text-xs font-normal text-muted-foreground">aktualna cena zakupu netto</span>
              </TableHead>
              <TableHead className="pr-4 text-right">
                Możliwa strata<span className="block text-xs font-normal text-muted-foreground">zł</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {flagged.map((line) => (
              <LineRow key={line.itm8} line={line} />
            ))}
            {!onlyAnomalies && normalCount > 0 && (
              <Fragment>
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={8} className="bg-muted/40 py-1.5 pl-4 text-xs font-medium text-muted-foreground">
                    W normie · {normalCount}
                  </TableCell>
                </TableRow>
                {bon.isError ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={8} className="py-3 pl-4 text-sm text-muted-foreground">
                      Nie udało się pobrać reszty bonu.{' '}
                      <Button variant="link" size="sm" className="h-auto p-0" onClick={() => bon.refetch()}>
                        Spróbuj ponownie
                      </Button>
                    </TableCell>
                  </TableRow>
                ) : !bon.data ? (
                  Array.from({ length: Math.min(normalCount, 3) }, (_, index) => (
                    <TableRow key={index} aria-hidden className="hover:bg-transparent">
                      <TableCell colSpan={8} className="py-3 pl-4">
                        <div className="h-4 w-1/3 loading-shimmer rounded" />
                      </TableCell>
                    </TableRow>
                  ))
                ) : (
                  normal.map((line) => <LineRow key={line.itm8} line={line} />)
                )}
              </Fragment>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function LineRow({ line }: { line: DeliveryReviewLine }) {
  const flagged = line.anomalies.length > 0
  const overpaid = line.overpaid
  const base = line.outsideBase
  return (
    <TableRow className={cn(!flagged && 'text-muted-foreground')}>
      <TableCell className="max-w-96 min-w-72 pl-4 whitespace-normal">
        <p className={cn('font-medium', !flagged && 'font-normal')}>
          {line.name ?? 'Bez nazwy'}
          {line.corrected && (
            <span className="ml-1 text-muted-foreground" title="Po korekcie">
              *
            </span>
          )}
          <ArticleStatsButton article={line} className="ml-1" />
        </p>
        <p className="text-xs text-muted-foreground">
          {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`].filter(Boolean).join(' · ')}
        </p>
        {flagged && (
          <p className="mt-1 flex flex-wrap gap-1">
            {line.anomalies.map((kind) => (
              <span key={kind} className={cn(pill, ANOMALY_BY_KIND[kind].style)}>
                {ANOMALY_BY_KIND[kind].short}
              </span>
            ))}
          </p>
        )}
      </TableCell>
      <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
      <TableCell className={cn('text-right', flagged && 'font-medium')}>{formatPrice(line.purchasePrice)}</TableCell>
      <TableCell className="text-right whitespace-normal">
        {line.salePrice == null ? '–' : formatPrice(line.salePrice)}
        {line.currentPromoPrice != null && (
          <span
            className={cn(
              pill,
              'mt-0.5 ml-auto flex w-fit',
              'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
            )}
            title="Artykuł jest teraz w promocji; ocena marży liczy się z ceny zwykłej"
          >
            teraz w promocji {formatPrice(line.currentPromoPrice)}
            {line.currentPromoTo && ` do ${formatDay(line.currentPromoTo)}`}
          </span>
        )}
        {line.promotion ? (
          <span
            className="block text-xs text-orange-700 dark:text-orange-300"
            title={`Na przyjęciu cena promocyjna (promocja ${PROMOTION_LABELS[line.promotion]})`}
          >
            na PZ w promocji {formatPrice(line.documentSalePrice)}
          </span>
        ) : (
          line.documentSalePrice != null &&
          line.documentSalePrice !== line.salePrice && (
            <span className="block text-xs text-muted-foreground" title="Cena sprzedaży z przyjęcia PZ">
              na PZ {formatPrice(line.documentSalePrice)}
            </span>
          )
        )}
      </TableCell>
      <TableCell className="text-right">
        {line.marginPercent == null ? (
          '–'
        ) : (
          <span
            className={cn(
              'rounded-md px-1.5 py-0.5',
              line.deviation && ['font-medium', DEVIATION_STYLES[line.deviation]],
            )}
            title={
              line.deviation === 'LOW' ? 'Za niska marża' : line.deviation === 'HIGH' ? 'Za wysoka marża' : undefined
            }
          >
            {formatPercent(line.marginPercent)}
          </span>
        )}
        {line.requiredMargin != null && (
          <span className="block text-xs text-muted-foreground">
            wym. {formatPercent(line.requiredMargin)}
            {line.deviation === 'LOW' && line.targetPurchasePrice != null && (
              <> · zakup do {formatPrice(line.targetPurchasePrice)}</>
            )}
          </span>
        )}
        {line.currentPromoMargin != null && (
          <span
            className={cn(
              pill,
              'mt-1 ml-auto flex w-fit',
              line.requiredMargin != null && line.currentPromoMargin < line.requiredMargin
                ? 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'
                : 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
            )}
            title={`Marża na cenie promocyjnej ${formatPrice(line.currentPromoPrice)} z tej samej ceny zakupu`}
          >
            w promocji {formatPercent(line.currentPromoMargin)}
          </span>
        )}
      </TableCell>
      <TableCell className="text-right whitespace-normal">
        {overpaid ? (
          <>
            <span className="font-medium">{formatPrice(overpaid.referencePrice)}</span>
            <span className="block text-xs text-amber-800 dark:text-amber-300">
              drożej o {formatPercent(overpaid.overpaidPercent)}
            </span>
            {overpaid.references.length > 0 && (
              <span
                className="block text-xs text-muted-foreground"
                title={overpaid.references
                  .map(
                    (reference) =>
                      `${formatDocumentDate(reference.documentDate)} bon ${bonNumber(reference.documentNumber)}: ${formatPrice(reference.purchasePrice)} zł (${reference.supplierName ?? reference.supplierCode ?? 'dostawca nieznany'})`,
                  )
                  .join('\n')}
              >
                <Link
                  to={documentPaths.document('PZ', overpaid.references[0].documentId)}
                  className="underline-offset-4 hover:underline"
                >
                  bon {bonNumber(overpaid.references[0].documentNumber)}
                </Link>
                {overpaid.references.length > 1 && ` +${overpaid.references.length - 1}`}
              </span>
            )}
          </>
        ) : (
          '–'
        )}
      </TableCell>
      <TableCell className="text-right whitespace-normal">
        {base ? (
          <>
            {base.cadencierPurchasePrice != null ? (
              <>
                <span className="block font-medium">{formatPrice(base.cadencierPurchasePrice)}</span>
                <CadencierDifference purchasePrice={line.purchasePrice} cadencierPrice={base.cadencierPurchasePrice} />
              </>
            ) : (
              <span className="text-xs text-muted-foreground">brak ceny</span>
            )}
            {base.incidentDate && (
              <span
                className="block text-xs text-muted-foreground"
                title={[base.incidentReason, base.incidentComment].filter(Boolean).join(' · ') || undefined}
              >
                po incydencie {formatDay(base.incidentDate)}
              </span>
            )}
          </>
        ) : (
          '–'
        )}
      </TableCell>
      <TableCell className="pr-4 text-right">
        {line.lossValue > 0 ? (
          <span className="font-semibold text-red-700 dark:text-red-300">−{formatMoney(line.lossValue)}</span>
        ) : line.deviation === 'HIGH' && line.excessValue != null ? (
          <span className="text-sky-700 dark:text-sky-300" title="Marża ponad wymaganą">
            +{formatMoney(line.excessValue)}
          </span>
        ) : (
          '–'
        )}
      </TableCell>
    </TableRow>
  )
}
