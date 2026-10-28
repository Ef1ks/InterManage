import { ArrowLeftIcon, ChevronLeftIcon, ChevronRightIcon, DownloadIcon, PrinterIcon, TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { DateField, SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { formatMoney } from '@/features/documents/format'
import { usePeriodSummary, usePeriodSummaryPdf, useSalesReportsOverview } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import {
  canShiftForward,
  currentPeriod,
  type DateRange,
  dayCount,
  daysLabel,
  formatRange,
  lastCompleteWeekEnd,
  MAX_PERIOD_DAYS,
  PERIOD_MODES,
  type PeriodMode,
  resolveAnchor,
  shiftAnchor,
} from '@/features/sales/periods'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import type { PeriodSummary, PeriodSummaryFigures, PeriodSummaryLine } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Okres w adresie strony po polsku, jak w porównaniu sprzedaży. */
const MODE_PARAMS: Record<PeriodMode, string> = {
  day: 'dzien',
  week: 'tydzien',
  month: 'miesiac',
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

const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const budgetFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2 })
const percent = (value: number | null) => (value == null ? '–' : `${percentFormat.format(value)}%`)

/**
 * Podsumowanie sprzedaży i strat za jeden okres (bez porównania), następca arkusza „kombajn”: rejony nomenklatury
 * w grupach z sumami, marża, straty z likwidacji i marża po stratach, obok budżet rejonu z Ustawień sklepu.
 * Procent w budżecie ma zielone tło, poza budżetem czerwone. Wydruk to PDF z serwera.
 */
export function PeriodSummaryReport({ storeId }: { storeId: string }) {
  const overview = useSalesReportsOverview(storeId)
  const [params, setParams] = useSearchParams()

  // Raport najwyżej za wczoraj: dzisiejsza sprzedaż jeszcze trwa.
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday
  const mode = (Object.keys(MODE_PARAMS) as PeriodMode[]).find((key) => MODE_PARAMS[key] === params.get('okres')) ?? 'week'
  const anchor = resolveAnchor(mode, params.get('data') ?? lastSaleDate, lastSaleDate)
  const custom: DateRange = {
    from: params.get('od') ?? addDays(lastSaleDate, -6),
    to: params.get('do') ?? lastSaleDate,
  }
  const customError =
    mode !== 'custom'
      ? null
      : custom.from > custom.to
        ? 'Data „od” jest późniejsza niż „do”.'
        : dayCount(custom) > MAX_PERIOD_DAYS
          ? `Okres może mieć najwyżej ${MAX_PERIOD_DAYS} dni.`
          : null
  const range = currentPeriod(mode, anchor, custom, lastSaleDate)

  const update = (changes: Record<string, string>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(changes).forEach(([key, value]) => next.set(key, value))
        return next
      },
      { replace: true },
    )

  const summary = usePeriodSummary(storeId, range, !customError && !overview.isPending)
  const pdf = usePeriodSummaryPdf(storeId)
  const onPdfError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')

  return (
    <>
      {/* Tabela ma dużo kolumn, więc raport bierze całą szerokość strony (AppLayout). */}
      <span data-wide-page hidden />
      <Link
        to={salesPaths.overview}
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        <ArrowLeftIcon className="size-4" />
        Wszystkie raporty
      </Link>

      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Podsumowanie sprzedaży i strat</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.lastReportGeneratedAt} />}
      </div>

      <Card className="mb-4 gap-4 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <SegmentedField label="Okres" options={PERIOD_MODES} value={mode} onChange={(next) => update({ okres: MODE_PARAMS[next] })} />
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="lg"
              loading={pdf.download.isPending}
              disabled={!summary.data || !!customError}
              onClick={() => pdf.download.mutate(range, { onError: onPdfError })}
            >
              <DownloadIcon />
              Pobierz PDF
            </Button>
            <Button
              size="lg"
              loading={pdf.print.isPending}
              disabled={!summary.data || !!customError}
              onClick={() => pdf.print.mutate(range, { onError: onPdfError })}
            >
              <PrinterIcon />
              Drukuj
            </Button>
          </div>
        </div>

        {mode === 'custom' ? (
          <div className="flex flex-wrap items-start gap-3">
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
              shortcuts={[{ label: 'Ostatni dzień sprzedaży', value: lastSaleDate }]}
              error={customError ?? undefined}
              className="w-full sm:w-64"
            />
          </div>
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
                mode === 'week'
                  ? [{ label: 'Ostatni pełny tydzień', value: lastCompleteWeekEnd(lastSaleDate) }]
                  : [{ label: 'Ostatni dzień sprzedaży', value: lastSaleDate }]
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

        {!customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(range)}</span>
            <span className="text-muted-foreground"> · {daysLabel(dayCount(range))}</span>
          </p>
        )}
      </Card>

      {customError ? null : summary.isError && !summary.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć podsumowania</p>
          <Button variant="outline" onClick={() => summary.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !summary.data ? (
        <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', summary.isPlaceholderData && 'opacity-60')}>
          <Summary summary={summary.data} />
        </div>
      )}
    </>
  )
}

