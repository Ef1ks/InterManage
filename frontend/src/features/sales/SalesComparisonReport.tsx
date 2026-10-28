import { createContext, Fragment, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  ChevronDownIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronsDownUpIcon,
  ChevronUpIcon,
  ChartColumnIcon,
} from 'lucide-react'
import { useSearchParams } from 'react-router'

import { BackLink } from '@/components/BackLink'
import { DateField, MultiComboboxField, SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type ReportKind,
  type SalesChartTarget,
  salesComparisonKey,
  useDeliCostsOverview,
  useSalesComparison,
  useSalesNomenclatures,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { salesPaths } from '@/features/sales/paths'
import {
  canShiftForward,
  COMPARE_LABELS,
  type CompareMode,
  compareModes,
  comparePeriods,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  lastCompleteWeekEnd,
  MAX_PERIOD_DAYS,
  PERIOD_MODES,
  type PeriodMode,
  type Periods,
  resolveAnchor,
  resolveCompareMode,
  shiftAnchor,
} from '@/features/sales/periods'
import { addDays, todayIso } from '@/lib/dates'
import type { SalesComparison, SalesComparisonLevel, SalesComparisonRow, SalesFigures } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Okres w adresie strony po polsku, żeby link do raportu otwierał ten sam widok. */
const MODE_PARAMS: Record<PeriodMode, string> = {
  day: 'dzien',
  week: 'tydzien',
  month: 'miesiac',
  custom: 'wlasny',
}
const COMPARE_PARAMS: Record<CompareMode, string> = {
  year: 'rok',
  previous: 'poprzedni',
  week: 'tydzien',
  custom: 'wlasny',
}
const ANCHOR_LABELS: Record<Exclude<PeriodMode, 'custom'>, string> = {
  day: 'Dzień',
  week: 'Tydzień z dniem',
  month: 'Miesiąc z dniem',
}
const STEP_LABELS: Record<Exclude<PeriodMode, 'custom'>, [string, string]> = {
  day: ['Poprzedni dzień', 'Następny dzień'],
  week: ['Poprzedni tydzień', 'Następny tydzień'],
  month: ['Poprzedni miesiąc', 'Następny miesiąc'],
}

type SaleBasis = 'gross' | 'net'

/** Rejon nomenklatury garmażu: koszty garmażu liczą tylko jego sprzedaż. */
const DELI_REGION = '36'

/** Bez filtra nomenklatury (koszty garmażu i tak liczą tylko rejon 36). */
const NO_FILTER: string[] = []

/**
 * Porównanie sprzedaży okresu z innym okresem (domyślnie te same dni tygodnia rok wcześniej), w całości (nie dzień po dniu): na górze sumy sklepu,
 * pod nimi tabela wg nomenklatury. Rejon rozwija się w rodziny, rodzina w podrodziny, a podrodzina w sprzedane
 * artykuły; każdy poziom serwer liczy dopiero po rozwinięciu.
 *
 * {@code kind="deli"} to koszty garmażu: ten sam widok tylko dla rejonu 36, a zakup FIFO zastępuje wartość protokołów
 * reprezentacji z okresu. Kosztu nie da się przypisać do rodzin i artykułów, więc koszt i marża są tylko przy całym
 * garmażu (wiersz 36, Razem, kafelki, wykres rejonu).
 */
export function SalesComparisonReport({ storeId, kind = 'sales' }: { storeId: string; kind?: ReportKind }) {
  const deli = kind === 'deli'
  const overview = useSalesReportsOverview(storeId)
  const costs = useDeliCostsOverview(storeId, deli)
  const [params, setParams] = useSearchParams()
  const [basis, setBasis] = useState<SaleBasis>('gross')

  // Raport najwyżej za wczoraj: dzisiejsza sprzedaż jeszcze trwa (import i tak nie zapisuje dnia niezakończonego).
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday
  const fallbackDay = lastSaleDate
  const mode = (Object.keys(MODE_PARAMS) as PeriodMode[]).find((key) => MODE_PARAMS[key] === params.get('okres')) ?? 'day'
  const anchor = resolveAnchor(mode, params.get('data') ?? fallbackDay, lastSaleDate)
  const custom: DateRange = {
    from: params.get('od') ?? addDays(fallbackDay, -6),
    to: params.get('do') ?? fallbackDay,
  }
  const filter = useMemo(() => (deli ? NO_FILTER : [...new Set(params.getAll('nom'))].sort()), [params, deli])
  const nomenclatures = useSalesNomenclatures(storeId)
  const compare = resolveCompareMode(
    mode,
    (Object.keys(COMPARE_PARAMS) as CompareMode[]).find((key) => COMPARE_PARAMS[key] === params.get('porownaj')) ?? 'year',
  )

  const update = (changes: Record<string, string>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => next.set(key, value))
        return next
      },
      { replace: true },
    )

  const customError = mode === 'custom' ? rangeError(custom) : null
  // Bez wybranych dat własne porównanie startuje od roku temu.
  const yearAgo = comparePeriods(mode, anchor, custom, lastSaleDate, 'year', custom).previous
  const compareCustom: DateRange = {
    from: params.get('por_od') ?? yearAgo.from,
    to: params.get('por_do') ?? yearAgo.to,
  }
  const compareError = compare === 'custom' ? rangeError(compareCustom) : null
  const periods = comparePeriods(mode, anchor, custom, lastSaleDate, compare, compareCustom)
  const valid = !customError && !compareError

  return (
    <>
      {/* Tabela ma dużo kolumn, więc raport bierze całą szerokość strony (AppLayout). */}
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>
        Wszystkie raporty
      </BackLink>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">{deli ? 'Koszty garmażu' : 'Porównanie sprzedaży'}</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>
      {deli && (
        <div className="mb-4 max-w-3xl space-y-1 text-sm text-muted-foreground">
          <p>
            Sprzedaż nomenklatury 36 (garmaż) zestawiona z protokołami reprezentacji, czyli kosztem składników wydanych
            na produkcję garmażu. Składniki są z różnych nomenklatur i nie wiadomo, do którego wyrobu poszły, dlatego
            koszt i marża są tylko przy całym garmażu.
          </p>
          {costs.data && (
            <p className="flex flex-wrap items-center gap-x-2">
              Protokoły z raportu dokumentów
              <ReportFreshness generatedAt={costs.data.documentsReportGeneratedAt} />
            </p>
          )}
        </div>
      )}

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <SegmentedField
            label="Okres porównania"
            options={PERIOD_MODES}
            value={mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <SegmentedField
            label="Porównaj z"
            options={compareModes(mode)}
            value={compare}
            onChange={(next) =>
              // Własny okres zaczyna od tego, z czym porównujemy teraz, żeby było go łatwo przesunąć.
              update(
                next === 'custom' && compare !== 'custom'
                  ? {
                      porownaj: COMPARE_PARAMS[next],
                      por_od: periods.previous.from,
                      por_do: periods.previous.to,
                    }
                  : { porownaj: COMPARE_PARAMS[next] },
              )
            }
          />
          <SegmentedField
            label="Sprzedaż"
            options={[
              { value: 'gross', label: 'Brutto' },
              { value: 'net', label: 'Netto' },
            ]}
            value={basis}
            onChange={setBasis}
          />
        </div>

        <div className="flex flex-wrap items-start gap-3">
          {mode === 'custom' ? (
            <>
              <DateField
                label="Od"
                value={custom.from}
                onChange={(from) => update({ od: from, do: custom.to })}
                max={lastSaleDate}
                shortcuts={[]}
                className="w-full sm:w-64"
              />
              <DateField
                label="Do"
                value={custom.to}
                onChange={(to) => update({ od: custom.from, do: to })}
                max={lastSaleDate}
                shortcuts={
                  lastSaleDate
                    ? [
                        {
                          label: 'Ostatni dzień sprzedaży',
                          value: lastSaleDate,
                        },
                      ]
                    : []
                }
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
                max={lastSaleDate}
                shortcuts={
                  !lastSaleDate
                    ? []
                    : mode === 'week'
                      ? [
                          {
                            label: 'Ostatni pełny tydzień',
                            value: lastCompleteWeekEnd(lastSaleDate),
                          },
                        ]
                      : [
                          {
                            label: 'Ostatni dzień sprzedaży',
                            value: lastSaleDate,
                          },
                        ]
                }
                className="min-w-0 flex-1 sm:w-72 sm:flex-none"
              />
              <Button
                variant="outline"
                size="icon-lg"
                aria-label={STEP_LABELS[mode][1]}
                title={STEP_LABELS[mode][1]}
                disabled={!canShiftForward(mode, anchor, lastSaleDate)}
                onClick={() => update({ data: shiftAnchor(mode, anchor, 1) })}
              >
                <ChevronRightIcon />
              </Button>
            </div>
          )}
          {compare === 'custom' && (
            <>
              <DateField
                label="Porównaj od"
                value={compareCustom.from}
                onChange={(from) => update({ por_od: from, por_do: compareCustom.to })}
                max={lastSaleDate}
                shortcuts={[]}
                className="w-full sm:w-64"
              />
              <DateField
                label="Porównaj do"
                value={compareCustom.to}
                onChange={(to) => update({ por_od: compareCustom.from, por_do: to })}
                max={lastSaleDate}
                shortcuts={[]}
                error={compareError ?? undefined}
                className="w-full sm:w-64"
              />
            </>
          )}
        </div>

        {!deli && (
        <MultiComboboxField
          label="Nomenklatura"
          description="Porównaj tylko wybrane rejony, rodziny albo podrodziny. Puste = cały sklep."
          options={(nomenclatures.data ?? []).map((item) => ({
            value: item.code,
            label: `${displayCode(item.code)} ${item.name.replace(/^\d+_/, '')}`,
            hint: LEVEL_STYLES[item.level].label,
          }))}
          value={filter}
          onChange={(codes) =>
            setParams(
              (current) => {
                const next = new URLSearchParams(current)
                next.delete('nom')
                codes.forEach((code) => next.append('nom', code))
                return next
              },
              { replace: true },
            )
          }
          placeholder={nomenclatures.data?.length === 0 ? 'Brak wgranej nomenklatury' : 'Szukaj po kodzie albo nazwie…'}
          className="max-w-3xl"
        />
        )}

        {valid && (
          <PeriodsSummary
            periods={periods}
            compare={compare}
            firstSaleDate={overview.data?.firstSaleDate ?? null}
            lastSaleDate={lastSaleDate}
            costsCoveredUntil={deli && costs.data ? costs.data.costsCoveredUntil : undefined}
          />
        )}
      </Card>

      {/* Domyślny dzień to ostatni dzień ze sprzedażą, więc liczymy dopiero, gdy wiadomo, który to. */}
      {valid && !overview.isPending && (
        <Comparison
          storeId={storeId}
          kind={kind}
          periods={periods}
          filter={filter}
          compare={compare}
          basis={basis}
          lastSaleDate={lastSaleDate}
        />
      )}
    </>
  )
}

