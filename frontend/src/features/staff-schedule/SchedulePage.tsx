import { useEffect, useState } from 'react'
import {
  CalendarCheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CopyPlusIcon,
  EraserIcon,
  FilterIcon,
  HistoryIcon,
  ListTreeIcon,
  SettingsIcon,
} from 'lucide-react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  useChangeSchedule,
  useOpenHoliday,
  useSchedule,
  useScheduleDepartments,
  useScheduleTimeOff,
  type ScheduleChange,
  type ScheduleScope,
} from '@/features/staff-schedule/api'
import { useCan } from '@/features/auth/api'
import { useLeaveRemoval, useStaffRecords } from '@/features/staff-records/api'
import { RecordDialog } from '@/features/staff-records/RecordDialogs'
import { CellEditor, type EditedCell } from '@/features/staff-schedule/CellEditor'
import { PublishDialog, RateDialog, SettingsDialog, VersionsDialog } from '@/features/staff-schedule/ScheduleDialogs'
import { LayoutDialog } from '@/features/staff-schedule/LayoutDialog'
import { ScheduleGrid, ScheduleLegend } from '@/features/staff-schedule/ScheduleGrid'
import {
  brushHours,
  cellsByDate,
  dayOfWeek,
  formatDay,
  daysBetween,
  formatPay,
  formatPeriodDay,
  hoursLabel,
  isSunday,
  needsBalancing,
  shiftMinutes,
} from '@/features/staff-schedule/schedule-utils'
import { distinctHours, sameHours, shiftLabel, type ShiftHours } from '@/features/staff/shifts'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { addMonths, currentMonth, formatMonth } from '@/lib/dates'
import type { Schedule, ScheduleDay, ScheduleRow, StaffLeave, StoreSummary, TimeOffInitiative } from '@/lib/types'
import { cn } from '@/lib/utils'

const STATUS: Record<Schedule['status'], { label: string; className: string }> = {
  DRAFT: { label: 'Roboczy', className: 'bg-zinc-300 text-zinc-900 dark:bg-zinc-600 dark:text-zinc-50' },
  PUBLISHED: {
    label: 'Opublikowany',
    className: 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50',
  },
  CHANGED: {
    label: 'Zmiany do publikacji',
    className: 'bg-amber-300 text-amber-950 dark:bg-amber-800 dark:text-amber-50',
  },
}

/** Art. 151(11) § 1 pkt 1 KP. */
const SUNDAY_DAY_OFF_DAYS = 6

/** Szybkie wpisywanie: wybrane godziny albo czyszczenie trafiają w kolejne klikane komórki. */
type Brush = ShiftHours | 'clear' | 'W5' | null

const onError = (cause: unknown) =>
  toast.error(cause instanceof ApiError ? cause.message : 'Nie udało się zapisać grafiku')

export function SchedulePage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Grafik" />
        <FullPageMessage title="Brak sklepu" description="Grafik układa się osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StoreSchedule key={store.id} store={store} />
}