function Summary({ summary }: { summary: PeriodSummary }) {
  const canEditBudgets = useCan('SALES', 'EDIT')
  const total = summary.total
  const warnings = [
    (!summary.lastSaleDate || summary.to > summary.lastSaleDate) &&
      `Sprzedaż jest wgrana ${summary.lastSaleDate ? `do ${formatRange({ from: summary.lastSaleDate, to: summary.lastSaleDate })}` : 'w ogóle nie jest wgrana'}, okres jest niepełny.`,
    (!summary.documentsCoveredUntil || summary.to > summary.documentsCoveredUntil) &&
      `Likwidacje są wgrane ${summary.documentsCoveredUntil ? `do ${formatRange({ from: summary.documentsCoveredUntil, to: summary.documentsCoveredUntil })}` : 'w ogóle nie są wgrane'}, straty mogą być niepełne.`,
  ].filter(Boolean) as string[]

  return (
    <>
      {warnings.length > 0 && (
        <div className="mb-4 flex gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
          <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div>
            {warnings.map((warning) => (
              <p key={warning}>{warning}</p>
            ))}
          </div>
        </div>
      )}

      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Tile label="Sprzedaż brutto" value={`${formatMoney(total.saleValueGross)} zł`} detail={`netto ${formatMoney(total.saleValueNet)} zł`} />
        <Tile label="Marża" value={percent(total.marginPercent)} detail={`${formatMoney(total.marginValue)} zł`} />
        <Tile
          label="Straty (likwidacje)"
          value={percent(total.lossPercent)}
          detail={`${formatMoney(total.lossValue)} zł`}
        />
        <Tile
          label="Marża po stratach"
          value={percent(total.marginAfterLossesPercent)}
          detail={`${formatMoney(total.marginAfterLossesValue)} zł`}
          strong
        />
      </div>

      {!summary.budgetsSet && (
        <p className="mb-3 text-sm text-muted-foreground">
          Bez budżetu rejonów raport nie pokaże, gdzie marża jest za niska, a straty za wysokie.{' '}
          {canEditBudgets ? (
            <Link to="/ustawienia-sklepu/budzet" className="font-medium text-foreground underline underline-offset-4">
              Ustaw budżet w Ustawieniach sklepu
            </Link>
          ) : (
            'Budżet ustawia osoba z edycją sprzedaży w Ustawieniach sklepu.'
          )}
        </p>
      )}

      {summary.lines.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak sprzedaży i strat w tym okresie</p>
          <p className="max-w-sm text-sm text-muted-foreground">Wybierz inny okres albo wgraj raport sprzedaży w Imporcie danych.</p>
        </Card>
      ) : (
        <Card className="overflow-hidden py-0">
          <div className="max-h-[80svh] overflow-auto overscroll-contain">
            <table className="w-full text-sm tabular-nums">
              <TableHeader className="sticky top-0 z-20 bg-card shadow-[0_1px_0_var(--border)]">
                <TableRow className="hover:bg-transparent">
                  <TableHead className="sticky left-0 z-10 min-w-56 bg-card pl-4">Rejon</TableHead>
                  <NumberHead>Sprzedaż netto</NumberHead>
                  <NumberHead>Sprzedaż brutto</NumberHead>
                  <NumberHead>Zakup FIFO</NumberHead>
                  <NumberHead hint="% od sprzedaży netto · budżet">Marża</NumberHead>
                  <NumberHead hint="likwidacje · % od brutto · max">Straty</NumberHead>
                  <NumberHead hint="marża − straty">Marża po stratach</NumberHead>
                  <NumberHead hint="w sprzedaży sklepu" className="pr-4">
                    Udział
                  </NumberHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {summary.lines.map((line, index) => (
                  <SummaryRow key={index} line={line} />
                ))}
              </TableBody>
              <TableFooter className="sticky bottom-0 z-20 bg-foreground text-background shadow-[0_-1px_0_var(--border)]">
                <TableRow className="hover:bg-foreground">
                  <TableCell className="sticky left-0 z-10 bg-foreground py-3 pl-4 font-semibold">Razem sklep</TableCell>
                  <FigureCells figures={total} variant="total" />
                </TableRow>
              </TableFooter>
            </table>
          </div>
        </Card>
      )}

      <p className="mt-3 max-w-4xl text-xs leading-relaxed text-muted-foreground">
        Marża w % od sprzedaży netto. Straty to likwidacje w cenach zakupu, w % od sprzedaży brutto. Marża po stratach =
        marża − straty, w % od sprzedaży netto. Budżet marży i max strat ustawia się w Ustawieniach sklepu.
      </p>
    </>
  )
}

