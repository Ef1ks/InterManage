import { useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'

import { addDays, parseIsoDate, toIsoDate, todayIso } from '@/lib/dates'
import { cn } from '@/lib/utils'

const WEEKDAYS = ['Pn', 'Wt', 'Śr', 'Cz', 'Pt', 'So', 'Nd']
const MONTHS = Array.from({ length: 12 }, (_, index) =>
  new Intl.DateTimeFormat('pl-PL', { month: 'long' }).format(new Date(2026, index, 1)),
)
const MONTHS_SHORT = Array.from({ length: 12 }, (_, index) =>
  new Intl.DateTimeFormat('pl-PL', { month: 'short' }).format(new Date(2026, index, 1)).replace('.', ''),
)
const dayLabel = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })

type CalendarProps = {
  /** Wybrana data RRRR-MM-DD albo pusty tekst. */
  value: string
  onChange: (value: string) => void
  /** Najwcześniejsza dozwolona data (RRRR-MM-DD). */
  min?: string
  /** Najpóźniejsza dozwolona data (RRRR-MM-DD). */
  max?: string
  className?: string
}

type View = 'days' | 'months' | 'years'

/**
 * Kalendarz w barwach marki: wybrany dzień na czerwieni Intermarché, dzisiejszy oznaczony kropką.
 * Tydzień od poniedziałku, polskie nazwy. W nagłówku klik w miesiąc daje siatkę miesięcy, a klik w rok siatkę lat
 * (wybrany rok przechodzi do miesięcy). Klawiatura: strzałki (dzień/tydzień), PageUp/PageDown (miesiąc),
 * Home/End (początek/koniec tygodnia), Enter lub spacja wybiera.
 */
