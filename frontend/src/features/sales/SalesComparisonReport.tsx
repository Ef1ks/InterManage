import {
  createContext,
  Fragment,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronRightIcon, ChevronsDownUpIcon } from 'lucide-react'

import { BackLink } from '@/components/BackLink'
import { DateField, MultiComboboxField, SegmentedField, SelectField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type ReportKind,
  type SalesChartTarget,
  salesComparisonKey,
  useCounterStores,
  useDeliCostsOverview,
  useSalesComparison,
  useSalesNomenclatures,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { barClass, displayCode, LEVEL_STYLES, type NomenclatureLevel } from '@/features/sales/nomenclature-levels'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { NomenclatureNav, type NomenclatureNavItem } from '@/features/sales/NomenclatureNav'
import { salesPaths } from '@/features/sales/paths'
import { ChartButton, nomenclatureName } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  rangeError,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import {
  COMPARE_LABELS,
  type CompareMode,
  compareModes,
  comparePeriods,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  PERIOD_MODES,
  type Periods,
  resolveCompareMode,
} from '@/features/sales/periods'
import { marginShare } from '@/lib/margin'
import type {
  CounterStoreOption,
  SalesComparison,
  SalesComparisonLevel,
  SalesComparisonRow,
  SalesFigures,
} from '@/lib/types'
import { specialDayLabel, specialDaysIn } from '@/lib/holidays'
import { cn } from '@/lib/utils'

/** Okres w adresie strony po polsku, żeby link do raportu otwierał ten sam widok. */
const COMPARE_PARAMS: Record<CompareMode, string> = {
  year: 'rok',
  previous: 'poprzedni',
  week: 'tydzien',
  custom: 'wlasny',
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
  const { params, update } = useReportParams()
  const [basis, setBasis] = useState<SaleBasis>('gross')

  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod({ params, lastDate: lastSaleDate, defaultMode: 'day', customDays: 7 })
  const { mode, anchor, custom, customError } = period
  const filter = useMemo(() => (deli ? NO_FILTER : [...new Set(params.getAll('nom'))].sort()), [params, deli])
  const nomenclatures = useSalesNomenclatures(storeId)
  const compare = resolveCompareMode(
    mode,
    (Object.keys(COMPARE_PARAMS) as CompareMode[]).find((key) => COMPARE_PARAMS[key] === params.get('porownaj')) ??
      'year',
  )

  // Bez wybranych dat własne porównanie startuje od roku temu.
  const yearAgo = comparePeriods(mode, anchor, custom, lastSaleDate, 'year', custom).previous
  const compareCustom: DateRange = {
    from: params.get('por_od') ?? yearAgo.from,
    to: params.get('por_do') ?? yearAgo.to,
  }
  const compareError = compare === 'custom' ? rangeError(compareCustom) : null
  const periods = comparePeriods(mode, anchor, custom, lastSaleDate, compare, compareCustom)
  const valid = !customError && !compareError

  // Porównanie z drugim sklepem (swoim albo zaprzyjaźnionym, lista jak w raporcie lady); kwoty na życzenie.
  const storeOptions = useCounterStores(storeId)
  const otherStore = deli ? null : (storeOptions.data?.find((option) => option.id === params.get('sklep')) ?? null)
  const amounts = params.get('kwoty') === 'tak'
  const view = useMemo<View>(() => ({ other: otherStore, amounts }), [otherStore, amounts])

  return (
    <>
      {/* Tabela ma dużo kolumn, więc raport bierze całą szerokość strony (AppLayout). */}
      <span data-wide-page hidden />
      <BackLink to={deli ? '/garmaz' : salesPaths.overview}>{deli ? 'Garmaż' : 'Wszystkie raporty'}</BackLink>

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

        {!deli && (storeOptions.data?.length ?? 0) > 0 && (
          <div className="flex flex-wrap items-end gap-3">
            <SelectField
              label="Porównaj ze sklepem"
              options={[
                { value: NO_STORE, label: 'Bez porównania ze sklepem' },
                ...(storeOptions.data ?? []).map((option) => ({
                  value: option.id,
                  label: `${option.number} ${option.name}${option.partner ? ' (zaprzyjaźniony)' : ''}`,
                })),
              ]}
              value={otherStore?.id ?? NO_STORE}
              onChange={(id) => update({ sklep: id === NO_STORE ? null : id })}
              className="w-full sm:w-80"
            />
            {otherStore && (
              <SegmentedField
                label="Kwoty sprzedaży"
                options={[
                  { value: 'hidden', label: 'Ukryte' },
                  { value: 'shown', label: 'Pokaż' },
                ]}
                value={view.amounts ? 'shown' : 'hidden'}
                onChange={(next) => update({ kwoty: next === 'shown' ? 'tak' : null })}
              />
            )}
          </div>
        )}

        <PeriodPicker period={period} update={update}>
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
        </PeriodPicker>

        {!deli && (
          <MultiComboboxField
            label="Nomenklatura"
            description="Porównaj tylko wybrane rejony, rodziny albo podrodziny. Puste = cały sklep."
            options={(nomenclatures.data ?? []).map((item) => ({
              value: item.code,
              label: `${displayCode(item.code)} ${nomenclatureName(item.name)}`,
              hint: LEVEL_STYLES[item.level].label,
            }))}
            value={filter}
            onChange={(codes) => update({ nom: codes })}
            placeholder={
              nomenclatures.data?.length === 0 ? 'Brak wgranej nomenklatury' : 'Szukaj po kodzie albo nazwie…'
            }
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
          view={view}
          lastSaleDate={lastSaleDate}
        />
      )}
    </>
  )
}

/** Wartość listy sklepów bez porównania (pusty tekst SelectField traktuje jak brak wyboru). */
const NO_STORE = 'brak'

/** Porównanie z drugim sklepem: który sklep i czy pokazywać kwoty sprzedaży (domyślnie same ilości i marża %). */
type View = { other: CounterStoreOption | null; amounts: boolean }

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
      <SpecialDaysNote label="Aktualny" range={periods.current} />
      <SpecialDaysNote label={label} range={periods.previous} />
      {days !== comparedDays && (
        <p className="text-amber-700 dark:text-amber-400">
          Okresy mają różną liczbę dni, sumy nie są porównywalne wprost
        </p>
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
        <p className="text-amber-700 dark:text-amber-400">
          Brak protokołów reprezentacji: wgraj raport dokumentów w Imporcie danych
        </p>
      )}
      {costsCoveredUntil && (periods.current.to > costsCoveredUntil || periods.previous.to > costsCoveredUntil) && (
        <p className="text-amber-700 dark:text-amber-400">
          Protokoły reprezentacji są wgrane do {formatRange({ from: costsCoveredUntil, to: costsCoveredUntil })}
        </p>
      )}
    </div>
  )
}

