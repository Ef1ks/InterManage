import { Fragment, type ReactNode, type UIEvent, useMemo, useRef, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, Link2Icon, PrinterIcon, TriangleAlertIcon } from 'lucide-react'
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
import { ArticleLinkDialog, type LinkStore } from '@/features/article-links/ArticleLinkDialog'
import { shortItm8 } from '@/features/article-links/format'
import { useCan } from '@/features/auth/api'
import {
  type CounterPrint,
  useCounterPdf,
  useCounterReport,
  useCounterStores,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
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
import type { CounterArticle, CounterFigures, CounterMetric, CounterReport as Report, CounterValues } from '@/lib/types'
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
  { key: 'MARGIN', param: 'marza', label: 'Marża zł', short: 'Marża po likw. zł' },
  { key: 'MARGIN_PERCENT', param: 'marza-proc', label: 'Marża %', short: 'Marża po likw. %' },
  { key: 'SHARE', param: 'udzial', label: 'Udział %', short: 'Udział %' },
  { key: 'LOSSES', param: 'likwidacje', label: 'Likwidacje', short: 'Likwidacje' },
]

/** Miary bez kolorów największej i najmniejszej wartości: zakup FIFO mało kogo interesuje, a likwidacje mają odwrotny sens. */
const UNCOLORED: ReadonlySet<CounterMetric> = new Set(['COST', 'LOSSES'])
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
          <ReportBody storeId={storeId} report={report.data} metrics={metrics} basis={basis} />
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

function ReportBody({
  storeId,
  report,
  metrics,
  basis,
}: {
  storeId: string
  report: Report
  metrics: CounterMetric[]
  basis: Basis
}) {
  // Rejony i rodziny są otwarte od startu (tak wyglądał arkusz), podrodziny otwierają się do artykułów.
  const [closed, setClosed] = useState<ReadonlySet<string>>(() => new Set())
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set())
  const [linking, setLinking] = useState<CounterArticle | null>(null)
  const canLink = useCan('SALES', 'EDIT')
  const compared = report.stores.length > 1
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
  const perMetric = stores.length
  const tableWidth = `${NAME_REM + metrics.length * stores.length * NUMBER_REM}rem`
  const columnSpec = { metrics: metrics.length, stores: stores.length }
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
            <span>
              {compared
                ? `W wierszu największa wartość na zielono, najmniejsza na czerwono. Pod liczbą sklepu różnica względem ${stores[0].number}.`
                : 'Dołóż sklep do porównania, żeby zobaczyć różnice.'}
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
          <Card className="w-fit max-w-full gap-0 overflow-clip py-0">
            <div
              ref={headRef}
              className="sticky top-0 z-20 overflow-hidden bg-card shadow-[0_1px_0_var(--border)] lg:top-14"
            >
              <table className={TABLE_CLASS} style={{ width: tableWidth }}>
                <Columns {...columnSpec} />
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
                        colSpan={perMetric}
                        className={cn(
                          'border-b px-2 pt-2 pb-1 text-center text-xs font-semibold tracking-wide uppercase',
                          index > 0 && GROUP_EDGE,
                        )}
                      >
                        {metricLabel(metric, basis)}
                      </th>
                    ))}
                  </tr>
                  <tr>
                    {metrics.map((metric, index) => (
                      <Fragment key={metric}>
                        {stores.map((store, storeIndex) => (
                          <th
                            key={store.id}
                            title={`${store.number} ${store.name}`}
                            className={cn(
                              'border-b px-2 py-1.5 text-right text-xs font-medium whitespace-nowrap',
                              storeIndex === 0 ? 'text-foreground' : 'text-muted-foreground',
                              storeIndex === 0 && index > 0 && GROUP_EDGE,
                            )}
                          >
                            {store.number}
                          </th>
                        ))}
                      </Fragment>
                    ))}
                  </tr>
                </thead>
              </table>
            </div>
            <div ref={bodyRef} onScroll={sync} className="overflow-x-auto scrollbar-none [&::-webkit-scrollbar]:hidden">
              <table className={TABLE_CLASS} style={{ width: tableWidth }}>
                <Columns {...columnSpec} />
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
                          compared={compared}
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
                                  compared={compared}
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
                                          compared={compared}
                                          depth={2}
                                          hint={`${subfamily.articles.length} art.`}
                                        />
                                        {subfamilyOpen &&
                                          subfamily.articles.map((article, articleIndex) => (
                                            <ArticleRow
                                              key={article.itm8}
                                              article={article}
                                              stores={stores}
                                              striped={articleIndex % 2 === 1}
                                              canLink={canLink}
                                              onLink={() => setLinking(article)}
                                            >
                                              <ValueCells
                                                values={article.values}
                                                metrics={metrics}
                                                basis={basis}
                                                compared={compared}
                                              />
                                            </ArticleRow>
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
              <table className={TABLE_CLASS} style={{ width: tableWidth }}>
                <Columns {...columnSpec} />
                <tbody>
                  <tr>
                    <td className="sticky left-0 z-10 bg-foreground px-4 py-3 font-semibold">Razem lada</td>
                    <ValueCells
                      values={report.total}
                      metrics={metrics}
                      basis={basis}
                      compared={compared}
                      variant="total"
                    />
                  </tr>
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}

      {linking && (
        <CounterLinkDialog
          storeId={storeId}
          report={report}
          article={linking}
          canEdit={canLink}
          onClose={() => setLinking(null)}
        />
      )}

      <p className="mt-3 max-w-4xl text-xs leading-relaxed text-muted-foreground">
        Zakup to koszt FIFO sprzedanego towaru. Marża jest po likwidacjach: sprzedaż netto − zakup FIFO − likwidacje
        (protokoły likwidacji z okresu, wartość zakupu netto), % od sprzedaży brutto. Artykuł zlikwidowany bez sprzedaży
        też ma wiersz, z marżą na minusie. Udział = sprzedaż brutto pozycji w sprzedaży brutto wybranych rejonów lady w
        sklepie, więc porównuje strukturę sklepów różnej wielkości.
        {compared &&
          ` Pod liczbą innego sklepu: o ile różni się od ${stores[0].number} (kwoty i ilość w %, marża % i udział w punktach procentowych).`}{' '}
        „–” = brak sprzedaży w sklepie. Artykuły łączą się po ITM8; ten sam towar pod innym kodem w drugim sklepie
        (lokalne 99…) połącz ikoną łańcucha przy artykule, a raport zapamięta to na przyszłość.
      </p>
    </>
  )
}

