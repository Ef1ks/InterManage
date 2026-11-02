import { createContext, Fragment, type ReactNode, useCallback, useContext, useMemo, useState } from 'react'
import { ChartColumnIcon, ChevronLeftIcon, ChevronRightIcon, DownloadIcon, PrinterIcon, TriangleAlertIcon } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField, SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { formatMoney } from '@/features/documents/format'
import {
  type SalesChartTarget,
  usePeriodSummary,
  usePeriodSummaryChildren,
  usePeriodSummaryPdf,
  useSalesReportsOverview,
} from '@/features/sales/api'
import { barClass, displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
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
      <BackLink to={salesPaths.overview}>
        Wszystkie raporty
      </BackLink>

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
          <Summary storeId={storeId} summary={summary.data} />
        </div>
      )}
    </>
  )
}

function Summary({ storeId, summary }: { storeId: string; summary: PeriodSummary }) {
  const canEditBudgets = useCan('SALES', 'EDIT')
  const total = summary.total
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const toggle = useCallback(
    (key: string) =>
      setExpanded((current) => {
        const next = new Set(current)
        if (next.has(key)) {
          // Zwinięcie zwija też poziomy pod spodem.
          for (const item of current) if (item === key || (key !== UNASSIGNED_KEY && item.startsWith(key))) next.delete(item)
        } else {
          next.add(key)
        }
        return next
      }),
    [],
  )
  const tree = useMemo<Tree>(
    () => ({
      storeId,
      range: { from: summary.from, to: summary.to },
      storeGross: total.saleValueGross,
      expanded,
      toggle,
      openChart: setChart,
    }),
    [storeId, summary.from, summary.to, total.saleValueGross, expanded, toggle],
  )
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
        // Jeden pasek przewijania (strony): nagłówek kolumn klei się pod nagłówkiem aplikacji, „Razem” do dołu okna.
        // overflow-clip zamiast hidden, bo hidden robi z karty obszar przewijania i sticky przestaje działać. Na wąskim
        // ekranie tabela przewija się w bok we własnym obszarze (wtedy nagłówek klei się w nim).
        <Card className="overflow-clip py-0">
          <div className="max-lg:overflow-x-auto">
            <table className="w-full text-sm tabular-nums">
              <TableHeader className="sticky top-0 z-20 bg-card shadow-[0_1px_0_var(--border)] lg:top-14">
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
                <TreeContext.Provider value={tree}>
                  {summary.lines.map((line, index) => (
                    <SummaryRow key={index} line={line} />
                  ))}
                </TreeContext.Provider>
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
        marża − straty, w % od sprzedaży netto. Budżet marży i max strat ustawia się w Ustawieniach sklepu. Rejon
        rozwija się do rodzin, podrodzin i artykułów: sprzedaż według nomenklatury artykułu z kartoteki, straty według
        nomenklatury linii likwidacji.
      </p>

      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        target={chart}
        filter={NO_FILTER}
        basis="gross"
        lastSaleDate={summary.lastSaleDate ?? summary.to}
        onClose={() => setChart(null)}
      />
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
  if (line.kind === 'REGION' && line.code != null) return <RegionRow line={line} code={line.code} />
  return (
    <TableRow className="border-t-2 border-t-foreground/15 bg-muted/50 font-semibold hover:bg-muted/50">
      <TableCell className="sticky left-0 z-10 bg-muted py-2.5 pr-4 whitespace-normal" style={{ paddingLeft: indent(line.depth) }}>
        {line.name}
      </TableCell>
      <FigureCells figures={line.figures!} variant="subtotal" />
    </TableRow>
  )
}

/** Klucz rozwiniętego wiersza: kod nomenklatury (unikalny na wszystkich poziomach), a sprzedaż bez nomenklatury - "brak". */
const UNASSIGNED_KEY = 'brak'
const NO_FILTER: string[] = []

type Tree = {
  storeId: string
  range: DateRange
  /** Sprzedaż brutto sklepu, od której liczy się udział rozwiniętych pozycji. */
  storeGross: number
  expanded: ReadonlySet<string>
  toggle: (key: string) => void
  openChart: (target: SalesChartTarget) => void
}

const TreeContext = createContext<Tree | null>(null)

function useTree() {
  const tree = useContext(TreeContext)
  if (!tree) throw new Error('Wiersze podsumowania poza tabelą')
  return tree
}

/** Rejon z budżetem; rozwija się do rodzin (a „Bez nomenklatury” do artykułów), jak w porównaniu sprzedaży. */
function RegionRow({ line, code }: { line: PeriodSummaryLine; code: string }) {
  const { expanded, toggle, openChart } = useTree()
  const key = code === '' ? UNASSIGNED_KEY : code
  const open = expanded.has(key)
  const name = code === '' ? 'Bez nomenklatury' : (line.name ?? 'Brak nazwy w nomenklaturze')
  return (
    <>
      <TableRow
        className={cn('cursor-pointer', open ? LEVEL_STYLES.REGION.open : 'bg-card hover:bg-muted')}
        onClick={() => toggle(key)}
      >
        <TableCell
          className={cn('sticky left-0 z-10 bg-inherit py-2.5 pr-4 whitespace-normal', open && [barClass, LEVEL_STYLES.REGION.bar])}
          style={{ paddingLeft: indent(line.depth) }}
        >
          <div className="flex items-start gap-1">
            <ExpandButton open={open} onToggle={() => toggle(key)}>
              {code ? (
                <span className="flex min-w-0 items-baseline gap-2">
                  <span className="shrink-0 text-xs text-muted-foreground">{code}</span>
                  <span className={cn(open && 'font-semibold', !line.name && 'text-muted-foreground')}>{name}</span>
                </span>
              ) : (
                <span className="text-muted-foreground">{line.name}</span>
              )}
            </ExpandButton>
            <ChartButton
              onClick={() =>
                openChart(
                  code === ''
                    ? { kind: 'group', code: '', unassigned: true, name, levelLabel: 'Artykuły spoza kartoteki' }
                    : { kind: 'group', code, unassigned: false, name, levelLabel: `Rejon ${code}` },
                )
              }
            />
          </div>
        </TableCell>
        <FigureCells figures={line.figures!} marginBudget={line.marginBudgetPercent} maxLoss={line.maxLossPercent} variant="region" />
      </TableRow>
      {open && <LevelRows parent={code} unassigned={code === ''} depth={line.depth + 1} ancestors={[key]} />}
    </>
  )
}