function StoreSchedule({ store }: { store: StoreSummary }) {
  const [params, setParams] = useSearchParams()
  const departments = useScheduleDepartments(store.id)
  const month = params.get('miesiac') ?? currentMonth()
  const version = params.get('wersja') ? Number(params.get('wersja')) : null
  const list = departments.data ?? []
  const department =
    list.find((item) => item.id === params.get('dzial')) ?? list.find((item) => item.canEdit) ?? list[0]
  const scope = department ? { storeId: store.id, departmentId: department.id, month } : null
  const schedule = useSchedule(scope, version)

  const update = (changes: Record<string, string | null>) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null) next.delete(key)
          else next.set(key, value)
        }
        return next
      },
      { replace: true },
    )

  const description = schedule.data
    ? `${storeLabel(store)} · okres rozliczeniowy ${formatPeriodDay(schedule.data.periodStart)} – ${formatPeriodDay(schedule.data.periodEnd)}`
    : storeLabel(store)

  return (
    <>
      <span data-wide-page hidden />
      <PageHeader title="Grafik" description={description} />
      {departments.isError ? (
        <FullPageMessage
          title="Nie udało się wczytać działów"
          action={{ label: 'Spróbuj ponownie', onClick: () => void departments.refetch() }}
          inline
        />
      ) : !departments.data ? (
        <div className="h-64 animate-pulse rounded-xl bg-muted" aria-busy />
      ) : !department || !scope ? (
        <FullPageMessage
          title="Nie ma jeszcze działów"
          description="Działy i stanowiska zakłada się w zakładce Pracownicy."
          inline
        />
      ) : (
        <div className="grid gap-3">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <nav aria-label="Dział" className="flex max-w-full gap-1 overflow-x-auto rounded-xl bg-muted p-1">
              {list.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  aria-current={item.id === department.id ? 'page' : undefined}
                  onClick={() => update({ dzial: item.id, wersja: null })}
                  className={cn(
                    'shrink-0 rounded-lg px-3.5 py-1.5 text-sm font-medium whitespace-nowrap text-muted-foreground outline-none transition-[background-color,color,box-shadow] duration-150 hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
                    item.id === department.id && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
                  )}
                >
                  {item.name}
                </button>
              ))}
            </nav>
            <div className="ml-auto flex items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label="Poprzedni miesiąc"
                onClick={() => update({ miesiac: addMonths(month, -1), wersja: null })}
              >
                <ChevronLeftIcon aria-hidden />
              </Button>
              <span className="min-w-36 text-center text-sm font-medium">{formatMonth(month)}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label="Następny miesiąc"
                onClick={() => update({ miesiac: addMonths(month, 1), wersja: null })}
              >
                <ChevronRightIcon aria-hidden />
              </Button>
            </div>
          </div>
          {schedule.isError ? (
            <FullPageMessage
              title="Nie udało się wczytać grafiku"
              action={{ label: 'Spróbuj ponownie', onClick: () => void schedule.refetch() }}
              inline
            />
          ) : !schedule.data ? (
            <div className="h-96 animate-pulse rounded-xl bg-muted" aria-busy />
          ) : (
            <Loaded
              key={scope.departmentId + month}
              store={store}
              scope={scope}
              departmentName={department.name}
              schedule={schedule.data}
              onVersion={(number) => update({ wersja: number === null ? null : String(number) })}
            />
          )}
        </div>
      )}
    </>
  )
}

