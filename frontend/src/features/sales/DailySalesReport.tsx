import type { ReactNode } from 'react'
import { DownloadIcon, PrinterIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { SegmentedField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDailySales, useDailySalesPdf, usePriceCheckCategories, useSalesReportsOverview } from '@/features/sales/api'
import { displayCode } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { dayCount, daysLabel, formatRange, PERIOD_MODES, type PeriodMode } from '@/features/sales/periods'
import { formatPercent, nomenclatureName, StatTile } from '@/features/sales/report-parts'
import {
  lastCompleteDay,
  MODE_PARAMS,
  PeriodPicker,
  useReportParams,
  useReportPeriod,
} from '@/features/sales/report-period'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import { parseIsoDate } from '@/lib/dates'
import type {
  DailySalesFigures,
  DailySalesGrouping,
  DailySalesLayout,
  DailySalesLevel,
  DailySalesLine,
  DailySalesMeasure,
  DailySalesReport as Report,
} from '@/lib/types'
import { cn } from '@/lib/utils'

type Mode = Exclude<PeriodMode, 'day'>
const MODES = PERIOD_MODES.filter((option): option is { value: Mode; label: string } => option.value !== 'day')
const MODE_VALUES = MODES.map((option) => option.value)
/** Jak DailySalesService.MAX_DAYS: każdy dzień to kolumna. */
const MAX_DAYS = 62

const MEASURES: { value: DailySalesMeasure; label: string; param: string | null }[] = [
  { value: 'GROSS', label: 'Brutto', param: null },
  { value: 'NET', label: 'Netto', param: 'netto' },
  { value: 'MARGIN', label: 'Marża zł', param: 'marza' },
  { value: 'MARGIN_PERCENT', label: 'Marża %', param: 'marza-procent' },
  { value: 'QUANTITY', label: 'Ilość', param: 'ilosc' },
]
const LAYOUTS: { value: DailySalesLayout; label: string; param: string | null }[] = [
  { value: 'DAYS_AS_COLUMNS', label: 'Dni w kolumnach', param: null },
  { value: 'DAYS_AS_ROWS', label: 'Dni w wierszach', param: 'dni-w-wierszach' },
]
const LEVELS: { value: DailySalesLevel; label: string; param: string | null }[] = [
  { value: 'REGION', label: 'Rejony', param: null },
  { value: 'FAMILY', label: 'Rejony i rodziny', param: 'rodziny' },
]
/** Przy kategoriach drugi poziom to rejony kategorii (ten sam parametr adresu). */
const CATEGORY_LEVELS: { value: DailySalesLevel; label: string; param: string | null }[] = [
  { value: 'REGION', label: 'Same kategorie', param: null },
  { value: 'FAMILY', label: 'Kategorie i rejony', param: 'rodziny' },
]
const GROUPINGS: { value: DailySalesGrouping; label: string; param: string | null }[] = [
  { value: 'NOMENCLATURE', label: 'Nomenklatura', param: null },
  { value: 'CATEGORY', label: 'Kategorie sklepu', param: 'kategorie' },
]

const WEEKDAYS = ['nd', 'pn', 'wt', 'śr', 'cz', 'pt', 'sb']
const whole = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0, useGrouping: 'always' })
/** Niedziela: pełne tło, bo komórki nagłówka i pierwszej kolumny są przyklejone. */
const SUNDAY = 'bg-red-100 dark:bg-red-950'

const fromParam = <T extends string>(options: { value: T; param: string | null }[], param: string | null): T =>
  (options.find((option) => option.param === param) ?? options[0]).value

const paramOf = <T extends string>(options: { value: T; param: string | null }[], value: T) =>
  options.find((option) => option.value === value)?.param ?? null

const margin = (figures: DailySalesFigures) =>
  figures.saleValueGross ? (figures.marginValue * 100) / figures.saleValueGross : null

function cellText(figures: DailySalesFigures | null, measure: DailySalesMeasure) {
  if (!figures) return ''
  switch (measure) {
    case 'GROSS':
      return whole.format(figures.saleValueGross)
    case 'NET':
      return whole.format(figures.saleValueNet)
    case 'MARGIN':
      return whole.format(figures.marginValue)
    case 'QUANTITY':
      return whole.format(figures.quantity)
    case 'MARGIN_PERCENT':
      return formatPercent(margin(figures))
  }
}

