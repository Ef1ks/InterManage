import { useState } from 'react'
import { ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, DownloadIcon, PrinterIcon, TriangleAlertIcon } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { SegmentedField, SelectField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { FIRST_GRATIS_MONTH } from '@/features/documents/DocumentGratis'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { type MonthRange, useGratisReport, useGratisReportPdf } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { ApiError } from '@/lib/api'
import { addDays, addMonths, currentMonth, formatMonth, todayIso } from '@/lib/dates'
import type { GratisFigures, GratisReport as Report, GratisReportTile } from '@/lib/types'
import { cn } from '@/lib/utils'

type Mode = 'month' | 'range' | 'year'

const MODES: { value: Mode; label: string }[] = [
  { value: 'month', label: 'Miesiąc' },
  { value: 'range', label: 'Kilka miesięcy' },
  { value: 'year', label: 'Cały rok' },
]
/** Okres w adresie strony po polsku, jak w innych raportach. */
const MODE_PARAMS: Record<Mode, string> = { month: 'miesiac', range: 'zakres', year: 'rok' }

const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = (value: number | null) => (value == null ? '–' : `${percentFormat.format(value)}%`)
const wholeFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 })

/** Miesiące od stycznia 2026 do bieżącego, od najnowszego. */
function monthOptions() {
  const options = []
  for (let month = currentMonth(); month >= FIRST_GRATIS_MONTH; month = addMonths(month, -1)) {
    options.push({ value: month, label: formatMonth(month) })
  }
  return options
}

function yearOptions() {
  const options = []
  for (let year = Number(currentMonth().slice(0, 4)); year >= Number(FIRST_GRATIS_MONTH.slice(0, 4)); year--) {
    options.push({ value: String(year), label: String(year) })
  }
  return options
}

const clampMonth = (month: string | null, fallback: string) =>
  month && /^\d{4}-\d{2}$/.test(month) && month >= FIRST_GRATIS_MONTH && month <= currentMonth() ? month : fallback

/** Okres z adresu: miesiąc, zakres miesięcy albo rok (od stycznia do grudnia albo do bieżącego miesiąca). */
function resolveRange(mode: Mode, params: URLSearchParams): MonthRange {
  const now = currentMonth()
  if (mode === 'year') {
    const year = clampMonth(`${params.get('rok')}-01`, `${now.slice(0, 4)}-01`).slice(0, 4)
    const december = `${year}-12`
    return { from: `${year}-01`, to: december < now ? december : now }
  }
  const from = clampMonth(params.get('od'), now)
  if (mode === 'month') return { from, to: from }
  const to = clampMonth(params.get('do'), now)
  return to < from ? { from: to, to: from } : { from, to }
}

/**
 * Raport przyjętych gratisów: kafelek na każdy miesiąc (przy jednym miesiącu od razu rozwinięty), w nim rejony
 * nomenklatury z gratisem: za handel / ekspozycję, za pokrycie strat, obrót brutto rejonu i udział gratisów w obrocie.
 * Przy kilku miesiącach na górze ten sam kafelek za cały okres. Wydruk to PDF z serwera.
 */
