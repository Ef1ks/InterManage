import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDownIcon, ChevronsDownUpIcon, ChevronsUpDownIcon, TriangleAlertIcon } from 'lucide-react'

import { BackLink } from '@/components/BackLink'
import { MultiComboboxField, SegmentedField, SelectField, TextField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import {
  type SalesChartTarget,
  useSalesNomenclatures,
  useSalesReportsOverview,
  useTopSales,
  useTopSalesPartners,
} from '@/features/sales/api'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { NomenclatureNav, type NomenclatureNavItem } from '@/features/sales/NomenclatureNav'
import { salesPaths } from '@/features/sales/paths'
import { ChartButton, formatPercent, nomenclatureName } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import { dayCount, daysLabel, formatRange, PERIOD_MODES } from '@/features/sales/periods'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import type { TopSalesGroup, TopSalesLevel, TopSalesMetric, TopSalesReport as Report, TopSalesRow } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const METRICS: { value: TopSalesMetric; label: string; param: string }[] = [
  { value: 'QUANTITY', label: 'Ilość', param: 'ilosc' },
  { value: 'MARGIN', label: 'Marża zł', param: 'marza' },
  { value: 'MARGIN_PERCENT', label: 'Marża %', param: 'marza-procent' },
]
const LEVELS: { value: TopSalesLevel; label: string; param: string }[] = [
  { value: 'REGION', label: 'Rejon', param: 'rejon' },
  { value: 'FAMILY', label: 'Rodzina', param: 'rodzina' },
  { value: 'SUBFAMILY', label: 'Podrodzina', param: 'podrodzina' },
  { value: 'STORE', label: 'Cały sklep', param: 'sklep' },
]

const DEFAULT_LIMIT = 20
const MAX_LIMIT = 1000
/** Przy pojedynczych sztukach procent marży jest przypadkowy, więc top % ma próg ilości. */
const DEFAULT_MIN_QUANTITY = 5
const UNASSIGNED_KEY = 'brak'
/** Przyklejony nagłówek aplikacji: pozycja jest bieżąca, gdy jej kafelek doszedł do tej wysokości. */
const HEADER_OFFSET_PX = 96

const money = (value: number) => `${formatMoney(value)} zł`
const groupKey = (group: TopSalesGroup) => (group.code === '' ? UNASSIGNED_KEY : (group.code ?? 'sklep'))
const groupName = (group: TopSalesGroup) =>
  group.code === '' ? 'Bez nomenklatury' : group.name ? nomenclatureName(group.name) : 'Brak nazwy w nomenklaturze'

const metricValue = (
  metric: TopSalesMetric,
  figures: { quantity: number; marginValue: number; marginPercent: number | null },
) =>
  metric === 'QUANTITY'
    ? formatQuantity(figures.quantity)
    : metric === 'MARGIN'
      ? money(figures.marginValue)
      : formatPercent(figures.marginPercent)

/**
 * Topy sprzedażowe: najlepsze artykuły okresu według ilości, marży zł albo marży %, w każdej pozycji wybranego poziomu
 * nomenklatury albo w całym sklepie, z porównaniem ze sklepami zaprzyjaźnionymi. Stan raportu jest w adresie.
 */
export function TopSalesReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const partners = useTopSalesPartners(storeId)
  const { params, update } = useReportParams()

  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod({ params, lastDate: lastSaleDate, defaultMode: 'day', customDays: 7 })
  const { mode, customError, range } = period

  const metric = METRICS.find((item) => item.param === params.get('top'))?.value ?? 'QUANTITY'
  const level = LEVELS.find((item) => item.param === params.get('poziom'))?.value ?? 'REGION'
  const filter = useMemo(() => [...new Set(params.getAll('nom'))].sort(), [params])
  const compared = useMemo(() => [...new Set(params.getAll('sklep'))].sort(), [params])
  const rankParam = params.get('ranking')
  const rankStore = rankParam && compared.includes(rankParam) ? rankParam : storeId

  const limitField = useSearchParamText('ile')
  const limit = limitField.debounced === '' ? DEFAULT_LIMIT : Number(limitField.debounced)
  const limitError =
    Number.isInteger(limit) && limit >= 1 && limit <= MAX_LIMIT ? undefined : `Podaj liczbę od 1 do ${MAX_LIMIT}.`
  const minQuantityField = useSearchParamText('min_ilosc')
  const minQuantity =
    minQuantityField.debounced === '' ? DEFAULT_MIN_QUANTITY : Number(minQuantityField.debounced.replace(',', '.'))
  const minQuantityError = Number.isFinite(minQuantity) && minQuantity >= 0 ? undefined : 'Podaj ilość.'

  const valid = !customError && !limitError && !(metric === 'MARGIN_PERCENT' && minQuantityError)
  const report = useTopSales(
    storeId,
    {
      range,
      metric,
      level,
      limit,
      minQuantity: metric === 'MARGIN_PERCENT' ? minQuantity : 0,
      filter,
      compared,
      rankStore,
    },
    valid && !overview.isPending,
  )

  const storeOptions = [
    { value: storeId, label: 'Ten sklep' },
    ...(partners.data ?? [])
      .filter((partner) => compared.includes(partner.id))
      .map((partner) => ({ value: partner.id, label: `${partner.number} ${partner.name}` })),
  ]

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Topy sprzedażowe</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Czym jest top"
            options={METRICS}
            value={metric}
            onChange={(next) => update({ top: METRICS.find((item) => item.value === next)?.param ?? null })}
          />
          <SegmentedField
            label="Poziom"
            options={LEVELS}
            value={level}
            onChange={(next) => update({ poziom: LEVELS.find((item) => item.value === next)?.param ?? null })}
          />
          <TextField
            label={`Ile wyników (domyślnie ${DEFAULT_LIMIT})`}
            inputMode="numeric"
            value={limitField.text}
            onChange={(event) => limitField.setText(event.target.value)}
            error={limitError}
            className="w-full sm:w-60"
          />
          {metric === 'MARGIN_PERCENT' && (
            <TextField
              label={`Najmniejsza ilość (domyślnie ${DEFAULT_MIN_QUANTITY})`}
              inputMode="decimal"
              value={minQuantityField.text}
              onChange={(event) => minQuantityField.setText(event.target.value)}
              error={minQuantityError}
              className="w-full sm:w-72"
            />
          )}
        </div>

        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Okres"
            options={PERIOD_MODES}
            value={mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <PeriodPicker period={period} update={update} />
        </div>

        <div className="grid gap-3 lg:grid-cols-2">
          <MultiComboboxField
            label="Nomenklatura"
            description="Tylko wybrane rejony, rodziny albo podrodziny. Puste = wszystkie."
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
          />
          {(partners.data?.length ?? 0) > 0 && (
            <div className="flex flex-wrap items-start gap-3">
              <MultiComboboxField
                label="Porównaj ze sklepami"
                description="Sklepy zaprzyjaźnione. Puste = tylko ten sklep."
                options={(partners.data ?? []).map((partner) => ({
                  value: partner.id,
                  label: `${partner.number} ${partner.name}`,
                }))}
                value={compared}
                onChange={(stores) => update({ sklep: stores })}
                placeholder="Wybierz sklepy…"
                className="min-w-0 flex-1 basis-64"
              />
              {compared.length > 0 && (
                <SelectField
                  label="Ranking ze sklepu"
                  options={storeOptions}
                  value={rankStore}
                  onChange={(next) => update({ ranking: next === storeId ? null : next })}
                  className="w-full sm:w-64"
                />
              )}
            </div>
          )}
        </div>

        {!customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground"> · {daysLabel(dayCount(range))}</span>
          </p>
        )}
      </Card>

      {!valid ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć topów</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <Card className="items-center px-6 py-10 text-center text-sm text-muted-foreground">Liczę topy…</Card>
      ) : (
        <Results
          key={`${level}:${report.data.rankStoreId}`}
          storeId={storeId}
          report={report.data}
          lastSaleDate={lastSaleDate}
          refreshing={report.isFetching}
        />
      )}
    </>
  )
}