function rangeError(range: DateRange) {
  if (range.from > range.to) return 'Data „od” jest późniejsza niż „do”.'
  if (dayCount(range) > MAX_PERIOD_DAYS) return `Okres może mieć najwyżej ${MAX_PERIOD_DAYS} dni.`
  return null
}

function PeriodsSummary({
  periods,
  compare,
  firstSaleDate,
  lastSaleDate,
  costsCoveredUntil,
}: {
  periods: Periods
  compare: CompareMode
  firstSaleDate: string | null
  lastSaleDate: string | null
  /** Koszty garmażu: do kiedy sięgają wgrane raporty dokumentów ({@code null} = brak); bez wartości pomijane. */
  costsCoveredUntil?: string | null
}) {
  const days = dayCount(periods.current)
  const comparedDays = dayCount(periods.previous)
  const label = COMPARE_LABELS[compare].column
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 border-t pt-4 text-sm tabular-nums">
      <p>
        <span className="text-muted-foreground">Aktualny </span>
        <span className="font-medium">{formatRange(periods.current)}</span>
      </p>
      <p>
        <span className="text-muted-foreground">{label} </span>
        <span className="font-medium">{formatRange(periods.previous)}</span>
      </p>
      <p className="text-muted-foreground">
        {daysLabel(days)} do {daysLabel(comparedDays)}
      </p>
      {days !== comparedDays && (
        <p className="text-amber-700 dark:text-amber-400">Okresy mają różną liczbę dni, sumy nie są porównywalne wprost</p>
      )}
      {lastSaleDate && periods.previous.to > lastSaleDate && (
        <p className="text-amber-700 dark:text-amber-400">
          {label} niepełny: sprzedaż jest wgrana do {formatRange({ from: lastSaleDate, to: lastSaleDate })}
        </p>
      )}
      {lastSaleDate && periods.current.to > lastSaleDate && (
        <p className="text-amber-700 dark:text-amber-400">
          Sprzedaż jest wgrana do {formatRange({ from: lastSaleDate, to: lastSaleDate })}
        </p>
      )}
      {firstSaleDate && periods.previous.from < firstSaleDate && (
        <p className="text-amber-700 dark:text-amber-400">
          {label} niepełny: sprzedaż jest wgrana od {formatRange({ from: firstSaleDate, to: firstSaleDate })}
        </p>
      )}
      {costsCoveredUntil === null && (
        <p className="text-amber-700 dark:text-amber-400">Brak protokołów reprezentacji: wgraj raport dokumentów w Imporcie danych</p>
      )}
      {costsCoveredUntil && (periods.current.to > costsCoveredUntil || periods.previous.to > costsCoveredUntil) && (
        <p className="text-amber-700 dark:text-amber-400">
          Protokoły reprezentacji są wgrane do {formatRange({ from: costsCoveredUntil, to: costsCoveredUntil })}
        </p>
      )}
    </div>
  )
}