function Loaded({
  store,
  scope,
  departmentName,
  schedule,
  onVersion,
}: {
  store: StoreSummary
  scope: ScheduleScope
  departmentName: string
  schedule: Schedule
  onVersion: (version: number | null) => void
}) {
  const change = useChangeSchedule(scope)
  const holiday = useOpenHoliday(scope)
  const timeOff = useScheduleTimeOff(scope)
  // Osoby, których miesiąc nie mieści się w celu albo mają nadgodziny do odebrania; filtr tabeli.
  const [onlyBalancing, setOnlyBalancing] = useState(false)
  const [rateRow, setRateRow] = useState<ScheduleRow | null>(null)
  const [edited, setEdited] = useState<EditedCell | null>(null)
  // Urlop wpisuje się w Kadrach, bo to dane kadrowe; z grafiku tylko z dostępem do nich.
  const canAddLeave = useCan('STAFF_RECORDS', 'EDIT')
  const [leave, setLeave] = useState<{ employeeId: string; date: string; entry?: StaffLeave } | null>(null)
  const leaveRemoval = useLeaveRemoval(store.id)
  const removeLeave = (row: ScheduleRow, entry: StaffLeave) => {
    const target = { employeeId: row.employeeId, leaveId: entry.id }
    const onError = (cause: unknown) =>
      toast.error(cause instanceof ApiError ? cause.message : 'Nie udało się zapisać zmiany')
    leaveRemoval.remove.mutate(target, {
      onSuccess: () =>
        toast.success(`Usunięto urlop: ${row.name}`, {
          action: { label: 'Przywróć', onClick: () => leaveRemoval.restore.mutate(target, { onError }) },
        }),
      onError,
    })
  }
  // Pozostały urlop też z Kadr: przy orzeczeniu jest w nim 10 dni z art. 19, więc bez dostępu do Kadr go nie ma.
  const records = useStaffRecords(store.id, useCan('STAFF_RECORDS', 'VIEW'))
  const leaveLeft = new Map(records.data?.map((record) => [record.employeeId, record.remainingLeave]))
  const [brush, setBrush] = useState<Brush>(null)
  const [dialog, setDialog] = useState<'publish' | 'versions' | 'settings' | 'layout' | null>(null)
  const [copied, setCopied] = useState<ScheduleRow | null>(null)
  const { canEdit, viewedVersion, status, rows, days, versions } = schedule
  const brushes = brushHours(schedule.positions, rows)
  const warnings = rows.flatMap((row) => row.warnings.map((warning) => ({ ...warning, name: row.name })))
  const balancing = rows.filter(needsBalancing)

  useEffect(() => {
    if (brush === null && copied === null) return
    const stop = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setBrush(null)
      setCopied(null)
    }
    window.addEventListener('keydown', stop)
    return () => window.removeEventListener('keydown', stop)
  }, [brush, copied])

  const save = (changes: ScheduleChange[], onSuccess?: () => void) => change.mutate(changes, { onSuccess, onError })

  const saveTimeOff = (
    row: ScheduleRow,
    day: ScheduleDay,
    minutes: number | null,
    initiative: TimeOffInitiative | null,
    undo = true,
  ) => {
    const cell = cellsByDate(row).get(day.date)
    const previous = { minutes: cell?.timeOffMinutes ?? null, initiative: cell?.timeOffInitiative ?? null }
    timeOff.mutate(
      { employeeId: row.employeeId, date: day.date, minutes, initiative },
      {
        onError,
        onSuccess: () => {
          if (!undo) return
          toast(
            minutes === null
              ? `Usunięto czas wolny za nadgodziny: ${row.name}, ${formatDay(day.date)}`
              : `Czas wolny za nadgodziny ${hoursLabel(minutes)}: ${row.name}, ${formatDay(day.date)}`,
            {
              action: {
                label: 'Przywróć',
                onClick: () => saveTimeOff(row, day, previous.minutes, previous.initiative, false),
              },
            },
          )
        },
      },
    )
  }

  const clear = (row: ScheduleRow, day: ScheduleDay) => {
    const cell = cellsByDate(row).get(day.date)
    if (!cell?.start && !cell?.dayOff) return
    const previous = {
      employeeId: row.employeeId,
      civil: row.civil,
      date: day.date,
      start: cell.start,
      end: cell.end,
      storeId: cell.storeId,
      dayOff: cell.dayOff,
      forDate: cell.forDate,
    }
    save([{ ...previous, start: null, end: null, storeId: null, dayOff: null, forDate: null }], () =>
      toast(`Wyczyszczono: ${row.name}, ${formatDay(day.date)}`, {
        action: { label: 'Przywróć', onClick: () => save([previous]) },
      }),
    )
  }

  const setHoliday = (day: ScheduleDay, open: boolean, undo = true) =>
    holiday.mutate(
      { date: day.date, open },
      {
        onError,
        onSuccess: () => {
          if (!undo) return
          toast(`${day.holiday}: sklep ${open ? 'czynny' : 'zamknięty'}`, {
            action: { label: 'Cofnij', onClick: () => setHoliday(day, !open, false) },
          })
        },
      },
    )

  const onCell = (row: ScheduleRow, day: ScheduleDay, target: HTMLElement) => {
    // Zamknięte święto jest dniem wolnym: godziny wpisuje się dopiero po oznaczeniu sklepu jako czynnego.
    const closedEmpty = day.closed && !cellsByDate(row).get(day.date)?.start
    if (closedEmpty && brush !== 'clear') {
      toast(`${day.holiday}: sklep zamknięty`, {
        description: schedule.canConfigure
          ? 'Żeby ułożyć zmianę, kliknij nagłówek dnia i oznacz sklep jako czynny.'
          : 'Zmianę da się ułożyć dopiero, gdy kadry oznaczą sklep jako czynny.',
      })
      return
    }
    if (brush === 'clear') clear(row, day)
    else if (brush === 'W5' && row.civil) {
      toast('W5 oznacza się w wierszu umowy o pracę')
    } else if (brush === 'W5') {
      const dayOff = { start: null, end: null, storeId: null, dayOff: 'W5', forDate: null } as const
      save([{ employeeId: row.employeeId, date: day.date, ...dayOff }])
    } else if (brush) {
      save([
        {
          employeeId: row.employeeId,
          date: day.date,
          start: brush.start,
          end: brush.end,
          storeId: null,
          civil: row.civil,
        },
      ])
    } else setEdited({ row, day, target })
  }

  /**
   * Cały miesiąc staje się taki jak u kopiowanego pracownika, także pustymi dniami. Dni nieobecności i poza umową odbiorcy
   * zostają bez zmian. Bierzemy aktualny wiersz, bo po skopiowaniu grafik mógł się zmienić.
   */
  const paste = (target: ScheduleRow) => {
    const source = cellsByDate(rows.find((row) => row.employeeId === copied?.employeeId && !row.civil) ?? target)
    const current = cellsByDate(target)
    const wanted: ScheduleChange[] = []
    const previous: ScheduleChange[] = []
    for (const { date, closed } of days) {
      const from = source.get(date)
      const to = current.get(date)
      if (to?.absent || target.outOfContractDays.includes(date)) continue
      if (closed && !to?.start) continue
      if (from?.absent && !from.start) continue
      const same =
        from?.start === to?.start &&
        from?.end === to?.end &&
        from?.dayOff === to?.dayOff &&
        from?.forDate === to?.forDate
      if (same) continue
      const base = { employeeId: target.employeeId, date }
      wanted.push({
        ...base,
        start: from?.start ?? null,
        end: from?.end ?? null,
        storeId: from?.storeId ?? null,
        dayOff: from?.dayOff ?? null,
        forDate: from?.forDate ?? null,
      })
      previous.push({
        ...base,
        start: to?.start ?? null,
        end: to?.end ?? null,
        storeId: to?.storeId ?? null,
        dayOff: to?.dayOff ?? null,
        forDate: to?.forDate ?? null,
      })
    }
    if (wanted.length === 0) {
      toast(`${target.name} ma już ten sam grafik`)
      return
    }
    save(wanted, () =>
      toast(`Wklejono grafik: ${target.name}`, {
        action: { label: 'Cofnij', onClick: () => save(previous) },
      }),
    )
  }

  /** Każda niedziela i święto dostaje najbliższy pusty dzień miesiąca; ułożonych dni nie rusza. */
  const fillWn = () => {
    const changes: ScheduleChange[] = []
    for (const row of rows.filter((candidate) => !candidate.civil)) {
      const cells = cellsByDate(row)
      const taken = new Set<string>()
      for (const owed of row.owedDays) {
        const free = days
          .filter(({ date, holiday }) => !holiday && !isSunday(date) && !cells.has(date) && !taken.has(date))
          .filter(({ date }) => !row.outOfContractDays.includes(date))
          .filter(({ date }) => !isSunday(owed) || daysBetween(date, owed) <= SUNDAY_DAY_OFF_DAYS)
          .sort((a, b) => daysBetween(a.date, owed) - daysBetween(b.date, owed))[0]
        if (!free) continue
        taken.add(free.date)
        changes.push({
          employeeId: row.employeeId,
          date: free.date,
          start: null,
          end: null,
          storeId: null,
          dayOff: 'WN',
          forDate: owed,
        })
      }
    }
    if (changes.length === 0) {
      toast('Nie ma pustych dni na WN w tym miesiącu')
      return
    }
    save(changes, () =>
      toast(`Oznaczono WN: ${changes.length}`, {
        action: {
          label: 'Cofnij',
          onClick: () => save(changes.map((item) => ({ ...item, dayOff: null, forDate: null }))),
        },
      }),
    )
  }

  /** Stałe godziny wchodzą tylko w puste dni otwartego sklepu; nie ruszają tego, co już ułożono. */
  const fillFixed = () => {
    const changes: ScheduleChange[] = []
    let capped = 0
    for (const row of rows.filter((candidate) => !candidate.guest && !candidate.civil)) {
      const cells = cellsByDate(row)
      // Do celu miesiąca: nadwyżka z wcześniejszych miesięcy okresu nie wraca przez stałe godziny.
      let room = row.monthTargetMinutes === null ? Infinity : row.monthTargetMinutes - (row.monthMinutes ?? 0)
      for (const day of days) {
        const hours = distinctHours(row.fixedShifts.filter((shift) => shift.dayOfWeek === dayOfWeek(day.date)))[0]
        // Dzień urlopu bez godzin też: stałe godziny mówią, że to dzień pracy, więc urlop go zużywa.
        const cell = cells.get(day.date)
        const empty = !cell || (cell.absent && !cell.start && !cell.dayOff)
        if (hours && !day.closed && empty && !row.outOfContractDays.includes(day.date)) {
          // Dzień urlopu nie dokłada godzin, więc nie zjada celu.
          const minutes = cell?.absent ? 0 : shiftMinutes(hours.start, hours.end)
          if (minutes > room) {
            capped++
            continue
          }
          room -= minutes
          changes.push({ employeeId: row.employeeId, date: day.date, ...hours, storeId: null })
        }
      }
    }
    if (changes.length === 0) {
      toast(
        capped > 0
          ? 'Stałe godziny przekroczyłyby cel miesiąca, więc nic nie wstawiono'
          : 'Nie ma pustych dni do uzupełnienia stałymi godzinami',
      )
      return
    }
    save(changes, () =>
      toast(`Wstawiono stałe godziny: ${changes.length}`, {
        description: capped > 0 ? `Pominięto dni ponad cel miesiąca: ${capped}. Cel wynika z salda okresu.` : undefined,
        action: {
          label: 'Cofnij',
          onClick: () => save(changes.map((item) => ({ ...item, start: null, end: null, storeId: null }))),
        },
      }),
    )
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        {viewedVersion !== null ? (
          <>
            <Badge variant="secondary">Wersja {viewedVersion}, tylko do odczytu</Badge>
            <Button type="button" variant="outline" size="sm" onClick={() => onVersion(null)}>
              Wróć do bieżącego grafiku
            </Button>
          </>
        ) : (
          <Badge className={STATUS[status].className}>{STATUS[status].label}</Badge>
        )}
        {canEdit && brushes.length > 0 && (
          <div
            role="group"
            aria-label="Szybkie wpisywanie"
            className="flex flex-wrap items-center gap-0.5 rounded-xl bg-muted p-1"
          >
            <span className="px-2 text-xs text-muted-foreground">Wpisuj klikiem</span>
            {brushes.map((hours) => {
              const active = typeof brush === 'object' && brush !== null && sameHours(brush, hours)
              return (
                <Button
                  key={shiftLabel(hours)}
                  type="button"
                  size="sm"
                  variant={active ? 'default' : 'ghost'}
                  aria-pressed={active}
                  onClick={() => setBrush(active ? null : hours)}
                  className="tabular-nums"
                >
                  {shiftLabel(hours)}
                </Button>
              )
            })}
            <Button
              type="button"
              size="sm"
              variant={brush === 'W5' ? 'default' : 'ghost'}
              aria-pressed={brush === 'W5'}
              title="Wolne z tytułu pięciodniowego tygodnia pracy"
              onClick={() => setBrush(brush === 'W5' ? null : 'W5')}
            >
              W5
            </Button>
            <Button
              type="button"
              size="sm"
              variant={brush === 'clear' ? 'default' : 'ghost'}
              aria-pressed={brush === 'clear'}
              aria-label="Czyszczenie dni"
              title="Czyszczenie dni"
              onClick={() => setBrush(brush === 'clear' ? null : 'clear')}
            >
              <EraserIcon aria-hidden />
            </Button>
          </div>
        )}
        <div className="ml-auto flex flex-wrap gap-2">
          {canEdit && rows.some((row) => row.owedDays.length > 0) && (
            <Button type="button" variant="outline" size="sm" onClick={fillWn}>
              <CalendarCheckIcon aria-hidden />
              Oznacz WN ({rows.reduce((sum, row) => sum + row.owedDays.length, 0)})
            </Button>
          )}
          {canEdit && (
            <Button type="button" variant="outline" size="sm" onClick={fillFixed}>
              <CopyPlusIcon aria-hidden />
              Wstaw stałe godziny
            </Button>
          )}
          {canEdit && (onlyBalancing || balancing.length > 0) && (
            <Button
              type="button"
              variant={onlyBalancing ? 'default' : 'outline'}
              size="sm"
              aria-pressed={onlyBalancing}
              title="Osoby ponad celem miesiąca albo z nadgodzinami dobowymi do odebrania"
              onClick={() => setOnlyBalancing(!onlyBalancing)}
            >
              <FilterIcon aria-hidden />
              Do wyrównania ({balancing.length})
            </Button>
          )}
          {versions.length > 0 && (
            <Button type="button" variant="outline" size="sm" onClick={() => setDialog('versions')}>
              <HistoryIcon aria-hidden />
              Wersje ({versions.length})
            </Button>
          )}
          {canEdit && (
            <Button type="button" variant="outline" size="sm" onClick={() => setDialog('layout')}>
              <ListTreeIcon aria-hidden />
              Grupy i kolejność
            </Button>
          )}
          {schedule.canConfigure && (
            <Button type="button" variant="outline" size="sm" onClick={() => setDialog('settings')}>
              <SettingsIcon aria-hidden />
              Ustawienia działu
            </Button>
          )}
          {canEdit && (
            <Button type="button" size="sm" disabled={status === 'PUBLISHED'} onClick={() => setDialog('publish')}>
              Opublikuj
            </Button>
          )}
        </div>
      </div>

      {copied !== null && (
        <p className="text-xs text-muted-foreground">
          Skopiowano grafik: {copied.name}. Wklej go przyciskiem przy innych pracownikach; zastąpi ich cały miesiąc. Esc
          kończy.
        </p>
      )}

      {brush !== null && (
        <p className="text-xs text-muted-foreground">
          {brush === 'clear'
            ? 'Klikaj dni do wyczyszczenia.'
            : `Klikaj dni, żeby wpisać ${brush === 'W5' ? 'W5' : shiftLabel(brush)}.`}{' '}
          Esc kończy.
        </p>
      )}

      {rows.length === 0 ? (
        <FullPageMessage
          title="Ten dział nie ma pracowników w tym sklepie"
          description="Pracowników i stanowiska dodaje się w zakładce Pracownicy."
          inline
        />
      ) : (
        <ScheduleGrid
          schedule={onlyBalancing ? { ...schedule, rows: balancing } : schedule}
          onRate={canEdit ? setRateRow : undefined}
          onCell={canEdit ? onCell : undefined}
          onClear={canEdit ? clear : undefined}
          copied={copied}
          onCopy={canEdit ? (row) => setCopied(copied?.employeeId === row.employeeId ? null : row) : undefined}
          onPaste={canEdit ? paste : undefined}
          leaveLeft={records.data ? leaveLeft : undefined}
          onHoliday={schedule.canConfigure && viewedVersion === null ? (day) => setHoliday(day, day.closed) : undefined}
        />
      )}
      {schedule.showsPay && rows.length > 0 && (
        <p className="text-sm" title="Suma szacowanych wypłat miesiąca w tym dziale i sklepie, z umów o pracę i zleceń">
          <span className="text-muted-foreground">Wynagrodzenia w miesiącu: </span>
          <span className="font-medium tabular-nums">
            {formatPay(rows.reduce((sum, row) => sum + (row.pay ?? 0), 0))}
          </span>
          {rows.some((row) => row.hourlyRate === null && !row.guest) && (
            <span className="text-muted-foreground"> · nie wszyscy mają stawkę</span>
          )}
        </p>
      )}
      <ScheduleLegend />

      {warnings.length > 0 && (
        <section aria-label="Do sprawdzenia" className="mt-2">
          <h2 className="mb-2 text-sm font-medium">
            Do sprawdzenia <span className="text-muted-foreground tabular-nums">· {warnings.length}</span>
          </h2>
          <ul className="grid gap-x-6 gap-y-1 text-sm lg:grid-cols-2">
            {warnings.map((warning, index) => (
              <li key={index}>
                <span className="font-medium">{warning.name}</span>
                {warning.date && <span className="text-muted-foreground"> · {formatDay(warning.date)}</span>}
                {': '}
                {warning.message} <span className="text-xs text-muted-foreground">({warning.basis})</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {canEdit && (
        <CellEditor
          schedule={schedule}
          storeId={store.id}
          edited={edited}
          onChange={(item) => save([item])}
          onClear={clear}
          onLeave={
            canAddLeave
              ? (row, day, entry) => setLeave({ employeeId: row.employeeId, date: day.date, entry })
              : undefined
          }
          onRemoveLeave={canAddLeave ? removeLeave : undefined}
          onTimeOff={saveTimeOff}
          onClose={() => setEdited(null)}
        />
      )}
      {leave && (
        <RecordDialog
          storeId={store.id}
          employeeId={leave.employeeId}
          editor={{ type: 'leave', date: leave.date, entry: leave.entry }}
          profile={null}
          year={Number((leave.entry?.startDate ?? leave.date).slice(0, 4))}
          onClose={() => setLeave(null)}
        />
      )}
      <RateDialog scope={scope} row={rateRow} onClose={() => setRateRow(null)} />
      <LayoutDialog open={dialog === 'layout'} scope={scope} schedule={schedule} onClose={() => setDialog(null)} />
      <PublishDialog open={dialog === 'publish'} scope={scope} schedule={schedule} onClose={() => setDialog(null)} />
      <VersionsDialog
        open={dialog === 'versions'}
        schedule={schedule}
        onPick={(number) => {
          setDialog(null)
          onVersion(number)
        }}
        onClose={() => setDialog(null)}
      />
      <SettingsDialog
        open={dialog === 'settings'}
        storeId={store.id}
        departmentId={scope.departmentId}
        departmentName={departmentName}
        onClose={() => setDialog(null)}
      />
    </>
  )
}
