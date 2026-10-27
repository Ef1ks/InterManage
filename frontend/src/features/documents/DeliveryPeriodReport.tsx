import { useState } from 'react'
import { FileSpreadsheetIcon } from 'lucide-react'
import { toast } from 'sonner'

import { DateField, FormActions, FormNote } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useDownloadDeliveryReport } from '@/features/documents/api'
import { ApiError } from '@/lib/api'
import { addDays, parseIsoDate, todayIso } from '@/lib/dates'

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

/**
 * Raport przyjęć towaru za wybrany okres w Excelu: wiersz przyjęcia (data i nr bonu, faktura, dostawca, korekty),
 * a pod nim, wcięte, zakupy netto po korektach według rejonów nomenklatury.
 */
export function DeliveryPeriodReport({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)
  const today = todayIso()
  const [period, setPeriod] = useState(() => presets(today)[0])
  const download = useDownloadDeliveryReport(storeId)

  const error =
    period.from && period.to && period.to < period.from
      ? 'Koniec okresu jest przed początkiem'
      : period.from && period.to && daysInPeriod(period.from, period.to) > MAX_DAYS
        ? `Raport obejmuje najwyżej ${MAX_DAYS} dni`
        : undefined
  const ready = Boolean(period.from && period.to) && !error

  const submit = () =>
    download.mutate(
      { from: period.from, to: period.to },
      {
        onSuccess: () => setOpen(false),
        onError: (failure) =>
          toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się przygotować raportu'),
      },
    )

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <FileSpreadsheetIcon />
        Raport za okres
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="text-lg">Raport przyjęć za okres</DialogTitle>
            <DialogDescription>
              Każde przyjęcie z numerem bonu, fakturą, dostawcą i korektami, a pod nim zakupy netto po korektach według
              rejonów nomenklatury.
            </DialogDescription>
          </DialogHeader>
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
          <FormNote icon={<FileSpreadsheetIcon />}>
            Wiersze rejonów są zgrupowane pod przyjęciem, więc w Excelu da się je zwinąć przyciskiem „−” z lewej.
          </FormNote>
          <FormActions>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Anuluj
            </Button>
            <Button size="xl" loading={download.isPending} disabled={!ready} onClick={submit}>
              Pobierz Excel
            </Button>
          </FormActions>
        </DialogContent>
      </Dialog>
    </>
  )
}