/** Wartość sprzedaży wg wybranej podstawy: brutto jak na paragonie albo netto, od którego liczy się marża. */
const saleValue = (figures: SalesFigures, basis: SaleBasis) => (basis === 'gross' ? figures.saleValueGross : figures.saleValueNet)

/** % marży od sprzedaży netto, jak w raporcie systemu sklepu; bez marży (koszty garmażu poniżej rejonu) pusty. */
const marginPercent = (figures: SalesFigures) =>
  figures.marginValue == null || figures.saleValueNet === 0 ? null : (figures.marginValue / figures.saleValueNet) * 100

const percentFormat = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

/** Klucz rozwijanego wiersza: kod nomenklatury jest unikalny na wszystkich poziomach ("01", "0101", "010101"). */
const UNASSIGNED_KEY = 'brak'

const rowKey = (row: SalesComparisonRow, level: SalesComparisonLevel) =>
  level === 'REGION' && row.code === '' ? UNASSIGNED_KEY : row.code

/** Rozwinięta pozycja, której wiersz trzyma poziom wyżej: rodzic rodziny to rejon, rejonu - cały sklep. */
const parentOf = (key: string) => (key === UNASSIGNED_KEY || key.length === 2 ? '' : key.slice(0, -2))
const depthOf = (key: string) => (key === UNASSIGNED_KEY ? 0 : key.length / 2 - 1)

type NomenclatureLevel = Exclude<SalesComparisonLevel, 'PRODUCT'>
const LEVELS: NomenclatureLevel[] = ['REGION', 'FAMILY', 'SUBFAMILY']

/**
 * Kolor poziomu nomenklatury: pasek przy nazwie i tło wiersza (mocniejsze, gdy grupa jest otwarta). Tła są pełne,
 * bo przyklejona kolumna nazwy przykrywa przewijane w bok liczby. Czerwień zostaje dla spadków.
 */
const LEVEL_STYLES: Record<NomenclatureLevel, { label: string; row: string; open: string; bar: string; dot: string }> = {
  REGION: {
    label: 'Rejon',
    row: 'bg-sky-50 hover:bg-sky-100 dark:bg-sky-950 dark:hover:bg-sky-900',
    open: 'bg-sky-100 hover:bg-sky-100 dark:bg-sky-900 dark:hover:bg-sky-900',
    bar: 'before:bg-sky-500',
    dot: 'bg-sky-500',
  },
  FAMILY: {
    label: 'Rodzina',
    row: 'bg-violet-50 hover:bg-violet-100 dark:bg-violet-950 dark:hover:bg-violet-900',
    open: 'bg-violet-100 hover:bg-violet-100 dark:bg-violet-900 dark:hover:bg-violet-900',
    bar: 'before:bg-violet-500',
    dot: 'bg-violet-500',
  },
  SUBFAMILY: {
    label: 'Podrodzina',
    row: 'bg-teal-50 hover:bg-teal-100 dark:bg-teal-950 dark:hover:bg-teal-900',
    open: 'bg-teal-100 hover:bg-teal-100 dark:bg-teal-900 dark:hover:bg-teal-900',
    bar: 'before:bg-teal-500',
    dot: 'bg-teal-500',
  },
}

/** Pasek koloru poziomu przy lewej krawędzi komórki nazwy. */
const barClass = 'before:absolute before:inset-y-0 before:left-0 before:w-1'

type Tree = {
  storeId: string
  kind: ReportKind
  periods: Periods
  filter: string[]
  basis: SaleBasis
  expanded: ReadonlySet<string>
  toggle: (key: string) => void
  /** Po wczytaniu poziomu przeliczamy przypięte wiersze i ściągę (zmieniła się tabela). */
  onLevelLoaded: () => void
  openChart: (target: SalesChartTarget) => void
}

const TreeContext = createContext<Tree | null>(null)

function useTree() {
  const tree = useContext(TreeContext)
  if (!tree) throw new Error('LevelRows poza tabelą porównania')
  return tree
}

/** Wymiary tabeli, do których przypięte wiersze dopasowują swoje kolumny. */
type TableLayout = {
  headerHeight: number
  tableWidth: number
  widths: number[]
}

/** Pozycja ściągi: każdy rejon, a pod rozwiniętymi rejonami ich rodziny i podrodziny. */
type NavItem = {
  key: string
  row: SalesComparisonRow
  level: NomenclatureLevel
  depth: number
}

