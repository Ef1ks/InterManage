import { Fragment, type KeyboardEvent } from 'react'
import { ClipboardPasteIcon, CopyIcon, TriangleAlertIcon } from 'lucide-react'

import {
  absenceOf,
  cellKind,
  cellsByDate,
  clock,
  dayMonth,
  dayNumber,
  dayOffReason,
  formatPay,
  hoursLabel,
  weekdayLabel,
} from '@/features/staff-schedule/schedule-utils'
import type { Schedule, ScheduleCell, ScheduleDay, ScheduleRow } from '@/lib/types'
import { todayIso } from '@/lib/dates'
import { cn } from '@/lib/utils'

const KIND_STYLES: Record<string, string> = {
  morning: 'bg-sky-200 text-sky-950 dark:bg-sky-800 dark:text-sky-50',
  afternoon: 'bg-indigo-300 text-indigo-950 dark:bg-indigo-700 dark:text-indigo-50',
  store: 'bg-teal-300 text-teal-950 dark:bg-teal-700 dark:text-teal-50',
  leave: 'bg-violet-300 text-violet-950 dark:bg-violet-700 dark:text-violet-50',
  absent: 'bg-zinc-300 text-zinc-800 dark:bg-zinc-600 dark:text-zinc-50',
  wn: 'bg-amber-300 text-amber-950 dark:bg-amber-700 dark:text-amber-50',
  w5: 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50',
  timeoff: 'bg-blue-300 text-blue-950 dark:bg-blue-700 dark:text-blue-50',
}

/** Kreski, a nie sam szary: szare są już nieobecności i dni zamknięte. */
const OUT_OF_CONTRACT = 'bg-[repeating-linear-gradient(135deg,var(--muted)_0_4px,var(--border)_4px_8px)]'

const LEGEND = [
  { kind: 'morning', label: 'Rano' },
  { kind: 'afternoon', label: 'Popołudnie' },
  { kind: 'store', label: 'W innym sklepie' },
  { kind: 'leave', label: 'U: urlop wypoczynkowy, UŻ: na żądanie' },
  { kind: 'absent', label: 'Inna nieobecność z Kadr' },
  { kind: 'wn', label: 'WN: wolne za niedzielę lub święto, pod spodem jego data' },
  { kind: 'w5', label: 'W5: wolne za pięciodniowy tydzień pracy' },
  { kind: 'timeoff', label: 'CW: czas wolny za nadgodziny (w rogu: przy krótszej zmianie)' },
]

const HEAD = 'sticky top-0 z-20 border-r border-b border-border bg-card'
/** Grubsza kreska przed poniedziałkiem: tydzień widać bez liczenia kolumn. */
const WEEK_START = 'border-l-2 border-l-foreground/20'
const isMonday = (date: string) => weekdayLabel(date) === 'pn'
const NAME = 'sticky left-0 z-10 border-r border-b border-border bg-card'
// Na telefonie przyklejone sumy i nazwiska zajęłyby cały ekran, więc zostają tylko nazwiska.
const SUM = 'z-10 border-b border-l border-border bg-card md:sticky md:right-0'

type Props = {
  schedule: Schedule
  /** Puste, gdy grafik jest tylko do odczytu. */
  onCell?: (row: ScheduleRow, day: ScheduleDay, target: HTMLElement) => void
  onClear?: (row: ScheduleRow, day: ScheduleDay) => void
  /** Puste bez prawa do ustawień: święto czynne to decyzja o całym sklepie. */
  onHoliday?: (day: ScheduleDay) => void
  /** Kopiowanie grafiku między pracownikami; {@code copied} to wiersz, od którego skopiowano. */
  copied?: ScheduleRow | null
  onCopy?: (row: ScheduleRow) => void
  onPaste?: (row: ScheduleRow) => void
  /** Dni urlopu wypoczynkowego, które zostały w tym roku, po pracowniku; tylko z dostępem do Kadr. */
  leaveLeft?: Map<string, number | null>
  /** Stawka za godzinę przy pracowniku; tylko gdy dział liczy wynagrodzenia, a oglądający układa grafik. */
  onRate?: (row: ScheduleRow) => void
}

