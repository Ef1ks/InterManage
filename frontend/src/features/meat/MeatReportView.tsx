import { useMemo } from 'react'
import { useSearchParams } from 'react-router'

import { DateField, MultiComboboxField, SegmentedField } from '@/components/form'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useMe } from '@/features/auth/api'
import { useMeatReport } from '@/features/meat/api'
import { formatPercent, formatPrice, margin } from '@/features/meat/format'
import { addDays, formatShortDate, todayIso } from '@/lib/dates'
import type { MeatReport, MeatReportCell, MeatReportMode, MeatReportRow, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const MODES: { value: MeatReportMode; label: string }[] = [
  { value: 'SPLIT', label: 'Po dniach dostaw' },
  { value: 'AVERAGE', label: 'Średnia z okresu' },
]

/**
 * Porównanie sklepów jak w arkuszu: cena zakupu netto za kg z WZ, cena sprzedaży brutto za kg z dnia dostawy i marża
 * (od sprzedaży netto). Stan w adresie: `od`, `do`, `tryb=srednia`, `sklep` (powtarzany).
 */
export function MeatReportView({ store }: { store: StoreSummary }) {
  const { data: me } = useMe()
  const [params, setParams] = useSearchParams()
  const today = todayIso()
  const from = params.get('od') ?? addDays(today, -6)
  const to = params.get('do') ?? today
  const mode: MeatReportMode = params.get('tryb') === 'srednia' ? 'AVERAGE' : 'SPLIT'
  const accessible = me?.stores ?? []
  const chosen = params.getAll('sklep').filter((id) => accessible.some((item) => item.id === id))
  const stores = chosen.length > 0 ? chosen : accessible.map((item) => item.id)
  const report = useMeatReport(store.id, { from, to, mode, stores })

  const update = (change: Record<string, string | string[] | null>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.entries(change).forEach(([key, value]) => {
          next.delete(key)
          if (Array.isArray(value)) value.forEach((item) => next.append(key, item))
          else if (value != null) next.set(key, value)
        })
        return next
      },
      { replace: true },
    )

  const shortcuts = [
    { label: 'Dziś', from: today, to: today },
    { label: 'Wczoraj', from: addDays(today, -1), to: addDays(today, -1) },
    { label: '7 dni', from: addDays(today, -6), to: today },
    { label: '30 dni', from: addDays(today, -29), to: today },
  ]

  return (
    <div data-wide-page className="space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <DateField label="Od" value={from} onChange={(value) => update({ od: value })} max={to} className="w-48" />
        <DateField label="Do" value={to} onChange={(value) => update({ do: value })} min={from} className="w-48" />
        <div className="flex flex-wrap gap-1">
          {shortcuts.map((shortcut) => (
            <Button
              key={shortcut.label}
              variant={shortcut.from === from && shortcut.to === to ? 'secondary' : 'ghost'}
              onClick={() => update({ od: shortcut.from, do: shortcut.to })}
            >
              {shortcut.label}
            </Button>
          ))}
        </div>
        <SegmentedField
          label="Sposób liczenia"
          options={MODES}
          value={mode}
          onChange={(value) => update({ tryb: value === 'AVERAGE' ? 'srednia' : null })}
        />
      </div>
      {accessible.length > 1 && (
        <MultiComboboxField
          label="Sklepy w porównaniu"
          options={accessible.map((item) => ({ value: item.id, label: `${item.number} ${item.name}` }))}
          value={stores}
          onChange={(value) => update({ sklep: value.length === accessible.length ? null : value })}
          className="max-w-2xl"
        />
      )}

      {report.isError && <p className="text-sm text-destructive">{report.error.message}</p>}
      {report.isPending && stores.length > 0 && <ReportSkeleton />}
      {report.data && report.data.rows.length === 0 && (
        <p className="rounded-xl border bg-card px-4 py-10 text-center text-sm text-muted-foreground">
          W tym okresie żaden sklep nie wprowadził dokumentu WZ.
        </p>
      )}
      {report.data && report.data.rows.length > 0 && <ReportTables report={report.data} />}
    </div>
  )
}

function ReportSkeleton() {
  return (
    <div className="overflow-hidden rounded-xl border bg-card">
      <Table>
        <TableBody>
          <TableSkeletonRows columns={5} rows={6} />
        </TableBody>
      </Table>
    </div>
  )
}

type Kind = 'purchase' | 'sale' | 'margin'

type Value = { value: number | null; fallback: boolean; title?: string }

function valueOf(kind: Kind, row: MeatReportRow, cell: MeatReportCell): Value {
  if (kind === 'purchase') return { value: cell.purchasePrice, fallback: false }
  const fallback = cell.saleFallback
  const title =
    fallback && cell.saleDate
      ? `Ostatnia znana cena sprzedaży z ${formatShortDate(cell.saleDate)}`
      : fallback
        ? undefined
        : 'Cena sprzedaży z dnia dostawy'
  if (kind === 'sale') return { value: cell.salePrice, fallback, title }
  return {
    value: cell.purchasePrice == null ? null : margin(cell.purchasePrice, cell.salePrice, row.vatPercent),
    fallback,
    title: fallback ? 'Marża z ostatniej znanej ceny sprzedaży' : undefined,
  }
}