function NumberHead({ children, hint, className }: { children: string; hint?: string; className?: string }) {
  return (
    <TableHead className={cn('h-auto py-2 text-right align-bottom whitespace-nowrap', className)}>
      {children}
      {hint && <span className="block text-xs font-normal text-muted-foreground">{hint}</span>}
    </TableHead>
  )
}

/** Wcięcie poziomu grupy w rem (rozmiary interfejsu rosną z bazowym rem). */
const indent = (depth: number) => `${1 + depth * 1}rem`

function SummaryRow({ line }: { line: PeriodSummaryLine }) {
  if (line.kind === 'GROUP') {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell
          colSpan={9}
          className={cn(
            'pt-4 pb-1.5 text-xs font-semibold tracking-wide uppercase',
            line.depth === 0 ? 'bg-muted text-foreground' : 'text-muted-foreground',
          )}
          style={{ paddingLeft: indent(line.depth) }}
        >
          {line.name}
        </TableCell>
      </TableRow>
    )
  }
  const subtotal = line.kind === 'SUBTOTAL'
  return (
    <TableRow className={cn(subtotal ? 'border-t-2 border-t-foreground/15 bg-muted/50 font-semibold hover:bg-muted/50' : 'hover:bg-muted/40')}>
      <TableCell
        className={cn('sticky left-0 z-10 py-2.5 pr-4 whitespace-normal', subtotal ? 'bg-muted' : 'bg-card')}
        style={{ paddingLeft: indent(line.depth) }}
      >
        {subtotal ? (
          line.name
        ) : line.code ? (
          <span className="flex items-baseline gap-2">
            <span className="shrink-0 text-xs text-muted-foreground">{line.code}</span>
            <span className={cn(!line.name && 'text-muted-foreground')}>{line.name ?? 'Brak nazwy w nomenklaturze'}</span>
          </span>
        ) : (
          <span className="text-muted-foreground">{line.name}</span>
        )}
      </TableCell>
      <FigureCells
        figures={line.figures!}
        marginBudget={line.marginBudgetPercent}
        maxLoss={line.maxLossPercent}
        variant={subtotal ? 'subtotal' : 'region'}
      />
    </TableRow>
  )
}

