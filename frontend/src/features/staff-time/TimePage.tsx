import { useState } from 'react'
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  NfcIcon,
  PrinterIcon,
  SettingsIcon,
} from 'lucide-react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { formatDay, formatPeriodDay } from '@/features/staff-schedule/schedule-utils'
import {
  downloadTimeExport,
  printTimeSheet,
  useTimeAttention,
  useTimeCards,
  useTimeDecisions,
  useTimeDepartments,
} from '@/features/staff-time/api'
import { DayView, WithoutCard } from '@/features/staff-time/DayView'
import { PeriodGrid } from '@/features/staff-time/PeriodGrid'
import { SegmentDialog, SimulatorDialog, TimeSettingsDialog } from '@/features/staff-time/TimeDialogs'
import { SymfoniaExportDialog } from '@/features/staff-time/SymfoniaExportDialog'
import { LEGEND, STATUS_STYLES, hm, range, shift, type TimeView } from '@/features/staff-time/time-utils'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { useCan } from '@/features/auth/api'
import { ApiError } from '@/lib/api'
import { addDays, formatMonth, todayIso } from '@/lib/dates'
import type { StoreSummary, TimeCardRow, TimeCards, TimeSegment } from '@/lib/types'
import { cn } from '@/lib/utils'

const VIEWS: { value: TimeView; label: string }[] = [
  { value: 'dzien', label: 'Dzień' },
  { value: 'tydzien', label: 'Tydzień' },
  { value: 'miesiac', label: 'Miesiąc' },
]

export function TimePage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Czas pracy" />
        <FullPageMessage title="Brak sklepu" description="Karty czasu pracy prowadzi się w każdym sklepie." inline />
      </>
    )
  }
  return <StoreTime key={store.id} store={store} />
}