export function GratisReport({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const mode = (Object.keys(MODE_PARAMS) as Mode[]).find((key) => MODE_PARAMS[key] === params.get('okres')) ?? 'month'
  const range = resolveRange(mode, params)
  const report = useGratisReport(storeId, range)
  const pdf = useGratisReportPdf(storeId)
  const months = monthOptions()

  const update = (changes: Record<string, string>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => next.set(key, value))
        return next
      },
      { replace: true },
    )
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')

  return (
    <>
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>
      <h2 className="mb-4 text-xl font-semibold tracking-tight">Przyjęte gratisy</h2>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <SegmentedField
            label="Okres"
            options={MODES}
            value={mode}
            onChange={(next) =>
              update(
                next === 'year'
                  ? { okres: MODE_PARAMS[next], rok: range.to.slice(0, 4) }
                  : { okres: MODE_PARAMS[next], od: next === 'month' ? range.to : range.from, do: range.to },
              )
            }
          />
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="lg"
              loading={pdf.download.isPending}
              disabled={!report.data}
              onClick={() => pdf.download.mutate(range, { onError: onPdfError })}
            >
              <DownloadIcon />
              Pobierz PDF
            </Button>
            <Button
              size="lg"
              loading={pdf.print.isPending}
              disabled={!report.data}
              onClick={() => pdf.print.mutate(range, { onError: onPdfError })}
            >
              <PrinterIcon />
              Drukuj
            </Button>
          </div>
        </div>

        {mode === 'month' ? (
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <Button
              variant="outline"
              size="icon-lg"
              aria-label="Poprzedni miesiąc"
              title="Poprzedni miesiąc"
              disabled={range.from <= FIRST_GRATIS_MONTH}
              onClick={() => update({ od: addMonths(range.from, -1) })}
            >
              <ChevronLeftIcon />
            </Button>
            <SelectField
              label="Miesiąc"
              options={months}
              value={range.from}
              onChange={(od) => update({ od })}
              className="min-w-0 flex-1 sm:w-64 sm:flex-none"
            />
            <Button
              variant="outline"
              size="icon-lg"
              aria-label="Następny miesiąc"
              title="Następny miesiąc"
              disabled={range.from >= currentMonth()}
              onClick={() => update({ od: addMonths(range.from, 1) })}
            >
              <ChevronRightIcon />
            </Button>
          </div>
        ) : mode === 'range' ? (
          <div className="flex flex-wrap gap-3">
            <SelectField
              label="Od"
              options={months}
              value={range.from}
              onChange={(od) => update({ od, do: od > range.to ? od : range.to })}
              className="w-full sm:w-64"
            />
            <SelectField
              label="Do"
              options={months}
              value={range.to}
              onChange={(to) => update({ od: to < range.from ? to : range.from, do: to })}
              className="w-full sm:w-64"
            />
          </div>
        ) : (
          <SelectField
            label="Rok"
            options={yearOptions()}
            value={range.from.slice(0, 4)}
            onChange={(rok) => update({ rok })}
            className="w-full sm:w-48"
          />
        )}
      </Card>

      {report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć raportu</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-72 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <Tiles key={`${report.data.from}-${report.data.to}`} report={report.data} />
        </div>
      )}

      <p className="mt-4 max-w-4xl text-xs leading-relaxed text-muted-foreground">
        Wartość gratisu to sprzedaż brutto przeniesienia stanów zarejestrowanego jako gratis (w Dokumentach), liczona
        w miesiącu gratisu: z daty dokumentu albo wybranym przy rejestracji. Obrót to sprzedaż brutto rejonu w tym
        okresie, a w wierszu Razem całego sklepu. % obrotu = gratisy razem / obrót.
      </p>
    </>
  )
}

