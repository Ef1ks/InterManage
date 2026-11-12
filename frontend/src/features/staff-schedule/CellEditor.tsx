import { useState } from 'react'
import { Popover } from '@base-ui/react/popover'
import { PencilIcon, StoreIcon, Trash2Icon, TreePalmIcon } from 'lucide-react'

import { TimeField } from '@/components/form'
import { Button } from '@/components/ui/button'
import type { ScheduleChange } from '@/features/staff-schedule/api'
import {
  absenceOf,
  cellsByDate,
  dayMonth,
  dayOffReason,
  daysBetween,
  formatDay,
  hoursFor,
  hoursLabel,
  isSunday,
} from '@/features/staff-schedule/schedule-utils'
import { sameHours, shiftLabel, type ShiftHours } from '@/features/staff/shifts'
import type { Schedule, ScheduleDay, ScheduleDayOffKind, ScheduleRow, StaffLeave, TimeOffInitiative } from '@/lib/types'
import { cn } from '@/lib/utils'

export type EditedCell = { row: ScheduleRow; day: ScheduleDay; target: HTMLElement }

type Props = {
  schedule: Schedule
  storeId: string
  edited: EditedCell | null
  onChange: (change: ScheduleChange) => void
  onClear: (row: ScheduleRow, day: ScheduleDay) => void
  /** Wpis urlopu w Kadrach (z {@code entry}: poprawa istniejącego); bez dostępu do Kadr nie ma przycisku. */
  onLeave?: (row: ScheduleRow, day: ScheduleDay, entry?: StaffLeave) => void
  /** Usunięcie urlopu z Kadr, z „Przywróć”. */
  onRemoveLeave?: (row: ScheduleRow, entry: StaffLeave) => void
  /** Czas wolny za nadgodziny (art. 151² KP); puste minuty go usuwają. */
  onTimeOff?: (row: ScheduleRow, day: ScheduleDay, minutes: number | null, initiative: TimeOffInitiative | null) => void
  onClose: () => void
}

/** Jedno okienko na całą tabelę, przy klikniętej komórce: komórek są setki. */
export function CellEditor({
  schedule,
  storeId,
  edited,
  onChange,
  onClear,
  onLeave,
  onRemoveLeave,
  onTimeOff,
  onClose,
}: Props) {
  const close = () => {
    edited?.target.focus()
    onClose()
  }
  return (
    <Popover.Root open={edited !== null} onOpenChange={(open) => !open && close()}>
      <Popover.Portal>
        <Popover.Positioner anchor={edited?.target} sideOffset={6} collisionPadding={8} className="isolate z-50">
          <Popover.Popup className="w-72 origin-(--transform-origin) rounded-2xl bg-popover p-4 text-popover-foreground shadow-[0_12px_32px_-12px_rgb(0_0_0/0.25)] ring-1 ring-foreground/10 outline-none transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.97] data-ending-style:opacity-0 data-starting-style:scale-[0.97] data-starting-style:opacity-0 motion-reduce:transition-none">
            {edited && (
              <EditorBody
                key={`${edited.row.employeeId}:${edited.day.date}`}
                schedule={schedule}
                storeId={storeId}
                edited={edited}
                onChange={(change) => {
                  onChange(change)
                  close()
                }}
                onClear={() => {
                  onClear(edited.row, edited.day)
                  close()
                }}
                onLeave={
                  onLeave &&
                  ((entry) => {
                    onLeave(edited.row, edited.day, entry)
                    onClose()
                  })
                }
                onRemoveLeave={
                  onRemoveLeave &&
                  ((entry) => {
                    onRemoveLeave(edited.row, entry)
                    close()
                  })
                }
                onTimeOff={
                  onTimeOff &&
                  ((minutes, initiative) => {
                    onTimeOff(edited.row, edited.day, minutes, initiative)
                    close()
                  })
                }
              />
            )}
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  )
}