function Comparison({
  storeId,
  kind,
  periods,
  filter,
  compare,
  basis,
  lastSaleDate,
}: {
  storeId: string
  kind: ReportKind
  periods: Periods
  filter: string[]
  compare: CompareMode
  basis: SaleBasis
  lastSaleDate: string
}) {
  const deli = kind === 'deli'
  const { data, isPending, isError, refetch, isPlaceholderData } = useSalesComparison(storeId, kind, periods, filter, '')
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const queryClient = useQueryClient()
  // W kosztach garmażu jest jeden rejon, więc od razu widać jego rodziny.
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set(deli ? [DELI_REGION] : []))
  const [pinned, setPinned] = useState<string[]>([])
  const [current, setCurrent] = useState<string | null>(null)
  const [layout, setLayout] = useState<TableLayout | null>(null)
  const [, setLoadedLevels] = useState(0)
  const cardRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const tableRef = useRef<HTMLTableElement>(null)
  const headRef = useRef<HTMLTableSectionElement>(null)
  const frame = useRef(0)
  const expandedRef = useRef(expanded)

  useEffect(() => {
    expandedRef.current = expanded
  }, [expanded])

  /**
   * Które rozwinięte wiersze przypiąć pod nagłówkiem: przodkowie pierwszego wiersza widocznego pod nimi
   * (rejon › rodzina › podrodzina). Przypięte wiersze zasłaniają kilka wierszy, więc liczymy kilka razy,
   * aż łańcuch się ustali. Przy okazji: która pozycja ściągi jest bieżąca.
   */
  const updatePinned = useCallback(() => {
    cancelAnimationFrame(frame.current)
    frame.current = requestAnimationFrame(() => {
      const container = scrollRef.current
      const head = headRef.current
      if (!container || !head) return
      const rows = Array.from(container.querySelectorAll<HTMLTableRowElement>('tbody tr[data-ancestors]'))
      const headerBottom = head.getBoundingClientRect().bottom
      let chain: string[] = []
      let row: HTMLTableRowElement | undefined
      for (let attempt = 0; attempt < 4; attempt++) {
        const probe = headerBottom + chain.reduce((sum, key) => sum + rowHeight(container, key), 0)
        row = firstRowBelow(rows, probe)
        const next = row?.dataset.ancestors ? row.dataset.ancestors.split(',') : []
        const key = row?.dataset.key
        if (row && key && expandedRef.current.has(key) && row.getBoundingClientRect().top < probe) next.push(key)
        if (next.join() === chain.join()) break
        chain = next
      }
      setPinned((previous) => (previous.join() === chain.join() ? previous : chain))
      setCurrent(chain.at(-1) ?? row?.dataset.key ?? null)
    })
  }, [])

  const onLevelLoaded = useCallback(() => {
    setLoadedLevels((count) => count + 1)
    updatePinned()
  }, [updatePinned])

  useEffect(() => {
    const table = tableRef.current
    const head = headRef.current
    if (!table || !head) return
    const measure = () => {
      const nameHead = head.querySelector<HTMLElement>('th[data-column="name"]')
      const leafHeads = Array.from(head.querySelectorAll<HTMLElement>('th[data-leaf]'))
      if (!nameHead) return
      setLayout({
        headerHeight: head.offsetHeight,
        tableWidth: table.offsetWidth,
        widths: [nameHead, ...leafHeads].map((cell) => cell.getBoundingClientRect().width),
      })
      updatePinned()
    }
    const observer = new ResizeObserver(measure)
    observer.observe(table)
    return () => observer.disconnect()
  }, [updatePinned, data])

  useEffect(() => () => cancelAnimationFrame(frame.current), [])

  const toggle = useCallback(
    (key: string) => {
      setExpanded((previous) => {
        const next = new Set(previous)
        if (next.has(key)) {
          // Zwinięcie zwija też wszystko pod spodem, żeby ponowne rozwinięcie nie otwierało całego drzewa.
          next.forEach((open) => open.startsWith(key) && next.delete(open))
        } else {
          next.add(key)
        }
        return next
      })
      updatePinned()
    },
    [updatePinned],
  )

  /** Przewija tabelę tak, żeby wiersz stanął tuż pod nagłówkiem i swoimi przypiętymi przodkami. */
  const scrollToRow = useCallback((key: string, smooth: boolean) => {
    const container = scrollRef.current
    const row = container?.querySelector<HTMLElement>(`tbody tr[data-key="${key}"]`)
    const head = headRef.current
    const card = cardRef.current
    if (!container || !row || !head || !card) return
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const behavior = smooth && !reduceMotion ? 'smooth' : 'auto'
    // Tabela ma własny pasek przewijania, więc najpierw strona: tabela ma być na ekranie, pod paskiem aplikacji.
    const cardTop = card.getBoundingClientRect().top
    if (cardTop < 0 || cardTop > window.innerHeight / 3) window.scrollBy({ top: cardTop - 72, behavior })
    const ancestorsHeight = (row.dataset.ancestors ? row.dataset.ancestors.split(',') : []).reduce(
      (sum, ancestor) => sum + rowHeight(container, ancestor),
      0,
    )
    container.scrollTo({
      top: container.scrollTop + row.getBoundingClientRect().top - head.getBoundingClientRect().bottom - ancestorsHeight,
      behavior,
    })
  }, [])

  /** Klik w przypięty wiersz: zwija jego grupę i wraca do niej, bo po rozwinięciu mogła być daleko w górze. */
  const collapsePinned = (key: string) => {
    toggle(key)
    requestAnimationFrame(() => scrollToRow(key, false))
  }

  const tree = useMemo<Tree>(
    () => ({
      storeId,
      kind,
      periods,
      filter,
      basis,
      expanded,
      toggle,
      onLevelLoaded,
      openChart: setChart,
    }),
    [storeId, kind, periods, filter, basis, expanded, toggle, onLevelLoaded],
  )

  if (isError && !data) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">Nie udało się policzyć sprzedaży</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }

  /** Wiersze poziomu z pamięci zapytań: każdy rozwinięty wiersz wczytał już swój poziom. */
  const cachedLevel = (parent: string) =>
    queryClient.getQueryData<SalesComparison>(salesComparisonKey(storeId, kind, periods, filter, parent))

  const pinnedRows = pinned.flatMap((key) => {
    const level = cachedLevel(parentOf(key))
    const row = level?.rows.find((candidate) => rowKey(candidate, level.level) === key)
    return row ? [{ key, row, depth: depthOf(key), level: LEVELS[depthOf(key)] }] : []
  })

  const navItems: NavItem[] = []
  const collectNav = (parent: string, depth: number) => {
    const level = depth === 0 ? data : cachedLevel(parent)
    if (!level || level.level === 'PRODUCT') return
    for (const row of level.rows) {
      const key = rowKey(row, level.level)
      navItems.push({ key, row, level: level.level, depth })
      if (key !== UNASSIGNED_KEY && expanded.has(key)) collectNav(key, depth + 1)
    }
  }
  collectNav('', 0)

  const empty = data && data.rows.length === 0

  return (
    <div className={cn('transition-opacity duration-150', isPlaceholderData && 'opacity-60')}>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <TotalTile
          label={basis === 'gross' ? 'Sprzedaż brutto' : 'Sprzedaż netto'}
          previous={data && saleValue(data.previousTotal, basis)}
          current={data && saleValue(data.currentTotal, basis)}
          format={formatZloty}
          compare={compare}
        />
        <TotalTile
          label="Marża"
          previous={data?.previousTotal.marginValue ?? undefined}
          current={data?.currentTotal.marginValue ?? undefined}
          format={formatZloty}
          compare={compare}
          note={data && <MarginPercentChange previous={data.previousTotal} current={data.currentTotal} />}
        />
        <TotalTile
          label={deli ? 'Koszt reprezentacji' : 'Zakup FIFO'}
          previous={data?.previousTotal.costValue ?? undefined}
          current={data?.currentTotal.costValue ?? undefined}
          format={formatZloty}
          compare={compare}
          neutral
        />
        <TotalTile
          label="Ilość"
          previous={data?.previousTotal.quantity}
          current={data?.currentTotal.quantity}
          format={formatQuantity}
          compare={compare}
        />
      </div>

      {empty ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">
            {deli ? 'Brak sprzedaży garmażu i protokołów reprezentacji w obu okresach' : 'Brak sprzedaży w obu okresach'}
          </p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {deli
              ? 'Wybierz inny okres albo wgraj raport sprzedaży i raport dokumentów w Imporcie danych.'
              : 'Wybierz inny okres albo wgraj raport sprzedaży w Imporcie danych.'}
          </p>
        </Card>
      ) : (
        <>
          <div className="mb-2 flex min-h-8 flex-wrap items-center justify-between gap-x-4 gap-y-1">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
              <span>Kliknij wiersz, żeby zejść niżej. Otwarte grupy zostają u góry tabeli.</span>
              <span className="flex items-center gap-3" aria-label="Kolory poziomów">
                {LEVELS.map((level) => (
                  <span key={level} className="inline-flex items-center gap-1.5">
                    <span aria-hidden className={cn('size-2.5 rounded-sm', LEVEL_STYLES[level].dot)} />
                    {LEVEL_STYLES[level].label}
                  </span>
                ))}
              </span>
            </div>
            {expanded.size > 0 && (
              <Button variant="ghost" size="sm" onClick={() => setExpanded(new Set())}>
                <ChevronsDownUpIcon />
                Zwiń wszystko
              </Button>
            )}
          </div>
          {/* Od xl ściąga nomenklatury stoi w kolumnie obok tabeli, więc niczego nie zasłania. */}
          <div className="xl:grid xl:grid-cols-[minmax(0,1fr)_16rem] xl:items-start xl:gap-4">
            <Card ref={cardRef} className="overflow-hidden py-0">
              <div ref={scrollRef} onScroll={updatePinned} className="relative max-h-[80svh] overflow-auto overscroll-contain">
                {pinnedRows.length > 0 && layout && (
                  // Wysokość 0: przypięte wiersze leżą nad tabelą i niczego nie przesuwają.
                  <div className="pointer-events-none sticky z-20 h-0" style={{ top: layout.headerHeight }}>
                    <table
                      className="pointer-events-auto table-fixed text-sm tabular-nums shadow-[0_6px_10px_-8px_rgb(0_0_0/0.35)]"
                      style={{ width: layout.tableWidth }}
                    >
                      <colgroup>
                        {layout.widths.map((width, index) => (
                          <col key={index} style={{ width }} />
                        ))}
                      </colgroup>
                      <tbody>
                        {pinnedRows.map((entry) => (
                          <TableRow
                            key={entry.key}
                            className={cn('cursor-pointer', LEVEL_STYLES[entry.level].open)}
                            title="Zwiń i wróć do początku grupy"
                            onClick={() => collapsePinned(entry.key)}
                          >
                            <TableCell
                              className={cn(
                                'sticky left-0 z-10 bg-inherit py-2.5 pr-4 whitespace-normal',
                                barClass,
                                LEVEL_STYLES[entry.level].bar,
                              )}
                              style={{ paddingLeft: indent(entry.depth) }}
                            >
                              <span className="flex items-start gap-1.5">
                                <ChevronRightIcon aria-hidden className="mt-0.5 size-4 shrink-0 rotate-90 text-foreground" />
                                <RowLabel row={entry.row} level={entry.level} />
                              </span>
                            </TableCell>
                            <FigureCells previous={entry.row.previous} current={entry.row.current} basis={basis} strong />
                          </TableRow>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                <table ref={tableRef} className="w-full caption-bottom text-sm tabular-nums">
                  <TableHeader ref={headRef} className="sticky top-0 z-30 bg-card shadow-[0_1px_0_var(--border)]">
                    <TableRow className="hover:bg-transparent">
                      <TableHead rowSpan={2} data-column="name" className="sticky left-0 z-10 min-w-64 bg-card pl-4 align-bottom">
                        Nomenklatura
                      </TableHead>
                      {GROUPS.map((group) => (
                        <TableHead key={group.key} colSpan={group.columns.length} className="h-9 border-l text-center">
                          {group.key === 'sale'
                            ? basis === 'gross'
                              ? 'Sprzedaż brutto zł'
                              : 'Sprzedaż netto zł'
                            : group.key === 'cost' && deli
                              ? 'Reprezentacja zł'
                              : group.label}
                        </TableHead>
                      ))}
                    </TableRow>
                    <TableRow className="hover:bg-transparent">
                      {GROUPS.map((group) =>
                        group.columns.map((column, index) => (
                          <TableHead
                            key={`${group.key}-${column}`}
                            data-leaf
                            className={cn('h-8 text-right text-xs font-normal text-muted-foreground', index === 0 && 'border-l')}
                          >
                            {columnLabel(column, compare)}
                          </TableHead>
                        )),
                      )}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {isPending ? (
                      <LoadingRow depth={0} rows={6} />
                    ) : (
                      <TreeContext.Provider value={tree}>
                        <LevelRows parent="" ancestors={[]} />
                      </TreeContext.Provider>
                    )}
                  </TableBody>
                  {data && (
                    <TableFooter className="sticky bottom-0 z-30 bg-muted shadow-[0_-1px_0_var(--border)]">
                      <TableRow className="hover:bg-transparent">
                        <TableCell className="sticky left-0 z-10 bg-muted py-3 pl-4 font-semibold">Razem</TableCell>
                        <FigureCells previous={data.previousTotal} current={data.currentTotal} basis={basis} strong />
                      </TableRow>
                    </TableFooter>
                  )}
                </table>
              </div>
            </Card>
            <NomenclatureNav items={navItems} current={current} onJump={(key) => scrollToRow(key, true)} />
          </div>
        </>
      )}

      <SalesChartDialog
        storeId={storeId}
        kind={kind}
        target={chart}
        filter={filter}
        basis={basis}
        lastSaleDate={lastSaleDate}
        onClose={() => setChart(null)}
      />
    </div>
  )
}

/** Wysokość wiersza rozwiniętej pozycji w tabeli (przypięta kopia ma tę samą). */
function rowHeight(container: HTMLElement, key: string) {
  return container.querySelector<HTMLElement>(`tbody tr[data-key="${key}"]`)?.offsetHeight ?? 0
}

/** Pierwszy wiersz, który kończy się poniżej linii {@code probe}; wiersze są w kolejności, więc szukamy połówkowo. */
function firstRowBelow(rows: HTMLTableRowElement[], probe: number) {
  let low = 0
  let high = rows.length
  while (low < high) {
    const middle = (low + high) >> 1
    if (rows[middle].getBoundingClientRect().bottom > probe) high = middle
    else low = middle + 1
  }
  return rows[low]
}

/**
 * Grupy kolumn tabeli. Zmiana % stoi pod wartością aktualną, żeby cała tabela mieściła się na ekranie; tylko marża
 * ma osobną kolumnę zmiany (zmiana kwoty i różnica % marży w punktach procentowych).
 */
const GROUPS = [
  { key: 'quantity', label: 'Ilość', columns: ['previous', 'current'] },
  { key: 'cost', label: 'Zakup FIFO zł', columns: ['previous', 'current'] },
  { key: 'sale', label: 'Sprzedaż zł', columns: ['previous', 'current'] },
  {
    key: 'margin',
    label: 'Marża zł',
    columns: ['previous', 'current', 'change'],
  },
] as const

/** Nagłówek kolumny; porównywany okres nazywa się wg trybu porównania (Rok temu, Poprzedni...). */
const columnLabel = (column: (typeof GROUPS)[number]['columns'][number], compare: CompareMode) =>
  column === 'previous' ? COMPARE_LABELS[compare].column : column === 'current' ? 'Aktualny' : 'Zmiana'

/** Numery kolumn liczb, od których zaczyna się grupa (z kreską po lewej). */
const GROUP_STARTS = new Set(
  GROUPS.reduce<number[]>((starts, _, index) => {
    starts.push(index === 0 ? 0 : starts[index - 1] + GROUPS[index - 1].columns.length)
    return starts
  }, []),
)
const NUMBER_COLUMNS = GROUPS.reduce((sum, group) => sum + group.columns.length, 0)
const COLUMN_COUNT = 1 + NUMBER_COLUMNS

type LevelProps = {
  /** Rozwinięta pozycja; pusty tekst = rejony sklepu. */
  parent: string
  /** Artykuły bez nomenklatury (rozwinięty wiersz „Bez nomenklatury”). */
  unassigned?: boolean
  /** Klucze rozwiniętych wierszy nad tym poziomem, od rejonu. */
  ancestors: string[]
}

/** Wiersze jednego poziomu drzewa; rozwinięty wiersz wstawia pod sobą wiersze następnego poziomu. */
function LevelRows({ parent, unassigned = false, ancestors }: LevelProps) {
  const { storeId, kind, periods, filter, basis, expanded, toggle, onLevelLoaded, openChart } = useTree()
  const { data, isPending, isError, refetch } = useSalesComparison(storeId, kind, periods, filter, parent, unassigned)
  const depth = ancestors.length

  useEffect(() => {
    if (data) onLevelLoaded()
  }, [data, onLevelLoaded])

  if (isPending) return <LoadingRow depth={depth} />
  if (isError && !data) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={COLUMN_COUNT} className="py-3 text-sm text-muted-foreground" style={{ paddingLeft: indent(depth) }}>
          Nie udało się wczytać.{' '}
          <button type="button" className="font-medium text-foreground underline underline-offset-4" onClick={() => refetch()}>
            Spróbuj ponownie
          </button>
        </TableCell>
      </TableRow>
    )
  }
  if (data.rows.length === 0) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={COLUMN_COUNT} className="py-3 text-sm text-muted-foreground" style={{ paddingLeft: indent(depth) }}>
          Brak sprzedaży w obu okresach.
        </TableCell>
      </TableRow>
    )
  }

  const level = data.level
  return data.rows.map((row) => {
    const key = rowKey(row, level)
    const expandable = level !== 'PRODUCT'
    const open = expandable && expanded.has(key)
    const style = level === 'PRODUCT' ? null : LEVEL_STYLES[level]
    return (
      <Fragment key={key}>
        <TableRow
          data-key={expandable ? key : undefined}
          data-ancestors={ancestors.join(',')}
          className={cn(expandable && 'cursor-pointer', style ? (open ? style.open : style.row) : 'bg-card hover:bg-muted')}
          onClick={expandable ? () => toggle(key) : undefined}
        >
          <TableCell
            className={cn('sticky left-0 z-10 min-w-64 bg-inherit py-2.5 pr-4 whitespace-normal', style && [barClass, style.bar])}
            style={{ paddingLeft: indent(depth) }}
          >
            {/* Linie prowadzące: każdy poziom wyżej ma swoją pionową kreskę pod strzałką rodzica. */}
            {ancestors.map((ancestor, index) => (
              <span
                key={ancestor}
                aria-hidden
                className="absolute inset-y-0 w-px bg-foreground/15"
                style={{ left: `calc(${indent(index)} + 0.5rem)` }}
              />
            ))}
            <div className="flex items-start gap-1">
              {expandable ? (
                <button
                  type="button"
                  aria-expanded={open}
                  onClick={(event) => {
                    event.stopPropagation()
                    toggle(key)
                  }}
                  className="flex min-w-0 flex-1 items-start gap-1.5 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  <ChevronRightIcon
                    aria-hidden
                    className={cn(
                      'mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out motion-reduce:transition-none',
                      open && 'rotate-90 text-foreground',
                    )}
                  />
                  <RowLabel row={row} level={level} />
                </button>
              ) : (
                <div className="min-w-0 flex-1 pl-5.5">
                  <RowLabel row={row} level={level} />
                </div>
              )}
              <ChartButton onClick={() => openChart(chartTarget(row, level, key))} />
            </div>
          </TableCell>
          <FigureCells previous={row.previous} current={row.current} basis={basis} strong={depth === 0 || open} />
        </TableRow>
        {open && (
          <LevelRows
            parent={key === UNASSIGNED_KEY ? '' : key}
            unassigned={key === UNASSIGNED_KEY}
            ancestors={[...ancestors, key]}
          />
        )}
      </Fragment>
    )
  })
}