/** Stan w adresie: {@code ?dzial=&widok=dzien|tydzien|miesiac&data=RRRR-MM-DD}. */
function StoreTime({ store }: { store: StoreSummary }) {
  const [params, setParams] = useSearchParams()
  const departments = useTimeDepartments(store.id)
  const view = (VIEWS.find((item) => item.value === params.get('widok'))?.value ?? 'dzien') as TimeView
  const date = params.get('data') ?? todayIso()
  const list = departments.data ?? []
  const department =
    list.find((item) => item.id === params.get('dzial')) ?? list.find((item) => item.canEdit) ?? list[0]
  const { from, to } = range(view, date)
  const cards = useTimeCards(department ? { storeId: store.id, departmentId: department.id, from, to } : null)
  const [dialog, setDialog] = useState<'simulator' | 'settings' | 'symfonia' | null>(null)
  const [picked, setPicked] = useState<{ row: TimeCardRow; segment: TimeSegment } | null>(null)
  const decisions = useTimeDecisions(store.id, department?.id ?? '')
  const attention = useTimeAttention(store.id, useCan('STAFF_TIME', 'EDIT'))
  const waiting = new Map(attention.data?.departments.map((item) => [item.departmentId, item.count]))
  const month = date.slice(0, 7)
  // Poprzedni okres pokazany do czasu odpowiedzi: dzień rysuje się z jego własnej daty.
  const stale = cards.isPlaceholderData
  const shownDate = stale ? (cards.data?.days[0]?.date ?? date) : date
  // Który plik się właśnie robi: 'sheet' (karty działu), 'xlsx' albo id pracownika.
  const [busy, setBusy] = useState<string | null>(null)

  const run = (key: string, action: () => Promise<void>, failure: string) => {
    setBusy(key)
    action()
      .catch((cause) => toast.error(cause instanceof ApiError ? cause.message : failure))
      .finally(() => setBusy(null))
  }
  const printSheet = (employeeId?: string) =>
    department &&
    run(
      employeeId ?? 'sheet',
      () => printTimeSheet(store.id, department.id, month, employeeId),
      'Nie udało się przygotować karty ewidencji',
    )

  const balance = (row: TimeCardRow) =>
    decisions.decide.mutate(
      {
        employeeId: row.employeeId,
        kind: 'BALANCE',
        start: `${date}T00:00:00`,
        end: `${addDays(date, 1)}T00:00:00`,
        decision: 'OFFSET',
      },
      {
        onSuccess: ({ id }) =>
          toast.success(`Zrównoważono: ${row.name}`, {
            action: { label: 'Cofnij', onClick: () => decisions.remove.mutate(id) },
          }),
        onError: (cause) => toast.error(cause instanceof ApiError ? cause.message : 'Nie udało się zrównoważyć dnia'),
      },
    )

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

  const title =
    view === 'dzien'
      ? formatDay(date)
      : view === 'tydzien'
        ? `${formatPeriodDay(from)} – ${formatPeriodDay(to)}`
        : formatMonth(date.slice(0, 7))

  return (
    <>
      <span data-wide-page hidden />
      <PageHeader
        title="Czas pracy"
        description={`${storeLabel(store)} · karty czasu pracy z odbić kart NFC`}
        actions={
          <>
            {department && (
              <Button
                type="button"
                variant="outline"
                loading={busy === 'sheet'}
                title={`Karty ewidencji czasu pracy działu ${department.name} za ${formatMonth(month)}, kartka na osobę`}
                onClick={() => printSheet()}
              >
                <PrinterIcon aria-hidden />
                Karty ewidencji
              </Button>
            )}
            <Button
              type="button"
              variant="outline"
              title={`Plik do Symfonii R2Płatnik za ${formatMonth(month)} z instrukcją importu`}
              onClick={() => setDialog('symfonia')}
            >
              <FileTextIcon aria-hidden />
              Symfonia {formatMonth(month)}
            </Button>
            <Button
              type="button"
              variant="outline"
              loading={busy === 'xlsx'}
              title={`Godziny wszystkich pracowników sklepu za ${formatMonth(month)} z numerami kart RCP`}
              onClick={() =>
                run('xlsx', () => downloadTimeExport(store.id, month), 'Nie udało się przygotować pliku Excel')
              }
            >
              <FileSpreadsheetIcon aria-hidden />
              Excel
            </Button>
            {cards.data?.canSimulate && (
              <Button type="button" variant="outline" onClick={() => setDialog('simulator')}>
                <NfcIcon aria-hidden />
                Symulator odbić
              </Button>
            )}
            {cards.data?.canConfigure && (
              <Button type="button" variant="outline" onClick={() => setDialog('settings')}>
                <SettingsIcon aria-hidden />
                Ustawienia
              </Button>
            )}
          </>
        }
      />
      <SymfoniaExportDialog
        open={dialog === 'symfonia'}
        storeId={store.id}
        month={month}
        onClose={() => setDialog(null)}
      />
      {departments.isError ? (
        <FullPageMessage
          title="Nie udało się wczytać działów"
          action={{ label: 'Spróbuj ponownie', onClick: () => void departments.refetch() }}
          inline
        />
      ) : !departments.data ? (
        <div className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" aria-busy />
      ) : !department ? (
        <FullPageMessage
          title="Nie ma jeszcze działów"
          description="Działy i stanowiska zakłada się w zakładce Pracownicy."
          inline
        />
      ) : (
        <div className="grid gap-3">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <Tabs
              label="Dział"
              items={list.map((item) => ({ value: item.id, label: item.name, count: waiting.get(item.id) }))}
              value={department.id}
              onChange={(value) => update({ dzial: value })}
            />
            <Tabs label="Widok" items={VIEWS} value={view} onChange={(value) => update({ widok: value })} />
            <div className="ml-auto flex items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label="Wcześniej"
                onClick={() => update({ data: shift(view, date, -1) })}
              >
                <ChevronLeftIcon aria-hidden />
              </Button>
              <span className="min-w-44 text-center text-sm font-semibold tabular-nums">{title}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label="Później"
                onClick={() => update({ data: shift(view, date, 1) })}
              >
                <ChevronRightIcon aria-hidden />
              </Button>
              {!(from <= todayIso() && todayIso() <= to) && (
                <Button type="button" variant="ghost" size="sm" onClick={() => update({ data: null })}>
                  Dziś
                </Button>
              )}
            </div>
          </div>
          {cards.isError ? (
            <FullPageMessage
              title="Nie udało się wczytać kart czasu pracy"
              action={{ label: 'Spróbuj ponownie', onClick: () => void cards.refetch() }}
              inline
            />
          ) : !cards.data ? (
            <div className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" aria-busy />
          ) : (
            <div
              aria-busy={stale || undefined}
              className={cn(
                'grid gap-3 transition-opacity duration-150 ease-out',
                stale && 'pointer-events-none opacity-60',
              )}
            >
              <Summary cards={cards.data} />
              {view === 'dzien' ? (
                <DayView
                  cards={cards.data}
                  date={shownDate}
                  onSegment={(row, segment) => setPicked({ row, segment })}
                  onBalance={balance}
                  balancing={decisions.decide.isPending}
                  onPrint={(row) => printSheet(row.employeeId)}
                  printing={busy}
                />
              ) : (
                <PeriodGrid
                  cards={cards.data}
                  onDay={(day) => update({ widok: 'dzien', data: day })}
                  onPrint={(row) => printSheet(row.employeeId)}
                  printing={busy}
                />
              )}
              <WithoutCard people={cards.data.withoutCard} />
              <Legend tolerance={cards.data.toleranceMinutes} canDecide={cards.data.canDecide} view={view} />
              {cards.data.canSimulate && dialog === 'simulator' && (
                <SimulatorDialog
                  open
                  storeId={store.id}
                  cards={cards.data}
                  date={view === 'dzien' ? date : todayIso() < to ? todayIso() : to}
                  onClose={() => setDialog(null)}
                />
              )}
            </div>
          )}
          <SegmentDialog
            storeId={store.id}
            departmentId={department.id}
            row={picked?.row ?? null}
            segment={picked ? (currentSegment(cards.data, picked) ?? picked.segment) : null}
            onClose={() => setPicked(null)}
          />
          <TimeSettingsDialog open={dialog === 'settings'} storeId={store.id} onClose={() => setDialog(null)} />
        </div>
      )}
    </>
  )
}