const weekday = (day: string) => WEEKDAYS[parseIsoDate(day).getDay()]
const isSunday = (day: string) => parseIsoDate(day).getDay() === 0
const dayLabel = (day: string) => `${day.slice(8, 10)}.${day.slice(5, 7)}`
const lineName = (line: DailySalesLine) =>
  line.level === 'CATEGORY'
    ? (line.name ?? '')
    : line.code === ''
      ? 'Bez nomenklatury'
      : line.name
        ? nomenclatureName(line.name)
        : 'Brak nazwy w nomenklaturze'
/** Kod przy nazwie: rejon i rodzina mają kod nomenklatury, kategoria nie. */
const lineCode = (line: DailySalesLine) =>
  line.level === 'CATEGORY' ? null : line.code === '' ? '–' : displayCode(line.code)
/** Pozycja pierwszego poziomu: kategoria albo, bez kategorii, rejon. */
const isTop = (line: DailySalesLine) => line.level === 'CATEGORY' || line.level === 'REGION'

/**
 * Sprzedaż dzień po dniu: rejony (i rodziny) nomenklatury w wierszach, dni w kolumnach, na dole suma sklepu w każdym
 * dniu; układ da się odwrócić (dni w wierszach). Okres tydzień, miesiąc albo własny do 62 dni. Wydruk A4 poziomo
 * zmniejsza czcionkę, żeby miesiąc rejonów zmieścił się na jednej kartce. Stan raportu jest w adresie.
 */
export function DailySalesReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const { params, update } = useReportParams()
  const lastSaleDate = lastCompleteDay(overview.data?.lastSaleDate)
  const period = useReportPeriod<Mode>({
    params,
    lastDate: lastSaleDate,
    defaultMode: 'month',
    customDays: 14,
    maxDays: MAX_DAYS,
    modes: MODE_VALUES,
  })
  const { mode, customError, range } = period

  const measure = fromParam(MEASURES, params.get('miara'))
  const layout = fromParam(LAYOUTS, params.get('uklad'))
  const level = fromParam(LEVELS, params.get('poziom'))
  const categories = usePriceCheckCategories(storeId)
  const noCategories = categories.data?.length === 0
  const grouping = noCategories ? 'NOMENCLATURE' : fromParam(GROUPINGS, params.get('grupuj'))
  const levels = grouping === 'CATEGORY' ? CATEGORY_LEVELS : LEVELS

  const report = useDailySales(storeId, range, grouping, !customError && !overview.isPending)
  const pdf = useDailySalesPdf(storeId)
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
  const print = { range, level, measure, layout, grouping }
  const empty = !report.data || report.data.regions.length === 0

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Sprzedaż dzienna</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <SegmentedField
            label="Okres"
            options={MODES}
            value={mode}
            onChange={(next) => update({ okres: MODE_PARAMS[next] })}
          />
          <PeriodPicker period={period} update={update} />
        </div>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3 border-t pt-4">
          <Choice label="Pokaż">
            <SegmentedField
              label="Miara"
              options={MEASURES}
              value={measure}
              onChange={(next) => update({ miara: paramOf(MEASURES, next) })}
            />
          </Choice>
          <Choice label="Wiersze">
            <SegmentedField
              label="Wiersze"
              options={GROUPINGS.map((option) => ({
                ...option,
                disabled: option.value === 'CATEGORY' && noCategories,
              }))}
              value={grouping}
              onChange={(next) => update({ grupuj: paramOf(GROUPINGS, next) })}
            />
          </Choice>
          <Choice label="Szczegółowość">
            <SegmentedField
              label="Szczegółowość"
              options={levels}
              value={level}
              onChange={(next) => update({ poziom: paramOf(LEVELS, next) })}
            />
          </Choice>
          <Choice label="Układ">
            <SegmentedField
              label="Układ"
              options={LAYOUTS}
              value={layout}
              onChange={(next) => update({ uklad: paramOf(LAYOUTS, next) })}
            />
          </Choice>
          <div className="ml-auto flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="lg"
              loading={pdf.download.isPending}
              disabled={empty || !!customError}
              onClick={() => pdf.download.mutate(print, { onError: onPdfError })}
            >
              <DownloadIcon />
              Pobierz PDF
            </Button>
            <Button
              variant="outline"
              size="lg"
              loading={pdf.print.isPending}
              disabled={empty || !!customError}
              onClick={() => pdf.print.mutate(print, { onError: onPdfError })}
            >
              <PrinterIcon />
              Drukuj
            </Button>
          </div>
        </div>
        {!customError && (
          <p className="text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground">
              {' '}
              · {daysLabel(dayCount(range))} · kwoty w pełnych złotych, wydruk A4 poziomo w tym samym układzie
            </span>
          </p>
        )}
        {grouping === 'CATEGORY' ? (
          <p className="-mt-2 text-xs text-muted-foreground">
            Kategoria sumuje swoje rejony, a rejon w kilku kategoriach liczy się w każdej. Rejony spoza kategorii i
            sprzedaż bez nomenklatury są w „Pozostałe”.{' '}
            <Link to={storeSettingsPaths.priceCheck} className="underline underline-offset-4">
              Zmień kategorie
            </Link>
          </p>
        ) : (
          noCategories && (
            <p className="-mt-2 text-xs text-muted-foreground">
              Rejony można zebrać we własne kategorie (np. świeże, suche): załóż je w{' '}
              <Link to={storeSettingsPaths.priceCheck} className="underline underline-offset-4">
                Ustawieniach sklepu → Weryfikacja cen
              </Link>
              .
            </p>
          )
        )}
      </Card>

      {customError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć sprzedaży</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <LoadingPanel className="h-96 rounded-xl" />
      ) : empty ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak sprzedaży</p>
          <p className="text-sm text-muted-foreground">W tym okresie nie ma wgranej sprzedaży.</p>
        </Card>
      ) : (
        <div className={cn('transition-opacity duration-150', report.isFetching && 'opacity-60')}>
          <Tiles report={report.data} />
          <Card className="overflow-clip py-0">
            <div className="max-h-[calc(100dvh-9rem)] overflow-auto">
              {layout === 'DAYS_AS_COLUMNS' ? (
                <DaysAsColumns report={report.data} measure={measure} level={level} />
              ) : (
                <DaysAsRows report={report.data} measure={measure} level={level} />
              )}
            </div>
          </Card>
        </div>
      )}
    </>
  )
}