/** Ikonka wykresu przy nazwie wiersza; nie rozwija wiersza. */
function ChartButton({ onClick }: { onClick: () => void }) {
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      aria-label="Wykres sprzedaży"
      title="Wykres sprzedaży"
      className="-my-0.5 shrink-0 text-muted-foreground hover:text-foreground"
      onClick={(event) => {
        event.stopPropagation()
        onClick()
      }}
    >
      <ChartColumnIcon />
    </Button>
  )
}

function chartTarget(row: SalesComparisonRow, level: SalesComparisonLevel, key: string): SalesChartTarget {
  if (level === 'PRODUCT')
    return {
      kind: 'article',
      itm8: row.code,
      name: row.name,
      ean: row.ean,
      brand: row.brand,
    }
  if (key === UNASSIGNED_KEY) {
    return {
      kind: 'group',
      code: '',
      unassigned: true,
      name: 'Bez nomenklatury',
      levelLabel: 'Artykuły spoza kartoteki',
    }
  }
  return {
    kind: 'group',
    code: row.code,
    unassigned: false,
    name: displayName(row) ?? 'Brak nazwy w nomenklaturze',
    levelLabel: `${LEVEL_STYLES[level].label} ${displayCode(row.code)}`,
  }
}

/** Wcięcie poziomu drzewa w rem (rozmiary interfejsu rosną z bazowym rem). */
const indent = (depth: number) => `${1 + depth * 1.25}rem`

