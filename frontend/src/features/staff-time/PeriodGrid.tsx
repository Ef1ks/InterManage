import { Fragment } from 'react'

import { CivilBadge, Empty, PrintSheetButton } from '@/features/staff-time/DayView'
import { ABSENCE, dayNumber, weekdayLabel } from '@/features/staff-schedule/schedule-utils'
import { byPosition, dayTone, hm, rowKey } from '@/features/staff-time/time-utils'
import { todayIso } from '@/lib/dates'
import type { TimeCardRow, TimeCards, TimeDayCard } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Dzisiejsza kolumna: delikatne tło od nagłówka do ostatniego wiersza, żeby w miesiącu od razu było widać „dziś”. */
const TODAY_COLUMN = 'bg-muted/60'

type Props = {
  cards: TimeCards
  onDay: (date: string) => void
  /** Karta ewidencji czasu pracy pracownika za miesiąc. */
  onPrint: (row: TimeCardRow) => void
  printing: string | null
}

/**
 * Tydzień i miesiąc: w komórce przepracowany czas (godziny i minuty), kolor mówi, co wymaga uwagi: bursztyn czeka na
 * decyzję, czerwień to braki albo brak wyjścia, niebieski przyjęte nadgodziny. Klik otwiera dzień.
 */
export function PeriodGrid({ cards, onDay, onPrint, printing }: Props) {
  if (cards.rows.length === 0) return <Empty />
  const today = todayIso()
  return (
    <div className="overflow-x-auto rounded-xl border bg-card">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-xs text-muted-foreground">
            <th className="sticky left-0 z-10 min-w-40 border-b bg-card px-3 py-2 text-left font-medium">Pracownik</th>
            {cards.days.map((day) => (
              <th
                key={day.date}
                title={day.holiday ?? undefined}
                className={cn(
                  'min-w-12 border-b px-1 py-2 text-center font-medium',
                  weekdayLabel(day.date) === 'pn' && 'border-l-2',
                  day.holiday && 'text-red-700 dark:text-red-300',
                  day.date === today && TODAY_COLUMN,
                )}
              >
                <span
                  className={cn(
                    'mx-auto flex size-6 items-center justify-center rounded-full text-sm tabular-nums',
                    day.date === today && 'bg-foreground text-background',
                  )}
                >
                  {dayNumber(day.date)}
                </span>
                <span className="block">{weekdayLabel(day.date)}</span>
              </th>
            ))}
            <th className="sticky right-0 z-10 min-w-36 border-b border-l bg-card px-3 py-2 text-right font-medium">
              Czas <span className="font-normal">/ grafik</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {byPosition(cards.rows).map((group) => (
            <Fragment key={group.position}>
              <tr>
                <th
                  scope="rowgroup"
                  colSpan={cards.days.length + 2}
                  className="border-b bg-muted p-0 text-left text-xs font-semibold"
                >
                  <span className="sticky left-0 inline-flex items-baseline gap-2 px-3 py-1.5">
                    {group.position}
                    <span className="font-normal text-muted-foreground">{group.rows.length}</span>
                  </span>
                </th>
              </tr>
              {group.rows.map((row) => (
                <tr key={rowKey(row)}>
                  <th scope="row" className="sticky left-0 z-10 border-b bg-card px-3 py-2 text-left font-normal">
                    <div className="flex max-w-56 items-center gap-1">
                      <div className="truncate font-medium" title={row.name}>
                        {row.name}
                      </div>
                      {!row.civil && (
                        <PrintSheetButton row={row} onPrint={onPrint} loading={printing === row.employeeId} />
                      )}
                    </div>
                    {row.civil && <CivilBadge />}
                  </th>
                  {row.days.map((day) => (
                    <td
                      key={day.date}
                      className={cn(
                        'border-b p-0.5',
                        weekdayLabel(day.date) === 'pn' && 'border-l-2',
                        day.date === today && TODAY_COLUMN,
                      )}
                    >
                      <DayCell day={day} onClick={() => onDay(day.date)} />
                    </td>
                  ))}
                  <td className="sticky right-0 z-10 border-b border-l bg-card px-3 py-2 text-right tabular-nums">
                    <div className="font-semibold">
                      {hm(row.totals.worked)}
                      <span className="font-normal text-muted-foreground"> / {hm(row.totals.planned)}</span>
                    </div>
                    {row.totals.overtime > 0 && (
                      <div className="text-xs text-sky-700 dark:text-sky-300">nadgodziny {hm(row.totals.overtime)}</div>
                    )}
                    {row.totals.pending > 0 && (
                      <div className="text-xs font-medium text-amber-700 dark:text-amber-300">
                        czeka {hm(row.totals.pending)}
                      </div>
                    )}
                    {row.totals.shortfall > 0 && (
                      <div className="text-xs font-medium text-red-700 dark:text-red-300">
                        brak {hm(row.totals.shortfall)}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DayCell({ day, onClick }: { day: TimeDayCard; onClick: () => void }) {
  const empty = day.worked === 0 && day.planned === 0 && !day.absence && day.punches.length === 0
  const details = [
    day.planned > 0 ? `grafik ${hm(day.planned)}` : null,
    day.worked > 0 ? `czas pracy ${hm(day.worked)}` : null,
    day.overtime > 0 ? `nadgodziny ${hm(day.overtime)}` : null,
    day.pending > 0 ? `czeka na decyzję ${hm(day.pending)}` : null,
    day.shortfall > 0 ? `brak ${hm(day.shortfall)}` : null,
    day.missingExit ? 'brak wyjścia' : null,
    day.absence ? ABSENCE[day.absence].label : null,
  ].filter(Boolean)
  return (
    <button
      type="button"
      onClick={onClick}
      title={details.join(', ') || 'Bez grafiku i odbić'}
      className={cn(
        'flex h-11 w-full flex-col items-center justify-center rounded-md text-xs tabular-nums outline-none transition-[box-shadow,transform] duration-150 ease-out hover:ring-2 hover:ring-foreground/30 focus-visible:ring-3 focus-visible:ring-ring active:scale-[0.96]',
        dayTone(day),
        empty && 'text-muted-foreground',
      )}
    >
      {day.absence && day.worked === 0 ? (
        <span className="font-semibold">{ABSENCE[day.absence].symbol}</span>
      ) : day.worked > 0 || day.pending > 0 || day.openSince ? (
        <>
          <span className="font-semibold">{hm(day.worked)}</span>
          {day.planned > 0 && day.worked !== day.planned && (
            <span className="text-[0.625rem] opacity-75">/ {hm(day.planned)}</span>
          )}
        </>
      ) : day.planned > 0 ? (
        <span className="text-muted-foreground">0:00</span>
      ) : (
        <span aria-hidden>·</span>
      )}
    </button>
  )
}