/** Ile szczególnych dni okres wymienia z datą; przy dłuższych okresach sama liczba świąt i dni przed nimi. */
const SPECIAL_DAYS_LISTED = 4

const holidaysWord = (count: number) =>
  count === 1
    ? 'święto'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? 'święta'
      : 'świąt'

/**
 * Święta i dni przed świętami w okresie: dzień przed świętem ma większy ruch, a w święto sklep jest zwykle zamknięty,
 * więc porównanie z okresem bez nich (np. 9.11 wobec 10.11 rok temu, dnia przed 11.11) odbiega od zwykłego.
 */
function SpecialDaysNote({ label, range }: { label: string; range: Periods['current'] }) {
  const special = specialDaysIn(range)
  if (special.length === 0) return null
  const holidays = special.filter((item) => item.day.kind === 'holiday').length
  const eves = special.length - holidays
  return (
    <p className="font-medium text-red-700 dark:text-red-300">
      <span className="font-medium">{label}: </span>
      {special.length <= SPECIAL_DAYS_LISTED
        ? special.map(({ date, day }) => `${formatRange({ from: date, to: date })} ${specialDayLabel(day)}`).join(', ')
        : [
            holidays > 0 && `${holidays} ${holidaysWord(holidays)}`,
            eves > 0 && `${eves} ${eves === 1 ? 'dzień' : 'dni'} przed świętem`,
          ]
            .filter(Boolean)
            .join(', ')}
    </p>
  )
}