/** Nazwa bez kodu z systemu sklepu: rejony mają nazwy z kodem ("01_DELIKATESY"), a kod i tak stoi obok. */
const displayName = (row: SalesComparisonRow) => row.name?.replace(/^\d+_/, '') ?? null
const displayCode = (code: string) => code.match(/\d{2}/g)?.join(' ') ?? code

function RowLabel({ row, level }: { row: SalesComparisonRow; level: SalesComparisonLevel }) {
  if (level === 'PRODUCT') {
    const details = [`ITM8 ${row.code}`, row.ean && `EAN ${row.ean}`, row.brand].filter(Boolean).join(' · ')
    return (
      <span className="min-w-0">
        <span className={cn('block', !row.name && 'text-muted-foreground')}>{row.name ?? 'Brak w kartotece'}</span>
        <span className="block text-xs text-muted-foreground">{details}</span>
      </span>
    )
  }
  if (row.code === '') {
    return (
      <span className="min-w-0">
        <span className="block font-medium">Bez nomenklatury</span>
        <span className="block text-xs text-muted-foreground">artykuły spoza kartoteki</span>
      </span>
    )
  }
  return (
    <span className="flex min-w-0 items-baseline gap-2">
      <span className="shrink-0 text-xs text-muted-foreground">{displayCode(row.code)}</span>
      <span
        className={cn(
          level === 'REGION' && 'font-semibold',
          level === 'FAMILY' && 'font-medium',
          !row.name && 'text-muted-foreground',
        )}
      >
        {displayName(row) ?? 'Brak nazwy w nomenklaturze'}
      </span>
    </span>
  )
}

