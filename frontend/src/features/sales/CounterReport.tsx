import { Fragment, type ReactNode, type UIEvent, useMemo, useRef, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, PrinterIcon, TriangleAlertIcon } from 'lucide-react'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { MultiComboboxField, SegmentedField, SwitchField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type CounterPrint,
  useCounterPdf,
  useCounterReport,
  useCounterStores,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { barClass, displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { formatPercent } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import { dayCount, daysLabel, formatRange, PERIOD_MODES } from '@/features/sales/periods'
import { ApiError } from '@/lib/api'
import type { CounterFigures, CounterMetric, CounterReport as Report, CounterValues } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Rejony lady w kolejności raportu, z krótką nazwą do przełącznika. */
const REGIONS = [
  { code: '24', label: 'Mięso' },
  { code: '36', label: 'Garmaż' },
  { code: '46', label: 'Drób' },
] as const

/** Ile sklepów można dołożyć do bieżącego (backend: najwyżej 6 razem). */
const MAX_COMPARED = 5

type MetricSpec = { key: CounterMetric; param: string; label: string; short: string }

const METRICS: MetricSpec[] = [
  { key: 'QUANTITY', param: 'ilosc', label: 'Ilość', short: 'Ilość' },
  { key: 'COST', param: 'zakup', label: 'Zakup FIFO', short: 'Zakup FIFO' },
  { key: 'SALE', param: 'sprzedaz', label: 'Sprzedaż', short: 'Sprzedaż' },
  { key: 'MARGIN', param: 'marza', label: 'Marża zł', short: 'Marża zł' },
  { key: 'MARGIN_PERCENT', param: 'marza-proc', label: 'Marża %', short: 'Marża %' },
  { key: 'SHARE', param: 'udzial', label: 'Udział %', short: 'Udział %' },
]
const DEFAULT_METRICS: CounterMetric[] = ['QUANTITY', 'COST', 'SALE', 'MARGIN', 'MARGIN_PERCENT']

/** Kolumn liczb w jednej tabeli wydruku (jak `CounterReportPdf.MAX_COLUMNS`). */
const PRINT_MAX_COLUMNS = 18

type Basis = 'GROSS' | 'NET'

/**
 * Raport lady (następca arkusza „36 szczegółowo”): mięso, garmaż i drób w kilku sklepach naraz, rejony → rodziny →
 * podrodziny → artykuły. Pod każdą miarą stoją kolumny sklepów, więc przy 6 sklepach i 6 miarach tabela ma 36 kolumn:
 * miary da się ograniczyć, tabela przewija się we własnym obszarze z przyklejonym nagłówkiem, nazwami i sumą, a
 * wydruk dzieli kolumny na kilka tabel. Marża % w wierszu: najwyższa zielona, najniższa czerwona.
 */
export function CounterReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const options = useCounterStores(storeId)
  const { params, update } = useReportParams()

  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod({ params, lastDate: lastSaleDate, defaultMode: 'week', customDays: 7 })
  const { mode, customError, range } = period

  const regionParams = params.getAll('rejon')
  const regions = REGIONS.map((region): string => region.code).filter(
    (code) => regionParams.length === 0 || regionParams.includes(code),
  )
  const compared = useMemo(() => [...new Set(params.getAll('sklep'))].slice(0, MAX_COMPARED), [params])
  const metricParams = params.getAll('miary')
  const metrics = metricParams.length
    ? METRICS.filter((metric) => metricParams.includes(metric.param)).map((metric) => metric.key)
    : DEFAULT_METRICS
  const basis: Basis = params.get('sprzedaz') === 'netto' ? 'NET' : 'GROSS'

  // Klucz zapytania TanStack porównuje po wartości, więc nowy obiekt przy każdym renderze nie pobiera danych od nowa.
  const query = { range, regions, stores: compared }
  const report = useCounterReport(storeId, query, !customError && !overview.isPending)
  const [printOpen, setPrintOpen] = useState(false)

  const toggleRegion = (code: string) => {
    const next = regions.includes(code) ? regions.filter((item) => item !== code) : [...regions, code]
    if (next.length === 0) return
    update({ rejon: next.length === REGIONS.length ? null : next })
  }
  const toggleMetric = (key: CounterMetric) => {
    const next = metrics.includes(key) ? metrics.filter((item) => item !== key) : [...metrics, key]
    if (next.length === 0) return
    const ordered = METRICS.filter((metric) => next.includes(metric.key))
    const isDefault =
      ordered.length === DEFAULT_METRICS.length && ordered.every((metric) => DEFAULT_METRICS.includes(metric.key))
    update({ miary: isDefault ? null : ordered.map((metric) => metric.param) })
  }

  return (
    <>
      {/* Kolumn jest dużo, więc raport bierze całą szerokość strony (AppLayout). */}
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Raport lady</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <SegmentedField
            label="Okres"
            options={PERIOD_MODES}
            value={mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <Button size="lg" disabled={!report.data || !!customError} onClick={() => setPrintOpen(true)}>
            <PrinterIcon />
            Wydruk
          </Button>
        </div>

        <PeriodPicker period={period} update={update} />

        <div className="flex flex-wrap items-start gap-x-6 gap-y-4 border-t pt-4">
          <ChipGroup label="Rejony">
            {REGIONS.map((region) => (
              <Chip key={region.code} active={regions.includes(region.code)} onClick={() => toggleRegion(region.code)}>
                <span className="text-xs opacity-70 tabular-nums">{region.code}</span> {region.label}
              </Chip>
            ))}
          </ChipGroup>
          <ChipGroup label="Miary w tabeli">
            {METRICS.map((metric) => (
              <Chip key={metric.key} active={metrics.includes(metric.key)} onClick={() => toggleMetric(metric.key)}>
                {metric.label}
              </Chip>
            ))}
          </ChipGroup>
          <SegmentedField
            label="Sprzedaż"
            options={[
              { value: 'GROSS', label: 'Brutto' },
              { value: 'NET', label: 'Netto' },
            ]}
            value={basis}
            onChange={(next) => update({ sprzedaz: next === 'NET' ? 'netto' : null })}
          />
        </div>

        {(options.data?.length ?? 0) > 0 && (
          <MultiComboboxField
            label="Porównaj ze sklepami"
            description={`Twoje sklepy i sklepy zaprzyjaźnione, najwyżej ${MAX_COMPARED}. Puste = tylko ten sklep.`}
            options={(options.data ?? []).map((store) => ({
              value: store.id,
              label: `${store.number} ${store.name}`,
              hint: store.partner ? 'zaprzyjaźniony' : undefined,
            }))}
            value={compared}
            onChange={(stores) => {
              if (stores.length > MAX_COMPARED) toast.warning(`Porównać można najwyżej ${MAX_COMPARED} sklepów naraz.`)
              update({ sklep: stores.slice(0, MAX_COMPARED) })
            }}
            placeholder="Wybierz sklepy…"
            className="max-w-3xl"
          />
        )}

        {!customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground"> · {daysLabel(dayCount(range))}</span>
          </p>
        )}
      </Card>

      {customError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć raportu</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {report.error instanceof ApiError ? report.error.message : 'Spróbuj ponownie za chwilę.'}
          </p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <ReportBody report={report.data} metrics={metrics} basis={basis} />
        </div>
      )}

      {report.data && (
        <PrintDialog
          storeId={storeId}
          open={printOpen}
          onOpenChange={setPrintOpen}
          print={{ ...query, metrics, basis, articles: false }}
          storeCount={report.data.stores.length}
        />
      )}
    </>
  )
}

function ChipGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </div>
  )
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'h-9 rounded-full border px-3.5 text-sm font-medium transition-colors duration-100 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        active
          ? 'border-foreground bg-foreground text-background'
          : 'bg-card text-muted-foreground hover:bg-muted hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}

function ReportBody({ report, metrics, basis }: { report: Report; metrics: CounterMetric[]; basis: Basis }) {
  // Rejony i rodziny są otwarte od startu (tak wyglądał arkusz), podrodziny otwierają się do artykułów.
  const [closed, setClosed] = useState<ReadonlySet<string>>(() => new Set())
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set())
  const toggle = (set: typeof setClosed, key: string) =>
    set((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  const allSubfamilies = report.regions.flatMap((region) =>
    region.families.flatMap((family) => family.subfamilies.map((sub) => sub.code)),
  )
  const allOpen = allSubfamilies.length > 0 && allSubfamilies.every((code) => opened.has(code))

  const late = report.stores.filter((store) => !store.lastSaleDate || store.lastSaleDate < report.to)
  const stores = report.stores
  const columns = metrics.length * stores.length
  const tableWidth = `${NAME_REM + columns * NUMBER_REM}rem`
  const { headRef, bodyRef, footRef, sync } = useSyncedScroll()

  return (
    <>
      {late.length > 0 && (
        <div className="mb-4 flex gap-3 rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-500/15 dark:text-amber-100">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          <p>
            Sprzedaż nie jest wgrana za cały okres:{' '}
            {late
              .map(
                (store) =>
                  `${store.number} (${store.lastSaleDate ? `do ${formatRange({ from: store.lastSaleDate, to: store.lastSaleDate })}` : 'brak sprzedaży'})`,
              )
              .join(', ')}
            .
          </p>
        </div>
      )}

      <div
        className={cn(
          'mb-4 grid gap-3 sm:grid-cols-2',
          stores.length >= 3 && 'lg:grid-cols-3',
          stores.length >= 4 && '2xl:grid-cols-6',
        )}
      >
        {stores.map((store, index) => (
          <StoreTile
            key={store.id}
            number={store.number}
            name={store.name}
            figures={report.total[index]}
            basis={basis}
            own={index === 0}
          />
        ))}
      </div>

      {report.regions.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak sprzedaży lady w tym okresie</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Wybierz inny okres albo wgraj raport sprzedaży w Imporcie danych.
          </p>
        </Card>
      ) : (
        <>
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
            <span className="tabular-nums">
              {stores.length} {stores.length === 1 ? 'sklep' : stores.length < 5 ? 'sklepy' : 'sklepów'} ×{' '}
              {metrics.length} {metrics.length === 1 ? 'miara' : metrics.length < 5 ? 'miary' : 'miar'} = {columns}{' '}
              kolumn
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setOpened(allOpen ? new Set() : new Set(allSubfamilies))}
            >
              {allOpen ? 'Zwiń artykuły' : 'Rozwiń wszystkie artykuły'}
            </Button>
          </div>
          {/*
            Jeden pasek przewijania w pionie (strony). Tabela przewija się tylko w bok: nagłówek klei się pod nagłówkiem
            aplikacji, „Razem lada” z poziomym paskiem do dołu okna, a trzy części mają te same kolumny i przesuwają się
            razem (useSyncedScroll).
          */}
          <Card className="gap-0 overflow-clip py-0">
            <div
              ref={headRef}
              className="sticky top-0 z-20 overflow-hidden bg-card shadow-[0_1px_0_var(--border)] lg:top-14"
            >
              <table className={TABLE_CLASS} style={{ minWidth: tableWidth }}>
                <Columns count={columns} />
                <thead>
                  <tr>
                    <th
                      rowSpan={2}
                      className="sticky left-0 z-10 border-b bg-card px-4 py-2 text-left align-bottom font-medium"
                    >
                      Nomenklatura
                    </th>
                    {metrics.map((metric, index) => (
                      <th
                        key={metric}
                        colSpan={stores.length}
                        className={cn(
                          'border-b px-2 pt-2 pb-1 text-center text-xs font-semibold tracking-wide uppercase',
                          index > 0 && 'border-l-2 border-l-border',
                        )}
                      >
                        {metricLabel(metric, basis)}
                      </th>
                    ))}
                  </tr>
                  <tr>
                    {metrics.map((metric, index) =>
                      stores.map((store, storeIndex) => (
                        <th
                          key={`${metric}-${store.id}`}
                          title={store.name}
                          className={cn(
                            'border-b px-2 py-1.5 text-right text-xs font-medium whitespace-nowrap',
                            storeIndex === 0 ? 'text-foreground' : 'text-muted-foreground',
                            storeIndex === 0 && index > 0 && 'border-l-2 border-l-border',
                          )}
                        >
                          {store.number}
                        </th>
                      )),
                    )}
                  </tr>
                </thead>
              </table>
            </div>
            <div
              ref={bodyRef}
              onScroll={sync}
              className="overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
            >
              <table className={TABLE_CLASS} style={{ minWidth: tableWidth }}>
                <Columns count={columns} />
                <tbody>
                  {report.regions.map((region) => {
                    const regionOpen = !closed.has(region.code)
                    return (
                      <Fragment key={region.code}>
                        <GroupRow
                          level="REGION"
                          code={region.code}
                          name={region.name}
                          values={region.values}
                          open={regionOpen}
                          onToggle={() => toggle(setClosed, region.code)}
                          metrics={metrics}
                          basis={basis}
                          depth={0}
                        />
                        {regionOpen &&
                          region.families.map((family) => {
                            const familyOpen = !closed.has(family.code)
                            return (
                              <Fragment key={family.code}>
                                <GroupRow
                                  level="FAMILY"
                                  code={family.code}
                                  name={family.name}
                                  values={family.values}
                                  open={familyOpen}
                                  onToggle={() => toggle(setClosed, family.code)}
                                  metrics={metrics}
                                  basis={basis}
                                  depth={1}
                                />
                                {familyOpen &&
                                  family.subfamilies.map((subfamily) => {
                                    const subfamilyOpen = opened.has(subfamily.code)
                                    return (
                                      <Fragment key={subfamily.code}>
                                        <GroupRow
                                          level="SUBFAMILY"
                                          code={subfamily.code}
                                          name={subfamily.name}
                                          values={subfamily.values}
                                          open={subfamilyOpen}
                                          onToggle={() => toggle(setOpened, subfamily.code)}
                                          metrics={metrics}
                                          basis={basis}
                                          depth={2}
                                          hint={`${subfamily.articles.length} art.`}
                                        />
                                        {subfamilyOpen &&
                                          subfamily.articles.map((article) => (
                                            <tr key={article.itm8} className="group bg-card hover:bg-muted">
                                              <td
                                                className="sticky left-0 z-10 border-b bg-inherit py-2 pr-3"
                                                style={{ paddingLeft: indent(3) }}
                                              >
                                                <div className="flex items-start gap-1">
                                                  <span className="min-w-0 flex-1">
                                                    <span
                                                      className={cn('block', !article.name && 'text-muted-foreground')}
                                                    >
                                                      {article.name ?? 'Brak w kartotece tego sklepu'}
                                                    </span>
                                                    <span className="block text-xs text-muted-foreground">
                                                      {[`ITM8 ${article.itm8}`, article.brand]
                                                        .filter(Boolean)
                                                        .join(' · ')}
                                                    </span>
                                                  </span>
                                                  <ArticleStatsButton
                                                    article={{
                                                      itm8: article.itm8,
                                                      name: article.name,
                                                      brand: article.brand,
                                                    }}
                                                  />
                                                </div>
                                              </td>
                                              <ValueCells values={article.values} metrics={metrics} basis={basis} />
                                            </tr>
                                          ))}
                                      </Fragment>
                                    )
                                  })}
                              </Fragment>
                            )
                          })}
                      </Fragment>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <div
              ref={footRef}
              onScroll={sync}
              className="sticky bottom-0 z-20 overflow-x-auto bg-foreground text-background [scrollbar-color:color-mix(in_oklab,var(--background)_45%,transparent)_transparent]"
            >
              <table className={TABLE_CLASS} style={{ minWidth: tableWidth }}>
                <Columns count={columns} />
                <tbody>
                  <tr>
                    <td className="sticky left-0 z-10 bg-foreground px-4 py-3 font-semibold">Razem lada</td>
                    <ValueCells values={report.total} metrics={metrics} basis={basis} variant="total" />
                  </tr>
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      <p className="mt-3 max-w-4xl text-xs leading-relaxed text-muted-foreground">
        Zakup to koszt FIFO sprzedanego towaru. Marża = sprzedaż netto − zakup FIFO, % od sprzedaży brutto. Udział =
        sprzedaż brutto pozycji w sprzedaży brutto wybranych rejonów lady w sklepie, więc porównuje strukturę sklepów
        różnej wielkości.
        {stores.length > 1 && ' Marża %: na zielono najwyższa, na czerwono najniższa w wierszu.'} „–” = brak sprzedaży w
        sklepie. Artykuły łączą się po ITM8, więc lokalne kody 99… innych sklepów stoją w osobnych wierszach.
      </p>
    </>
  )
}

/** Szerokości kolumn w rem: nazwa stała, liczby dzielą resztę po równo, ale nie węższe niż NUMBER_REM. */
const NAME_REM = 18
const NUMBER_REM = 6.5
const TABLE_CLASS = 'w-full table-fixed border-separate border-spacing-0 text-sm tabular-nums'

/** Te same kolumny w nagłówku, treści i sumie, żeby trzy osobne tabele trzymały jedną siatkę. */
function Columns({ count }: { count: number }) {
  return (
    <colgroup>
      <col style={{ width: `${NAME_REM}rem` }} />
      {Array.from({ length: count }, (_, index) => (
        <col key={index} />
      ))}
    </colgroup>
  )
}

/**
 * Przesunięcie w bok wspólne dla nagłówka, treści i sumy. Przewija się treść (gest, Shift + kółko) albo pasek pod
 * sumą; nagłówek idzie za nimi, bo sam nie ma paska.
 */
function useSyncedScroll() {
  const headRef = useRef<HTMLDivElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const footRef = useRef<HTMLDivElement>(null)
  const sync = (event: UIEvent<HTMLDivElement>) => {
    const left = event.currentTarget.scrollLeft
    for (const ref of [headRef, bodyRef, footRef]) {
      if (ref.current && ref.current !== event.currentTarget && ref.current.scrollLeft !== left) {
        ref.current.scrollLeft = left
      }
    }
  }
  return { headRef, bodyRef, footRef, sync }
}

const indent = (depth: number) => `${1 + depth}rem`

function GroupRow({
  level,
  code,
  name,
  values,
  open,
  onToggle,
  metrics,
  basis,
  depth,
  hint,
}: {
  level: 'REGION' | 'FAMILY' | 'SUBFAMILY'
  code: string
  name: string | null
  values: CounterValues
  open: boolean
  onToggle: () => void
  metrics: CounterMetric[]
  basis: Basis
  depth: number
  hint?: string
}) {
  const style = LEVEL_STYLES[level]
  return (
    <tr className={cn('cursor-pointer', open ? style.open : style.row)} onClick={onToggle}>
      <td
        className={cn('sticky left-0 z-10 border-b bg-inherit py-2.5 pr-3', barClass, style.bar)}
        style={{ paddingLeft: indent(depth) }}
      >
        <button
          type="button"
          aria-expanded={open}
          onClick={(event) => {
            event.stopPropagation()
            onToggle()
          }}
          className="flex w-full min-w-0 items-start gap-1.5 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <ChevronRightIcon
            aria-hidden
            className={cn(
              'mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out motion-reduce:transition-none',
              open && 'rotate-90 text-foreground',
            )}
          />
          <span className="flex min-w-0 flex-wrap items-baseline gap-x-2">
            <span className="shrink-0 text-xs text-muted-foreground">{displayCode(code)}</span>
            <span className={cn(level !== 'SUBFAMILY' && 'font-semibold', !name && 'text-muted-foreground')}>
              {name ?? 'Brak nazwy w nomenklaturze'}
            </span>
            {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
          </span>
        </button>
      </td>
      <ValueCells values={values} metrics={metrics} basis={basis} variant={level === 'SUBFAMILY' ? 'row' : 'group'} />
    </tr>
  )
}

/** Najwyższa i najniższa marża % w wierszu; bez porównania (mniej niż dwa sklepy z marżą albo remis) nic. */
function marginExtremes(values: CounterValues) {
  const known = values
    .map((figures, index) => ({ index, value: figures?.marginPercent ?? null }))
    .filter((item): item is { index: number; value: number } => item.value != null)
  if (known.length < 2) return null
  const best = known.reduce((a, b) => (b.value > a.value ? b : a))
  const worst = known.reduce((a, b) => (b.value < a.value ? b : a))
  return best.value === worst.value ? null : { best: best.index, worst: worst.index }
}

function ValueCells({
  values,
  metrics,
  basis,
  variant = 'row',
}: {
  values: CounterValues
  metrics: CounterMetric[]
  basis: Basis
  variant?: 'row' | 'group' | 'total'
}) {
  const total = variant === 'total'
  const extremes = total ? null : marginExtremes(values)
  return metrics.map((metric, metricIndex) =>
    values.map((figures, storeIndex) => {
      const highlight =
        metric === 'MARGIN_PERCENT' && extremes
          ? storeIndex === extremes.best
            ? 'bg-emerald-200 font-semibold text-emerald-950 dark:bg-emerald-500/30 dark:text-emerald-50'
            : storeIndex === extremes.worst
              ? 'bg-red-200 font-semibold text-red-950 dark:bg-red-500/30 dark:text-red-50'
              : null
          : null
      return (
        <td
          key={`${metric}-${storeIndex}`}
          className={cn(
            'px-2 py-2.5 text-right whitespace-nowrap',
            !total && 'border-b',
            (variant !== 'row' || storeIndex === 0) && 'font-semibold',
            storeIndex === 0 && metricIndex > 0 && 'border-l-2 border-l-border',
            figures == null && (total ? 'text-background/60' : 'text-muted-foreground'),
            metric === 'COST' && figures && !total && 'text-muted-foreground',
            highlight,
          )}
        >
          {figures == null ? '–' : metricValue(metric, figures, basis)}
        </td>
      )
    }),
  )
}

function metricLabel(metric: CounterMetric, basis: Basis) {
  if (metric === 'SALE') return basis === 'NET' ? 'Sprzedaż netto' : 'Sprzedaż brutto'
  return METRICS.find((item) => item.key === metric)!.short
}

function metricValue(metric: CounterMetric, figures: CounterFigures, basis: Basis) {
  switch (metric) {
    case 'QUANTITY':
      return formatQuantity(figures.quantity)
    case 'COST':
      return formatMoney(figures.costValue)
    case 'SALE':
      return formatMoney(basis === 'NET' ? figures.saleValueNet : figures.saleValueGross)
    case 'MARGIN':
      return formatMoney(figures.marginValue)
    case 'MARGIN_PERCENT':
      return formatPercent(figures.marginPercent)
    case 'SHARE':
      return formatPercent(figures.sharePercent)
  }
}

function StoreTile({
  number,
  name,
  figures,
  basis,
  own,
}: {
  number: string
  name: string
  figures: CounterFigures | null
  basis: Basis
  own: boolean
}) {
  const muted = own ? 'text-background/70' : 'text-muted-foreground'
  return (
    <Card className={cn('gap-1 p-4', own && 'bg-foreground text-background ring-0')}>
      <p className={cn('truncate text-sm', muted)} title={name}>
        <span className="font-semibold tabular-nums">{number}</span> · {name}
      </p>
      <p className="text-2xl font-semibold tracking-tight tabular-nums">
        {figures ? `${formatMoney(basis === 'NET' ? figures.saleValueNet : figures.saleValueGross)} zł` : '–'}
      </p>
      <p className={cn('text-sm tabular-nums', muted)}>
        {figures
          ? `marża ${formatPercent(figures.marginPercent)} · ${formatMoney(figures.marginValue)} zł`
          : 'brak sprzedaży lady'}
      </p>
    </Card>
  )
}

/** Podział miar na tabele wydruku, jak `CounterReportPdf.split`. */
function printParts(metrics: CounterMetric[], stores: number) {
  const tables = Math.max(1, Math.ceil((metrics.length * stores) / PRINT_MAX_COLUMNS))
  const perTable = Math.ceil(metrics.length / tables)
  const parts: CounterMetric[][] = []
  for (let start = 0; start < metrics.length; start += perTable) parts.push(metrics.slice(start, start + perTable))
  return parts
}

function PrintDialog({
  storeId,
  open,
  onOpenChange,
  print,
  storeCount,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  print: CounterPrint
  storeCount: number
}) {
  const pdf = useCounterPdf(storeId)
  const [articles, setArticles] = useState(false)
  const request = { ...print, articles }
  const parts = printParts(print.metrics, storeCount)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Wydruk raportu lady</DialogTitle>
          <DialogDescription>A4 poziomo, te same rejony, sklepy i miary co na ekranie.</DialogDescription>
        </DialogHeader>
        <div className="rounded-lg bg-muted px-4 py-3 text-sm">
          {parts.length === 1 ? (
            <p>Wszystkie miary zmieszczą się w jednej tabeli.</p>
          ) : (
            <>
              <p className="font-medium">
                {storeCount} sklepów × {print.metrics.length} {print.metrics.length < 5 ? 'miary' : 'miar'} nie mieści
                się w szerokości kartki, więc wydruk ma {parts.length} tabele:
              </p>
              <ol className="mt-1 list-decimal pl-5 text-muted-foreground">
                {parts.map((part, index) => (
                  <li key={index}>{part.map((metric) => metricLabel(metric, print.basis)).join(', ')}</li>
                ))}
              </ol>
            </>
          )}
        </div>
        <SwitchField
          label="Z artykułami"
          description="Pod każdą podrodziną jej artykuły. Wydruk będzie miał kilka stron."
          checked={articles}
          onCheckedChange={setArticles}
        />
        <DialogFooter>
          <Button
            variant="outline"
            size="lg"
            loading={pdf.download.isPending}
            onClick={() => pdf.download.mutate(request, { onError })}
          >
            <DownloadIcon />
            Pobierz PDF
          </Button>
          <Button size="lg" loading={pdf.print.isPending} onClick={() => pdf.print.mutate(request, { onError })}>
            <PrinterIcon />
            Drukuj
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
