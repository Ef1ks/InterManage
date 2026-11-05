import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { ArrowUpRightIcon, ChevronRightIcon, CircleCheckIcon, SendIcon, Undo2Icon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { Form, FormActions, SegmentedField, TextField } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { bonNumber, documentPaths, formatDocumentDate } from '@/features/documents/format'
import { usePriceReviewActions, type ReviewedReport } from '@/features/sales/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { PriceCheckCategory, PriceCheckReview, PriceCheckStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Części wspólne raportów cen wyjaśnianych z dostawcą: zakupu bazowego poza bazą, przepłaconych i za niskich marż. */

export type ReviewedDelivery = {
  id: string
  number: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  supplierDocumentNumber: string | null
  correctionCount: number
  review: PriceCheckReview | null
  history: PriceCheckReview[]
  lines: { regionCode: string | null }[]
}

export type StatusFilter = 'open' | 'reported' | 'resolved' | 'all'
export type ReviewState = Exclude<StatusFilter, 'all'>

export const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
  { value: 'open', label: 'Do wyjaśnienia' },
  { value: 'reported', label: 'Zgłoszone' },
  { value: 'resolved', label: 'Wyjaśnione' },
  { value: 'all', label: 'Wszystkie' },
]
export const FILTER_PARAMS: Record<StatusFilter, string> = {
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

export type PeriodFilter = '7' | '14' | '30'
export const PERIOD_FILTERS: { value: PeriodFilter; label: string }[] = [
  { value: '7', label: '7 dni' },
  { value: '14', label: '14 dni' },
  { value: '30', label: '30 dni' },
]

export const ALL_SUPPLIERS = 'wszyscy'

/** Własne kategorie z Ustawień sklepu (`?kategoria=<id>`), do tego wszystkie i przyjęcia spoza kategorii. */
export const ALL_CATEGORIES = 'wszystkie'
export const OTHER_CATEGORY = 'pozostale'
export type CategoryKey = string

/** Przyjęcie należy do kategorii, gdy ma artykuł z jej rejonu; „pozostałe” = z żadnej kategorii. */
export function inCategory(delivery: ReviewedDelivery, key: CategoryKey, categories: PriceCheckCategory[]) {
  if (key === ALL_CATEGORIES) return true
  const regions = (codes: string[]) => delivery.lines.some((line) => line.regionCode && codes.includes(line.regionCode))
  if (key === OTHER_CATEGORY) return !categories.some((category) => regions(category.regionCodes))
  const category = categories.find((item) => item.id === key)
  return category ? regions(category.regionCodes) : true
}

/** Kolor statusu wyjaśniania: bursztyn czeka na sklep, niebieski na dostawcę, zieleń zamknięte. */
export const STATUS_STYLES: Record<ReviewState, { label: string; dot: string; badge: string; tile: string }> = {
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
export const STATUS_ORDER: ReviewState[] = ['open', 'reported', 'resolved']

const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

export const filterOf = (delivery: ReviewedDelivery): ReviewState =>
  delivery.review?.status === 'REPORTED' ? 'reported' : delivery.review?.status === 'RESOLVED' ? 'resolved' : 'open'

export const supplierKey = (delivery: ReviewedDelivery) => delivery.supplierCode ?? delivery.supplierName ?? ''

/** Data RRRR-MM-DD o `days - 1` dni przed `to` (okres z dzisiejszym dniem włącznie). */
export function periodStart(to: string, days: number) {
  const date = new Date(`${to}T12:00:00`)
  date.setDate(date.getDate() - (days - 1))
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-')
}

/** "1 artykuł", "3 artykuły", "5 artykułów". */
export function articlesWord(count: number) {
  if (count === 1) return '1 artykuł'
  const lastTwo = count % 100
  const last = count % 10
  return `${count} ${last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'artykuły' : 'artykułów'}`
}

/** Rozwinięte przyjęcia zostają po wejściu w bon i powrocie (pamięć karty przeglądarki). */
export function useExpanded(storageKey: string) {
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
 * Druga linia zakładek: własne kategorie z Ustawień sklepu (rejony nomenklatury) w bieżącej zakładce, z liczbą
 * przyjęć. Bez kategorii pokazuje tylko odnośnik do ustawień.
 */
export function CategoryTabs({
  categories,
  deliveries,
  value,
  onChange,
}: {
  categories: PriceCheckCategory[]
  deliveries: ReviewedDelivery[]
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

/** Kafelek statusu: w kolorze statusu, gdy coś w nim jest, wyciszony przy zerze. */
export function SummaryTile({ status, value, detail }: { status: ReviewState; value: number; detail: string }) {
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

/**
 * Przyjęcie na liście raportu: nagłówek z dostawcą i kwotami, a po rozwinięciu link do bonu, zmiana statusu, artykuły
 * (`children`) i historia statusów, każda zmiana do cofnięcia.
 */
export function ReviewedDeliveryRow({
  storeId,
  report,
  delivery,
  pills,
  emptyText,
  open,
  onToggle,
  children,
}: {
  storeId: string
  report: ReviewedReport
  delivery: ReviewedDelivery
  pills: ReactNode
  emptyText: string
  open: boolean
  onToggle: () => void
  children: ReactNode
}) {
  const canEdit = useCan('SALES', 'EDIT')
  const [dialog, setDialog] = useState<PriceCheckStatus | null>(null)
  const { review, remove, restore } = usePriceReviewActions(storeId, report)
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
            <span className="rounded bg-muted px-1.5 font-medium text-foreground">
              Bon {bonNumber(delivery.number)}
            </span>
            {formatDocumentDate(delivery.documentDate)}
            {delivery.supplierDocumentNumber && <span>· faktura {delivery.supplierDocumentNumber}</span>}
            {delivery.correctionCount > 0 && (
              <span>· {delivery.correctionCount === 1 ? '1 korekta' : `korekty: ${delivery.correctionCount}`}</span>
            )}
          </span>
        </span>
        <span className="col-start-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm tabular-nums md:col-start-3 md:justify-end md:text-right">
          {pills}
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
            <CopyButton
              value={bonNumber(delivery.number)}
              label={`Kopiuj numer bonu ${bonNumber(delivery.number)}`}
              className="-ml-2 mr-auto"
            />
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
            <p className="rounded-lg border bg-background px-4 py-3 text-sm text-muted-foreground">{emptyText}</p>
          ) : (
            children
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

/** Kwota w pigułce: czerwona, gdy oznacza stratę sklepu (drożej niż baza, poniżej minimum, przepłacone). */
export function MoneyPill({ label, value, tone }: { label: string; value: string; tone: 'loss' | 'neutral' }) {
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

function ReviewForm({
  delivery,
  initialStatus,
  onSubmit,
  onDone,
  onCancel,
}: {
  delivery: ReviewedDelivery
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