/** Kafelki: podsumowanie okresu (rozwinięte) i miesiące, rozwinięte od razu tylko przy jednym miesiącu. */
function Tiles({ report }: { report: Report }) {
  const [open, setOpen] = useState<Set<string>>(
    () => new Set(report.summary ? ['summary'] : report.months.map((tile) => tile.from)),
  )
  const toggle = (key: string) =>
    setOpen((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  return (
    <>
      <CoverageWarning report={report} />
      <div className="grid items-start gap-3 lg:grid-cols-2">
        {report.summary && (
          <Tile
            tile={report.summary}
            summary
            open={open.has('summary')}
            onToggle={() => toggle('summary')}
            className="lg:col-span-2"
          />
        )}
        {report.months.map((tile) => (
          <Tile
            key={tile.from}
            tile={tile}
            open={open.has(tile.from)}
            onToggle={() => toggle(tile.from)}
            className={cn(report.months.length === 1 && 'lg:col-span-2')}
          />
        ))}
      </div>
    </>
  )
}

/** Ostrzeżenie, gdy okres sięga dalej niż wgrana sprzedaż albo dokumenty (najwyżej do wczoraj). */
function CoverageWarning({ report }: { report: Report }) {
  const yesterday = addDays(todayIso(), -1)
  const lastMonthDay = addDays(`${addMonths(report.to, 1)}-01`, -1)
  const end = lastMonthDay < yesterday ? lastMonthDay : yesterday
  const warnings = [
    (!report.lastSaleDate || report.lastSaleDate < end) &&
      `Sprzedaż jest wgrana ${report.lastSaleDate ? `do ${formatDocumentDate(report.lastSaleDate)}` : 'w ogóle nie jest wgrana'}, obrót jest niepełny.`,
    (!report.documentsCoveredUntil || report.documentsCoveredUntil < end) &&
      `Dokumenty są wgrane ${report.documentsCoveredUntil ? `do ${formatDocumentDate(report.documentsCoveredUntil)}` : 'w ogóle nie są wgrane'}, gratisów może być więcej.`,
  ].filter(Boolean) as string[]
  if (warnings.length === 0) return null
  return (
    <div className="mb-4 flex gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
      <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <div>
        {warnings.map((warning) => (
          <p key={warning}>{warning}</p>
        ))}
      </div>
    </div>
  )
}

function documentsLabel(count: number) {
  if (count === 0) return 'bez gratisów'
  if (count === 1) return '1 gratis'
  const lastTwo = count % 100
  const last = count % 10
  return `${count} ${last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'gratisy' : 'gratisów'}`
}

function Tile({
  tile,
  summary = false,
  open,
  onToggle,
  className,
}: {
  tile: GratisReportTile
  summary?: boolean
  open: boolean
  onToggle: () => void
  className?: string
}) {
  const total = tile.total
  return (
    <Card
      className={cn(
        'gap-0 overflow-hidden py-0',
        summary && 'ring-emerald-300/70 dark:ring-emerald-500/30',
        className,
      )}
    >
      <button
        type="button"
        aria-expanded={open}
        onClick={onToggle}
        className={cn(
          'flex w-full flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 text-left transition-colors duration-150 outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-inset',
          summary && 'bg-emerald-50/70 hover:bg-emerald-50 dark:bg-emerald-500/5 dark:hover:bg-emerald-500/10',
        )}
      >
        <span className="flex min-w-0 flex-1 items-center gap-2">
          <ChevronDownIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out',
              !open && '-rotate-90',
            )}
          />
          <span className="min-w-0">
            <span className="block truncate font-semibold">{tile.label}</span>
            <span className="block text-xs text-muted-foreground">{documentsLabel(tile.documentCount)}</span>
          </span>
        </span>
        <span className="flex gap-6 text-right tabular-nums">
          <HeaderFigure label="Gratisy razem" value={`${formatMoney(total.gratisValue)} zł`} strong />
          <HeaderFigure label="% obrotu" value={percent(total.gratisPercent)} />
        </span>
      </button>

      {open && (
        <div className="overflow-x-auto border-t">
          <table className="w-full text-sm tabular-nums">
            <thead>
              <tr className="border-b text-muted-foreground">
                <th className="py-2 pr-3 pl-4 text-left font-medium">Rejon</th>
                <NumberHead>Handel / ekspozycja</NumberHead>
                <NumberHead>Pokrycie strat</NumberHead>
                <NumberHead>Obrót brutto</NumberHead>
                <NumberHead className="pr-4">% obrotu</NumberHead>
              </tr>
            </thead>
            <tbody>
              {tile.regions.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-4 text-muted-foreground">
                    Brak gratisów w tym okresie.
                  </td>
                </tr>
              ) : (
                tile.regions.map((region) => (
                  <tr key={region.code} className="border-b transition-colors hover:bg-muted/40">
                    <td className="py-2 pr-3 pl-4">
                      {region.code ? (
                        <span className="flex items-baseline gap-2">
                          <span className="shrink-0 text-xs text-muted-foreground">{region.code}</span>
                          <span className={cn(!region.name && 'text-muted-foreground')}>
                            {region.name ?? 'Brak nazwy w nomenklaturze'}
                          </span>
                        </span>
                      ) : (
                        <span className="text-muted-foreground">{region.name}</span>
                      )}
                    </td>
                    <FigureCells figures={region.figures} />
                  </tr>
                ))
              )}
            </tbody>
            <tfoot>
              <tr className="bg-foreground font-semibold text-background">
                <td className="py-2.5 pr-3 pl-4">
                  Razem
                  <span className="block text-xs font-normal text-background/70">obrót całego sklepu</span>
                </td>
                <FigureCells figures={total} />
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </Card>
  )
}

function HeaderFigure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <span>
      <span className="block text-xs text-muted-foreground">{label}</span>
      <span className={cn('block', strong ? 'font-semibold' : 'text-muted-foreground')}>{value}</span>
    </span>
  )
}

function NumberHead({ children, className }: { children: string; className?: string }) {
  return <th className={cn('px-3 py-2 text-right font-medium whitespace-nowrap', className)}>{children}</th>
}

function FigureCells({ figures }: { figures: GratisFigures }) {
  return (
    <>
      <td className="px-3 py-2 text-right">{formatMoney(figures.tradeValue)}</td>
      <td className="px-3 py-2 text-right">{formatMoney(figures.lossValue)}</td>
      <td className="px-3 py-2 text-right opacity-70">{wholeFormat.format(figures.saleValueGross)}</td>
      <td className="py-2 pr-4 pl-3 text-right">{percent(figures.gratisPercent)}</td>
    </>
  )
}