/** Wybrany odcinek ze świeżych kart: okno widzi decyzję i dołączony plik zaraz po odświeżeniu danych. */
function currentSegment(cards: TimeCards | undefined, picked: { row: TimeCardRow; segment: TimeSegment }) {
  return cards?.rows
    .find((row) => row.employeeId === picked.row.employeeId && row.civil === picked.row.civil)
    ?.days.flatMap((day) => day.segments)
    .find(
      (segment) =>
        segment.part === picked.segment.part &&
        segment.start === picked.segment.start &&
        segment.end === picked.segment.end,
    )
}

function Tabs<T extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string
  items: { value: T; label: string; count?: number }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <nav aria-label={label} className="flex max-w-full gap-1 overflow-x-auto rounded-xl bg-muted p-1">
      {items.map((item) => (
        <button
          key={item.value}
          type="button"
          aria-current={item.value === value ? 'page' : undefined}
          onClick={() => onChange(item.value)}
          className={cn(
            'shrink-0 rounded-lg px-3.5 py-1.5 text-sm font-medium whitespace-nowrap text-muted-foreground outline-none transition-[background-color,color,box-shadow,transform] duration-150 ease-out hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-[0.97]',
            item.value === value && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
          )}
        >
          {item.label}
          {item.count ? (
            <span
              aria-label={`${item.count} dni do sprawdzenia`}
              className="ml-1.5 rounded-full bg-amber-300 px-1.5 text-[0.7rem] leading-4.5 font-semibold text-amber-950 tabular-nums dark:bg-amber-500 dark:text-amber-950"
            >
              {item.count}
            </span>
          ) : null}
        </button>
      ))}
    </nav>
  )
}

/** Kafelki sum całego widoku: najważniejsze to minuty czekające na decyzję i braki. */
function Summary({ cards }: { cards: TimeCards }) {
  // Czas pracy to karty umowy o pracę; godziny zlecenia idą osobnym kafelkiem.
  const sum = (key: 'planned' | 'worked' | 'overtime' | 'pending' | 'shortfall', civil = false) =>
    cards.rows.filter((row) => row.civil === civil).reduce((total, row) => total + row.totals[key], 0)
  const tiles = [
    { label: 'Czas pracy', value: hm(sum('worked')), hint: `grafik ${hm(sum('planned'))}` },
    { label: 'Nadgodziny przyjęte', value: hm(sum('overtime')), tone: 'text-sky-700 dark:text-sky-300' },
    {
      label: 'Czeka na decyzję',
      value: hm(sum('pending')),
      tone: sum('pending') > 0 ? 'text-amber-700 dark:text-amber-300' : undefined,
    },
    {
      label: 'Braki',
      value: hm(sum('shortfall')),
      tone: sum('shortfall') > 0 ? 'text-red-700 dark:text-red-300' : undefined,
    },
    ...(cards.rows.some((row) => row.civil)
      ? [{ label: 'Godziny zlecenia', value: hm(sum('worked', true)), hint: `grafik ${hm(sum('planned', true))}` }]
      : []),
  ]
  return (
    <div className={cn('grid grid-cols-2 gap-2', tiles.length > 4 ? 'lg:grid-cols-5' : 'lg:grid-cols-4')}>
      {tiles.map((tile) => (
        <div key={tile.label} className="rounded-xl border bg-card px-4 py-3">
          <div className="text-xs text-muted-foreground">{tile.label}</div>
          <div className={cn('text-xl font-semibold tracking-tight tabular-nums', tile.tone)}>{tile.value}</div>
          {tile.hint && <div className="text-xs text-muted-foreground tabular-nums">{tile.hint}</div>}
        </div>
      ))}
    </div>
  )
}

function Legend({ tolerance, canDecide, view }: { tolerance: number; canDecide: boolean; view: TimeView }) {
  return (
    <div className="grid gap-2 text-xs text-muted-foreground">
      {view === 'dzien' && (
        <ul className="flex flex-wrap gap-x-4 gap-y-1">
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="h-3 w-5 rounded-sm border-2 border-dashed border-foreground/40" />
            Grafik
          </li>
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="h-3 w-0.5 rounded-full bg-foreground" />
            Odbicie karty
          </li>
          {LEGEND.map((status) => (
            <li key={status} className="flex items-center gap-1.5">
              <span aria-hidden className={cn('size-3 rounded-sm', STATUS_STYLES[status].bar)} />
              {STATUS_STYLES[status].label}
            </li>
          ))}
        </ul>
      )}
      <p>
        {tolerance > 0
          ? `Tolerancja działu: ${tolerance} min. Krótsze odcinki poza grafikiem się nie liczą, a krótsze braki liczą się jak w grafiku.`
          : 'Bez tolerancji: każda minuta poza grafikiem czeka na decyzję. Tolerancję ustawia się w ustawieniach działu w Grafiku.'}{' '}
        {canDecide
          ? view === 'dzien'
            ? 'Kliknij odcinek z obwódką, żeby zdecydować.'
            : 'Kliknij dzień, żeby zobaczyć odcinki i zdecydować.'
          : 'Decyzje podejmuje kierownik działu.'}
      </p>
    </div>
  )
}