function Results({
  storeId,
  report,
  lastSaleDate,
  refreshing,
}: {
  storeId: string
  report: Report
  lastSaleDate: string
  refreshing: boolean
}) {
  const { groups, level } = report
  const [opened, setOpened] = useState<ReadonlySet<string>>(() => new Set())
  const [current, setCurrent] = useState<string | null>(null)
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const tiles = useRef(new Map<string, HTMLDivElement>())
  // Jedna pozycja nie ma czego zwijać, więc od razu pokazuje wyniki.
  const single = groups.length === 1
  const isOpen = (key: string) => single || opened.has(key)
  const toggle = (key: string) =>
    setOpened((previous) => {
      const next = new Set(previous)
      if (!next.delete(key)) next.add(key)
      return next
    })

  useEffect(() => {
    let frame = 0
    const update = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        let active: string | null = null
        for (const group of groups) {
          const key = groupKey(group)
          const top = tiles.current.get(key)?.getBoundingClientRect().top
          if (top == null) continue
          if (top > HEADER_OFFSET_PX && active != null) break
          active = key
        }
        setCurrent(active)
      })
    }
    update()
    window.addEventListener('scroll', update, { passive: true })
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('scroll', update)
    }
  }, [groups])

  const jump = useCallback((key: string) => {
    setOpened((previous) => new Set(previous).add(key))
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    tiles.current.get(key)?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }, [])

  const navItems: NomenclatureNavItem[] =
    level === 'STORE'
      ? []
      : groups.map((group) => ({
          key: groupKey(group),
          code: group.code ?? '',
          name: groupName(group),
          level,
          depth: 0,
        }))

  const rankedStore = report.stores[0]
  const others = report.stores.slice(1)
  const withoutSales = report.stores.filter((store) => store.articleCount === 0)
  const allOpen = groups.every((group) => opened.has(groupKey(group)))

  return (
    <div className={cn('transition-opacity duration-150', refreshing && 'opacity-60')}>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="space-y-1 text-sm text-muted-foreground">
          {rankedStore.promotionSkipped > 0 && (
            <p>
              Pominięto {formatQuantity(rankedStore.promotionSkipped)} art. z promocji z marżą do{' '}
              {report.maxPromotionMarginPercent}%, bo ich ilość to efekt ceny.
            </p>
          )}
          {rankedStore.noCostSkipped > 0 && (
            <p>
              Pominięto {formatQuantity(rankedStore.noCostSkipped)} art. z zerowym kosztem FIFO, bo marża byłaby
              fałszywa.
            </p>
          )}
        </div>
        {!single && (
          <Button variant="outline" onClick={() => setOpened(allOpen ? new Set() : new Set(groups.map(groupKey)))}>
            {allOpen ? <ChevronsDownUpIcon /> : <ChevronsUpDownIcon />}
            {allOpen ? 'Zwiń wszystko' : 'Rozwiń wszystko'}
          </Button>
        )}
      </div>

      {withoutSales.length > 0 && (
        <p className="mb-3 flex items-center gap-2 rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
          <TriangleAlertIcon className="size-4 shrink-0" />
          Brak sprzedaży w tym okresie: {withoutSales.map((store) => `${store.number} ${store.name}`).join(', ')}.
        </p>
      )}

      {groups.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak artykułów do pokazania</p>
          <p className="text-sm text-muted-foreground">W tym okresie żaden artykuł nie spełnia warunków raportu.</p>
        </Card>
      ) : (
        <div className="xl:grid xl:grid-cols-[minmax(0,1fr)_16rem] xl:items-start xl:gap-4">
          <div className="space-y-3">
            {groups.map((group) => {
              const key = groupKey(group)
              return (
                <Tile
                  key={key}
                  ref={(element) => {
                    if (element) tiles.current.set(key, element)
                    else tiles.current.delete(key)
                  }}
                  group={group}
                  report={report}
                  others={others}
                  open={isOpen(key)}
                  collapsible={!single}
                  onToggle={() => toggle(key)}
                  onChart={
                    report.rankStoreId === storeId
                      ? (row) =>
                          setChart({ kind: 'article', itm8: row.itm8, name: row.name, ean: row.ean, brand: row.brand })
                      : undefined
                  }
                />
              )
            })}
          </div>
          <NomenclatureNav items={navItems} current={current} onJump={jump} />
        </div>
      )}

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={[]}
        basis="gross"
        lastSaleDate={lastSaleDate}
        onClose={() => setChart(null)}
      />
    </div>
  )
}