function EditorBody({
  schedule,
  storeId,
  edited,
  onChange,
  onClear,
  onLeave,
  onRemoveLeave,
  onTimeOff,
}: {
  schedule: Schedule
  storeId: string
  edited: EditedCell
  onChange: (change: ScheduleChange) => void
  onClear: () => void
  onLeave?: (entry?: StaffLeave) => void
  onRemoveLeave?: (entry: StaffLeave) => void
  onTimeOff?: (minutes: number | null, initiative: TimeOffInitiative | null) => void
}) {
  const { row, day } = edited
  const cell = cellsByDate(row).get(day.date)
  const position = schedule.positions.find((candidate) => candidate.id === row.positionId)
  const options = hoursFor(row, position, day.date)
  const [targetStore, setTargetStore] = useState(cell?.storeId ?? storeId)
  // Dzień w innym sklepie zdarza się rzadko, więc wybór sklepu jest schowany, dopóki dzień nie jest gdzie indziej.
  const [pickStore, setPickStore] = useState(targetStore !== storeId)
  const otherStore = schedule.stores.find((store) => store.id === targetStore && store.id !== storeId)
  const [start, setStart] = useState(cell?.start ?? '')
  const [end, setEnd] = useState(cell?.end ?? '')
  const warnings = row.warnings.filter((warning) => warning.date === day.date)
  const current = cell?.start && cell.end ? { start: cell.start, end: cell.end } : null

  // Niedziela i święto są wolne same z siebie, więc W5 i WN tylko w inne dni; zwykłe W każdego dnia (np. wolna niedziela).
  // Pod urlopem W5 mówi, że to dzień wolny, którego urlop nie zużywa.
  const canMarkOff = !row.civil
  const canMarkLegalOff = canMarkOff && !isSunday(day.date) && !day.holiday
  const owed = [...row.owedDays].sort((a, b) => daysBetween(a, day.date) - daysBetween(b, day.date)).slice(0, 4)

  const markOff = (dayOff: ScheduleDayOffKind, forDate: string | null) =>
    onChange({ employeeId: row.employeeId, date: day.date, start: null, end: null, storeId: null, dayOff, forDate })

  const save = (hours: ShiftHours) =>
    onChange({
      employeeId: row.employeeId,
      date: day.date,
      start: hours.start,
      end: hours.end,
      storeId: targetStore === storeId ? null : targetStore,
      civil: row.civil,
    })

  return (
    <div className="grid gap-3">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <Popover.Title className="text-sm font-medium">{row.name}</Popover.Title>
          <Popover.Description className="text-xs text-muted-foreground">
            {formatDay(day.date)}
            {row.civil && ' · zlecenie'}
            {day.holiday && ` · ${day.holiday}`}
            {cell?.absent && ` · ${absenceOf(cell).label.toLowerCase()}`}
            {row.outOfContractDays.includes(day.date) && ' · brak umowy'}
            {otherStore && ` · sklep ${otherStore.number}`}
          </Popover.Description>
        </div>
        {onLeave && !row.civil && (
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            title="Dodaj urlop od tego dnia (Kadry)"
            aria-label="Dodaj urlop"
            onClick={() => onLeave()}
          >
            <TreePalmIcon aria-hidden />
          </Button>
        )}
        {schedule.stores.length > 1 && (
          <Button
            type="button"
            size="icon-sm"
            variant={pickStore ? 'secondary' : 'ghost'}
            title="Ten dzień w innym sklepie"
            aria-label="Ten dzień w innym sklepie"
            aria-pressed={pickStore}
            onClick={() => {
              if (pickStore) setTargetStore(storeId)
              setPickStore(!pickStore)
            }}
          >
            <StoreIcon aria-hidden />
          </Button>
        )}
      </div>

      {cell?.leave && onLeave && (
        <div className="grid gap-2 rounded-lg bg-muted p-2">
          <p className="text-xs">
            <span className="font-medium">{absenceOf(cell).label}</span>{' '}
            <span className="text-muted-foreground tabular-nums">
              {dayMonth(cell.leave.startDate)}
              {cell.leave.endDate !== cell.leave.startDate && `–${dayMonth(cell.leave.endDate)}`} · {cell.leave.days}{' '}
              {cell.leave.days === 1 ? 'dzień' : 'dni'}
            </span>
          </p>
          <div className="flex flex-wrap gap-1">
            <Button type="button" size="sm" variant="outline" onClick={() => onLeave(cell.leave ?? undefined)}>
              <PencilIcon aria-hidden />
              Zmień daty
            </Button>
            {onRemoveLeave && (
              <Button type="button" size="sm" variant="outline" onClick={() => cell.leave && onRemoveLeave(cell.leave)}>
                <Trash2Icon aria-hidden />
                Anuluj urlop
              </Button>
            )}
          </div>
        </div>
      )}

      {day.closed && (
        <p className="rounded-lg bg-muted p-2 text-xs text-muted-foreground">
          Sklep jest w to święto zamknięty, więc to dzień wolny. Tę zmianę ułożono wcześniej; możesz ją wyczyścić.
        </p>
      )}

      {cell?.absent && (
        <p className="rounded-lg bg-muted p-2 text-xs text-muted-foreground">
          {cell.dayOff
            ? 'Dzień wolny w grafiku, więc urlop go nie zużywa.'
            : cell.start
              ? 'Dzień pracy z grafiku pod urlopem: to dzień urlopu, nie praca. Z wymiaru schodzi pełny dzień (8 h razy etat).'
              : 'Bez zaplanowanej pracy ten dzień nie zużywa urlopu i nie zmienia wymiaru. Jeśli pracownik miałby tego dnia pracować, ułóż godziny: wtedy to dzień urlopu.'}
        </p>
      )}

      {!day.closed && pickStore && schedule.stores.length > 1 && (
        <div role="group" aria-label="Sklep tego dnia" className="flex flex-wrap gap-1">
          {schedule.stores.map((store) => (
            <Button
              key={store.id}
              type="button"
              size="sm"
              variant={targetStore === store.id ? 'default' : 'outline'}
              aria-pressed={targetStore === store.id}
              onClick={() => setTargetStore(store.id)}
            >
              {store.number}
            </Button>
          ))}
        </div>
      )}

      {!day.closed && options.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {options.map((hours) => (
            <Button
              key={shiftLabel(hours)}
              type="button"
              size="sm"
              variant={current && sameHours(current, hours) ? 'default' : 'outline'}
              onClick={() => save(hours)}
              className="tabular-nums"
            >
              {shiftLabel(hours)}
            </Button>
          ))}
        </div>
      )}

      <div className={cn('grid grid-cols-[1fr_1fr_auto] items-start gap-2', day.closed && 'hidden')}>
        <TimeField label="Od" value={start} onChange={setStart} />
        <TimeField label="Do" value={end} onChange={setEnd} />
        <Button
          type="button"
          className="mt-1"
          disabled={!start || !end || start === end}
          onClick={() => save({ start, end })}
        >
          Wpisz
        </Button>
      </div>

      {canMarkOff && (
        <div className="grid gap-1">
          <p className="text-xs text-muted-foreground">Dzień wolny</p>
          <div className="flex flex-wrap gap-1">
            <Button
              type="button"
              size="sm"
              variant={cell?.dayOff === 'W' ? 'default' : 'outline'}
              aria-pressed={cell?.dayOff === 'W'}
              title="Dzień wolny: pracownik widzi, że tego dnia nie pracuje"
              onClick={() => markOff('W', null)}
            >
              W
            </Button>
            {canMarkLegalOff && cell?.dayOff === 'WN' && cell.forDate && (
              <Button type="button" size="sm" aria-pressed>
                WN za {dayMonth(cell.forDate)}
              </Button>
            )}
            {canMarkLegalOff &&
              owed.map((date) => (
                <Button
                  key={date}
                  type="button"
                  size="sm"
                  variant="outline"
                  title={`Wolne za pracę ${isSunday(date) ? 'w niedzielę' : 'w święto'} ${dayMonth(date)}`}
                  onClick={() => markOff('WN', date)}
                >
                  WN za {dayMonth(date)}
                </Button>
              ))}
            {canMarkLegalOff && (
              <Button
                type="button"
                size="sm"
                variant={cell?.dayOff === 'W5' ? 'default' : 'outline'}
                aria-pressed={cell?.dayOff === 'W5'}
                title="Wolne z tytułu pięciodniowego tygodnia pracy"
                onClick={() => markOff('W5', null)}
              >
                W5
              </Button>
            )}
          </div>
          {cell?.dayOff && <p className="text-xs text-muted-foreground">{dayOffReason(cell)}</p>}
        </div>
      )}

      {onTimeOff && !row.civil && !day.closed && !cell?.absent && !cell?.dayOff && (
        <TimeOffSection
          row={row}
          current={cell?.timeOffMinutes ?? null}
          initiative={cell?.timeOffInitiative ?? null}
          onSave={onTimeOff}
        />
      )}

      {warnings.length > 0 && (
        <ul
          className={cn('grid gap-1 rounded-lg bg-red-200 p-2 text-xs text-red-950 dark:bg-red-900 dark:text-red-50')}
        >
          {warnings.map((warning) => (
            <li key={warning.message}>
              {warning.message} <span className="opacity-80">({warning.basis})</span>
            </li>
          ))}
        </ul>
      )}

      {(cell?.start || cell?.dayOff) && (
        <Button type="button" variant="ghost" size="sm" onClick={onClear} className="justify-self-start">
          <Trash2Icon aria-hidden />
          Wyczyść dzień
        </Button>
      )}
    </div>
  )
}

