import { type FormEvent, useMemo, useState } from 'react'
import { TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FormNote, SegmentedField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { bonNumber, formatMoney, formatPercent, formatPrice, formatQuantity } from '@/features/documents/format'
import { useCan } from '@/features/auth/api'
import { InsertIntoDataEntry } from '@/features/data-entry/InsertIntoDataEntry'
import { VerificationBadge, VerificationMenu } from '@/features/documents/DocumentVerification'
import { InvoicePrintButton } from '@/features/ksef/InvoicePrintButton'
import { emptyRow, withScannedProduct } from '@/features/data-entry/rows'
import { useLowMargins, useSetLowMarginGaps } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ExcludedSuppliersSettings } from '@/features/sales/ExcludedSuppliersSettings'
import { ReviewedReportPage } from '@/features/sales/ReviewedReportPage'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import type { LowMarginDelivery, LowMarginDeviation, LowMarginPromotion } from '@/lib/types'
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

const DEVIATION_PARAM = 'marza'
const DEVIATION_FILTERS: { value: string; label: string; deviation: LowMarginDeviation | null }[] = [
  { value: 'wszystkie', label: 'Wszystkie', deviation: null },
  { value: 'niska', label: 'Za niska', deviation: 'LOW' },
  { value: 'wysoka', label: 'Za wysoka', deviation: 'HIGH' },
]

const DEVIATION_STYLES: Record<LowMarginDeviation, string> = {
  LOW: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  HIGH: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
}

/**
 * Marże poza normą: przyjęcia z wybranego okresu (domyślnie 30 dni, najwyżej rok) z artykułem, którego marża jest
 * o co najmniej 5 punktów procentowych niższa albo o co najmniej 10 wyższa niż wymagana na jego nomenklaturze. Marża
 * liczy się z aktualnej ceny zwykłej na kasie, nie z ceny z PZ. Sprawę wyjaśnia się z dostawcą jak w zakupie bazowym
 * poza bazą, ale statusy są osobne.
 */
