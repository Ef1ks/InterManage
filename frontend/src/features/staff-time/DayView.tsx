import { Fragment, useEffect, useState } from 'react'

import { PrinterIcon, ScaleIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { ABSENCE } from '@/features/staff-schedule/schedule-utils'
import {
  STATUS_STYLES,
  byPosition,
  rowKey,
  canBalance,
  clockOf,
  decidable,
  hm,
  minuteOf,
} from '@/features/staff-time/time-utils'
import type { TimeCardRow, TimeCards, TimeSegment } from '@/lib/types'
import { cn } from '@/lib/utils'

type Props = {
  cards: TimeCards
  date: string
  onSegment: (row: TimeCardRow, segment: TimeSegment) => void
  /** Brak i czas poza grafikiem tego dnia znoszą się (spóźnienie odrobione dłuższym zostaniem). */
  onBalance: (row: TimeCardRow) => void
  balancing: boolean
  /** Karta ewidencji czasu pracy pracownika za miesiąc tego dnia. */
  onPrint: (row: TimeCardRow) => void
  /** Id pracownika, którego karta właśnie się przygotowuje. */
  printing: string | null
}

// Na wąskim ekranie oś godzin przewija się w bok, a nazwisko i sumy zostają na miejscu (jak w tygodniu i miesiącu).
const STICKY_LEFT = 'sticky left-0 z-10 bg-card'
const STICKY_RIGHT = 'sticky right-0 z-10 border-l bg-card'

const DEFAULT_FROM = 5 * 60
const DEFAULT_TO = 22 * 60

/**
 * Dzień: każdy pracownik ma pasek godzin. Ramka przerywana to zmiana z grafiku, kolorowe odcinki to czas z odbić
 * (praca, poza grafikiem, braki), kreski to odbicia. Odcinek do decyzji jest przyciskiem.
 */
export function DayView({ cards, date, onSegment, onBalance, balancing, onPrint, printing }: Props) {
  const rows = cards.rows.map((row) => ({ row, day: row.days.find((day) => day.date === date) }))
  const now = useNow()
  const nowMinute = date === localDate(now) ? now.getHours() * 60 + now.getMinutes() : null

  // Oś obejmuje grafik i odbicia wszystkich, z godziną zapasu, a bez danych 5-22.
  const points = rows.flatMap(({ day }) =>
    day
      ? [
          ...(day.plannedStart ? [minuteOf(day.plannedStart, date)] : []),
          ...(day.plannedEnd ? [minuteOf(day.plannedEnd, date)] : []),
          ...(day.civilEnd ? [minuteOf(day.civilEnd, date)] : []),
          ...day.punches.map((punch) => minuteOf(punch.at, date)),
        ]
      : [],
  )
  const from = Math.min(DEFAULT_FROM, ...points.map((point) => Math.floor(point / 60) * 60 - 60))
  const to = Math.max(DEFAULT_TO, ...points.map((point) => Math.ceil(point / 60) * 60 + 60))
  const span = to - from
  const at = (minute: number) => `${((minute - from) / span) * 100}%`
  const width = (start: number, end: number) => `${((end - start) / span) * 100}%`
  const hours = Array.from({ length: span / 60 + 1 }, (_, index) => from + index * 60)

  if (rows.length === 0) return <Empty />

  return (
    <div className="overflow-x-auto rounded-xl border bg-card">
      <div className="min-w-[48rem]">
        <div className="grid grid-cols-[11rem_1fr_7rem] border-b text-xs text-muted-foreground">
          <div className={cn(STICKY_LEFT, 'px-3 py-2 font-medium')}>Pracownik</div>
          <div className="relative h-8">
            {/* Skrajne godziny stoją na krawędzi osi i chowałyby się do połowy pod przyklejonymi kolumnami. */}
            {hours.slice(1, -1).map((hour) => (
              <span key={hour} className="absolute top-2 -translate-x-1/2 tabular-nums" style={{ left: at(hour) }}>
                {(hour / 60) % 24}
              </span>
            ))}
          </div>
          <div className={cn(STICKY_RIGHT, 'px-3 py-2 text-right font-medium')}>Czas / grafik</div>
        </div>
        {byPosition(rows.map((item) => ({ ...item, positionName: item.row.positionName }))).map((group) => (
          <Fragment key={group.position}>
            <PositionHeader position={group.position} count={group.rows.length} />
            {group.rows.map(({ row, day }) => (
              <div key={rowKey(row)} className="grid grid-cols-[11rem_1fr_7rem] border-b last:border-b-0">
                <div className={cn(STICKY_LEFT, 'min-w-0 px-3 py-2.5')}>
                  <div className="flex items-center gap-1">
                    <div className="truncate text-sm font-medium" title={row.name}>
                      {row.name}
                    </div>
                    {!row.civil && (
                      <PrintSheetButton row={row} onPrint={onPrint} loading={printing === row.employeeId} />
                    )}
                  </div>
                  <div className="truncate text-xs text-muted-foreground">
                    {row.civil && <CivilBadge className="mr-1.5" />}
                    {day?.plannedStart && day.plannedEnd
                      ? `grafik ${clockOf(day.plannedStart)}–${clockOf(day.plannedEnd)}`
                      : 'bez grafiku'}
                    {day?.civilStart &&
                      day.civilEnd &&
                      ` · zlecenie ${clockOf(day.civilStart)}–${clockOf(day.civilEnd)}`}
                  </div>
                </div>
                <div className="relative my-2 h-10">
                  {hours.map((hour) => (
                    <span
                      key={hour}
                      aria-hidden
                      className="absolute inset-y-0 w-px bg-border"
                      style={{ left: at(hour) }}
                    />
                  ))}
                  {day?.plannedStart && day.plannedEnd && (
                    <span
                      aria-hidden
                      className="absolute inset-y-0 rounded-md border-2 border-dashed border-foreground/40"
                      style={{
                        left: at(minuteOf(day.plannedStart, date)),
                        width: width(minuteOf(day.plannedStart, date), minuteOf(day.plannedEnd, date)),
                      }}
                    />
                  )}
                  {day?.civilStart && day.civilEnd && (
                    // Godziny zlecenia: odbicia w nich nie należą do karty umowy o pracę.
                    <span
                      title={`Zlecenie ${clockOf(day.civilStart)}–${clockOf(day.civilEnd)}: poza kartą umowy o pracę`}
                      className="absolute inset-y-0 rounded-md border-2 border-dotted border-teal-600 bg-teal-200/50 dark:border-teal-400 dark:bg-teal-900/50"
                      style={{
                        left: at(minuteOf(day.civilStart, date)),
                        width: width(minuteOf(day.civilStart, date), minuteOf(day.civilEnd, date)),
                      }}
                    />
                  )}
                  {day?.segments.map((segment) => {
                    const start = minuteOf(segment.start, date)
                    const end = minuteOf(segment.end, date)
                    const style = STATUS_STYLES[segment.status]
                    const label = `${clockOf(segment.start)}–${clockOf(segment.end)} · ${hm(segment.minutes)} · ${style.label}`
                    const shared = cn(
                      'absolute top-1.5 bottom-1.5 rounded-sm',
                      style.bar,
                      segment.status === 'REJECTED' &&
                        'bg-[repeating-linear-gradient(135deg,transparent_0_4px,rgb(0_0_0/0.18)_4px_6px)]',
                    )
                    // Odcinek z kilku minut ma na osi ułamek piksela: najmniejsza szerokość, żeby dało się go zobaczyć
                    // i kliknąć.
                    const position = { left: at(start), width: width(start, end), minWidth: '0.75rem' }
                    return decidable(segment) && cards.canDecide ? (
                      <button
                        key={segment.part + segment.start}
                        type="button"
                        title={label}
                        aria-label={`${row.name}: ${label}`}
                        onClick={() => onSegment(row, segment)}
                        className={cn(
                          shared,
                          'z-10 cursor-pointer outline-none transition-[box-shadow] hover:ring-2 hover:ring-foreground/60 focus-visible:ring-3 focus-visible:ring-ring',
                          segment.status === 'PENDING' && 'ring-2 ring-amber-700 dark:ring-amber-300',
                        )}
                        style={position}
                      />
                    ) : (
                      <span key={segment.part + segment.start} title={label} className={shared} style={position} />
                    )
                  })}
                  {day?.openSince && (
                    <span
                      title={`Wejście ${clockOf(day.openSince)} bez wyjścia`}
                      // Trwające wejście: pasek wygasa w stronę „teraz”, bez pulsowania, które przez cały dzień
                      // ściągałoby wzrok z boku ekranu.
                      className={cn(
                        'absolute top-1.5 bottom-1.5 rounded-sm',
                        day.missingExit
                          ? 'bg-red-300 dark:bg-red-800'
                          : 'bg-linear-to-r from-emerald-400 to-emerald-400/25 dark:from-emerald-600 dark:to-emerald-600/25',
                      )}
                      style={{
                        left: at(minuteOf(day.openSince, date)),
                        width: width(
                          minuteOf(day.openSince, date),
                          day.missingExit || nowMinute === null
                            ? minuteOf(day.openSince, date) + 30
                            : Math.max(nowMinute, minuteOf(day.openSince, date) + 5),
                        ),
                      }}
                    />
                  )}
                  {day?.breakSince && (
                    <span
                      title={`Na przerwie od ${clockOf(day.breakSince)}`}
                      className="absolute top-1.5 bottom-1.5 rounded-sm bg-[repeating-linear-gradient(135deg,var(--color-zinc-300)_0_4px,transparent_4px_8px)] dark:bg-[repeating-linear-gradient(135deg,var(--color-zinc-600)_0_4px,transparent_4px_8px)]"
                      style={{
                        left: at(minuteOf(day.breakSince, date)),
                        width: width(
                          minuteOf(day.breakSince, date),
                          Math.max(nowMinute ?? 0, minuteOf(day.breakSince, date) + 5),
                        ),
                      }}
                    />
                  )}
                  {day?.punches.map((punch) => (
                    <span
                      key={punch.id}
                      title={`${punch.breakStart ? 'Wyjście na przerwę' : 'Odbicie'} ${clockOf(punch.at)}${punch.source === 'SIMULATED' ? ' (symulator)' : ''}`}
                      className="absolute -top-1 -bottom-1 w-0.5 rounded-full bg-foreground"
                      style={{ left: at(minuteOf(punch.at, date)) }}
                    />
                  ))}
                  {nowMinute !== null && nowMinute >= from && nowMinute <= to && (
                    <span aria-hidden className="absolute -inset-y-2 w-px bg-red-500" style={{ left: at(nowMinute) }} />
                  )}
                  {day?.absence && (
                    <span className="absolute inset-y-0 left-2 flex items-center text-xs font-medium text-muted-foreground">
                      {ABSENCE[day.absence].label}
                    </span>
                  )}
                </div>
                <div className={cn(STICKY_RIGHT, 'px-3 py-2.5 text-right tabular-nums')}>
                  <div className="text-sm font-semibold">
                    {hm(day?.worked ?? 0)}
                    <span className="font-normal text-muted-foreground"> / {hm(day?.planned ?? 0)}</span>
                  </div>
                  {day && day.pending > 0 && (
                    <div className="text-xs font-medium text-amber-700 dark:text-amber-300">
                      czeka {hm(day.pending)}
                    </div>
                  )}
                  {day && day.shortfall > 0 && (
                    <div className="text-xs font-medium text-red-700 dark:text-red-300">brak {hm(day.shortfall)}</div>
                  )}
                  {day?.missingExit && (
                    <div className="text-xs font-medium text-red-700 dark:text-red-300">brak wyjścia</div>
                  )}
                  {day && cards.canDecide && canBalance(day) && (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="mt-1"
                      disabled={balancing}
                      title="Brak i czas poza grafikiem tego dnia znoszą się, np. przyszedł godzinę później i został godzinę dłużej"
                      onClick={() => onBalance(row)}
                    >
                      <ScaleIcon aria-hidden />
                      Zrównoważ
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </Fragment>
        ))}
      </div>
    </div>
  )
}

/** Nagłówek stanowiska nad jego pracownikami (jak w grafiku bez własnych grup). */
function PositionHeader({ position, count }: { position: string; count: number }) {
  return (
    <div className="border-b bg-muted text-xs font-semibold">
      <span className="sticky left-0 inline-flex items-baseline gap-2 px-3 py-1.5">
        {position}
        <span className="font-normal text-muted-foreground">{count}</span>
      </span>
    </div>
  )
}

/** Osoby z działu bez karty czasu pracy w tym okresie, z powodem, żeby nie znikały bez słowa. */
export function WithoutCard({ people }: { people: TimeCards['withoutCard'] }) {
  if (people.length === 0) return null
  return (
    <div className="rounded-xl border border-dashed p-3 text-sm">
      <p className="text-muted-foreground">
        Bez karty czasu pracy w tym okresie (karty prowadzi się dla umów o pracę, art. 149 KP; umowy wpisuje się w
        Kadrach). Tych osób nie ma też w symulatorze odbić.
      </p>
      <ul className="mt-2 flex flex-wrap gap-2">
        {people.map((person) => (
          <li key={person.name + person.positionName} className="rounded-md bg-muted px-2 py-1">
            <span className="font-medium">{person.name}</span>
            <span className="text-muted-foreground">
              {' '}
              · {person.positionName} · {person.reason}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function Empty() {
  return (
    <p className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      W tym dziale nie ma pracowników z umową o pracę w tym okresie. Karty czasu pracy prowadzi się dla umów o pracę
      (art. 149 KP), a umowy wpisuje się w Kadrach.
    </p>
  )
}

function localDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** Bieżąca chwila odświeżana co minutę (kreska „teraz” i trwające wejście). */
function useNow() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}

/** Przy nazwisku w wierszu zlecenia: godziny z samych odbić, bez karty ewidencji i decyzji. */
export function CivilBadge({ className }: { className?: string }) {
  return (
    <span
      title="Ewidencja godzin zlecenia: czas z samych odbić, bez nadgodzin, braków i decyzji"
      className={cn(
        'inline-block shrink-0 rounded-sm bg-teal-200 px-1 text-[0.6875rem] leading-4 font-medium text-teal-950 dark:bg-teal-900/70 dark:text-teal-100',
        className,
      )}
    >
      zlecenie
    </span>
  )
}

/** Ikona przy nazwisku: karta ewidencji czasu pracy tej osoby za miesiąc. */
export function PrintSheetButton({
  row,
  onPrint,
  loading,
}: {
  row: TimeCardRow
  onPrint: (row: TimeCardRow) => void
  loading: boolean
}) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-xs"
      loading={loading}
      aria-label={`Drukuj kartę ewidencji: ${row.name}`}
      title="Karta ewidencji czasu pracy za miesiąc"
      className="shrink-0 text-muted-foreground"
      onClick={() => onPrint(row)}
    >
      <PrinterIcon aria-hidden />
    </Button>
  )
}
