import { useState } from 'react'
import { PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, MultiComboboxField, TextField, TimeField } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  useScheduleRate,
  usePublishSchedule,
  useSaveScheduleSettings,
  useScheduleSettings,
  type ScheduleScope,
} from '@/features/staff-schedule/api'
import { formatDateTime, formatPay, hoursLabel, needsBalancing } from '@/features/staff-schedule/schedule-utils'
import { ApiError } from '@/lib/api'
import { addDays, formatMonth, formatShortDate, todayIso } from '@/lib/dates'
import type { Schedule, ScheduleBreak, ScheduleRow, ScheduleSettings } from '@/lib/types'

const failed = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

export function PublishDialog({
  open,
  scope,
  schedule,
  onClose,
}: {
  open: boolean
  scope: ScheduleScope
  schedule: Schedule
  onClose: () => void
}) {
  const publish = usePublishSchedule(scope)
  const first = schedule.versions.length === 0
  const deadline = addDays(`${schedule.month}-01`, -7)
  const late = first && todayIso() > deadline
  const warnings = schedule.rows.reduce((sum, row) => sum + row.warnings.length, 0)
  const balancing = schedule.rows.filter(needsBalancing)

  const submit = () =>
    publish.mutate(undefined, {
      onSuccess: () => {
        toast.success(first ? 'Grafik opublikowany' : 'Opublikowano nową wersję grafiku')
        onClose()
      },
      onError: (cause) => toast.error(failed(cause, 'Nie udało się opublikować grafiku')),
    })

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{first ? 'Opublikować grafik?' : 'Opublikować zmiany?'}</DialogTitle>
          <DialogDescription>
            {first
              ? `${formatMonth(schedule.month)}. Pracownicy zobaczą ten grafik, a ta pierwsza wersja zostanie zapisana na stałe jako rozkład czasu pracy przekazany pracownikom.`
              : `${formatMonth(schedule.month)}. Powstanie wersja ${schedule.versions.length + 1}. Poprzednie wersje, w tym pierwsza, zostają bez zmian.`}
          </DialogDescription>
        </DialogHeader>
        {late && (
          <FormNote>
            Kodeks pracy wymaga przekazania grafiku co najmniej tydzień przed miesiącem, czyli do{' '}
            {formatShortDate(deadline)} (art. 129 § 3). Data publikacji zostanie zapisana.
          </FormNote>
        )}
        {balancing.length > 0 && (
          <div className="grid gap-1.5 rounded-lg bg-amber-200 p-3 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
            <p className="font-medium">Do wyrównania w okresie rozliczeniowym</p>
            <ul className="grid gap-0.5 tabular-nums">
              {balancing.map((row) => {
                const over = row.monthTargetMinutes !== null ? (row.monthMinutes ?? 0) - row.monthTargetMinutes : 0
                const left = row.toCompensateMinutes ?? 0
                return (
                  <li key={row.employeeId}>
                    <span className="font-medium">{row.name}</span>
                    {over > 0 && <> · {hoursLabel(over)} ponad cel miesiąca</>}
                    {left > 0 && <> · nadgodziny do odebrania {hoursLabel(left)}</>}
                  </li>
                )
              })}
            </ul>
            <p className="text-xs">
              Godziny ponad cel zamienią się na koniec okresu w nadgodziny z dodatkiem. Publikacji to nie blokuje.
            </p>
          </div>
        )}
        {warnings > 0 && <FormNote>Do sprawdzenia zostało {warnings}. Publikacji to nie blokuje.</FormNote>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>
            Anuluj
          </Button>
          <Button type="button" loading={publish.isPending} onClick={submit}>
            Opublikuj
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

export function VersionsDialog({
  open,
  schedule,
  onPick,
  onClose,
}: {
  open: boolean
  schedule: Schedule
  onPick: (version: number) => void
  onClose: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Wersje grafiku</DialogTitle>
          <DialogDescription>
            Każda publikacja zostaje na stałe. Pierwsza to rozkład przekazany pracownikom, kolejne to zmiany po nim.
          </DialogDescription>
        </DialogHeader>
        <ul className="grid gap-1">
          {schedule.versions.map((version) => (
            <li key={version.number}>
              <button
                type="button"
                onClick={() => onPick(version.number)}
                className="flex w-full flex-wrap items-center gap-x-2 gap-y-1 rounded-lg px-3 py-2 text-left text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <span className="font-medium">Wersja {version.number}</span>
                {version.number === 1 && <Badge variant="secondary">pierwsza</Badge>}
                {version.late && (
                  <Badge className="bg-amber-200 text-amber-950 dark:bg-amber-900 dark:text-amber-50">
                    później niż tydzień przed
                  </Badge>
                )}
                <span className="w-full text-xs text-muted-foreground tabular-nums">
                  {formatDateTime(version.publishedAt)}
                  {version.publishedBy && ` · ${version.publishedBy}`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </DialogContent>
    </Dialog>
  )
}

export function SettingsDialog({
  open,
  storeId,
  departmentId,
  departmentName,
  onClose,
}: {
  open: boolean
  storeId: string
  departmentId: string
  departmentName: string
  onClose: () => void
}) {
  const { data } = useScheduleSettings(storeId, departmentId, open)
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Ustawienia działu: {departmentName}</DialogTitle>
          <DialogDescription>
            Kto układa grafik, ile osób musi być w pracy i kiedy są przerwy. Obsada i przerwy dotyczą tego sklepu.
          </DialogDescription>
        </DialogHeader>
        {data ? (
          <SettingsForm
            key={departmentId}
            storeId={storeId}
            departmentId={departmentId}
            settings={data}
            onDone={onClose}
          />
        ) : (
          <div className="h-32 animate-pulse rounded-xl bg-muted" aria-busy />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SettingsForm({
  storeId,
  departmentId,
  settings,
  onDone,
}: {
  storeId: string
  departmentId: string
  settings: ScheduleSettings
  onDone: () => void
}) {
  const save = useSaveScheduleSettings(storeId, departmentId)
  const [managers, setManagers] = useState(settings.managerIds)
  const [counts, setCounts] = useState(
    Object.fromEntries(
      settings.minimums.map((item) => [
        item.positionId,
        { min: item.minCount?.toString() ?? '', target: item.targetCount?.toString() ?? '' },
      ]),
    ),
  )
  const setCount = (positionId: string, field: 'min' | 'target', value: string) =>
    setCounts({ ...counts, [positionId]: { ...counts[positionId], [field]: value.replace(/\D/g, '').slice(0, 2) } })
  const [breaks, setBreaks] = useState<ScheduleBreak[]>(settings.breaks)
  const [tolerance, setTolerance] = useState(String(settings.toleranceMinutes))
  const [payEnabled, setPayEnabled] = useState(settings.payEnabled)
  const setBreak = (index: number, change: Partial<ScheduleBreak>) =>
    setBreaks(breaks.map((item, at) => (at === index ? { ...item, ...change } : item)))
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    setError(null)
    const wrongBreak = breaks.find((item) => !item.start || !item.end || item.end <= item.start)
    if (wrongBreak) {
      setError('Każda przerwa musi mieć początek i koniec po początku')
      return
    }
    const wrong = settings.minimums.find(({ positionId }) => {
      const { min, target } = counts[positionId]
      return min && target && Number(target) < Number(min)
    })
    if (wrong) {
      setError(`${wrong.positionName}: docelowa obsada nie może być mniejsza niż minimum`)
      return
    }
    save.mutate(
      {
        managerIds: managers,
        minimums: settings.minimums.map(({ positionId }) => ({
          positionId,
          minCount: counts[positionId].min ? Number(counts[positionId].min) : null,
          targetCount: counts[positionId].target ? Number(counts[positionId].target) : null,
        })),
        breaks,
        toleranceMinutes: Math.min(60, Number(tolerance || 0)),
        payEnabled,
      },
      {
        onSuccess: () => {
          toast.success('Zapisano ustawienia działu')
          onDone()
        },
        onError: (cause) => setError(failed(cause, 'Nie udało się zapisać ustawień')),
      },
    )
  }

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        submit()
      }}
    >
      <MultiComboboxField
        label="Układają grafik"
        description="Muszą mieć też uprawnienie Grafik do edycji. Kadry i właściciel układają wszystkie działy."
        options={settings.people.map((person) => ({
          value: person.userId,
          label: person.name,
          hint: person.positionName,
        }))}
        value={managers}
        onChange={setManagers}
      />
      {settings.minimums.length > 0 && (
        <div className="grid gap-3">
          <p className="text-sm text-muted-foreground">
            Obsada na dzień w tym sklepie. Poniżej minimum dzień jest czerwony, poniżej celu bursztynowy. Nic nie
            blokuje.
          </p>
          {settings.minimums.map((item) => (
            <fieldset key={item.positionId} className="grid gap-2">
              <legend className="mb-1 text-sm font-medium">{item.positionName}</legend>
              <div className="grid grid-cols-2 gap-3">
                <TextField
                  label="Minimum osób"
                  inputMode="numeric"
                  value={counts[item.positionId].min}
                  onChange={(event) => setCount(item.positionId, 'min', event.target.value)}
                />
                <TextField
                  label="Docelowo osób"
                  inputMode="numeric"
                  value={counts[item.positionId].target}
                  onChange={(event) => setCount(item.positionId, 'target', event.target.value)}
                />
              </div>
            </fieldset>
          ))}
        </div>
      )}
      <div className="grid gap-3">
        <div className="grid gap-0.5">
          <p className="text-sm font-medium">Przerwy</p>
          <p className="text-sm text-muted-foreground">
            Godziny przerw w tym sklepie. Grafik ich nie pokazuje, posłużą rejestracji czasu pracy. Płatna wlicza się do
            czasu pracy.
          </p>
        </div>
        {breaks.map((item, index) => (
          <div key={index} className="grid grid-cols-[1fr_1fr_auto_auto] items-center gap-3">
            <TimeField label="Od" value={item.start} onChange={(start) => setBreak(index, { start })} />
            <TimeField label="Do" value={item.end} onChange={(end) => setBreak(index, { end })} />
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <Switch checked={item.paid} onCheckedChange={(paid) => setBreak(index, { paid })} />
              płatna
            </label>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label="Usuń przerwę"
              onClick={() => setBreaks(breaks.filter((_, at) => at !== index))}
            >
              <Trash2Icon />
            </Button>
          </div>
        ))}
        <Button
          type="button"
          variant="outline"
          className="justify-self-start"
          onClick={() => setBreaks([...breaks, { start: '', end: '', paid: true }])}
        >
          <PlusIcon />
          Dodaj przerwę
        </Button>
      </div>
      <TextField
        label="Tolerancja kart czasu pracy (minuty)"
        inputMode="numeric"
        hint="Odbicie tyle minut przed albo po grafiku nie czeka na decyzję kierownika (0-60). Dotyczy tego sklepu."
        value={tolerance}
        onChange={(event) => setTolerance(event.target.value.replace(/\D/g, '').slice(0, 2))}
      />
      <label className="flex cursor-pointer items-start gap-3">
        <Switch checked={payEnabled} onCheckedChange={setPayEnabled} className="mt-0.5" />
        <span className="grid gap-0.5">
          <span className="text-sm font-medium">Licz wynagrodzenia w grafiku</span>
          <span className="text-sm text-muted-foreground">
            Przy pracowniku wpisuje się stawkę za godzinę, a grafik pokazuje szacowaną wypłatę miesiąca. Widzą ją tylko
            osoby, które układają grafik tego działu.
          </span>
        </span>
      </label>
      {error && <FormNote>{error}</FormNote>}
      <FormActions>
        <Button type="submit" loading={save.isPending}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}

/** Stawka za godzinę od miesiąca grafiku; wcześniejsze miesiące liczą się dalej ze swojej stawki. */
export function RateDialog({
  scope,
  row,
  onClose,
}: {
  scope: ScheduleScope
  row: ScheduleRow | null
  onClose: () => void
}) {
  return (
    <Dialog open={row !== null} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-sm">
        {row && <RateForm key={row.employeeId + String(row.civil)} scope={scope} row={row} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function RateForm({ scope, row, onClose }: { scope: ScheduleScope; row: ScheduleRow; onClose: () => void }) {
  const save = useScheduleRate(scope)
  const [value, setValue] = useState(row.hourlyRate === null ? '' : row.hourlyRate.toFixed(2).replace('.', ','))
  const [error, setError] = useState<string | null>(null)
  const parsed = Number(value.replace(/\s/g, '').replace(',', '.'))
  const submit = (rate: number | null) => {
    setError(null)
    if (rate !== null && (!Number.isFinite(rate) || rate <= 0)) {
      setError('Podaj stawkę większą od zera, np. 32,50')
      return
    }
    const previous = row.hourlyRate
    save.mutate(
      { employeeId: row.employeeId, civil: row.civil, rate: rate === null ? null : Math.round(rate * 100) / 100 },
      {
        onSuccess: () => {
          onClose()
          toast(rate === null ? `Usunięto stawkę: ${row.name}` : `Stawka ${formatPay(rate)}/h: ${row.name}`, {
            action: {
              label: 'Przywróć',
              onClick: () => save.mutate({ employeeId: row.employeeId, civil: row.civil, rate: previous }),
            },
          })
        },
        onError: (cause) => setError(failed(cause, 'Nie udało się zapisać stawki')),
      },
    )
  }
  return (
    <>
      <DialogHeader>
        <DialogTitle>Stawka za godzinę</DialogTitle>
        <DialogDescription>
          {row.name} · {row.civil ? 'umowa zlecenia' : 'umowa o pracę'} · od {formatMonth(scope.month)}. Wcześniejsze
          miesiące zostają przy swojej stawce.
        </DialogDescription>
      </DialogHeader>
      <Form
        onSubmit={(event) => {
          event.preventDefault()
          submit(parsed)
        }}
      >
        <TextField
          label="Stawka brutto (zł/h)"
          inputMode="decimal"
          autoFocus
          value={value}
          onChange={(event) => setValue(event.target.value.replace(/[^\d,.\s]/g, ''))}
        />
        {error && <FormNote>{error}</FormNote>}
        <FormActions>
          {row.hourlyRate !== null && (
            <Button type="button" variant="ghost" onClick={() => submit(null)}>
              <Trash2Icon />
              Usuń stawkę
            </Button>
          )}
          <Button type="submit" loading={save.isPending} disabled={!value}>
            Zapisz
          </Button>
        </FormActions>
      </Form>
    </>
  )
}