/** Wartość sprzedaży wg wybranej podstawy: brutto jak na paragonie albo netto, od którego liczy się marża. */
const saleValue = (figures: SalesFigures, basis: SaleBasis) =>
  basis === 'gross' ? figures.saleValueGross : figures.saleValueNet

/** % marży od sprzedaży brutto, jak w raporcie systemu sklepu; bez marży (koszty garmażu poniżej rejonu) pusty. */
const marginPercent = (figures: SalesFigures) => marginShare(figures.marginValue, figures.saleValueGross)

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

const LEVELS: NomenclatureLevel[] = ['REGION', 'FAMILY', 'SUBFAMILY']

type Tree = {
  storeId: string
  kind: ReportKind
  periods: Periods
  filter: string[]
  basis: SaleBasis
  view: View
  groups: Group[]
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

function Comparison({
  storeId,
  kind,
  periods,
  filter,
  compare,
  basis,
  view,
  lastSaleDate,
}: {
  storeId: string
  kind: ReportKind
  periods: Periods
  filter: string[]
  compare: CompareMode
  basis: SaleBasis
  view: View
  lastSaleDate: string
}) {
  const deli = kind === 'deli'
  const compareStore = view.other?.id ?? null
  const groups = useMemo(() => tableGroups(view, basis, deli, compare), [view, basis, deli, compare])
  const { data, isPending, isError, refetch, isPlaceholderData } = useSalesComparison(
    storeId,
    kind,
    periods,
    filter,
    '',
    false,
    compareStore,
  )
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
      top:
        container.scrollTop + row.getBoundingClientRect().top - head.getBoundingClientRect().bottom - ancestorsHeight,
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
      view,
      groups,
      expanded,
      toggle,
      onLevelLoaded,
      openChart: setChart,
    }),
    [storeId, kind, periods, filter, basis, view, groups, expanded, toggle, onLevelLoaded],
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
    queryClient.getQueryData<SalesComparison>(
      salesComparisonKey(storeId, kind, periods, filter, parent, false, compareStore),
    )

  const pinnedRows = pinned.flatMap((key) => {
    const level = cachedLevel(parentOf(key))
    const row = level?.rows.find((candidate) => rowKey(candidate, level.level) === key)
    return row ? [{ key, row, depth: depthOf(key), level: LEVELS[depthOf(key)] }] : []
  })

  const navItems: NomenclatureNavItem[] = []
  const collectNav = (parent: string, depth: number) => {
    const level = depth === 0 ? data : cachedLevel(parent)
    if (!level || level.level === 'PRODUCT') return
    for (const row of level.rows) {
      const key = rowKey(row, level.level)
      const name = row.code === '' ? 'Bez nomenklatury' : (displayName(row) ?? 'Brak nazwy')
      navItems.push({ key, code: row.code, name, level: level.level, depth })
      if (key !== UNASSIGNED_KEY && expanded.has(key)) collectNav(key, depth + 1)
    }
  }
  collectNav('', 0)

  const empty = data && data.rows.length === 0

  return (
    <div className={cn('transition-opacity duration-150', isPlaceholderData && 'opacity-60')}>
      {view.other ? (
        <StoreTiles data={data} view={view} basis={basis} compare={compare} />
      ) : (
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
      )}

      {empty ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">
            {deli
              ? 'Brak sprzedaży garmażu i protokołów reprezentacji w obu okresach'
              : 'Brak sprzedaży w obu okresach'}
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
            {view.other && <StoreLegend other={view.other} />}
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
              <div
                ref={scrollRef}
                onScroll={updatePinned}
                className="relative max-h-[80svh] overflow-auto overscroll-contain"
              >
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
                                <ChevronRightIcon
                                  aria-hidden
                                  className="mt-0.5 size-4 shrink-0 rotate-90 text-foreground"
                                />
                                <RowLabel row={entry.row} level={entry.level} />
                              </span>
                            </TableCell>
                            <FigureCells
                              previous={entry.row.previous}
                              current={entry.row.current}
                              other={view.other ? entry.row.other : undefined}
                              amounts={view.amounts}
                              basis={basis}
                              strong
                            />
                          </TableRow>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                <table ref={tableRef} className="w-full caption-bottom text-sm tabular-nums">
                  <TableHeader ref={headRef} className="sticky top-0 z-30 bg-card shadow-[0_1px_0_var(--border)]">
                    <TableRow className="hover:bg-transparent">
                      <TableHead
                        rowSpan={2}
                        data-column="name"
                        className="sticky left-0 z-10 min-w-64 bg-card pl-4 align-bottom"
                      >
                        Nomenklatura
                      </TableHead>
                      {groups.map((group) => (
                        <TableHead key={group.key} colSpan={group.columns.length} className="h-9 border-l text-center">
                          {group.label}
                        </TableHead>
                      ))}
                    </TableRow>
                    <TableRow className="hover:bg-transparent">
                      {groups.map((group) =>
                        group.columns.map((column, index) => (
                          <TableHead
                            key={`${group.key}-${index}`}
                            data-leaf
                            className={cn(
                              'h-8 text-right text-xs font-normal text-muted-foreground',
                              index === 0 && 'border-l',
                            )}
                          >
                            {column}
                          </TableHead>
                        )),
                      )}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {isPending ? (
                      <LoadingRow depth={0} rows={6} groups={groups} />
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
                        <FigureCells
                          previous={data.previousTotal}
                          current={data.currentTotal}
                          other={view.other ? data.otherTotal : undefined}
                          amounts={view.amounts}
                          basis={basis}
                          strong
                        />
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

/** Grupa kolumn tabeli: nagłówek nad nią i podpisy kolumn liczb. */
type Group = { key: string; label: string; columns: string[] }

/**
 * Grupy kolumn tabeli. Zmiana % stoi pod wartością aktualną, żeby cała tabela mieściła się na ekranie; tylko marża
 * ma osobną kolumnę zmiany (zmiana kwoty i różnica % marży w punktach procentowych). Przy porównaniu ze sklepem:
 * ilość (nasza w obu okresach, ich aktualna i o ile więcej mają), marża % i kwoty sprzedaży na życzenie.
 */
function tableGroups(view: View, basis: SaleBasis, deli: boolean, compare: CompareMode): Group[] {
  const previous = COMPARE_LABELS[compare].column
  const sale = basis === 'gross' ? 'Sprzedaż brutto zł' : 'Sprzedaż netto zł'
  if (view.other) {
    const theirs = `Sklep ${view.other.number}`
    return [
      { key: 'quantity', label: 'Ilość', columns: [previous, 'Aktualny', theirs, 'U nich więcej'] },
      { key: 'margin', label: 'Marża % (aktualny)', columns: ['Nasza', theirs, 'U nich więcej'] },
      ...(view.amounts ? [{ key: 'sale', label: sale, columns: [previous, 'Aktualny', theirs] }] : []),
    ]
  }
  return [
    { key: 'quantity', label: 'Ilość', columns: [previous, 'Aktualny'] },
    { key: 'cost', label: deli ? 'Reprezentacja zł' : 'Zakup FIFO zł', columns: [previous, 'Aktualny'] },
    { key: 'sale', label: sale, columns: [previous, 'Aktualny'] },
    { key: 'margin', label: 'Marża zł', columns: [previous, 'Aktualny', 'Zmiana'] },
  ]
}

const numberColumns = (groups: Group[]) => groups.reduce((sum, group) => sum + group.columns.length, 0)

/** Numery kolumn liczb, od których zaczyna się grupa (z kreską po lewej). */
const groupStarts = (groups: Group[]) =>
  new Set(
    groups.reduce<number[]>((starts, _, index) => {
      starts.push(index === 0 ? 0 : starts[index - 1] + groups[index - 1].columns.length)
      return starts
    }, []),
  )

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
  const { storeId, kind, periods, filter, basis, view, groups, expanded, toggle, onLevelLoaded, openChart } = useTree()
  const { data, isPending, isError, refetch } = useSalesComparison(
    storeId,
    kind,
    periods,
    filter,
    parent,
    unassigned,
    view.other?.id ?? null,
  )
  const depth = ancestors.length
  const columnCount = 1 + numberColumns(groups)

  useEffect(() => {
    if (data) onLevelLoaded()
  }, [data, onLevelLoaded])

  if (isPending) return <LoadingRow depth={depth} groups={groups} />
  if (isError && !data) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell
          colSpan={columnCount}
          className="py-3 text-sm text-muted-foreground"
          style={{ paddingLeft: indent(depth) }}
        >
          Nie udało się wczytać.{' '}
          <button
            type="button"
            className="font-medium text-foreground underline underline-offset-4"
            onClick={() => refetch()}
          >
            Spróbuj ponownie
          </button>
        </TableCell>
      </TableRow>
    )
  }
  if (data.rows.length === 0) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell
          colSpan={columnCount}
          className="py-3 text-sm text-muted-foreground"
          style={{ paddingLeft: indent(depth) }}
        >
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
          className={cn(
            expandable && 'cursor-pointer',
            style ? (open ? style.open : style.row) : 'bg-card hover:bg-muted',
          )}
          onClick={expandable ? () => toggle(key) : undefined}
        >
          <TableCell
            className={cn(
              'sticky left-0 z-10 min-w-64 bg-inherit py-2.5 pr-4 whitespace-normal',
              style && [barClass, style.bar],
            )}
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
          <FigureCells
            previous={row.previous}
            current={row.current}
            other={view.other ? row.other : undefined}
            amounts={view.amounts}
            basis={basis}
            strong={depth === 0 || open}
          />
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
const displayName = (row: SalesComparisonRow) => (row.name ? nomenclatureName(row.name) : null)

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

function FigureCells({
  previous,
  current,
  other,
  amounts = false,
  basis,
  strong,
}: {
  previous: SalesFigures
  current: SalesFigures
  /** Porównanie ze sklepem: jego sprzedaż (brak w odpowiedzi = zera); bez porównania {@code undefined}. */
  other?: SalesFigures | null
  amounts?: boolean
  basis: SaleBasis
  strong?: boolean
}) {
  if (other !== undefined) {
    return (
      <StoreFigureCells
        previous={previous}
        current={current}
        other={other ?? ZERO_FIGURES}
        amounts={amounts}
        basis={basis}
        strong={strong}
      />
    )
  }
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
  plain,
}: {
  previous: number
  current: number
  neutral?: boolean
  large?: boolean
  /** W wyróżnionej komórce kolor bierze z niej. */
  plain?: boolean
}) {
  const { label, direction } = change(previous, current)
  return (
    <span
      className={cn('block font-medium', large ? 'text-sm' : 'text-xs', !plain && directionClass(direction, neutral))}
    >
      {label}
    </span>
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
            <p className={cn('text-sm font-medium tabular-nums', directionClass(delta!.direction, neutral))}>
              {delta!.label}
            </p>
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

function LoadingRow({ depth, rows = 2, groups }: { depth: number; rows?: number; groups: Group[] }) {
  const starts = groupStarts(groups)
  return Array.from({ length: rows }, (_, row) => (
    <TableRow key={row} className="hover:bg-transparent" aria-hidden>
      <TableCell className="py-3.5" style={{ paddingLeft: indent(depth) }}>
        <div className="h-3.5 w-40 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
      </TableCell>
      {Array.from({ length: numberColumns(groups) }, (_, column) => (
        <TableCell key={column} className={cn(starts.has(column) && 'border-l')}>
          <div className="ml-auto h-3.5 w-14 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
        </TableCell>
      ))}
    </TableRow>
  ))
}

const ZERO_FIGURES: SalesFigures = {
  quantity: 0,
  costValue: 0,
  saleValueGross: 0,
  saleValueNet: 0,
  marginValue: 0,
}

/** Wzrost naszej ilości względem poprzedniego okresu poniżej tylu % (także spadek) jest oznaczany na czerwono. */
const SMALL_GROWTH_PERCENT = 20

/** Oznaczenia w porównaniu ze sklepem: mocne tło, żeby było je widać na kolorach poziomów nomenklatury. */
const MARKS = {
  smallGrowth: 'bg-red-200 text-red-950 dark:bg-red-950/70 dark:text-red-100',
  moreQuantity: 'bg-amber-200 text-amber-950 dark:bg-amber-900/60 dark:text-amber-100',
  higherSale: 'bg-fuchsia-200 text-fuchsia-950 dark:bg-fuchsia-950/70 dark:text-fuchsia-100',
  lowerMargin: 'bg-orange-200 text-orange-950 dark:bg-orange-950/70 dark:text-orange-100',
} as const

/** Mały wzrost: poniżej {@link SMALL_GROWTH_PERCENT}; bez sprzedaży w poprzednim okresie nie ma od czego liczyć. */
const smallGrowth = (previous: number, current: number) =>
  previous > 0 && ((current - previous) / previous) * 100 < SMALL_GROWTH_PERCENT

/** O ile % drugi sklep ma więcej (minus = mniej) od naszego; bez naszej sprzedaży procentu nie ma. */
function aheadLabel(ours: number, theirs: number) {
  if (ours === 0) return theirs === 0 ? '–' : theirs > 0 ? 'tylko u nich' : '–'
  const percent = Math.round(((theirs - ours) / Math.abs(ours)) * 1000) / 10
  if (percent === 0) return '0,0%'
  return `${percent > 0 ? '+' : '−'}${percentFormat.format(Math.abs(percent))}%`
}

const percentLabel = (value: number | null) => (value == null ? '–' : `${percentFormat.format(value)}%`)

/** Różnica % marży w punktach procentowych (ich − nasza). */
function pointsLabel(ours: number | null, theirs: number | null) {
  if (ours == null || theirs == null) return '–'
  const points = Math.round((theirs - ours) * 10) / 10
  return `${points > 0 ? '+' : points < 0 ? '−' : ''}${percentFormat.format(Math.abs(points))} p.p.`
}

/** Nasza marża % niższa niż u nich (po zaokrągleniu do 0,1 p.p., jak w tabeli). */
const lowerMargin = (ours: number | null, theirs: number | null) =>
  ours != null && theirs != null && Math.round(ours * 10) < Math.round(theirs * 10)

/**
 * Kolumny porównania ze sklepem: nasza ilość w obu okresach (mały wzrost na czerwono), ich ilość i o ile % więcej
 * (więcej u nich na bursztynowo), marża % nasza i ich (nasza niższa na pomarańczowo) i na życzenie kwoty sprzedaży
 * (wyższa u nich na fuksjowo).
 */
function StoreFigureCells({
  previous,
  current,
  other,
  amounts,
  basis,
  strong,
}: {
  previous: SalesFigures
  current: SalesFigures
  other: SalesFigures
  amounts: boolean
  basis: SaleBasis
  strong?: boolean
}) {
  const valueClass = cn('text-right', strong && 'font-medium')
  const previousClass = 'border-l text-right text-muted-foreground'
  const small = smallGrowth(previous.quantity, current.quantity)
  const moreQuantity = other.quantity > current.quantity
  const ours = marginPercent(current)
  const theirs = marginPercent(other)
  const lower = lowerMargin(ours, theirs)
  const ourSale = saleValue(current, basis)
  const theirSale = saleValue(other, basis)
  const higherSale = theirSale > ourSale
  const store = 'w drugim sklepie'
  return (
    <>
      <TableCell className={previousClass}>{formatQuantity(previous.quantity)}</TableCell>
      <TableCell
        className={cn(valueClass, small && MARKS.smallGrowth)}
        title={small ? `Wzrost ilości poniżej ${SMALL_GROWTH_PERCENT}%` : undefined}
      >
        {formatQuantity(current.quantity)}
        <ChangeLine previous={previous.quantity} current={current.quantity} plain={small} />
      </TableCell>
      <TableCell className={cn(valueClass, moreQuantity && MARKS.moreQuantity)}>
        {formatQuantity(other.quantity)}
      </TableCell>
      <TableCell
        className={cn('text-right text-xs font-medium', moreQuantity && MARKS.moreQuantity)}
        title={moreQuantity ? `Większa ilość ${store}` : undefined}
      >
        {aheadLabel(current.quantity, other.quantity)}
      </TableCell>

      <TableCell
        className={cn('border-l text-right', strong && 'font-medium', lower && MARKS.lowerMargin)}
        title={lower ? `Niższa marża niż ${store}` : undefined}
      >
        {percentLabel(ours)}
      </TableCell>
      <TableCell className={valueClass}>{percentLabel(theirs)}</TableCell>
      <TableCell className={cn('text-right text-xs font-medium', lower && MARKS.lowerMargin)}>
        {pointsLabel(ours, theirs)}
      </TableCell>

      {amounts && (
        <>
          <TableCell className={previousClass}>{formatMoney(saleValue(previous, basis))}</TableCell>
          <TableCell className={valueClass}>
            {formatMoney(ourSale)}
            <ChangeLine previous={saleValue(previous, basis)} current={ourSale} />
          </TableCell>
          <TableCell
            className={cn(valueClass, higherSale && MARKS.higherSale)}
            title={higherSale ? `Wyższa sprzedaż ${store}` : undefined}
          >
            {formatMoney(theirSale)}
            <span className="block text-xs font-medium">{aheadLabel(ourSale, theirSale)}</span>
          </TableCell>
        </>
      )}
    </>
  )
}

function StoreLegend({ other }: { other: CounterStoreOption }) {
  const items = [
    { mark: MARKS.smallGrowth, label: `Nasz wzrost ilości poniżej ${SMALL_GROWTH_PERCENT}%` },
    { mark: MARKS.moreQuantity, label: `Więcej sztuk w sklepie ${other.number}` },
    { mark: MARKS.lowerMargin, label: 'Nasza marża % niższa' },
    { mark: MARKS.higherSale, label: 'Wyższa sprzedaż u nich' },
  ]
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground" aria-label="Oznaczenia">
      {items.map((item) => (
        <span key={item.label} className="inline-flex items-center gap-1.5">
          <span aria-hidden className={cn('size-3 rounded-sm', item.mark)} />
          {item.label}
        </span>
      ))}
    </span>
  )
}

/** Kafelki porównania ze sklepem: nasza ilość, ich ilość, marża % obu sklepów i na życzenie sprzedaż. */
function StoreTiles({
  data,
  view,
  basis,
  compare,
}: {
  data: SalesComparison | undefined
  view: View
  basis: SaleBasis
  compare: CompareMode
}) {
  const other = data?.otherTotal ?? (data ? ZERO_FIGURES : undefined)
  const number = view.other?.number ?? ''
  const ours = data ? marginPercent(data.currentTotal) : null
  const theirs = other ? marginPercent(other) : null
  return (
    <div className={cn('mb-4 grid gap-3 sm:grid-cols-2', view.amounts ? 'xl:grid-cols-4' : 'xl:grid-cols-3')}>
      <TotalTile
        label="Ilość"
        previous={data?.previousTotal.quantity}
        current={data?.currentTotal.quantity}
        format={formatQuantity}
        compare={compare}
      />
      <PlainTile
        label={`Ilość · sklep ${number}`}
        value={other && formatQuantity(other.quantity)}
        note={data && other && `u nich więcej: ${aheadLabel(data.currentTotal.quantity, other.quantity)}`}
      />
      <PlainTile
        label="Marża % (aktualny)"
        value={data && percentLabel(ours)}
        note={data && `sklep ${number}: ${percentLabel(theirs)} · u nich więcej: ${pointsLabel(ours, theirs)}`}
      />
      {view.amounts && (
        <PlainTile
          label={basis === 'gross' ? 'Sprzedaż brutto' : 'Sprzedaż netto'}
          value={data && formatZloty(saleValue(data.currentTotal, basis))}
          note={
            data &&
            other &&
            `sklep ${number}: ${formatZloty(saleValue(other, basis))} · ${aheadLabel(
              saleValue(data.currentTotal, basis),
              saleValue(other, basis),
            )}`
          }
        />
      )}
    </div>
  )
}

function PlainTile({ label, value, note }: { label: string; value: ReactNode; note: ReactNode }) {
  return (
    <Card className="gap-1 p-4">
      <p className="text-sm text-muted-foreground">{label}</p>
      {value != null ? (
        <>
          <p className="text-2xl font-semibold tracking-tight tabular-nums">{value}</p>
          <p className="text-sm text-muted-foreground tabular-nums">{note}</p>
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
