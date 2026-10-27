import { useMemo, useState } from 'react'
import { DownloadIcon, FileSpreadsheetIcon, HistoryIcon, LockIcon } from 'lucide-react'
import { toast } from 'sonner'

import { DateField, FormActions, FormNote, MultiComboboxField, type ComboboxOption } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import {
  useCloseDeliveryReport,
  useDeliveryReportClosingActions,
  useDeliveryReportClosings,
  useDownloadClosedReport,
  useDownloadDeliveryReport,
  useDownloadPendingChanges,
  useSetExcludedSuppliers,
  useSuppliers,
} from '@/features/documents/api'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import { addDays, parseIsoDate, todayIso } from '@/lib/dates'
import type { DeliveryReportClosing, DeliveryReportExcludedSupplier } from '@/lib/types'

/** Najdłuższy okres raportu, jak `DeliveryReportService.MAX_DAYS`. */
const MAX_DAYS = 366

const monthStart = (iso: string) => `${iso.slice(0, 8)}01`

/** Okresy pod ręką: bieżący miesiąc do dziś, poprzedni miesiąc, ostatnie 7 dni. */
function presets(today: string) {
  const previousEnd = addDays(monthStart(today), -1)
  return [
    { label: 'Ten miesiąc', from: monthStart(today), to: today },
    { label: 'Poprzedni miesiąc', from: monthStart(previousEnd), to: previousEnd },
    { label: 'Ostatnie 7 dni', from: addDays(today, -6), to: today },
  ]
}

const daysInPeriod = (from: string, to: string) =>
  Math.round((parseIsoDate(to).getTime() - parseIsoDate(from).getTime()) / 86_400_000) + 1