function FigureCells({
  figures,
  marginBudget = null,
  maxLoss = null,
  variant,
}: {
  figures: PeriodSummaryFigures
  marginBudget?: number | null
  maxLoss?: number | null
  variant: 'region' | 'subtotal' | 'total'
}) {
  const total = variant === 'total'
  const muted = total ? 'text-background/70' : 'text-muted-foreground'
  // Względem budżetu: null = bez budżetu, true = w budżecie, false = poza nim.
  const marginOk = marginBudget == null || figures.marginPercent == null ? null : figures.marginPercent >= marginBudget
  const lossOk = maxLoss == null || figures.lossPercent == null ? null : figures.lossPercent <= maxLoss
  const cell = cn('py-2.5 text-right', total && 'font-semibold')

  return (
    <>
      <TableCell className={cell}>{formatMoney(figures.saleValueNet)}</TableCell>
      <TableCell className={cell}>{formatMoney(figures.saleValueGross)}</TableCell>
      <TableCell className={cn(cell, muted)}>{formatMoney(figures.costValue)}</TableCell>
      <TableCell className={cn(cell, BUDGET_CELL[String(marginOk)])}>
        {formatMoney(figures.marginValue)}
        <BudgetLine value={figures.marginPercent} ok={marginOk} budget={marginBudget} label="cel" muted={muted} />
      </TableCell>
      <TableCell className={cn(cell, BUDGET_CELL[String(lossOk)])}>
        {formatMoney(figures.lossValue)}
        <BudgetLine value={figures.lossPercent} ok={lossOk} budget={maxLoss} label="max" muted={muted} />
      </TableCell>
      <TableCell className={cn(cell, 'font-semibold')}>
        {formatMoney(figures.marginAfterLossesValue)}
        <span className={cn('block text-xs', muted)}>{percent(figures.marginAfterLossesPercent)}</span>
      </TableCell>
      <TableCell className={cn(cell, 'pr-4', muted)}>{total ? '100%' : percent(figures.sharePercent)}</TableCell>
    </>
  )
}

/** Tło komórki względem budżetu: zielone w budżecie, czerwone poza nim, bez tła, gdy budżetu nie ma. */
const BUDGET_CELL: Record<string, string> = {
  true: 'bg-emerald-50 dark:bg-emerald-500/10',
  false: 'bg-rose-50 dark:bg-rose-500/10',
  null: '',
}

/** Procent pod kwotą, z budżetem obok; w budżecie i poza nim pogrubiony w kolorze. */
function BudgetLine({
  value,
  ok,
  budget,
  label,
  muted,
}: {
  value: number | null
  ok: boolean | null
  budget: number | null
  label: string
  muted: string
}) {
  return (
    <span className="block text-xs font-normal">
      <span
        className={
          ok == null
            ? muted
            : ok
              ? 'font-semibold text-emerald-700 dark:text-emerald-400'
              : 'font-semibold text-rose-700 dark:text-rose-400'
        }
      >
        {percent(value)}
      </span>
      {budget != null && (
        <span className={muted}>
          {' '}
          · {label} {budgetFormat.format(budget)}%
        </span>
      )}
    </span>
  )
}

function Tile({ label, value, detail, strong }: { label: string; value: string; detail: string; strong?: boolean }) {
  return (
    <Card className={cn('gap-1 p-4', strong && 'bg-foreground text-background ring-0')}>
      <p className={cn('text-sm', strong ? 'text-background/70' : 'text-muted-foreground')}>{label}</p>
      <p className="text-2xl font-semibold tracking-tight tabular-nums">{value}</p>
      <p className={cn('text-sm tabular-nums', strong ? 'text-background/70' : 'text-muted-foreground')}>{detail}</p>
    </Card>
  )
}