/**
 * Szerokości kolumn w rem. Tabela ma szerokość swoich kolumn (bez rozciągania na cały ekran), więc liczby sklepów
 * stoją obok siebie i obok nazwy, a nazwa ma miejsce na jedną linię.
 */
const NAME_REM = 24
const NUMBER_REM = 6.25
const TABLE_CLASS = 'table-fixed border-separate border-spacing-0 text-sm tabular-nums'
/** Granica między miarami: wyraźniejsza niż linie wierszy, bo dzieli grupy kolumn. */
const GROUP_EDGE = 'border-l border-l-foreground/25'

/** Miary porównywane w punktach procentowych (pozostałe w % różnicy). */
const PERCENT_POINTS: ReadonlySet<CounterMetric> = new Set(['MARGIN_PERCENT', 'SHARE'])

/** Pl-PL bez separatora dla liczb czterocyfrowych dawało „1108,58” obok „17 126,81”; tu zawsze z odstępem. */
const amount = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: 'always',
})
const points = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
  signDisplay: 'exceptZero',
})
const percentChange = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 0,
  signDisplay: 'exceptZero',
  useGrouping: 'always',
})

/** Te same kolumny w nagłówku, treści i sumie, żeby trzy osobne tabele trzymały jedną siatkę. */
function Columns({ metrics, stores }: { metrics: number; stores: number }) {
  return (
    <colgroup>
      <col style={{ width: `${NAME_REM}rem` }} />
      {Array.from({ length: metrics }, (_, metric) => (
        <Fragment key={metric}>
          {Array.from({ length: stores }, (_, store) => (
            <col key={store} style={{ width: `${NUMBER_REM}rem` }} />
          ))}
        </Fragment>
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

const indent = (depth: number) => `${1 + depth * 1.25}rem`

/**
 * Poziomy różni waga, wcięcie i tło (rejon na tle wyszarzonym, reszta na tle karty), a kolor poziomu zostaje tylko w
 * kropce przy kodzie. Pełne kolorowe tła wierszy zagłuszały liczby i mieszały się z kolorami ocen. Tła są pełne, bo
 * przyklejona kolumna nazwy przykrywa przewijane w bok liczby.
 */
const GROUP_ROWS = {
  REGION: { row: 'bg-muted hover:bg-accent', text: 'font-semibold', padding: 'py-2.5' },
  FAMILY: { row: 'bg-card hover:bg-muted', text: 'font-semibold', padding: 'py-2' },
  SUBFAMILY: { row: 'bg-card hover:bg-muted', text: 'font-medium', padding: 'py-1.5' },
} as const

function GroupRow({
  level,
  code,
  name,
  values,
  open,
  onToggle,
  metrics,
  basis,
  compared,
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
  compared: boolean
  depth: number
  hint?: string
}) {
  const style = GROUP_ROWS[level]
  return (
    <tr className={cn('cursor-pointer', style.row)} onClick={onToggle}>
      <td
        className={cn('sticky left-0 z-10 border-b bg-inherit pr-3', style.padding)}
        style={{ paddingLeft: indent(depth) }}
      >
        <button
          type="button"
          aria-expanded={open}
          onClick={(event) => {
            event.stopPropagation()
            onToggle()
          }}
          className="flex w-full min-w-0 items-center gap-1.5 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <ChevronRightIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out motion-reduce:transition-none',
              open && 'rotate-90 text-foreground',
            )}
          />
          <span aria-hidden className={cn('size-1.5 shrink-0 rounded-full', LEVEL_STYLES[level].dot)} />
          <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{displayCode(code)}</span>
          <span className={cn('truncate', style.text, !name && 'text-muted-foreground')} title={name ?? undefined}>
            {name ?? 'Brak nazwy w nomenklaturze'}
          </span>
          {hint && <span className="shrink-0 text-xs text-muted-foreground">{hint}</span>}
        </button>
      </td>
      <ValueCells
        values={values}
        metrics={metrics}
        basis={basis}
        compared={compared}
        variant={level === 'SUBFAMILY' ? 'row' : 'group'}
      />
    </tr>
  )
}

/**
 * Wiersz artykułu w jednej linii: nazwa ucięta (pełna w podpowiedzi) i krótki ITM8. Przy połączonych artykułach
 * ikona łańcucha jest zawsze widoczna i mówi, z czym połączono; przy wierszu, któremu brakuje sklepu, pojawia się po
 * najechaniu, żeby nie zaśmiecać tabeli.
 */
function ArticleRow({
  article,
  stores,
  striped,
  canLink,
  onLink,
  children,
}: {
  article: CounterArticle
  stores: Report['stores']
  striped: boolean
  canLink: boolean
  onLink: () => void
  children: ReactNode
}) {
  const linked = article.sources.some((source, index) => index > 0 && source?.linkId)
  const missing = stores.length > 1 && article.values.some((figures) => figures == null)
  const others = article.sources
    .map((source, index) =>
      source && source.itm8 !== article.itm8
        ? `${stores[index].number}: ${source.name ?? shortItm8(source.itm8)} (${shortItm8(source.itm8)})`
        : null,
    )
    .filter(Boolean)
  const title = [article.name, `ITM8 ${shortItm8(article.itm8)}`, article.brand, ...others].filter(Boolean).join('\n')
  return (
    <tr
      className={cn(
        'group',
        striped ? 'bg-[color-mix(in_oklab,var(--muted)_45%,var(--card))]' : 'bg-card',
        'hover:bg-muted',
      )}
    >
      <td className="sticky left-0 z-10 border-b bg-inherit py-1.5 pr-2" style={{ paddingLeft: indent(3) }}>
        <div className="flex min-w-0 items-center gap-2">
          <span className={cn('min-w-0 truncate', !article.name && 'text-muted-foreground')} title={title}>
            {article.name ?? 'Brak w kartotece tego sklepu'}
          </span>
          <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{shortItm8(article.itm8)}</span>
          <span className="ml-auto flex shrink-0 items-center">
            {(linked || (canLink && missing)) && (
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label={linked ? 'Połączony z artykułem innego sklepu' : 'Połącz z artykułem innego sklepu'}
                title={linked ? `Połączony:\n${others.join('\n')}` : 'Połącz z tym samym towarem w innym sklepie'}
                onClick={onLink}
                className={cn(
                  linked
                    ? 'text-sky-700 dark:text-sky-300'
                    : 'text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100',
                )}
              >
                <Link2Icon />
              </Button>
            )}
            <ArticleStatsButton article={{ itm8: article.itm8, name: article.name, brand: article.brand }} />
          </span>
        </div>
      </td>
      {children}
    </tr>
  )
}

/** Okno łączenia dla wiersza raportu lady: źródłem jest artykuł bieżącego sklepu, a bez niego pierwszego, który go ma. */
function CounterLinkDialog({
  storeId,
  report,
  article,
  canEdit,
  onClose,
}: {
  storeId: string
  report: Report
  article: CounterArticle
  canEdit: boolean
  onClose: () => void
}) {
  const stores: LinkStore[] = report.stores
  const from = Math.max(
    0,
    article.values.findIndex((figures) => figures != null),
  )
  const fromSource = article.sources[from] ?? { itm8: article.itm8, name: article.name, linkId: null }
  const candidates = useMemo(() => {
    const all = report.regions.flatMap((region) =>
      region.families.flatMap((family) =>
        family.subfamilies.flatMap((subfamily) =>
          subfamily.articles.map((item) => ({ item, subfamily: subfamily.name })),
        ),
      ),
    )
    return stores.map((_, target) =>
      all
        .filter(({ item }) => item.values[target] != null && item.values[from] == null && item.sources[target])
        .map(({ item, subfamily }) => ({
          itm8: item.sources[target]!.itm8,
          name: item.sources[target]!.name,
          brand: item.brand,
          hint: subfamily ?? undefined,
        })),
    )
  }, [report, stores, from])

  return (
    <ArticleLinkDialog
      storeId={storeId}
      open
      onOpenChange={(open) => !open && onClose()}
      canEdit={canEdit}
      source={{ store: stores[from], item: { itm8: fromSource.itm8, name: fromSource.name, brand: article.brand } }}
      linked={article.sources.flatMap((source, index) =>
        index !== from && source?.linkId
          ? [{ store: stores[index], item: { itm8: source.itm8, name: source.name }, linkId: source.linkId }]
          : [],
      )}
      targets={stores.flatMap((store, index) =>
        index !== from && article.values[index] == null ? [{ store, candidates: candidates[index] }] : [],
      )}
    />
  )
}

/** Wartość miary jako liczba (do różnicy między sklepami). */
function metricNumber(metric: CounterMetric, figures: CounterFigures, basis: Basis): number | null {
  switch (metric) {
    case 'QUANTITY':
      return figures.quantity
    case 'COST':
      return figures.costValue
    case 'SALE':
      return basis === 'NET' ? figures.saleValueNet : figures.saleValueGross
    case 'MARGIN':
      return figures.marginValue
    case 'MARGIN_PERCENT':
      return figures.marginPercent
    case 'SHARE':
      return figures.sharePercent
    case 'LOSSES':
      return figures.lossValue
  }
}

/**
 * Różnica sklepu względem bieżącego (pierwszego): przy marży % i udziale w punktach procentowych, przy ilościach i
 * kwotach w % różnicy. Bez wartości w którymś z nich nic.
 */
function difference(metric: CounterMetric, own: number | null, value: number | null) {
  if (own == null || value == null) return null
  if (PERCENT_POINTS.has(metric)) return value - own
  return own === 0 ? null : ((value - own) / Math.abs(own)) * 100
}

/** Najwyższa i najniższa wartość miary w wierszu; bez porównania (mniej niż dwa sklepy albo remis) nic. */
function extremes(numbers: (number | null)[]) {
  const known = numbers.flatMap((value, index) => (value == null ? [] : [{ index, value }]))
  if (known.length < 2) return null
  const best = known.reduce((a, b) => (b.value > a.value ? b : a))
  const worst = known.reduce((a, b) => (b.value < a.value ? b : a))
  return best.value === worst.value ? null : { best: best.index, worst: worst.index }
}

/** Kolory skrajnych wartości; w „Razem lada” (odwrócone tło) jaśniejsze w jasnym motywie i ciemniejsze w ciemnym. */
const BEST = 'text-emerald-700 dark:text-emerald-400'
const WORST = 'text-red-700 dark:text-red-400'
const BEST_ON_TOTAL = 'text-emerald-300 dark:text-emerald-700'
const WORST_ON_TOTAL = 'text-red-300 dark:text-red-700'

function ValueCells({
  values,
  metrics,
  basis,
  compared,
  variant = 'row',
}: {
  values: CounterValues
  metrics: CounterMetric[]
  basis: Basis
  compared: boolean
  variant?: 'row' | 'group' | 'total'
}) {
  const total = variant === 'total'
  return metrics.map((metric, metricIndex) => {
    const numbers = values.map((figures) => (figures ? metricNumber(metric, figures, basis) : null))
    const edge = compared && !UNCOLORED.has(metric) ? extremes(numbers) : null
    return (
      <Fragment key={metric}>
        {values.map((figures, storeIndex) => {
          const change = storeIndex > 0 ? difference(metric, numbers[0], numbers[storeIndex]) : null
          const tone =
            edge?.best === storeIndex
              ? total
                ? BEST_ON_TOTAL
                : BEST
              : edge?.worst === storeIndex
                ? total
                  ? WORST_ON_TOTAL
                  : WORST
                : null
          return (
            <td
              key={storeIndex}
              className={cn(
                'px-2 py-1.5 text-right align-top whitespace-nowrap',
                variant === 'group' && 'py-2',
                !total && 'border-b',
                total && 'py-3',
                (variant !== 'row' || storeIndex === 0) && 'font-semibold',
                storeIndex === 0 && metricIndex > 0 && (total ? 'border-l border-l-background/25' : GROUP_EDGE),
                figures == null && (total ? 'text-background/60' : 'text-muted-foreground'),
                UNCOLORED.has(metric) && figures && !total && 'text-muted-foreground',
                tone,
              )}
            >
              <span className="block">{figures == null ? '–' : metricValue(metric, figures, basis)}</span>
              {change != null && (
                <span
                  className={cn(
                    'block text-[0.6875rem] leading-tight font-normal',
                    total ? 'text-background/70' : 'text-muted-foreground',
                  )}
                >
                  {PERCENT_POINTS.has(metric) ? `${points.format(change)} p.p.` : `${percentChange.format(change)}%`}
                </span>
              )}
            </td>
          )
        })}
      </Fragment>
    )
  })
}

function metricLabel(metric: CounterMetric, basis: Basis) {
  if (metric === 'SALE') return basis === 'NET' ? 'Sprzedaż netto' : 'Sprzedaż brutto'
  return METRICS.find((item) => item.key === metric)!.short
}

function metricValue(metric: CounterMetric, figures: CounterFigures, basis: Basis) {
  switch (metric) {
    case 'QUANTITY':
      return amount.format(figures.quantity)
    case 'COST':
      return amount.format(figures.costValue)
    case 'SALE':
      return amount.format(basis === 'NET' ? figures.saleValueNet : figures.saleValueGross)
    case 'MARGIN':
      return amount.format(figures.marginValue)
    case 'MARGIN_PERCENT':
      return formatPercent(figures.marginPercent)
    case 'SHARE':
      return formatPercent(figures.sharePercent)
    case 'LOSSES':
      return amount.format(figures.lossValue)
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
        {figures ? `${amount.format(basis === 'NET' ? figures.saleValueNet : figures.saleValueGross)} zł` : '–'}
      </p>
      <p className={cn('text-sm tabular-nums', muted)}>
        {figures
          ? `marża po likwidacjach ${formatPercent(figures.marginPercent)} · ${amount.format(figures.marginValue)} zł`
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