/** Wiersze jednego poziomu pod rozwiniętym wierszem; rozwinięty wstawia pod sobą kolejny poziom. */
function LevelRows({
  parent,
  unassigned,
  depth,
  ancestors,
}: {
  parent: string
  unassigned: boolean
  depth: number
  /** Klucze rozwiniętych wierszy nad tym poziomem, od rejonu (linie prowadzące). */
  ancestors: string[]
}) {
  const { storeId, range, storeGross, expanded, toggle, openChart } = useTree()
  const { data, isPending, isError, refetch } = usePeriodSummaryChildren(storeId, range, parent, unassigned)
  const baseDepth = depth - ancestors.length

  if (isPending) {
    return Array.from({ length: 2 }, (_, row) => (
      <TableRow key={row} className="hover:bg-transparent" aria-hidden>
        <TableCell className="py-3.5" style={{ paddingLeft: indent(depth) }}>
          <div className="h-3.5 w-40 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
        </TableCell>
        {Array.from({ length: NUMBER_COLUMNS }, (_, column) => (
          <TableCell key={column}>
            <div className="ml-auto h-3.5 w-14 animate-pulse rounded-full bg-muted motion-reduce:animate-none" />
          </TableCell>
        ))}
      </TableRow>
    ))
  }
  if (isError && !data) {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={1 + NUMBER_COLUMNS} className="py-3 text-sm text-muted-foreground" style={{ paddingLeft: indent(depth) }}>
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
        <TableCell colSpan={1 + NUMBER_COLUMNS} className="py-3 text-sm text-muted-foreground" style={{ paddingLeft: indent(depth) }}>
          Brak sprzedaży i strat w tym okresie.
        </TableCell>
      </TableRow>
    )
  }

  const level = data.level
  return data.rows.map((row) => {
    const style = level === 'PRODUCT' ? null : LEVEL_STYLES[level]
    const open = style != null && expanded.has(row.code)
    const name = row.name?.replace(/^\d+_/, '') ?? null
    const figures = {
      ...row.figures,
      sharePercent: storeGross ? (row.figures.saleValueGross * 100) / storeGross : null,
    }
    const label =
      style == null ? (
        <span className="min-w-0">
          <span className={cn('block', !row.name && 'text-muted-foreground')}>{row.name ?? 'Brak w kartotece'}</span>
          <span className="block text-xs text-muted-foreground">
            {[`ITM8 ${row.code}`, row.ean && `EAN ${row.ean}`, row.brand].filter(Boolean).join(' · ')}
          </span>
        </span>
      ) : (
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="shrink-0 text-xs text-muted-foreground">{displayCode(row.code)}</span>
          <span className={cn(level === 'FAMILY' && 'font-medium', !name && 'text-muted-foreground')}>
            {name ?? 'Brak nazwy w nomenklaturze'}
          </span>
        </span>
      )
    return (
      <Fragment key={row.code}>
        <TableRow
          className={cn(style ? ['cursor-pointer', open ? style.open : style.row] : 'bg-card hover:bg-muted')}
          onClick={style ? () => toggle(row.code) : undefined}
        >
          <TableCell
            className={cn('sticky left-0 z-10 bg-inherit py-2.5 pr-4 whitespace-normal', style && [barClass, style.bar])}
            style={{ paddingLeft: indent(depth) }}
          >
            {/* Linie prowadzące: każdy poziom wyżej ma swoją pionową kreskę pod strzałką rodzica. */}
            {ancestors.map((ancestor, index) => (
              <span
                key={ancestor}
                aria-hidden
                className="absolute inset-y-0 w-px bg-foreground/15"
                style={{ left: `calc(${indent(baseDepth + index)} + 0.5rem)` }}
              />
            ))}
            <div className="flex items-start gap-1">
              {style ? (
                <ExpandButton open={open} onToggle={() => toggle(row.code)}>
                  {label}
                </ExpandButton>
              ) : (
                <div className="min-w-0 flex-1 pl-5.5">{label}</div>
              )}
              <ChartButton
                onClick={() =>
                  openChart(
                    style
                      ? {
                          kind: 'group',
                          code: row.code,
                          unassigned: false,
                          name: name ?? 'Brak nazwy w nomenklaturze',
                          levelLabel: `${style.label} ${displayCode(row.code)}`,
                        }
                      : { kind: 'article', itm8: row.code, name: row.name, ean: row.ean, brand: row.brand },
                  )
                }
              />
            </div>
          </TableCell>
          <FigureCells figures={figures} variant="region" />
        </TableRow>
        {open && <LevelRows parent={row.code} unassigned={false} depth={depth + 1} ancestors={[...ancestors, row.code]} />}
      </Fragment>
    )
  })
}

/** Liczba kolumn z liczbami (bez nazwy). */
const NUMBER_COLUMNS = 7

function ExpandButton({ open, onToggle, children }: { open: boolean; onToggle: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-expanded={open}
      onClick={(event) => {
        event.stopPropagation()
        onToggle()
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
      {children}
    </button>
  )
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