const clockOf = (minutes: number) =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
const minutesOf = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))

/**
 * Czas wolny za nadgodziny dobowe (art. 151² KP): na wniosek pracownika godzina za godzinę, z inicjatywy pracodawcy
 * półtorej godziny za godzinę, najpóźniej do końca okresu. Podpowiada tyle, ile zostało do odebrania; przy krótszej
 * zmianie wpisuje się tę część dnia, która jest wolna.
 */
function TimeOffSection({
  row,
  current,
  initiative: saved,
  onSave,
}: {
  row: ScheduleRow
  current: number | null
  initiative: TimeOffInitiative | null
  onSave: (minutes: number | null, initiative: TimeOffInitiative | null) => void
}) {
  const left = Math.max(0, row.toCompensateMinutes ?? 0)
  const [initiative, setInitiative] = useState<TimeOffInitiative>(saved ?? 'EMPLOYEE')
  const suggested = (kind: TimeOffInitiative) => (kind === 'EMPLOYER' ? Math.ceil(left * 1.5) : left)
  const [value, setValue] = useState(current ? clockOf(current) : left ? clockOf(suggested('EMPLOYEE')) : '')
  if (!current && left === 0) return null
  const minutes = value ? minutesOf(value) : 0
  return (
    <div className="grid gap-2 rounded-lg bg-blue-200 p-2 text-blue-950 dark:bg-blue-900 dark:text-blue-50">
      <p className="text-xs">
        <span className="font-medium">Czas wolny za nadgodziny</span>
        {left > 0 && <> · do odebrania {hoursLabel(left)}</>}
      </p>
      <div role="group" aria-label="Czyja inicjatywa" className="flex flex-wrap gap-1">
        {(['EMPLOYEE', 'EMPLOYER'] as const).map((kind) => (
          <Button
            key={kind}
            type="button"
            size="sm"
            variant={initiative === kind ? 'default' : 'outline'}
            aria-pressed={initiative === kind}
            title={
              kind === 'EMPLOYEE'
                ? 'Godzina wolnego za godzinę nadliczbową'
                : 'Półtorej godziny wolnego za godzinę, do końca okresu'
            }
            onClick={() => {
              setInitiative(kind)
              if (!current && left) setValue(clockOf(suggested(kind)))
            }}
          >
            {kind === 'EMPLOYEE' ? 'Wniosek pracownika 1:1' : 'Pracodawca 1,5:1'}
          </Button>
        ))}
      </div>
      <div className="grid grid-cols-[1fr_auto] items-start gap-2">
        <TimeField label="Ile godzin" value={value} onChange={setValue} />
        <Button type="button" className="mt-1" disabled={minutes <= 0} onClick={() => onSave(minutes, initiative)}>
          Wpisz
        </Button>
      </div>
      {current !== null && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="justify-self-start"
          onClick={() => onSave(null, null)}
        >
          <Trash2Icon aria-hidden />
          Usuń czas wolny
        </Button>
      )}
    </div>
  )
}