function Tile({
  ref,
  group,
  report,
  others,
  open,
  collapsible,
  onToggle,
  onChart,
}: {
  ref: (element: HTMLDivElement | null) => void
  group: TopSalesGroup
  report: Report
  others: Report['stores']
  open: boolean
  collapsible: boolean
  onToggle: () => void
  onChart?: (row: TopSalesRow) => void
}) {
  const header = (
    <>
      <span className="flex min-w-0 flex-1 items-center gap-2">
        {collapsible && (
          <ChevronDownIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out',
              !open && '-rotate-90',
            )}
          />
        )}
        <span className="min-w-0">
          <span className="flex items-baseline gap-2">
            {group.code && (
              <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{displayCode(group.code)}</span>
            )}
            <span className="truncate font-semibold">{group.code === null ? 'Cały sklep' : groupName(group)}</span>
          </span>
          <span className="block text-xs text-muted-foreground">
            {Math.min(report.limit, group.articleCount)} z {formatQuantity(group.articleCount)} art.
          </span>
        </span>
      </span>
      <span className="flex gap-6 text-right tabular-nums">
        <HeaderFigure label="Sprzedaż netto" value={money(group.saleValueNet)} />
        <HeaderFigure label="Marża" value={money(group.marginValue)} />
        <HeaderFigure label="Marża %" value={formatPercent(group.marginPercent)} />
      </span>
    </>
  )
  const headerClass = 'flex w-full flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 text-left'

  return (
    <Card ref={ref} className="scroll-mt-20 gap-0 overflow-hidden py-0">
      {collapsible ? (
        <button
          type="button"
          aria-expanded={open}
          onClick={onToggle}
          className={cn(
            headerClass,
            'transition-colors duration-150 outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-inset',
          )}
        >
          {header}
        </button>
      ) : (
        <div className={headerClass}>{header}</div>
      )}

      {open && (
        <div className="overflow-x-auto border-t">
          <table className="w-full text-sm tabular-nums">
            <thead>
              <tr className="border-b text-muted-foreground">
                <th className="w-12 py-2 pr-2 pl-4 text-right font-medium">Nr</th>
                <th className="px-3 py-2 text-left font-medium">Artykuł</th>
                <NumberHead strong={report.metric === 'QUANTITY'}>Ilość</NumberHead>
                <NumberHead>Sprzedaż brutto</NumberHead>
                <NumberHead strong={report.metric === 'MARGIN'}>Marża</NumberHead>
                <NumberHead strong={report.metric === 'MARGIN_PERCENT'}>Marża %</NumberHead>
                {others.map((store) => (
                  <th key={store.id} className="border-l px-3 py-2 text-right font-medium" title={store.name}>
                    {store.number}
                  </th>
                ))}
                {onChart && <th className="w-10" aria-label="Wykres" />}
              </tr>
            </thead>
            <tbody>
              {group.rows.map((row) => (
                <tr key={row.itm8} className="border-b last:border-b-0">
                  <td className="py-2 pr-2 pl-4 text-right font-medium text-muted-foreground">{row.rank}</td>
                  <td className="px-3 py-2">
                    <span className={cn('block', !row.name && 'text-muted-foreground')}>
                      {row.name ?? 'Brak w kartotece'}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {[`ITM8 ${row.itm8}`, row.ean && `EAN ${row.ean}`, row.brand].filter(Boolean).join(' · ')}
                    </span>
                  </td>
                  <NumberCell strong={report.metric === 'QUANTITY'}>{formatQuantity(row.quantity)}</NumberCell>
                  <NumberCell>{money(row.saleValueGross)}</NumberCell>
                  <NumberCell strong={report.metric === 'MARGIN'}>{money(row.marginValue)}</NumberCell>
                  <NumberCell strong={report.metric === 'MARGIN_PERCENT'}>
                    {formatPercent(row.marginPercent)}
                  </NumberCell>
                  {others.map((store) => {
                    const other = row.others.find((item) => item.storeId === store.id)
                    return (
                      <td key={store.id} className="border-l px-3 py-2 text-right whitespace-nowrap">
                        {other ? (
                          <>
                            <span className="block">{metricValue(report.metric, other)}</span>
                            <span className="block text-xs text-muted-foreground">miejsce {other.rank}</span>
                          </>
                        ) : (
                          <span className="text-muted-foreground">–</span>
                        )}
                      </td>
                    )
                  })}
                  {onChart && (
                    <td className="pr-2">
                      <ChartButton onClick={() => onChart(row)} />
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

function HeaderFigure({ label, value }: { label: string; value: string }) {
  return (
    <span>
      <span className="block text-xs text-muted-foreground">{label}</span>
      <span className="block font-medium">{value}</span>
    </span>
  )
}

function NumberHead({ children, strong }: { children: string; strong?: boolean }) {
  return (
    <th className={cn('px-3 py-2 text-right font-medium whitespace-nowrap', strong && 'text-foreground')}>
      {children}
    </th>
  )
}

function NumberCell({ children, strong }: { children: string; strong?: boolean }) {
  return <td className={cn('px-3 py-2 text-right whitespace-nowrap', strong && 'font-semibold')}>{children}</td>
}