export function LowMarginReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const period = PERIODS.find((option) => option.value === params.get('okres'))?.value ?? '30'
  const deviationFilter =
    DEVIATION_FILTERS.find((option) => option.value === params.get(DEVIATION_PARAM)) ?? DEVIATION_FILTERS[0]
  const raw = useLowMargins(storeId, Math.max(30, Number(period)))
  const deviation = deviationFilter.deviation
  const data = useMemo(() => {
    if (!raw.data || !deviation) return raw.data
    return {
      ...raw.data,
      deliveries: raw.data.deliveries.flatMap((delivery) => {
        const lines = delivery.lines.filter((line) => line.deviation === deviation)
        return lines.length > 0
          ? [
              {
                ...delivery,
                lines,
                linesPurchaseValue: lines.reduce((sum, line) => sum + line.purchaseValue, 0),
                excessValue: lines
                  .filter((line) => line.deviation === 'LOW')
                  .reduce((sum, line) => sum + line.excessValue, 0),
              },
            ]
          : []
      }),
    }
  }, [raw.data, deviation])
  const query = { ...raw, data } as typeof raw
  const lowGap = raw.data?.lowGap ?? 5
  const highGap = raw.data?.highGap ?? 10

  return (
    <ReviewedReportPage
      storeId={storeId}
      report="LOW_MARGIN"
      query={query}
      title="Weryfikacja przyjęć"
      intro={`Przyjęcia z wybranego okresu, w których artykuł ma marżę o co najmniej ${lowGap} punktów procentowych niższą (za niska) albo o co najmniej ${highGap} wyższą (za wysoka) niż marża wymagana na jego nomenklaturze. Marża jest liczona od sprzedaży brutto, z ceny zakupu po korektach i z aktualnej ceny zwykłej na kasie, więc po zmianie ceny raport od razu liczy z nowej; trwająca promocja się nie liczy. Raport pomija prasę od Kolportera i artykuły aktywne w cadencier, bo mają z góry ustaloną marżę.`}
      actions={
        raw.data && (
          <ExcludedSuppliersSettings
            storeId={storeId}
            path="low-margins"
            report={raw.data}
            description="Artykuły dostawców bez marży wymaganej nie trafiają do raportu. Lista obowiązuje w całym sklepie."
            label="Dostawcy bez marży wymaganej"
            placeholder="Marża dotyczy wszystkich dostawców. Szukaj po kodzie albo nazwie…"
            readOnlyLabel="Marża nie dotyczy dostawców"
          >
            <GapSettings
              key={`${raw.data.lowGap}:${raw.data.highGap}`}
              storeId={storeId}
              lowGap={raw.data.lowGap}
              highGap={raw.data.highGap}
            />
          </ExcludedSuppliersSettings>
        )
      }
      notice={
        raw.data?.marginsConfigured === false && (
          <p className="flex items-start gap-2 text-amber-700 dark:text-amber-400">
            <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>
              Sklep nie ma ustawionej marży wymaganej, więc raport nie ma z czym porównać. Ustawisz ją w{' '}
              <Link to={storeSettingsPaths.margins} className="underline underline-offset-4">
                Ustawieniach sklepu
              </Link>
              .
            </span>
          </p>
        )
      }
      periods={PERIODS}
      filterParams={[DEVIATION_PARAM]}
      filters={
        <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          Marża
          <SegmentedField
            label="Marża"
            options={DEVIATION_FILTERS}
            value={deviationFilter.value}
            onChange={(next) =>
              setParams(
                (current) => {
                  const updated = new URLSearchParams(current)
                  if (next === DEVIATION_FILTERS[0].value) updated.delete(DEVIATION_PARAM)
                  else updated.set(DEVIATION_PARAM, next)
                  return updated
                },
                { replace: true },
              )
            }
          />
        </span>
      }
      linesLabel="z marżą poza normą"
      excessLabel="brakująca marża"
      emptyDeliveryText="W tym przyjęciu nie ma już artykułów z marżą poza normą."
      nothingFoundText="W wybranym okresie żaden artykuł nie miał marży wyraźnie innej niż wymagana."
      renderLines={(delivery) => <LinesTable storeId={storeId} delivery={delivery} />}
      countsExcess={(line) => line.deviation === 'LOW'}
      renderBadges={(delivery) => (
        <VerificationBadge
          status={delivery.verificationStatus}
          at={delivery.verificationAt}
          by={delivery.verificationBy}
        />
      )}
    />
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
 * Artykuły przyjęcia do tabeli wprowadzania danych, żeby poprawić cenę sprzedaży („Zmień ceny”) albo artykuł: dane
 * z kartoteki jak po wpisaniu EAN-u, ilość i cena zakupu z przyjęcia po korektach.
 */
function InsertLinesIntoDataEntry({ storeId, delivery }: { storeId: string; delivery: LowMarginDelivery }) {
  const lines = delivery.lines.filter((line) => line.ean)
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
      title="Artykuły z tego przyjęcia trafią do tabeli wprowadzania danych, gdzie poprawisz ceny"
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
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center justify-end gap-2">
        <span className="mr-auto text-sm text-muted-foreground">
          Weryfikacja przyjęcia jest ta sama co w Dokumentach:
        </span>
        <VerificationMenu storeId={storeId} document={delivery} size="sm" />
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
              <TableHead className="text-right">Marża</TableHead>
              <TableHead className="text-right">
                Wymagana<span className="block text-xs font-normal text-muted-foreground">na nomenklaturze</span>
              </TableHead>
              <TableHead className="text-right">
                Cena zakupu<span className="block text-xs font-normal text-muted-foreground">dla marży wymaganej</span>
              </TableHead>
              <TableHead className="pr-4 text-right">
                Różnica zł
                <span className="block text-xs font-normal text-muted-foreground">brakuje / ponad wymaganą</span>
              </TableHead>
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
                    <ArticleStatsButton article={line} className="ml-1" />
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`].filter(Boolean).join(' · ')}
                  </p>
                </TableCell>
                <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
                <TableCell className="text-right font-medium">{formatPrice(line.purchasePrice)}</TableCell>
                <TableCell className="text-right whitespace-normal">
                  {formatPrice(line.salePrice)}
                  {line.currentPromoPrice != null && (
                    <span
                      className="mt-0.5 ml-auto block w-fit rounded-md bg-orange-200 px-1.5 py-0.5 text-xs font-medium text-orange-950 dark:bg-orange-900/70 dark:text-orange-100"
                      title="Artykuł jest teraz w promocji; ocena raportu liczy się z ceny zwykłej"
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
                    line.documentSalePrice !== line.salePrice && (
                      <span className="block text-xs text-muted-foreground" title="Cena sprzedaży z przyjęcia PZ">
                        na PZ {formatPrice(line.documentSalePrice)}
                      </span>
                    )
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <span
                    className={cn('rounded-md px-1.5 py-0.5 font-medium', DEVIATION_STYLES[line.deviation])}
                    title={line.deviation === 'LOW' ? 'Za niska marża' : 'Za wysoka marża'}
                  >
                    {formatPercent(line.marginPercent)}
                  </span>
                  {line.currentPromoMargin != null && (
                    <span
                      className={cn(
                        'mt-1 ml-auto block w-fit rounded-md px-1.5 py-0.5 text-xs font-medium',
                        line.currentPromoMargin < line.requiredMargin
                          ? 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'
                          : 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
                      )}
                      title={`Marża na cenie promocyjnej ${formatPrice(line.currentPromoPrice)} z tej samej ceny zakupu`}
                    >
                      w promocji {formatPercent(line.currentPromoMargin)}
                    </span>
                  )}
                </TableCell>
                <TableCell className="text-right">{formatPercent(line.requiredMargin)}</TableCell>
                <TableCell className="text-right">{formatPrice(line.targetPurchasePrice)}</TableCell>
                <TableCell
                  className={cn(
                    'pr-4 text-right font-semibold',
                    line.deviation === 'HIGH' && 'text-sky-700 dark:text-sky-300',
                  )}
                >
                  {line.deviation === 'HIGH' ? '+' : '−'}
                  {formatMoney(line.excessValue)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}
