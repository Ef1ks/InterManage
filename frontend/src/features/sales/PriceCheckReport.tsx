import { useState } from 'react'
import { useForm } from 'react-hook-form'
import {
  ChevronRightIcon,
  CircleCheckIcon,
  InfoIcon,
  SendIcon,
  TriangleAlertIcon,
  Undo2Icon,
  UploadIcon,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { Form, FormActions, SegmentedField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
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
import { usePriceCheck, usePriceCheckReviewActions } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { PriceCheckDelivery, PriceCheckReport as Report, PriceCheckReview, PriceCheckStatus } from '@/lib/types'
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

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

const filterOf = (delivery: PriceCheckDelivery): Exclude<StatusFilter, 'all'> =>
  delivery.review?.status === 'REPORTED' ? 'reported' : delivery.review?.status === 'RESOLVED' ? 'resolved' : 'open'

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

  const report = usePriceCheck(storeId)
  const deliveries = report.data?.deliveries ?? []
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
        dostawcy niż baza. Wartości po korektach: korekta przypięta do przyjęcia od razu zmienia raport.
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
          <ReportNotices report={report.data} />

          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <SummaryTile
              label="Do wyjaśnienia"
              value={String(counts.open)}
              detail={
                counts.open
                  ? `zakup ${formatMoney(openValue)} zł${openExcess > 0 ? ` · za drogo o ${formatMoney(openExcess)} zł` : ''}`
                  : 'nic nie czeka'
              }
              tone={counts.open ? 'warning' : undefined}
            />
            <SummaryTile label="Zgłoszone dostawcy" value={String(counts.reported)} detail="czekają na odpowiedź" />
            <SummaryTile label="Wyjaśnione" value={String(counts.resolved)} detail="w ostatnich 30 dniach" />
          </div>

          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <SegmentedField
              label="Status"
              options={STATUS_FILTERS}
              value={filter}
              onChange={(next) =>
                setParams(
                  (current) => {
                    const updated = new URLSearchParams(current)
                    updated.set('status', FILTER_PARAMS[next])
                    return updated
                  },
                  { replace: true },
                )
              }
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
                {filter === 'open' ? 'Nic nie czeka na wyjaśnienie' : 'Brak przyjęć z tym statusem'}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                W ostatnich 30 dniach artykuły z cadencier przychodziły tylko z bazy.
              </p>
            </Card>
          ) : (
            <Card className="gap-0 divide-y py-0">
              {shown.map((delivery) => (
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
          )}
        </>
      )}
    </>
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

function SummaryTile({
  label,
  value,
  detail,
  tone,
}: {
  label: string
  value: string
  detail: string
  tone?: 'warning'
}) {
  return (
    <Card className={cn('gap-1 p-4', tone === 'warning' && 'border-amber-300 dark:border-amber-500/40')}>
      <p className="text-sm text-muted-foreground">{label}</p>
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
        className="grid w-full grid-cols-[auto_minmax(0,1fr)] items-start gap-x-3 gap-y-2 px-4 py-3 text-left transition-colors duration-150 outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-inset md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-center"
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
          <span className="block text-sm text-muted-foreground tabular-nums">
            Bon {bonNumber(delivery.number)} · {formatDocumentDate(delivery.documentDate)}
            {delivery.supplierDocumentNumber && ` · faktura ${delivery.supplierDocumentNumber}`}
            {delivery.correctionCount > 0 &&
              ` · ${delivery.correctionCount === 1 ? '1 korekta' : `korekty: ${delivery.correctionCount}`}`}
          </span>
        </span>
        <span className="col-start-2 flex flex-wrap items-baseline gap-x-5 gap-y-1 text-sm tabular-nums md:col-start-3 md:justify-end md:text-right">
          <span>
            <span className="text-muted-foreground">z cadencier </span>
            <span className="font-medium">{articlesWord(delivery.lines.length)}</span>
          </span>
          {hasMinimum && delivery.belowMinimumCount > 0 && (
            <span className="font-medium text-destructive">poniżej minimum: {delivery.belowMinimumCount}</span>
          )}
          <span>
            <span className="text-muted-foreground">zakup </span>
            <span className="font-semibold">{formatMoney(delivery.linesPurchaseValue)} zł</span>
          </span>
          {delivery.baseDifferenceValue > 0 && (
            <span>
              <span className="text-muted-foreground">drożej niż baza </span>
              <span className="font-semibold">{formatMoney(delivery.baseDifferenceValue)} zł</span>
            </span>
          )}
        </span>
      </button>

      {open && (
        <div id={panelId} className="border-t bg-muted/20">
          <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <Link
              to={documentPaths.document('PZ', delivery.id)}
              className="text-sm font-medium underline-offset-4 hover:underline"
              title={`Numer wewnętrzny ${delivery.number}`}
            >
              Otwórz bon {bonNumber(delivery.number)}
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
            <p className="px-4 pb-3 text-sm text-muted-foreground">
              Po korektach w tym przyjęciu nie ma już artykułów z cadencier od innego dostawcy.
            </p>
          ) : (
            <LinesTable delivery={delivery} hasMinimum={hasMinimum} />
          )}

          {delivery.history.length > 0 && (
            <ul className="grid gap-1 border-t px-4 py-3 text-sm">
              {delivery.history.map((entry, index) => (
                <li key={entry.id} className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium">{STATUS_LABELS[entry.status]}</span>
                  <span className="text-muted-foreground tabular-nums">
                    {[entry.createdBy, dateTimeFormat.format(new Date(entry.createdAt))].filter(Boolean).join(', ')}
                  </span>
                  {entry.note && <span className="min-w-0 break-words">„{entry.note}”</span>}
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
  if (status === 'RESOLVED') {
    return <Badge variant="secondary">Wyjaśnione</Badge>
  }
  if (status === 'REPORTED') {
    return (
      <Badge variant="outline" className="border-sky-300 text-sky-800 dark:border-sky-500/40 dark:text-sky-300">
        Zgłoszone dostawcy
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="border-amber-300 text-amber-800 dark:border-amber-500/40 dark:text-amber-300">
      Do wyjaśnienia
    </Badge>
  )
}

function LinesTable({ delivery, hasMinimum }: { delivery: PriceCheckDelivery; hasMinimum: boolean }) {
  return (
    <div className="overflow-x-auto border-t bg-background">
      <Table className="tabular-nums">
        <TableHeader>
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
    return <span className="block text-xs text-muted-foreground">tyle samo</span>
  }
  const percent = basePrice > 0 ? (difference / basePrice) * 100 : null
  return (
    <span
      className={cn(
        'block text-xs font-medium',
        difference > 0 ? 'text-destructive' : 'text-emerald-700 dark:text-emerald-400',
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