const TABLES: { kind: Kind; title: string; legend: [string, string]; note: string }[] = [
  {
    kind: 'purchase',
    title: 'Cena zakupu netto za kg',
    legend: ['najtańszy zakup', 'najdroższy zakup'],
    note: 'Puste pole: sklep w tym okresie nie kupił.',
  },
  {
    kind: 'sale',
    title: 'Cena sprzedaży brutto za kg',
    legend: ['najtaniej', 'najdrożej'],
    note: 'Pogrubione: sprzedaż z dnia dostawy. Szare: ostatnia znana cena (sprzedaż z dnia dostawy jeszcze niewgrana albo brak zakupu).',
  },
  {
    kind: 'margin',
    title: 'Marża',
    legend: ['najniższa marża', 'najwyższa marża'],
    note: 'Marża od sprzedaży netto: (sprzedaż bez VAT − zakup) ÷ sprzedaż bez VAT.',
  },
]

function ReportTables({ report }: { report: MeatReport }) {
  return (
    <div className="space-y-8">
      {TABLES.map((table) => (
        <ReportTable key={table.kind} report={report} {...table} />
      ))}
    </div>
  )
}

const LOW = 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100'
const HIGH = 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'

function ReportTable({
  report,
  kind,
  title,
  legend,
  note,
}: {
  report: MeatReport
  kind: Kind
  title: string
  legend: [string, string]
  note: string
}) {
  const split = report.mode === 'SPLIT'
  const format = kind === 'margin' ? formatPercent : formatPrice
  const summary = useMemo(() => summarize(kind, report), [kind, report])

  return (
    <section className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className={cn('rounded-md px-1.5 py-0.5 font-medium', LOW)}>{legend[0]}</span>
          <span className={cn('rounded-md px-1.5 py-0.5 font-medium', HIGH)}>{legend[1]}</span>
        </div>
      </div>
      <p className="text-xs text-muted-foreground">{note}</p>
      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="min-w-48">Nazwa</TableHead>
              <TableHead>Gatunek</TableHead>
              {split && <TableHead>Data</TableHead>}
              {report.stores.map((item) => (
                <TableHead key={item.id} className="text-right" title={`${item.number} ${item.name}`}>
                  {item.name}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.rows.map((row) => {
              const values = row.cells.map((cell) => valueOf(kind, row, cell))
              const compared = values.filter((item, index) => item.value != null && !item.fallback && row.cells[index].purchasePrice != null)
              const numbers = compared.map((item) => item.value!)
              const min = numbers.length > 1 ? Math.min(...numbers) : null
              const max = numbers.length > 1 ? Math.max(...numbers) : null
              return (
                <TableRow key={row.key}>
                  <TableCell className="font-medium">
                    {row.name ?? '–'}
                    {row.itm8 == null && (
                      <span className="ml-2 text-xs font-normal text-muted-foreground">bez artykułu</span>
                    )}
                  </TableCell>
                  <TableCell className="text-muted-foreground">{row.brand ?? '–'}</TableCell>
                  {split && (
                    <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">
                      {row.date ? formatShortDate(row.date) : ''}
                    </TableCell>
                  )}
                  {values.map((item, index) => {
                    const purchased = row.cells[index].purchasePrice != null
                    const extreme =
                      min != null && max != null && max > min && item.value != null && !item.fallback && purchased
                        ? item.value === min
                          ? LOW
                          : item.value === max
                            ? HIGH
                            : null
                        : null
                    return (
                      <TableCell
                        key={row.cells[index].storeId}
                        title={item.value != null ? item.title : undefined}
                        className={cn(
                          'text-right whitespace-nowrap tabular-nums',
                          item.value != null && !item.fallback && purchased && 'font-semibold',
                          item.fallback && 'text-muted-foreground',
                          extreme,
                        )}
                      >
                        {item.value == null ? '' : format(item.value)}
                      </TableCell>
                    )
                  })}
                </TableRow>
              )
            })}
          </TableBody>
          <TableFooter>
            <TableRow>
              <TableCell colSpan={split ? 3 : 2} className="font-semibold">
                {kind === 'purchase' ? 'Średni zakup' : kind === 'sale' ? 'Średnia sprzedaż' : 'Średnia marża'}
              </TableCell>
              {summary.map((value, index) => (
                <TableCell key={report.stores[index].id} className="text-right font-semibold tabular-nums">
                  {value == null ? '' : format(value)}
                </TableCell>
              ))}
            </TableRow>
          </TableFooter>
        </Table>
      </div>
    </section>
  )
}

/**
 * Wiersz średnich jak w arkuszu: średnia cen zakupu, średnia cen sprzedaży z dni dostaw (bez cen zastępczych)
 * i marża z sum: (sprzedaż netto − zakup) ÷ sprzedaż netto po pozycjach z obiema cenami.
 */
function summarize(kind: Kind, report: MeatReport): (number | null)[] {
  return report.stores.map((_, index) => {
    const cells = report.rows.map((row) => ({ row, cell: row.cells[index] }))
    if (kind === 'purchase') {
      return average(cells.map(({ cell }) => cell.purchasePrice))
    }
    const sold = cells.filter(({ cell }) => cell.purchasePrice != null && cell.salePrice != null && !cell.saleFallback)
    if (kind === 'sale') {
      return average(sold.map(({ cell }) => cell.salePrice))
    }
    let net = 0
    let cost = 0
    sold.forEach(({ row, cell }) => {
      net += cell.salePrice! / (1 + row.vatPercent / 100)
      cost += cell.purchasePrice!
    })
    return net > 0 ? (net - cost) / net : null
  })
}

function average(values: (number | null)[]): number | null {
  const present = values.filter((value): value is number => value != null)
  return present.length === 0 ? null : present.reduce((sum, value) => sum + value, 0) / present.length
}