export function ScheduleLegend() {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
      {LEGEND.map(({ kind, label }) => (
        <li key={kind} className="flex items-center gap-1.5">
          <span aria-hidden className={cn('size-3 rounded-sm', KIND_STYLES[kind])} />
          {label}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <span aria-hidden className={cn('size-3 rounded-sm', OUT_OF_CONTRACT)} />
        Brak umowy
      </li>
      <li className="flex items-center gap-1.5">
        <span aria-hidden className="size-3 rounded-sm ring-2 ring-red-600 ring-inset dark:ring-red-400" />
        Do sprawdzenia
      </li>
    </ul>
  )
}

/** Wiersze to pracownicy, kolumny dni. Nagłówek, nazwiska i sumy zostają na miejscu przy przewijaniu. */
export function ScheduleGrid({
  schedule,
  onCell,
  onClear,
  onHoliday,
  copied,
  onCopy,
  onPaste,
  leaveLeft,
  onRate,
}: Props) {
  const { days, rows, positions } = schedule
  const team = rows.filter((row) => !row.guest)
  const guests = rows.filter((row) => row.guest)
  const custom = schedule.groups.length > 0
  const groups = (
    custom
      ? [
          { key: 'ungrouped', name: 'Bez grupy', rows: team.filter((row) => row.groupId === null) },
          ...schedule.groups.map((group) => ({
            key: group.id,
            name: group.name,
            rows: team.filter((row) => row.groupId === group.id),
          })),
        ]
      : positions.map((position) => ({
          key: position.id,
          name: position.name,
          rows: team.filter((r) => r.positionId === position.id),
        }))
  ).filter((group) => group.rows.length > 0)
  if (guests.length > 0) groups.push({ key: 'guests', name: 'Z innych sklepów i działów', rows: guests })
  const editable = onCell !== undefined
  const showSums = rows.some((row) => row.monthMinutes !== null)
  const staffing = positions.filter(
    (position) => position.minCount !== null || position.targetCount !== null || position.counts.some(Boolean),
  )
  const order = groups.flatMap((group) => group.rows)
  const today = todayIso()

  const move = (event: KeyboardEvent<HTMLElement>, row: number, column: number) => {
    const step = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }[event.key]
    if (step) {
      event.preventDefault()
      document.querySelector<HTMLElement>(`[data-cell="${row + step[0]}:${column + step[1]}"]`)?.focus()
    }
    if ((event.key === 'Delete' || event.key === 'Backspace') && onClear) {
      event.preventDefault()
      onClear(order[row], days[column])
    }
  }

  return (
    <div className="max-h-[calc(100dvh-15rem)] min-h-64 overflow-auto rounded-xl ring-1 ring-foreground/10">
      <table className="w-full border-separate border-spacing-0 text-xs">
        <thead>
          <tr>
            <th
              scope="col"
              className={cn(HEAD, 'left-0 z-30 w-40 min-w-40 px-3 text-left text-sm font-medium md:w-48 md:min-w-48')}
            >
              Pracownik
            </th>
            {days.map((day) => (
              <th
                key={day.date}
                scope="col"
                title={day.holiday ? `${day.holiday}${day.closed ? '' : ', sklep czynny'}` : undefined}
                aria-current={day.date === today ? 'date' : undefined}
                className={cn(
                  HEAD,
                  'min-w-7 py-1 text-center font-normal',
                  isMonday(day.date) && WEEK_START,
                  day.closed && 'bg-muted',
                )}
              >
                <DayHead day={day} today={day.date === today} onToggle={onHoliday && (() => onHoliday(day))} />
              </th>
            ))}
            {showSums && (
              <th
                scope="col"
                className={cn(
                  HEAD,
                  'z-30 w-32 min-w-32 border-r-0 border-l px-2 text-right font-medium whitespace-nowrap md:right-0',
                )}
              >
                Godziny <span className="font-normal text-muted-foreground">/ wymiar</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {groups.map((group) => (
            <Fragment key={group.key}>
              <tr>
                <th
                  scope="colgroup"
                  className={cn(NAME, 'bg-muted px-3 py-1.5 text-left text-xs font-medium text-muted-foreground')}
                >
                  {group.name}
                  <span className="ml-1.5 font-normal tabular-nums opacity-70">{group.rows.length}</span>
                </th>
                <td colSpan={days.length + (showSums ? 1 : 0)} className="border-b border-border bg-muted" />
              </tr>
              {group.rows.map((row) => {
                const cells = cellsByDate(row)
                const flagged = new Set(row.warnings.map((warning) => warning.date))
                const rowIndex = order.indexOf(row)
                return (
                  <tr key={row.employeeId + (row.civil ? ':zlecenie' : '')}>
                    {row.civil ? (
                      <th
                        scope="row"
                        title={`${row.name}: godziny na umowie zlecenia, poza wymiarem umowy o pracę`}
                        className={cn(NAME, 'px-3 py-1 text-left font-normal')}
                      >
                        <span className="block pl-3 text-xs font-medium text-muted-foreground">↳ zlecenie</span>
                        <span className="sr-only">{row.name}</span>
                      </th>
                    ) : (
                      <th scope="row" className={cn(NAME, 'px-3 py-1 text-left font-normal')}>
                        <span className="flex items-center gap-1.5 text-sm font-medium">
                          <span className="max-w-36 truncate" title={row.name}>
                            {row.name}
                          </span>
                          {row.warnings.length > 0 && (
                            <span
                              title={`Do sprawdzenia: ${row.warnings.length}`}
                              className="flex shrink-0 items-center gap-0.5 text-xs text-red-700 tabular-nums dark:text-red-300"
                            >
                              <TriangleAlertIcon aria-hidden className="size-3" />
                              {row.warnings.length}
                            </span>
                          )}
                          {onCopy && !row.guest && (
                            <RowCopy
                              row={row}
                              source={copied?.employeeId === row.employeeId}
                              pasting={copied !== null && copied !== undefined}
                              onCopy={() => onCopy(row)}
                              onPaste={() => onPaste?.(row)}
                            />
                          )}
                        </span>
                        <span className="block text-muted-foreground">
                          {[
                            row.guest ? `sklep ${row.homeStore}` : null,
                            row.guest || custom ? row.positionName : null,
                            row.contract,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                        </span>
                      </th>
                    )}
                    {days.map((day, column) => (
                      <td
                        key={day.date}
                        title={row.outOfContractDays.includes(day.date) ? 'Brak umowy tego dnia' : undefined}
                        className={cn(
                          'h-10 border-r border-b border-border p-0',
                          isMonday(day.date) && WEEK_START,
                          day.closed && 'bg-muted',
                          row.outOfContractDays.includes(day.date) && OUT_OF_CONTRACT,
                        )}
                      >
                        <DayCell
                          cell={visible(cells.get(day.date), day)}
                          flagged={flagged.has(day.date)}
                          label={`${row.name}${row.civil ? ' (zlecenie)' : ''}, ${day.date}${row.outOfContractDays.includes(day.date) ? ', brak umowy' : ''}`}
                          position={`${rowIndex}:${column}`}
                          first={rowIndex === 0 && column === 0}
                          onClick={onCell && ((target) => onCell(row, day, target))}
                          onKeyDown={editable ? (event) => move(event, rowIndex, column) : undefined}
                        />
                      </td>
                    ))}
                    {showSums && (
                      <Sums
                        row={row}
                        leaveLeft={leaveLeft?.get(row.employeeId)}
                        onRate={schedule.showsPay && onRate ? () => onRate(row) : undefined}
                      />
                    )}
                  </tr>
                )
              })}
            </Fragment>
          ))}
        </tbody>
        {staffing.length > 0 && (
          // Obsada przyklejona do dołu: przy długiej liście widać ją razem z dniami, które się układa.
          <tfoot className="sticky bottom-0 z-20">
            <tr>
              <th
                scope="colgroup"
                className={cn(
                  NAME,
                  'z-30 border-t-2 border-t-foreground/15 bg-muted px-3 py-1.5 text-left text-xs font-medium text-muted-foreground',
                )}
              >
                Obsada na dzień
              </th>
              <td
                colSpan={days.length + (showSums ? 1 : 0)}
                className="border-t-2 border-b border-border border-t-foreground/15 bg-muted"
              />
            </tr>
            {staffing.map((position) => (
              <tr key={position.id}>
                <th scope="row" className={cn(NAME, 'z-30 px-3 py-1 text-left font-normal')}>
                  <span className="block truncate text-sm font-medium">{position.name}</span>
                  {(position.minCount !== null || position.targetCount !== null) && (
                    <span className="block text-muted-foreground">
                      {[
                        position.minCount !== null ? `min. ${position.minCount}` : null,
                        position.targetCount !== null ? `cel ${position.targetCount}` : null,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  )}
                </th>
                {days.map((day, index) => (
                  <StaffingCell
                    key={day.date}
                    day={day}
                    count={position.counts[index]}
                    minCount={position.minCount}
                    targetCount={position.targetCount}
                  />
                ))}
                {showSums && <td className={SUM} />}
              </tr>
            ))}
          </tfoot>
        )}
      </table>
    </div>
  )
}

function RowCopy({
  row,
  source,
  pasting,
  onCopy,
  onPaste,
}: {
  row: ScheduleRow
  source: boolean
  pasting: boolean
  onCopy: () => void
  onPaste: () => void
}) {
  const paste = pasting && !source
  const Icon = paste ? ClipboardPasteIcon : CopyIcon
  const label = paste ? `Wklej grafik do: ${row.name}` : `Kopiuj grafik: ${row.name}`
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={source}
      title={label}
      onClick={paste ? onPaste : onCopy}
      className={cn(
        'ml-auto inline-flex size-6 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
        source && 'bg-foreground text-background hover:bg-foreground hover:text-background',
        paste && 'text-foreground ring-1 ring-foreground/30',
      )}
    >
      <Icon aria-hidden className="size-3.5" />
    </button>
  )
}

/**
 * Obsada stanowiska w dniu: „jest/cel”, a cel to docelowa, bez niej minimum. Kolor mówi, jak daleko do celu;
 * dzień zamknięty ma samą liczbę.
 */
function StaffingCell({
  day,
  count,
  minCount,
  targetCount,
}: {
  day: ScheduleDay
  count: number
  minCount: number | null
  targetCount: number | null
}) {
  const goal = day.closed ? null : (targetCount ?? minCount)
  const short = goal !== null && minCount !== null && count < minCount
  const belowTarget = goal !== null && !short && targetCount !== null && count < targetCount
  const reached = goal !== null && count >= goal
  const title = short
    ? `Brakuje ${minCount! - count} do minimum`
    : belowTarget
      ? `Brakuje ${targetCount! - count} do celu`
      : reached && targetCount !== null && count > targetCount
        ? `${count - targetCount} ponad cel`
        : undefined
  return (
    <td
      title={title}
      className={cn(
        'h-10 border-r border-b border-border bg-card p-0 text-center tabular-nums',
        isMonday(day.date) && WEEK_START,
        day.closed && 'bg-muted',
      )}
    >
      {goal === null ? (
        count > 0 && <span className="text-muted-foreground">{count}</span>
      ) : (
        <span
          className={cn(
            'flex size-full items-center justify-center',
            short && 'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
            belowTarget && 'bg-amber-300 text-amber-950 dark:bg-amber-700 dark:text-amber-50',
            reached && 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50',
          )}
        >
          <span>
            <span className="text-sm font-semibold">{count}</span>
            <span className="text-[0.625rem] opacity-70">/{goal}</span>
          </span>
        </span>
      )}
    </td>
  )
}

function DayHead({ day, today, onToggle }: { day: ScheduleDay; today: boolean; onToggle?: () => void }) {
  const content = (
    <>
      <span
        className={cn(
          'mx-auto flex size-6 items-center justify-center rounded-full text-sm font-medium tabular-nums',
          today && 'bg-foreground text-background',
        )}
      >
        {dayNumber(day.date)}
      </span>
      <span
        className={cn(
          'block text-muted-foreground',
          day.holiday && 'font-medium text-red-700 dark:text-red-300',
          !day.holiday && weekdayLabel(day.date) === 'nd' && 'font-medium text-foreground',
        )}
      >
        {weekdayLabel(day.date)}
      </span>
    </>
  )
  if (!day.holiday || !onToggle) return content
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={`${day.holiday}: ${day.closed ? 'oznacz sklep jako czynny' : 'oznacz sklep jako zamknięty'}`}
      className="w-full rounded-sm outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
    >
      {content}
    </button>
  )
}

/** Nieobecność w zamknięte święto nie jest dniem pracy, więc komórka zostaje pusta. */
function visible(cell: ScheduleCell | undefined, day: ScheduleDay) {
  return cell?.absent && day.closed && !cell.start && !cell.dayOff ? undefined : cell
}

function DayCell({
  cell,
  flagged,
  label,
  position,
  first,
  onClick,
  onKeyDown,
}: {
  cell: ScheduleCell | undefined
  flagged: boolean
  label: string
  position: string
  first: boolean
  onClick?: (target: HTMLElement) => void
  onKeyDown?: (event: KeyboardEvent<HTMLElement>) => void
}) {
  const kind = cellKind(cell)
  const isOff = kind === 'wn' || kind === 'w5'
  const timeOff = cell?.timeOffMinutes ?? null
  const content =
    kind === 'timeoff' ? (
      <>
        <span className="block leading-tight font-semibold">CW</span>
        <span className="block text-[0.625rem] leading-tight">{hoursLabel(timeOff ?? 0)}</span>
      </>
    ) : isOff ? (
      <>
        <span className="block leading-tight font-semibold">{cell!.dayOff}</span>
        {cell!.forDate && <span className="block text-[0.625rem] leading-tight">{dayMonth(cell!.forDate)}</span>}
      </>
    ) : kind === 'empty' ? null : cell!.absent ? (
      <>
        {/* Urlop zużywa tylko dzień z godzinami z grafiku; bez nich symbol jest blady. */}
        <span className={cn('block leading-tight font-semibold', !cell!.start && 'opacity-50')}>
          {absenceOf(cell!).symbol}
        </span>
        {cell!.start && (
          // Godziny z grafiku pod urlopem: dzień urlopu w tych godzinach, nie praca.
          <span className="block text-[0.625rem] leading-tight opacity-80">
            {clock(cell!.start)}–{clock(cell!.end!)}
          </span>
        )}
      </>
    ) : (
      <>
        <span className="block leading-tight">{clock(cell!.start!)}</span>
        <span className="block leading-tight">{clock(cell!.end!)}</span>
        {timeOff !== null && (
          <span
            aria-hidden
            className="absolute top-0 right-0 rounded-bl-sm bg-blue-700 px-0.5 text-[0.5rem] leading-tight font-semibold text-white dark:bg-blue-300 dark:text-blue-950"
          >
            CW
          </span>
        )}
      </>
    )
  const className = cn(
    'relative flex size-full flex-col items-center justify-center tabular-nums',
    KIND_STYLES[kind],
    flagged && 'ring-2 ring-red-600 ring-inset dark:ring-red-400',
  )
  const title = [
    isOff ? dayOffReason(cell!) : null,
    timeOff !== null
      ? `czas wolny za nadgodziny ${hoursLabel(timeOff)} (${cell!.timeOffInitiative === 'EMPLOYER' ? 'z inicjatywy pracodawcy, 1,5 h za 1 h' : 'na wniosek pracownika, 1 h za 1 h'})`
      : null,
    cell?.store ? `Sklep ${cell.store}` : null,
    cell?.absent ? absenceOf(cell).label : null,
    cell?.absent && cell.start
      ? `dzień pracy ${clock(cell.start)}–${clock(cell.end!)} pod urlopem, liczony jako dzień urlopu`
      : null,
    cell?.absent && cell.dayOff ? 'dzień wolny, urlop go nie zużywa' : null,
    cell?.absent && !cell.start && !cell.dayOff ? 'bez zaplanowanej pracy: nie zużywa urlopu ani wymiaru' : null,
  ]
    .filter(Boolean)
    .join(', ')

  if (!onClick) {
    return (
      <div className={className} title={title || undefined}>
        {content}
      </div>
    )
  }
  return (
    <button
      type="button"
      data-cell={position}
      tabIndex={first ? 0 : -1}
      aria-label={label}
      title={title || undefined}
      onClick={(event) => onClick(event.currentTarget)}
      onKeyDown={onKeyDown}
      className={cn(
        className,
        'outline-none hover:brightness-95 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset dark:hover:brightness-110',
        kind === 'empty' && 'hover:bg-accent',
      )}
    >
      {content}
    </button>
  )
}

/** Opis celu miesiąca w podpowiedzi: skąd się bierze i za które dni są godziny z kart. */
function targetTitle(row: ScheduleRow) {
  const lines = [`W miesiącu: ${hoursLabel(row.monthMinutes ?? 0)}`]
  if (row.monthTargetMinutes !== null) {
    lines.push(`Cel miesiąca, żeby okres zamknął się w wymiarze: ${hoursLabel(row.monthTargetMinutes)}`)
  }
  if (row.monthNormMinutes !== null) lines.push(`Wymiar miesiąca: ${hoursLabel(row.monthNormMinutes)}`)
  if (row.carriedMinutes) {
    lines.push(
      `Wcześniejsze miesiące okresu: ${row.carriedMinutes > 0 ? '+' : ''}${hoursLabel(row.carriedMinutes)} względem wymiaru`,
    )
  }
  lines.push(
    row.actualUntil
      ? `Do ${dayMonth(row.actualUntil)} godziny z kart czasu pracy, dalej z grafiku`
      : 'Godziny z grafiku (bez odbić w kartach czasu pracy)',
  )
  return lines.join('\n')
}

/** Szacowana wypłata miesiąca; klik zmienia stawkę za godzinę. */
function PayLine({ row, onRate }: { row: ScheduleRow; onRate: () => void }) {
  if (row.hourlyRate === null) {
    return (
      <button
        type="button"
        onClick={onRate}
        className="block w-full rounded-sm text-right text-xs font-medium text-blue-700 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring dark:text-blue-300"
      >
        + stawka
      </button>
    )
  }
  const title = [
    `Stawka ${formatPay(row.hourlyRate)}/h od tego miesiąca`,
    `Godziny miesiąca razy stawka${row.actualUntil ? ', za dni, które minęły, z kart czasu pracy' : ''}`,
    row.overtimeSupplement
      ? `w tym dodatek 50% za nieodebrane nadgodziny dobowe: ${formatPay(row.overtimeSupplement)}`
      : null,
    'Szacunek: bez dodatków nocnych, za niedziele i święta (100%) i bez składników spoza grafiku. Kliknij, żeby zmienić stawkę.',
  ]
    .filter(Boolean)
    .join('\n')
  return (
    <button
      type="button"
      title={title}
      onClick={onRate}
      className="block w-full rounded-sm text-right font-medium text-emerald-800 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring dark:text-emerald-300"
    >
      {formatPay(row.pay ?? 0)}
    </button>
  )
}

function Sums({ row, leaveLeft, onRate }: { row: ScheduleRow; leaveLeft?: number | null; onRate?: () => void }) {
  if (row.civil) {
    return (
      <td className={cn(SUM, 'px-2 py-1 text-right tabular-nums')}>
        <span className="block font-medium" title="Godziny na umowie zlecenia: nie wchodzą do wymiaru umowy o pracę">
          {hoursLabel(row.monthMinutes ?? 0)}
        </span>
        <span className="block text-muted-foreground">zlecenie</span>
        {onRate && <PayLine row={row} onRate={onRate} />}
      </td>
    )
  }
  const over = row.periodNormMinutes !== null && (row.periodMinutes ?? 0) > row.periodNormMinutes
  const month = row.monthMinutes ?? 0
  const target = row.monthTargetMinutes ?? row.monthNormMinutes
  return (
    <td className={cn(SUM, 'px-2 py-1 text-right tabular-nums')}>
      <span
        title={targetTitle(row)}
        className={cn(
          'block font-medium',
          target !== null && month > target && 'text-red-700 dark:text-red-300',
          target !== null && month === target && 'text-emerald-700 dark:text-emerald-300',
        )}
      >
        {hoursLabel(month)}
        {target !== null && (
          <span className="font-normal text-muted-foreground">
            {' '}
            / {row.monthTargetMinutes !== null && row.monthTargetMinutes !== row.monthNormMinutes && 'cel '}
            {hoursLabel(target)}
          </span>
        )}
      </span>
      {row.carriedMinutes !== null && row.carriedMinutes !== 0 && (
        <span
          title="Saldo wcześniejszych miesięcy okresu względem wymiaru: przesuwa cel tego miesiąca"
          className={cn(
            'block text-muted-foreground',
            row.carriedMinutes > 0 && 'font-medium text-amber-800 dark:text-amber-300',
          )}
        >
          z poprz. {row.carriedMinutes > 0 ? '+' : ''}
          {hoursLabel(row.carriedMinutes)}
        </span>
      )}
      {row.periodNormMinutes !== null ? (
        <span
          title="Okres rozliczeniowy"
          className={cn('block text-muted-foreground', over && 'font-medium text-red-700 dark:text-red-300')}
        >
          okres {hoursLabel(row.periodMinutes ?? 0)} / {hoursLabel(row.periodNormMinutes)}
        </span>
      ) : (
        <span className="block text-muted-foreground">{row.contract ? 'bez wymiaru' : 'brak umowy'}</span>
      )}
      {!!row.dailyOvertimeMinutes && (
        <span
          title={`Nadgodziny dobowe z kart czasu pracy w okresie: ${hoursLabel(row.dailyOvertimeMinutes)}. Nie wchodzą do salda: odbiera się je czasem wolnym (CW) albo płaci dodatek (art. 151¹, 151² KP).${row.timeOffMinutes ? ` Ułożony czas wolny: ${hoursLabel(row.timeOffMinutes)}.` : ''}`}
          className={cn(
            'block font-medium',
            (row.toCompensateMinutes ?? 0) > 0
              ? 'text-blue-700 dark:text-blue-300'
              : (row.toCompensateMinutes ?? 0) < 0
                ? 'text-red-700 dark:text-red-300'
                : 'text-emerald-700 dark:text-emerald-300',
          )}
        >
          {(row.toCompensateMinutes ?? 0) > 0
            ? `nadg. do odebr. ${hoursLabel(row.toCompensateMinutes ?? 0)}`
            : (row.toCompensateMinutes ?? 0) < 0
              ? `CW za dużo ${hoursLabel(-(row.toCompensateMinutes ?? 0))}`
              : `nadg. odebrane ${hoursLabel(row.dailyOvertimeMinutes)}`}
        </span>
      )}
      {!row.dailyOvertimeMinutes && !!row.timeOffMinutes && (
        <span
          title="Czas wolny za nadgodziny ułożony w okresie, a w kartach nie ma nadgodzin dobowych"
          className="block font-medium text-red-700 dark:text-red-300"
        >
          CW bez nadg. {hoursLabel(row.timeOffMinutes)}
        </span>
      )}
      {onRate && <PayLine row={row} onRate={onRate} />}
      {leaveLeft != null && (
        <span
          title="Urlop wypoczynkowy do wykorzystania w tym roku, z Kadr"
          className={cn('block text-muted-foreground', leaveLeft < 0 && 'font-medium text-red-700 dark:text-red-300')}
        >
          urlop: {leaveLeft} {leaveLeft === 1 ? 'dzień' : 'dni'}
        </span>
      )}
    </td>
  )
}