export function Calendar({ value, onChange, min, max, className }: CalendarProps) {
  const today = todayIso()
  const [focused, setFocused] = useState(value || (min && min > today ? min : max && max < today ? max : today))
  const [month, setMonth] = useState(() => monthStart(focused))
  const [view, setView] = useState<View>('days')
  // Pierwszy rok widocznej siatki lat.
  const [yearsFrom, setYearsFrom] = useState(() => Number(month.slice(0, 4)) - 7)
  const gridRef = useRef<HTMLDivElement>(null)

  const year = Number(month.slice(0, 4))
  const monthIndex = Number(month.slice(5, 7)) - 1
  const days = useMemo(() => monthGrid(month), [month])
  const isDisabled = (day: string) => (min ? day < min : false) || (max ? day > max : false)
  const canGoBack = !min || monthStart(min) < month
  const canGoForward = !max || monthStart(max) > month
  const monthOutside = (index: number) => {
    const start = `${year}-${String(index + 1).padStart(2, '0')}-01`
    return (min ? start < monthStart(min) : false) || (max ? start > monthStart(max) : false)
  }
  const yearOutside = (candidate: number) =>
    (min ? candidate < Number(min.slice(0, 4)) : false) || (max ? candidate > Number(max.slice(0, 4)) : false)

  /** Pokazuje miesiąc, przycięty do min i max. */
  const showMonth = (next: string) => {
    if (min && monthStart(min) > next) setMonth(monthStart(min))
    else if (max && monthStart(max) < next) setMonth(monthStart(max))
    else setMonth(next)
  }

  const focusDay = (day: string) => {
    setFocused(day)
    if (monthStart(day) !== month) setMonth(monthStart(day))
    requestAnimationFrame(() => gridRef.current?.querySelector<HTMLButtonElement>(`[data-day="${day}"]`)?.focus())
  }

  const onKeyDown = (event: KeyboardEvent) => {
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }
    let next: string | null = null
    if (event.key in moves) next = addDays(focused, moves[event.key])
    else if (event.key === 'PageUp') next = shiftMonth(focused, -1)
    else if (event.key === 'PageDown') next = shiftMonth(focused, 1)
    else if (event.key === 'Home') next = addDays(focused, -weekdayIndex(focused))
    else if (event.key === 'End') next = addDays(focused, 6 - weekdayIndex(focused))
    if (next) {
      event.preventDefault()
      focusDay(min && next < min ? min : max && next > max ? max : next)
    }
  }

  const back =
    view === 'days'
      ? { label: 'Poprzedni miesiąc', disabled: !canGoBack, go: () => setMonth(shiftMonth(month, -1)) }
      : view === 'months'
        ? {
            label: 'Poprzedni rok',
            disabled: yearOutside(year - 1),
            go: () => showMonth(`${year - 1}${month.slice(4)}`),
          }
        : { label: 'Wcześniejsze lata', disabled: false, go: () => setYearsFrom(yearsFrom - 12) }
  const forward =
    view === 'days'
      ? { label: 'Następny miesiąc', disabled: !canGoForward, go: () => setMonth(shiftMonth(month, 1)) }
      : view === 'months'
        ? {
            label: 'Następny rok',
            disabled: yearOutside(year + 1),
            go: () => showMonth(`${year + 1}${month.slice(4)}`),
          }
        : { label: 'Późniejsze lata', disabled: false, go: () => setYearsFrom(yearsFrom + 12) }

  return (
    <div className={cn('w-72 select-none', className)}>
      <div className="mb-3 flex items-center justify-between gap-2 px-1">
        <div aria-live="polite" className="-mx-1.5 flex items-center gap-0.5">
          {view === 'years' ? (
            <HeaderButton title="Wróć do dni" active onClick={() => setView('days')}>
              {yearsFrom} – {yearsFrom + 11}
            </HeaderButton>
          ) : (
            <>
              <HeaderButton
                title="Wybierz miesiąc"
                active={view === 'months'}
                onClick={() => setView(view === 'months' ? 'days' : 'months')}
              >
                <span className="capitalize">{MONTHS[monthIndex]}</span>
              </HeaderButton>
              <HeaderButton
                title="Wybierz rok"
                onClick={() => {
                  setYearsFrom(year - 7)
                  setView('years')
                }}
              >
                {year}
              </HeaderButton>
            </>
          )}
        </div>
        <div className="flex gap-1">
          <MonthButton label={back.label} disabled={back.disabled} onClick={back.go}>
            <ChevronLeftIcon />
          </MonthButton>
          <MonthButton label={forward.label} disabled={forward.disabled} onClick={forward.go}>
            <ChevronRightIcon />
          </MonthButton>
        </div>
      </div>

      {view === 'months' && (
        <div className="grid grid-cols-3 gap-1">
          {MONTHS_SHORT.map((name, index) => (
            <PickButton
              key={name}
              selected={index === monthIndex}
              disabled={monthOutside(index)}
              onClick={() => {
                setMonth(`${year}-${String(index + 1).padStart(2, '0')}-01`)
                setView('days')
              }}
            >
              <span className="capitalize">{name}</span>
            </PickButton>
          ))}
        </div>
      )}

      {view === 'years' && (
        <div className="grid grid-cols-3 gap-1">
          {Array.from({ length: 12 }, (_, index) => yearsFrom + index).map((candidate) => (
            <PickButton
              key={candidate}
              selected={candidate === year}
              disabled={yearOutside(candidate)}
              onClick={() => {
                showMonth(`${candidate}${month.slice(4)}`)
                setView('months')
              }}
            >
              {candidate}
            </PickButton>
          ))}
        </div>
      )}

      <div
        role="grid"
        ref={gridRef}
        onKeyDown={onKeyDown}
        className={cn('grid grid-cols-7 gap-y-1', view !== 'days' && 'hidden')}
      >
        {WEEKDAYS.map((weekday, index) => (
          <div
            key={weekday}
            role="columnheader"
            className={cn('pb-1 text-center text-xs font-medium text-muted-foreground', index >= 5 && 'text-brand/80')}
          >
            {weekday}
          </div>
        ))}
        {days.map((day) => {
          const inMonth = monthStart(day) === month
          const selected = day === value
          const disabled = isDisabled(day)
          return (
            <button
              key={day}
              type="button"
              role="gridcell"
              data-day={day}
              tabIndex={day === focused ? 0 : -1}
              aria-selected={selected}
              aria-current={day === today ? 'date' : undefined}
              aria-label={dayLabel.format(parseIsoDate(day))}
              disabled={disabled}
              onClick={() => {
                setFocused(day)
                onChange(day)
              }}
              className={cn(
                'relative mx-auto flex size-9 items-center justify-center rounded-full text-sm tabular-nums transition-[background-color,color,transform] duration-150 ease-out outline-none',
                'hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-95 motion-reduce:active:scale-100',
                !inMonth && 'text-muted-foreground/60',
                disabled && 'pointer-events-none text-muted-foreground/35 line-through decoration-muted-foreground/30',
                selected &&
                  'bg-brand font-semibold text-brand-foreground shadow-[0_2px_8px_-2px_var(--brand)] hover:bg-brand',
              )}
            >
              {parseIsoDate(day).getDate()}
              {day === today && (
                <span
                  aria-hidden
                  className={cn('absolute bottom-1 size-1 rounded-full', selected ? 'bg-brand-foreground' : 'bg-brand')}
                />
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function HeaderButton({
  title,
  active,
  onClick,
  children,
}: {
  title: string
  active?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={cn(
        'rounded-lg px-1.5 py-0.5 text-sm font-semibold tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50',
        active && 'bg-muted',
      )}
    >
      {children}
    </button>
  )
}

function PickButton({
  selected,
  disabled,
  onClick,
  children,
}: {
  selected: boolean
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'h-10 rounded-lg text-sm tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-35',
        selected && 'bg-brand font-semibold text-brand-foreground hover:bg-brand',
      )}
    >
      {children}
    </button>
  )
}

function MonthButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="flex size-8 items-center justify-center rounded-lg text-muted-foreground transition-colors duration-150 outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-30 [&_svg]:size-4"
    >
      {children}
    </button>
  )
}

/** Pierwszy dzień miesiąca daty, RRRR-MM-01. */
function monthStart(value: string): string {
  return `${value.slice(0, 7)}-01`
}

function shiftMonth(value: string, months: number): string {
  const date = parseIsoDate(value)
  const day = date.getDate()
  date.setDate(1)
  date.setMonth(date.getMonth() + months)
  // 31 stycznia + miesiąc = ostatni dzień lutego, a nie 3 marca.
  const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  date.setDate(Math.min(day, lastDay))
  return toIsoDate(date)
}

/** 0 = poniedziałek ... 6 = niedziela. */
function weekdayIndex(value: string): number {
  return (parseIsoDate(value).getDay() + 6) % 7
}

/** Sześć pełnych tygodni od poniedziałku przed pierwszym dniem miesiąca - siatka nie skacze między miesiącami. */
function monthGrid(month: string): string[] {
  const first = addDays(month, -weekdayIndex(month))
  return Array.from({ length: 42 }, (_, index) => addDays(first, index))
}
