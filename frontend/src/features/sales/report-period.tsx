import type { ReactNode } from 'react'
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'
import { useSearchParams } from 'react-router'

import { DateField } from '@/components/form'
import { Button } from '@/components/ui/button'
import {
  canShiftForward,
  currentPeriod,
  type DateRange,
  dayCount,
  lastCompleteWeekEnd,
  MAX_PERIOD_DAYS,
  type PeriodMode,
  resolveAnchor,
  shiftAnchor,
} from '@/features/sales/periods'
import { addDays, todayIso } from '@/lib/dates'

/** Okres w adresie strony po polsku. */
export const MODE_PARAMS: Record<PeriodMode, string> = {
  day: 'dzien',
  week: 'tydzien',
  month: 'miesiac',
  custom: 'wlasny',
}

type AnchorMode = Exclude<PeriodMode, 'custom'>

const ANCHOR_LABELS: Record<AnchorMode, string> = {
  day: 'Dzień',
  week: 'Tydzień z dniem',
  month: 'Miesiąc z dniem',
}
const STEP_LABELS: Record<AnchorMode, [string, string]> = {
  day: ['Poprzedni dzień', 'Następny dzień'],
  week: ['Poprzedni tydzień', 'Następny tydzień'],
  month: ['Poprzedni miesiąc', 'Następny miesiąc'],
}

export type ParamChanges = Record<string, string | string[] | null>

/** Stan raportu w adresie: {@code null} usuwa parametr, tablica zapisuje go powtórzony. */
export function useReportParams() {
  const [params, setParams] = useSearchParams()
  const update = (changes: ParamChanges) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => {
          next.delete(key)
          if (Array.isArray(value)) value.forEach((item) => next.append(key, item))
          else if (value != null) next.set(key, value)
        })
        return next
      },
      { replace: true },
    )
  return { params, update }
}

/** Raporty sięgają najwyżej do wczoraj: dzisiejsza sprzedaż jeszcze trwa. */
export function lastCompleteDay(savedUntil: string | null | undefined) {
  const yesterday = addDays(todayIso(), -1)
  return savedUntil && savedUntil < yesterday ? savedUntil : yesterday
}

export function rangeError(range: DateRange, maxDays = MAX_PERIOD_DAYS) {
  if (range.from > range.to) return 'Data „od” jest późniejsza niż „do”.'
  if (dayCount(range) > maxDays) return `Okres może mieć najwyżej ${maxDays} dni.`
  return null
}

export type ReportPeriod<Mode extends PeriodMode = PeriodMode> = {
  mode: Mode
  anchor: string
  custom: DateRange
  customError: string | null
  range: DateRange
  lastDate: string
}

/**
 * Okres raportu z adresu ({@code okres}, {@code data}, {@code od}, {@code do}). {@code customDays}: ile dni ma własny
 * okres, zanim użytkownik go zmieni. Bez {@code modes} dostępne są wszystkie tryby.
 */
export function useReportPeriod<Mode extends PeriodMode = PeriodMode>({
  params,
  lastDate,
  defaultMode,
  customDays,
  maxDays,
  modes,
}: {
  params: URLSearchParams
  lastDate: string
  defaultMode: Mode
  customDays: number
  maxDays?: number
  modes?: readonly Mode[]
}): ReportPeriod<Mode> {
  const allowed = modes ?? (Object.keys(MODE_PARAMS) as Mode[])
  const mode = allowed.find((key) => MODE_PARAMS[key] === params.get('okres')) ?? defaultMode
  const anchor = resolveAnchor(mode, params.get('data') ?? lastDate, lastDate)
  const custom: DateRange = {
    from: params.get('od') ?? addDays(lastDate, 1 - customDays),
    to: params.get('do') ?? lastDate,
  }
  return {
    mode,
    anchor,
    custom,
    customError: mode === 'custom' ? rangeError(custom, maxDays) : null,
    range: currentPeriod(mode, anchor, custom, lastDate),
    lastDate,
  }
}

/**
 * Wybór okresu: pola od-do przy własnym okresie, inaczej data ze strzałkami. {@code max} to najpóźniejsza data do
 * wyboru (domyślnie ostatni dzień danych), {@code children} stoją w tym samym rzędzie.
 */
export function PeriodPicker({
  period,
  update,
  max,
  lastDateLabel = 'Ostatni dzień sprzedaży',
  children,
}: {
  period: ReportPeriod
  update: (changes: ParamChanges) => void
  max?: string
  lastDateLabel?: string
  children?: ReactNode
}) {
  const { mode, anchor, custom, customError, lastDate } = period
  const latest = max ?? lastDate
  return (
    <div className="flex flex-wrap items-start gap-3">
      {mode === 'custom' ? (
        <>
          <DateField
            label="Od"
            value={custom.from}
            onChange={(from) => update({ od: from, do: custom.to })}
            max={latest}
            shortcuts={[]}
            className="w-full sm:w-64"
          />
          <DateField
            label="Do"
            value={custom.to}
            onChange={(to) => update({ od: custom.from, do: to })}
            max={latest}
            shortcuts={[{ label: lastDateLabel, value: lastDate }]}
            error={customError ?? undefined}
            className="w-full sm:w-64"
          />
        </>
      ) : (
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <Button
            variant="outline"
            size="icon-lg"
            aria-label={STEP_LABELS[mode][0]}
            title={STEP_LABELS[mode][0]}
            onClick={() => update({ data: shiftAnchor(mode, anchor, -1) })}
          >
            <ChevronLeftIcon />
          </Button>
          <DateField
            label={ANCHOR_LABELS[mode]}
            value={anchor}
            onChange={(data) => update({ data })}
            max={latest}
            shortcuts={
              mode === 'week'
                ? [{ label: 'Ostatni pełny tydzień', value: lastCompleteWeekEnd(lastDate) }]
                : [{ label: lastDateLabel, value: lastDate }]
            }
            className="min-w-0 flex-1 sm:w-72 sm:flex-none"
          />
          <Button
            variant="outline"
            size="icon-lg"
            aria-label={STEP_LABELS[mode][1]}
            title={STEP_LABELS[mode][1]}
            disabled={!canShiftForward(mode, anchor, lastDate)}
            onClick={() => update({ data: shiftAnchor(mode, anchor, 1) })}
          >
            <ChevronRightIcon />
          </Button>
        </div>
      )}
      {children}
    </div>
  )
}