const closedAtFormat = new Intl.DateTimeFormat('pl-PL', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const periodLabel = (closing: DeliveryReportClosing) =>
  `${formatDocumentDate(closing.periodFrom)} – ${formatDocumentDate(closing.periodTo)}`

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Raport przyjęć towaru za okres w Excelu: wiersz przyjęcia (data i nr bonu, faktura, dostawca, korekty), a pod nim,
 * zwinięte, zakupy netto po korektach według rejonów nomenklatury. Podgląd niczego nie zapisuje. Zamknięcie okresu
 * zapisuje raport: późniejsze korekty i importy przyjęć z tego okresu nie zmieniają go, tylko trafiają do następnego
 * raportu w sekcji „Zmiany w zamkniętych okresach”.
 */
export function DeliveryPeriodReport({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)
  const today = todayIso()
  const [period, setPeriod] = useState(() => presets(today)[0])
  const canClose = useCan('DOCUMENTS', 'EDIT')
  const { data: overview } = useDeliveryReportClosings(storeId)
  const preview = useDownloadDeliveryReport(storeId)
  const close = useCloseDeliveryReport(storeId)
  const pendingDownload = useDownloadPendingChanges(storeId)

  const error =
    period.from && period.to && period.to < period.from
      ? 'Koniec okresu jest przed początkiem'
      : period.from && period.to && daysInPeriod(period.from, period.to) > MAX_DAYS
        ? `Raport obejmuje najwyżej ${MAX_DAYS} dni`
        : undefined
  const ready = Boolean(period.from && period.to) && !error
  const closings = overview?.closings ?? []
  const overlapping = ready
    ? closings.find((closing) => closing.periodFrom <= period.to && closing.periodTo >= period.from)
    : undefined
  const closeBlocked = overlapping
    ? `Okres nachodzi na zamknięty raport ${periodLabel(overlapping)}. Podgląd pobierzesz, a zamknąć można tylko dni, które nie są jeszcze zamknięte.`
    : period.to > today
      ? 'Zamknąć można tylko okres, który już się skończył.'
      : null
  const pending = overview?.pendingChanges ?? 0

  const downloadPreview = () =>
    preview.mutate(
      { from: period.from, to: period.to },
      { onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się przygotować raportu')) },
    )
  const closePeriod = () =>
    close.mutate(
      { from: period.from, to: period.to },
      {
        onSuccess: (closing) => toast.success(`Zamknięto raport ${periodLabel(closing)}`),
        onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się zamknąć okresu')),
      },
    )

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <FileSpreadsheetIcon />
        Raport za okres
        {pending > 0 && <Badge variant="secondary">{pending}</Badge>}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="text-lg">Raport przyjęć za okres</DialogTitle>
            <DialogDescription>
              Każde przyjęcie z numerem bonu, fakturą, dostawcą i korektami (z numerami faktur korygujących), a pod nim
              (zwinięte) zakupy netto po korektach według rejonów nomenklatury.
            </DialogDescription>
          </DialogHeader>
          <ExcludedSuppliers
            storeId={storeId}
            excluded={overview?.excludedSuppliers ?? []}
            canEdit={canClose}
            loaded={overview !== undefined}
          />
          <div className="flex flex-wrap gap-2">
            {presets(today).map((preset) => (
              <Button
                key={preset.label}
                size="sm"
                variant={preset.from === period.from && preset.to === period.to ? 'secondary' : 'ghost'}
                onClick={() => setPeriod(preset)}
              >
                {preset.label}
              </Button>
            ))}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <DateField
              label="Data bonu od"
              value={period.from}
              max={today}
              shortcuts={[]}
              onChange={(from) => setPeriod((current) => ({ ...current, from }))}
            />
            <DateField
              label="Data bonu do"
              value={period.to}
              min={period.from || undefined}
              shortcuts={[{ label: 'Dziś', value: today }]}
              error={error}
              onChange={(to) => setPeriod((current) => ({ ...current, to }))}
            />
          </div>
          {pending > 0 && (
            <FormNote icon={<HistoryIcon />}>
              Przyjęcia z zamkniętych okresów zmienione po zamknięciu: {pending}. Raport doda je na końcu jako „Zmiany
              w zamkniętych okresach”, a po zamknięciu okresu przestaną czekać.{' '}
              <Button
                variant="link"
                className="h-auto p-0 text-sm"
                loading={pendingDownload.isPending}
                onClick={() =>
                  pendingDownload.mutate(undefined, {
                    onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się pobrać zmian')),
                  })
                }
              >
                Pobierz same zmiany
              </Button>
            </FormNote>
          )}
          {canClose && (
            <FormNote icon={<LockIcon />}>
              {closeBlocked ??
                'Zamknięcie zapisuje raport w tej postaci. Późniejsze korekty i importy przyjęć z tego okresu trafią do następnego raportu jako zmiany.'}
            </FormNote>
          )}
          <FormActions>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Anuluj
            </Button>
            <Button
              variant={canClose ? 'outline' : 'default'}
              size={canClose ? 'default' : 'xl'}
              loading={preview.isPending}
              disabled={!ready}
              onClick={downloadPreview}
            >
              Pobierz podgląd
            </Button>
            {canClose && (
              <Button size="xl" loading={close.isPending} disabled={!ready || closeBlocked !== null} onClick={closePeriod}>
                <LockIcon />
                Zamknij okres i pobierz
              </Button>
            )}
          </FormActions>
          {closings.length > 0 && <ClosedPeriods storeId={storeId} closings={closings} canEdit={canClose} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

const supplierLabel = (code: string, name: string | null) => (name ? `${code} ${name}` : code)

/**
 * Dostawcy, których przyjęć nie ma w raporcie (podgląd i zamykane okresy). Lista jest wspólna dla sklepu i zapisuje
 * się od razu; zmienia ją osoba z prawem edycji dokumentów, reszta tylko ją widzi.
 */
function ExcludedSuppliers({
  storeId,
  excluded,
  canEdit,
  loaded,
}: {
  storeId: string
  excluded: DeliveryReportExcludedSupplier[]
  canEdit: boolean
  loaded: boolean
}) {
  const { data: suppliers } = useSuppliers(storeId)
  const save = useSetExcludedSuppliers(storeId)
  // Do odpowiedzi serwera pole pokazuje to, co właśnie wybrano.
  const value = save.isPending && save.variables ? save.variables : excluded.map((supplier) => supplier.code)

  const options = useMemo<ComboboxOption[]>(() => {
    const listed = (suppliers ?? [])
      .filter((supplier) => supplier.code !== null)
      .map((supplier) => ({
        value: supplier.code!,
        label: supplierLabel(supplier.code!, supplier.name),
        hint: [supplier.city, supplier.nip && `NIP ${supplier.nip}`].filter(Boolean).join(' · ') || undefined,
      }))
    // Pominięty dostawca, którego nie ma już na liście dostawców, też musi mieć swoją etykietę.
    const missing = excluded
      .filter((supplier) => !listed.some((option) => option.value === supplier.code))
      .map((supplier) => ({ value: supplier.code, label: supplierLabel(supplier.code, supplier.name) }))
    return [...missing, ...listed]
  }, [suppliers, excluded])

  if (!canEdit) {
    if (excluded.length === 0) return null
    return (
      <FormNote>
        Raport pomija dostawców: {excluded.map((supplier) => supplierLabel(supplier.code, supplier.name)).join(', ')}.
      </FormNote>
    )
  }

  return (
    <MultiComboboxField
      label="Pomijani dostawcy"
      description="Ich przyjęć nie ma w podglądzie ani w zamykanych raportach. Lista zapisuje się od razu dla całego sklepu."
      options={options}
      value={value}
      placeholder={loaded ? 'Wszyscy dostawcy w raporcie. Szukaj po kodzie albo nazwie…' : 'Wczytywanie…'}
      emptyText="Żaden dostawca nie pasuje"
      onChange={(codes) =>
        save.mutate(codes, {
          onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się zapisać pomijanych dostawców')),
        })
      }
    />
  )
}

/** Zamknięte okresy od najnowszego: pobranie zamkniętego raportu, zmiany po zamknięciu, cofnięcie ostatniego. */
function ClosedPeriods({
  storeId,
  closings,
  canEdit,
}: {
  storeId: string
  closings: DeliveryReportClosing[]
  canEdit: boolean
}) {
  const download = useDownloadClosedReport(storeId)
  const { cancel, restore } = useDeliveryReportClosingActions(storeId)

  const restoreClosing = (closing: DeliveryReportClosing) =>
    restore.mutate(closing.id, {
      onSuccess: () => toast.success(`Przywrócono zamknięcie ${periodLabel(closing)}`),
      onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się przywrócić zamknięcia')),
    })
  const cancelClosing = (closing: DeliveryReportClosing) =>
    cancel.mutate(closing.id, {
      onSuccess: () =>
        toast.success(`Cofnięto zamknięcie ${periodLabel(closing)}`, {
          action: { label: 'Przywróć', onClick: () => restoreClosing(closing) },
        }),
      onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się cofnąć zamknięcia')),
    })

  return (
    <section aria-labelledby="closed-periods" className="border-t pt-4">
      <h3 id="closed-periods" className="mb-2 text-sm font-semibold">
        Zamknięte okresy
      </h3>
      <ul className="max-h-64 divide-y overflow-y-auto">
        {closings.map((closing) => (
          <li key={closing.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-sm">
            <div className="min-w-0 flex-1">
              <p className="font-medium tabular-nums">{periodLabel(closing)}</p>
              <p className="text-xs text-muted-foreground">
                zamknięto {closedAtFormat.format(new Date(closing.closedAt))}
                {closing.closedBy && `, ${closing.closedBy}`}
              </p>
            </div>
            {closing.pendingChanges > 0 && (
              <Badge variant="secondary" title="Trafią do następnego zamkniętego raportu">
                Zmiany po zamknięciu: {closing.pendingChanges}
              </Badge>
            )}
            <Button
              size="sm"
              variant="ghost"
              loading={download.isPending && download.variables?.id === closing.id}
              onClick={() =>
                download.mutate(closing, {
                  onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się pobrać raportu')),
                })
              }
            >
              <DownloadIcon />
              Pobierz
            </Button>
            {canEdit && closing.cancellable && (
              <Button
                size="sm"
                variant="ghost"
                loading={cancel.isPending}
                onClick={() => cancelClosing(closing)}
              >
                Cofnij
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * Baner nad listą przyjęć, gdy przyjęcia z zamkniętych okresów zmieniły się po zamknięciu: zmiany trafią do
 * następnego raportu, a tu można je pobrać od razu.
 */
export function PendingReportChanges({ storeId }: { storeId: string }) {
  const { data } = useDeliveryReportClosings(storeId)
  const download = useDownloadPendingChanges(storeId)
  const pending = data?.pendingChanges ?? 0
  if (pending === 0) return null
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border bg-card px-4 py-3">
      <HistoryIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
      <p className="min-w-0 flex-1 text-sm">
        <span className="font-medium">Przyjęcia z zamkniętych okresów zmienione po zamknięciu: {pending}.</span>{' '}
        <span className="text-muted-foreground">Trafią do następnego zamkniętego raportu jako zmiany.</span>
      </p>
      <Button
        variant="outline"
        size="sm"
        loading={download.isPending}
        onClick={() =>
          download.mutate(undefined, {
            onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się pobrać zmian')),
          })
        }
      >
        <DownloadIcon />
        Pobierz zmiany
      </Button>
    </div>
  )
}