/**
 * Ściąga do szybkiego przejścia po nomenklaturze, jak spis kategorii w zamówieniu garmażu: na dużym ekranie
 * w kolumnie obok tabeli (przyklejona pod paskiem aplikacji), na mniejszych strzałki po lewej - poprzednia / następna
 * pozycja.
 */
function NomenclatureNav({
  items,
  current,
  onJump,
}: {
  items: NavItem[]
  current: string | null
  onJump: (key: string) => void
}) {
  const listRef = useRef<HTMLOListElement>(null)
  const currentIndex = items.findIndex((item) => item.key === current)

  // Bieżąca pozycja zawsze widoczna w spisie, także gdy rozwinięto dużo grup. Przewijamy sam spis, nie stronę.
  useEffect(() => {
    const list = listRef.current
    const active = list?.querySelector<HTMLElement>('[aria-current="location"]')
    if (!list || !active) return
    const top = active.offsetTop - list.offsetTop
    if (top < list.scrollTop || top + active.offsetHeight > list.scrollTop + list.clientHeight) {
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      list.scrollTo({
        top: top - list.clientHeight / 2 + active.offsetHeight / 2,
        behavior: reduceMotion ? 'auto' : 'smooth',
      })
    }
  }, [current])

  if (items.length < 2) return null

  return (
    <>
      <div className="fixed bottom-4 left-4 z-40 flex flex-col gap-2 xl:hidden">
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Poprzednia pozycja nomenklatury"
          disabled={currentIndex <= 0}
          onClick={() => onJump(items[currentIndex - 1].key)}
        >
          <ChevronUpIcon />
        </Button>
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Następna pozycja nomenklatury"
          disabled={currentIndex >= items.length - 1}
          onClick={() => onJump(items[currentIndex + 1].key)}
        >
          <ChevronDownIcon />
        </Button>
      </div>

      <nav aria-label="Nomenklatura" className="sticky top-20 hidden xl:block">
        <div className="overflow-hidden rounded-xl bg-background ring-1 ring-foreground/10">
          <div className="border-b px-3 py-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Nomenklatura
          </div>
          <ol ref={listRef} className="max-h-[calc(80svh-2.5rem)] overflow-y-auto overscroll-contain p-1">
            {items.map((item) => (
              <li key={item.key}>
                <button
                  type="button"
                  aria-current={item.key === current ? 'location' : undefined}
                  onClick={() => onJump(item.key)}
                  style={{ paddingLeft: `${0.5 + item.depth * 0.875}rem` }}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-lg py-1.5 pr-2 text-left text-sm text-muted-foreground transition-colors duration-200 ease-out outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
                    item.key === current && 'bg-muted font-medium text-foreground',
                  )}
                >
                  <span aria-hidden className={cn('size-2 shrink-0 rounded-sm', LEVEL_STYLES[item.level].dot)} />
                  <span className="shrink-0 text-xs tabular-nums">{item.row.code ? displayCode(item.row.code) : '–'}</span>
                  <span className="min-w-0 flex-1 truncate">
                    {item.row.code === '' ? 'Bez nomenklatury' : (displayName(item.row) ?? 'Brak nazwy')}
                  </span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      </nav>
    </>
  )
}

function FigureCells({
  previous,
  current,
  basis,
  strong,
}: {
  previous: SalesFigures
  current: SalesFigures
  basis: SaleBasis
  strong?: boolean
}) {
  const valueClass = cn('text-right', strong && 'font-medium')
  const previousClass = 'border-l text-right text-muted-foreground'
  return (
    <>
      <TableCell className={previousClass}>{formatQuantity(previous.quantity)}</TableCell>
      <TableCell className={valueClass}>
        {formatQuantity(current.quantity)}
        <ChangeLine previous={previous.quantity} current={current.quantity} />
      </TableCell>

      {previous.costValue == null || current.costValue == null ? (
        <>
          <EmptyCell className={previousClass} />
          <EmptyCell className={valueClass} />
        </>
      ) : (
        <>
          <TableCell className={previousClass}>{formatMoney(previous.costValue)}</TableCell>
          <TableCell className={valueClass}>
            {formatMoney(current.costValue)}
            <ChangeLine previous={previous.costValue} current={current.costValue} neutral />
          </TableCell>
        </>
      )}

      <TableCell className={previousClass}>{formatMoney(saleValue(previous, basis))}</TableCell>
      <TableCell className={valueClass}>
        {formatMoney(saleValue(current, basis))}
        <ChangeLine previous={saleValue(previous, basis)} current={saleValue(current, basis)} />
      </TableCell>

      {previous.marginValue == null || current.marginValue == null ? (
        <>
          <EmptyCell className={previousClass} />
          <EmptyCell className={valueClass} />
          <EmptyCell className="text-right" />
        </>
      ) : (
        <>
          <TableCell className={previousClass}>
            {formatMoney(previous.marginValue)}
            <MarginPercent figures={previous} />
          </TableCell>
          <TableCell className={valueClass}>
            {formatMoney(current.marginValue)}
            <MarginPercent figures={current} />
          </TableCell>
          <TableCell className="text-right">
            <ChangeLine previous={previous.marginValue} current={current.marginValue} large />
            <MarginPercentChange previous={previous} current={current} />
          </TableCell>
        </>
      )}
    </>
  )
}

/** Kosztu garmażu nie da się przypisać do rodzin i artykułów, więc ich koszt i marża są puste. */
function EmptyCell({ className }: { className: string }) {
  return (
    <TableCell className={cn(className, 'text-muted-foreground')} title="Koszt jest tylko przy całym garmażu">
      –
    </TableCell>
  )
}

function MarginPercent({ figures }: { figures: SalesFigures }) {
  const percent = marginPercent(figures)
  return (
    <span className="block text-xs font-normal text-muted-foreground">
      {percent == null ? '–' : `${percentFormat.format(percent)}%`}
    </span>
  )
}

/** Różnica % marży w punktach procentowych. */
function MarginPercentChange({ previous, current }: { previous: SalesFigures; current: SalesFigures }) {
  const before = marginPercent(previous)
  const after = marginPercent(current)
  if (before == null || after == null) return <span className="block text-xs text-muted-foreground">–</span>
  const points = after - before
  return (
    <span className="block text-xs font-normal text-muted-foreground">
      {points > 0 ? '+' : points < 0 ? '−' : ''}
      {percentFormat.format(Math.abs(points))} p.p.
    </span>
  )
}

type Change = { label: string; direction: 'up' | 'down' | 'flat' }

/** Zmiana aktualnego okresu względem poprzedniego w %; bez poprzedniej sprzedaży nie ma od czego liczyć procentu. */
function change(previous: number, current: number): Change {
  if (previous === 0) {
    if (current === 0) return { label: '–', direction: 'flat' }
    return { label: 'nowe', direction: current > 0 ? 'up' : 'down' }
  }
  const percent = ((current - previous) / Math.abs(previous)) * 100
  const rounded = Math.round(percent * 10) / 10
  if (rounded === 0) return { label: '0,0%', direction: 'flat' }
  return {
    label: `${rounded > 0 ? '+' : '−'}${percentFormat.format(Math.abs(rounded))}%`,
    direction: rounded > 0 ? 'up' : 'down',
  }
}

const directionClass = (direction: Change['direction'], neutral?: boolean) =>
  neutral || direction === 'flat'
    ? 'text-muted-foreground'
    : direction === 'up'
      ? 'text-emerald-700 dark:text-emerald-400'
      : 'text-rose-700 dark:text-rose-400'

/**
 * Zmiana w % pod wartością aktualną; zakup ({@code neutral}) bez koloru, bo jego wzrost nie jest ani dobry, ani zły
 * sam w sobie. {@code large} w kolumnie Zmiana marży, gdzie stoi sama.
 */
function ChangeLine({
  previous,
  current,
  neutral,
  large,
}: {
  previous: number
  current: number
  neutral?: boolean
  large?: boolean
}) {
  const { label, direction } = change(previous, current)
  return (
    <span className={cn('block font-medium', large ? 'text-sm' : 'text-xs', directionClass(direction, neutral))}>{label}</span>
  )
}

function TotalTile({
  label,
  previous,
  current,
  format,
  compare,
  neutral,
  note,
}: {
  label: string
  compare: CompareMode
  previous: number | undefined
  current: number | undefined
  format: (value: number) => string
  neutral?: boolean
  note?: ReactNode
}) {
  const loaded = previous != null && current != null
  const delta = loaded ? change(previous, current) : null
  return (
    <Card className="gap-1 p-4">
      <p className="text-sm text-muted-foreground">{label}</p>
      {loaded ? (
        <>
          <div className="flex flex-wrap items-baseline justify-between gap-x-3">
            <p className="text-2xl font-semibold tracking-tight tabular-nums">{format(current)}</p>
            <p className={cn('text-sm font-medium tabular-nums', directionClass(delta!.direction, neutral))}>{delta!.label}</p>
          </div>
          <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-sm text-muted-foreground tabular-nums">
            <p>
              {COMPARE_LABELS[compare].inline} {format(previous)}
            </p>
            {note}
          </div>
        </>
      ) : (
        <div aria-hidden className="mt-1 space-y-2">
          <div className="h-7 w-32 animate-pulse rounded-md bg-muted motion-reduce:animate-none" />
          <div className="h-4 w-24 animate-pulse rounded-md bg-muted motion-reduce:animate-none" />
        </div>
      )}
    </Card>
  )
}

const formatZloty = (value: number) => `${formatMoney(value)} zł`

function LoadingRow({ depth, rows = 2 }: { depth: number; rows?: number }) {
  return Array.from({ length: rows }, (_, row) => (
    <TableRow key={row} className="hover:bg-transparent" aria-hidden>
      <TableCell className="py-3.5" style={{ paddingLeft: indent(depth) }}>
        <div className="h-3.5 w-40 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
      </TableCell>
      {Array.from({ length: NUMBER_COLUMNS }, (_, column) => (
        <TableCell key={column} className={cn(GROUP_STARTS.has(column) && 'border-l')}>
          <div className="ml-auto h-3.5 w-14 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
        </TableCell>
      ))}
    </TableRow>
  ))
}