function Choice({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
    </div>
  )
}

function Tiles({ report }: { report: Report }) {
  const sum = report.total.sum
  const soldDays = report.total.days.filter((day): day is DailySalesFigures => day != null)
  const best = soldDays.length
    ? report.days.reduce<{ day: string; gross: number } | null>((top, day, index) => {
        const gross = report.total.days[index]?.saleValueGross ?? null
        return gross != null && (!top || gross > top.gross) ? { day, gross } : top
      }, null)
    : null
  if (!sum) return null
  return (
    <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      <StatTile
        label="Sprzedaż brutto"
        value={`${whole.format(sum.saleValueGross)} zł`}
        detail={`netto ${whole.format(sum.saleValueNet)} zł`}
        tone="strong"
      />
      <StatTile label="Marża" value={`${whole.format(sum.marginValue)} zł`} detail={formatPercent(margin(sum))} />
      <StatTile
        label="Średnio dziennie"
        value={`${whole.format(sum.saleValueGross / soldDays.length)} zł`}
        detail={`z ${daysLabel(soldDays.length)} ze sprzedażą`}
      />
      {best && (
        <StatTile
          label="Najlepszy dzień"
          value={`${whole.format(best.gross)} zł`}
          detail={`${weekday(best.day)} ${dayLabel(best.day)}`}
        />
      )}
    </div>
  )
}

/** Rejony, a przy poziomie rodzin pod każdym rejonem jego rodziny. */
function visibleLines(report: Report, level: DailySalesLevel) {
  return report.regions.flatMap((region) => (level === 'FAMILY' ? [region, ...region.children] : [region]))
}

const headCell = 'sticky top-0 z-20 border-b bg-card px-2 py-2 text-right text-xs font-medium whitespace-nowrap'
const numberCell = 'px-2 py-1.5 text-right whitespace-nowrap'
const footCell =
  'sticky bottom-0 z-20 bg-foreground px-2 py-2.5 text-right font-semibold whitespace-nowrap text-background'

