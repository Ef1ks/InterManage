import { useState } from 'react'
import { PlusIcon } from 'lucide-react'

import { TimeField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { DAYS, distinctHours, sameHours, sameShift, shiftLabel, type ShiftHours } from '@/features/staff/shifts'
import type { StaffDay, StaffShift } from '@/lib/types'
import { cn } from '@/lib/utils'

const GRID = 'grid grid-cols-[minmax(0,1fr)_repeat(7,1.875rem)] items-center gap-1'
const PRESSABLE =
  'outline-none select-none transition-[background-color,color,transform] duration-150 ease-snappy focus-visible:ring-3 focus-visible:ring-ring/40 active:scale-[0.94] motion-reduce:active:scale-100'

type Props = {
  value: StaffShift[]
  onChange: (shifts: StaffShift[]) => void
  /** Godziny stanowiska; pusta lista = sam edytor godzin (przy stanowisku). */
  options?: StaffShift[]
  addLabel: string
  /** Nowe godziny od razu na cały tydzień; potem odznacza się dni wolne. */
  addForWholeWeek?: boolean
}

/**
 * Siatka tygodnia: wiersz to godziny, kolumna to dzień, więc każdy dzień może mieć inne. Klik w godziny zaznacza cały
 * wiersz. Można zaznaczyć każdy dzień i dopisać dowolne godziny, bo zdarzają się wyjątki.
 */
export function ShiftPicker({ value, onChange, options = [], addLabel, addForWholeWeek = false }: Props) {
  // Wiersz zostaje po odznaczeniu ostatniego dnia, żeby nie uciekał spod kursora.
  const [kept, setKept] = useState<ShiftHours[]>([])
  const rows = distinctHours([...options, ...value, ...kept])

  const keep = (hours: ShiftHours) => setKept((current) => [...current, hours])
  const has = (shifts: StaffShift[], hours: ShiftHours, day: StaffDay) =>
    shifts.some((shift) => sameShift(shift, { ...hours, dayOfWeek: day }))

  const toggleCell = (hours: ShiftHours, day: StaffDay) => {
    keep(hours)
    const shift = { ...hours, dayOfWeek: day }
    onChange(has(value, hours, day) ? value.filter((other) => !sameShift(other, shift)) : [...value, shift])
  }

  const toggleRow = (hours: ShiftHours) => {
    keep(hours)
    const offered = DAYS.filter((day) => has(options, hours, day.value))
    const days = offered.length > 0 ? offered : DAYS
    const others = value.filter((shift) => !sameHours(shift, hours))
    const allSelected = days.every((day) => has(value, hours, day.value))
    onChange(allSelected ? others : [...others, ...days.map((day) => ({ ...hours, dayOfWeek: day.value }))])
  }

  const add = (hours: ShiftHours) => {
    keep(hours)
    if (!addForWholeWeek) return
    const others = value.filter((shift) => !sameHours(shift, hours))
    onChange([...others, ...DAYS.map((day) => ({ ...hours, dayOfWeek: day.value }))])
  }

  const exceptions = options.length > 0 && value.some((shift) => !options.some((option) => sameShift(option, shift)))

  return (
    <div className="grid gap-1">
      {rows.length > 0 && (
        <div className={GRID} aria-hidden>
          <span />
          {DAYS.map((day) => (
            <span key={day.value} className="text-center text-[0.7rem] text-muted-foreground">
              {day.label}
            </span>
          ))}
        </div>
      )}
      {rows.map((hours) => (
        <div key={`${hours.start}|${hours.end}`} className={GRID}>
          <button
            type="button"
            title="Zaznacz cały wiersz"
            onClick={() => toggleRow(hours)}
            className={cn(
              PRESSABLE,
              'h-8 truncate rounded-lg px-1.5 text-left text-sm font-medium tabular-nums hover:bg-muted',
            )}
          >
            {shiftLabel(hours)}
          </button>
          {DAYS.map((day) => {
            const selected = has(value, hours, day.value)
            const offered = options.length === 0 || has(options, hours, day.value)
            return (
              <button
                key={day.value}
                type="button"
                aria-pressed={selected}
                aria-label={`${day.label} ${shiftLabel(hours)}`}
                title={!offered ? 'Spoza godzin stanowiska' : undefined}
                onClick={() => toggleCell(hours, day.value)}
                className={cn(
                  PRESSABLE,
                  'h-8 rounded-md',
                  selected && offered && 'bg-primary',
                  selected && !offered && 'bg-amber-400 dark:bg-amber-500',
                  !selected && offered && 'bg-muted hover:bg-muted-foreground/25',
                  !selected && !offered && 'border border-dashed border-border hover:bg-muted',
                )}
              />
            )
          })}
        </div>
      ))}
      {exceptions && (
        <p className="mt-1 text-xs text-muted-foreground">Bursztynowe dni są spoza godzin stanowiska w tym sklepie.</p>
      )}
      <AddHours label={addLabel} onAdd={add} />
    </div>
  )
}

function AddHours({ label, onAdd }: { label: string; onAdd: (hours: ShiftHours) => void }) {
  const [open, setOpen] = useState(false)
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const same = start !== '' && start === end

  if (!open) {
    return (
      <Button
        type="button"
        variant="ghost"
        onClick={() => setOpen(true)}
        className="mt-1 h-9 w-fit text-muted-foreground"
      >
        <PlusIcon />
        {label}
      </Button>
    )
  }

  const add = () => {
    onAdd({ start, end })
    setStart('')
    setEnd('')
    setOpen(false)
  }

  return (
    <div className="mt-2 flex w-full items-start gap-2">
      <TimeField label="Od" defaultOpen value={start} onChange={setStart} className="min-w-0 flex-1" />
      <TimeField
        label="Do"
        value={end}
        onChange={setEnd}
        error={same ? 'Inna niż początek' : undefined}
        className="min-w-0 flex-1"
      />
      <Button type="button" size="xl" disabled={!start || !end || same} onClick={add}>
        Dodaj
      </Button>
    </div>
  )
}