function DaysAsColumns({
  report,
  measure,
  level,
}: {
  report: Report
  measure: DailySalesMeasure
  level: DailySalesLevel
}) {
  const lines = visibleLines(report, level)
  return (
    <table className="w-full text-sm tabular-nums">
      <thead>
        <tr>
          <th className={cn(headCell, 'left-0 z-30 pl-4 text-left')}>Nomenklatura</th>
          {report.days.map((day) => (
            <th key={day} className={cn(headCell, isSunday(day) && SUNDAY)}>
              <span className="block text-muted-foreground">{weekday(day)}</span>
              {dayLabel(day)}
            </th>
          ))}
          <th className={cn(headCell, 'pr-4')}>Razem</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((line, row) => {
          const top = isTop(line)
          const strong = top && level === 'FAMILY'
          return (
            <tr
              key={`${row}-${line.code}`}
              className={cn('border-b last:border-0 hover:bg-muted/60', strong && 'bg-muted font-semibold')}
            >
              <td
                className={cn(
                  'sticky left-0 z-10 max-w-72 truncate bg-card py-1.5 pr-3 whitespace-nowrap',
                  strong && 'bg-muted',
                  top ? 'pl-4' : 'pl-8',
                )}
                title={lineName(line)}
              >
                {line.code !== '' && lineCode(line) && (
                  <span className="mr-2 text-xs text-muted-foreground">{lineCode(line)}</span>
                )}
                {lineName(line)}
              </td>
              {line.days.map((figures, index) => (
                <td key={report.days[index]} className={cn(numberCell, isSunday(report.days[index]) && SUNDAY)}>
                  {cellText(figures, measure)}
                </td>
              ))}
              <td className={cn(numberCell, 'pr-4 font-semibold')}>{cellText(line.sum, measure)}</td>
            </tr>
          )
        })}
      </tbody>
      <tfoot>
        <tr>
          <td className={cn(footCell, 'left-0 z-30 pl-4 text-left')}>Razem sklep</td>
          {report.total.days.map((figures, index) => (
            <td key={report.days[index]} className={footCell}>
              {cellText(figures, measure)}
            </td>
          ))}
          <td className={cn(footCell, 'pr-4')}>{cellText(report.total.sum, measure)}</td>
        </tr>
      </tfoot>
    </table>
  )
}

function DaysAsRows({
  report,
  measure,
  level,
}: {
  report: Report
  measure: DailySalesMeasure
  level: DailySalesLevel
}) {
  const lines = visibleLines(report, level)
  return (
    <table className="w-full text-sm tabular-nums">
      <thead>
        <tr>
          <th className={cn(headCell, 'left-0 z-30 pl-4 text-left align-bottom')}>Dzień</th>
          {lines.map((line, column) => (
            <th
              key={`${column}-${line.code}`}
              className={cn(
                headCell,
                'max-w-28 align-bottom whitespace-normal',
                isTop(line) && level === 'FAMILY' && 'bg-muted',
              )}
              title={lineName(line)}
            >
              {lineCode(line) && <span className="block">{lineCode(line)}</span>}
              <span className="line-clamp-2 font-normal text-muted-foreground">{lineName(line)}</span>
            </th>
          ))}
          <th className={cn(headCell, 'pr-4 align-bottom')}>Razem</th>
        </tr>
      </thead>
      <tbody>
        {report.days.map((day, index) => (
          <tr key={day} className={cn('border-b last:border-0 hover:bg-muted/60', isSunday(day) && SUNDAY)}>
            <td
              className={cn('sticky left-0 z-10 bg-card py-1.5 pr-3 pl-4 whitespace-nowrap', isSunday(day) && SUNDAY)}
            >
              <span className="mr-2 inline-block w-5 text-muted-foreground">{weekday(day)}</span>
              {dayLabel(day)}
            </td>
            {lines.map((line, column) => (
              <td
                key={`${column}-${line.code}`}
                className={cn(numberCell, isTop(line) && level === 'FAMILY' && 'font-semibold')}
              >
                {cellText(line.days[index], measure)}
              </td>
            ))}
            <td className={cn(numberCell, 'pr-4 font-semibold')}>{cellText(report.total.days[index], measure)}</td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <td className={cn(footCell, 'left-0 z-30 pl-4 text-left')}>Razem</td>
          {lines.map((line, column) => (
            <td key={`${column}-${line.code}`} className={footCell}>
              {cellText(line.sum, measure)}
            </td>
          ))}
          <td className={cn(footCell, 'pr-4')}>{cellText(report.total.sum, measure)}</td>
        </tr>
      </tfoot>
    </table>
  )
}
