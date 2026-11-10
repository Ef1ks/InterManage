import { Fragment, useDeferredValue, useMemo, useState } from 'react'
import { ChevronRightIcon, TriangleAlertIcon, XIcon } from 'lucide-react'

import { BackLink } from '@/components/BackLink'
import { CopyButton } from '@/components/CopyButton'
import { SegmentedField } from '@/components/form'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { useProtocolReportsOverview, useSupplierTurnover, useTurnoverSuppliers } from '@/features/sales/api'
import { barClass, displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'
import { salesPaths } from '@/features/sales/paths'
import { StatTile } from '@/features/sales/report-parts'
import { MODE_PARAMS, PeriodPicker, useReportParams, useReportPeriod } from '@/features/sales/report-period'
import { dayCount, daysLabel, formatRange, type PeriodMode } from '@/features/sales/periods'
import { todayIso } from '@/lib/dates'
import { matchesSearch } from '@/lib/search'
import type { SupplierTurnoverAmount, SupplierTurnoverReport as Report, SupplierTurnoverRow } from '@/lib/types'
import { cn } from '@/lib/utils'

const MODES: { value: Exclude<PeriodMode, 'day' | 'week'>; label: string }[] = [
  { value: 'month', label: 'Miesiąc' },
  { value: 'custom', label: 'Własny okres' },
]

const monthFormat = new Intl.DateTimeFormat('pl-PL', { month: 'long', year: 'numeric' })
const monthLabel = (iso: string) => {
  const label = monthFormat.format(new Date(`${iso}T12:00:00`))
  return label.charAt(0).toUpperCase() + label.slice(1)
}

/** Kwota do wklejenia w Excelu: bez odstępów tysięcy, z przecinkiem. */
const copyValue = (value: number) => value.toFixed(2).replace('.', ',')

/** Pierwszy dzień miesiąca {@code months} miesięcy wcześniej. */
function monthsBack(iso: string, months: number) {
  const [year, month] = iso.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1 - months, 1))
  return date.toISOString().slice(0, 10)
}

/** Kod rodzica w drzewie (rejon dla rodziny, rodzina dla podrodziny). */
const parentOf = (code: string) => (code.length > 2 ? code.slice(0, code.length - 2) : null)

/** Kwota z przyciskiem kopiowania. */
function Money({ value, label, strong }: { value: number; label: string; strong?: boolean }) {
  return (
    <span className="inline-flex items-center justify-end gap-0.5 whitespace-nowrap">
      <span className={cn('tabular-nums', strong && 'font-semibold', value < 0 && 'text-red-700 dark:text-red-400')}>
        {formatMoney(value)}
      </span>
      <CopyButton value={copyValue(value)} label={`Kopiuj: ${label}`} className="opacity-60 hover:opacity-100" />
    </span>
  )
}

/**
 * Obroty z dostawcami: za ile netto sklep kupił od wybranego dostawcy w każdym miesiącu okresu, po nomenklaturze
 * (rejon → rodzina → podrodzina). Liczą się przyjęcia PZ i przypięte do nich korekty. Zaznaczone pozycje sumują się
 * w pasku na dole, a każda kwota ma przycisk kopiowania.
 */
export function SupplierTurnoverReport({ storeId }: { storeId: string }) {
  const overview = useProtocolReportsOverview(storeId)
  const suppliers = useTurnoverSuppliers(storeId)
  const { params, update } = useReportParams()

  const today = todayIso()
  const coveredUntil = overview.data?.documentsCoveredUntil ?? null
  const lastDate = coveredUntil && coveredUntil < today ? coveredUntil : today
  const period = useReportPeriod({
    params,
    lastDate,
    defaultMode: 'month',
    customDays: 365,
    modes: MODES.map((mode) => mode.value),
  })
  const supplierCode = params.get('dostawca')
  const report = useSupplierTurnover(storeId, supplierCode, period.range, !period.customError)

  const selectedSupplier = suppliers.data?.find((supplier) => supplier.code === supplierCode) ?? null

  return (
    <>
      <span data-wide-page hidden />
      <BackLink to={salesPaths.overview}>Wszystkie raporty</BackLink>

      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-xl font-semibold tracking-tight">Obroty z dostawcami</h2>
        {overview.data && <ReportFreshness generatedAt={overview.data.documentsReportGeneratedAt} />}
      </div>
      <p className="mb-4 max-w-3xl text-sm text-muted-foreground">
        Za ile netto kupiliśmy od dostawcy w każdym miesiącu, po nomenklaturze: przyjęcia PZ wg daty przyjęcia razem z
        przypiętymi do nich korektami. Zaznacz pozycje, żeby zsumować je na dole strony.
      </p>

      <Card className="mb-4 gap-4 p-4">
        <SupplierSearch
          suppliers={suppliers.data}
          loading={suppliers.isPending}
          selected={selectedSupplier ? `${selectedSupplier.code} · ${selectedSupplier.name}` : supplierCode}
          onSelect={(code) => update({ dostawca: code })}
        />

        <div className="flex flex-wrap items-center gap-3">
          <SegmentedField
            label="Okres"
            options={MODES}
            value={period.mode}
            onChange={(next) =>
              update(
                next === 'custom' && period.mode !== 'custom'
                  ? { okres: MODE_PARAMS[next], od: monthsBack(lastDate, 11), do: lastDate }
                  : { okres: MODE_PARAMS[next] },
              )
            }
          />
        </div>
        <PeriodPicker period={period} update={update} max={today} lastDateLabel="Ostatni dzień z dokumentów" />

        {!period.customError && (
          <p className="border-t pt-4 text-sm tabular-nums">
            <span className="font-medium">{formatRange(period.range)}</span>
            <span className="text-muted-foreground"> · {daysLabel(dayCount(period.range))}</span>
          </p>
        )}
      </Card>

      {!supplierCode ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Wybierz dostawcę</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Wpisz nazwę, kod albo NIP dostawcy w polu wyszukiwania powyżej.
          </p>
        </Card>
      ) : period.customError ? null : report.isError && !report.data ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie udało się policzyć raportu</p>
          <Button variant="outline" onClick={() => report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <div aria-hidden className="h-96 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className={cn('transition-opacity duration-150', report.isPlaceholderData && 'opacity-60')}>
          <TurnoverTable
            key={`${report.data.supplierCode}:${report.data.from}:${report.data.to}`}
            report={report.data}
          />
        </div>
      )}
    </>
  )
}

function SupplierSearch({
  suppliers,
  loading,
  selected,
  onSelect,
}: {
  suppliers: { code: string; name: string; nip: string | null; lastDelivery: string }[] | undefined
  loading: boolean
  selected: string | null
  onSelect: (code: string | null) => void
}) {
  const [query, setQuery] = useState('')
  const deferred = useDeferredValue(query)
  const matches = useMemo(
    () =>
      deferred.trim()
        ? (suppliers ?? [])
            .filter((supplier) => matchesSearch([supplier.code, supplier.name, supplier.nip], deferred))
            .slice(0, 12)
        : [],
    [suppliers, deferred],
  )

  const pick = (code: string) => {
    onSelect(code)
    setQuery('')
  }

  return (
    <div className="space-y-2">
      <SearchInput
        value={query}
        onChange={setQuery}
        placeholder={loading ? 'Wczytywanie dostawców…' : 'Szukaj dostawcy: nazwa, kod albo NIP'}
        className="max-w-xl"
        autoFocus={!selected}
        onEnter={() => matches[0] && pick(matches[0].code)}
      />
      {query.trim() !== '' && (
        <div className="max-w-xl overflow-hidden rounded-xl border bg-popover">
          {matches.length === 0 ? (
            <p className="px-3 py-2.5 text-sm text-muted-foreground">
              {loading ? 'Wczytywanie…' : 'Brak dostawcy z przyjęciami PZ pasującego do wyszukiwania'}
            </p>
          ) : (
            <ul className="max-h-80 overflow-y-auto py-1">
              {matches.map((supplier) => (
                <li key={supplier.code}>
                  <button
                    type="button"
                    onClick={() => pick(supplier.code)}
                    className="flex w-full items-baseline gap-3 px-3 py-2 text-left text-sm transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
                  >
                    <span className="w-14 shrink-0 font-mono text-xs text-muted-foreground tabular-nums">
                      {supplier.code}
                    </span>
                    <span className="min-w-0 flex-1 truncate font-medium">{supplier.name}</span>
                    <span className="shrink-0 text-xs text-muted-foreground">
                      ostatnia dostawa {formatDocumentDate(supplier.lastDelivery)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {selected && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">Dostawca:</span>
          <span className="rounded-lg bg-muted px-2.5 py-1 font-medium">{selected}</span>
          <Button variant="ghost" size="icon-xs" aria-label="Usuń dostawcę" onClick={() => onSelect(null)}>
            <XIcon />
          </Button>
        </div>
      )}
    </div>
  )
}

function TurnoverTable({ report }: { report: Report }) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [selected, setSelected] = useState<Set<string>>(() => new Set())
  const months = report.months
  const multi = months.length > 1

  const children = useMemo(() => {
    const map = new Map<string | null, SupplierTurnoverRow[]>()
    report.rows.forEach((row) => {
      const parent = row.code ? parentOf(row.code) : null
      map.set(parent, [...(map.get(parent) ?? []), row])
    })
    // Pozycje od największego zakupu, „Bez nomenklatury” na końcu.
    map.forEach((rows) => rows.sort((a, b) => (a.code === '' ? 1 : b.code === '' ? -1 : b.total.net - a.total.net)))
    return map
  }, [report.rows])

  const byCode = useMemo(() => new Map(report.rows.map((row) => [row.code, row])), [report.rows])

  // Pozycja w zaznaczonym rodzicu już się liczy, więc nie dodajemy jej drugi raz.
  const coveredByParent = (code: string) => {
    for (let parent = parentOf(code); parent; parent = parentOf(parent)) if (selected.has(parent)) return true
    return false
  }
  const counted = [...selected].filter((code) => byCode.has(code) && (code === '' || !coveredByParent(code)))
  const selectedTotals = months.map((_, index) =>
    counted.reduce((sum, code) => sum + byCode.get(code)!.values[index].net, 0),
  )
  const selectedTotal = selectedTotals.reduce((sum, value) => sum + value, 0)

  const toggleSelected = (code: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })
  const toggleExpanded = (code: string) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(code)) {
        // Zwinięcie zwija też poziomy pod spodem.
        ;[...next].filter((key) => key.startsWith(code)).forEach((key) => next.delete(key))
      } else next.add(code)
      return next
    })
  const expandAll = () =>
    setExpanded(
      new Set(report.rows.filter((row) => row.level === 'REGION' || row.level === 'FAMILY').map((row) => row.code)),
    )

  const visible: SupplierTurnoverRow[] = []
  const walk = (parent: string | null) =>
    (children.get(parent) ?? []).forEach((row) => {
      visible.push(row)
      if (row.code && expanded.has(row.code)) walk(row.code)
    })
  walk(null)

  const amountCell = (value: SupplierTurnoverAmount, label: string, strong?: boolean) => (
    <td
      className="px-2 py-1.5 text-right"
      title={
        value.corrections !== 0
          ? `Z bonów ${formatMoney(value.delivered)} zł, korekty ${formatMoney(value.corrections)} zł`
          : undefined
      }
    >
      {value.net === 0 && value.delivered === 0 ? (
        <span className="text-muted-foreground">–</span>
      ) : (
        <Money value={value.net} label={label} strong={strong} />
      )}
    </td>
  )

  return (
    <>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <AmountTile label="Zakup z bonów (netto)" value={report.total.delivered} />
        <AmountTile label="Korekty przypięte do przyjęć" value={report.total.corrections} />
        <AmountTile label="Zakup po korektach (netto)" value={report.total.net} strong />
        <StatTile label="Przyjęcia PZ" value={String(report.deliveries.reduce((sum, count) => sum + count, 0))} />
      </div>

      {report.unattachedCorrections.count > 0 && (
        <p className="mb-4 flex items-start gap-2 rounded-xl bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" />
          <span>
            W okresie są korekty tego dostawcy nieprzypięte do żadnego przyjęcia ({report.unattachedCorrections.count},
            razem {formatMoney(report.unattachedCorrections.purchaseValue)} zł). Nie ma ich w kwotach raportu: przypnij
            je do przyjęć w Dokumentach.
          </span>
        </p>
      )}
      {report.documentsCoveredUntil && report.documentsCoveredUntil < report.to && (
        <p className="mb-4 flex items-start gap-2 rounded-xl bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" />
          Wgrane dokumenty sięgają do {formatDocumentDate(report.documentsCoveredUntil)}, więc ostatnie dni okresu mogą
          być niepełne.
        </p>
      )}

      {report.rows.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Brak przyjęć od dostawcy w tym okresie</p>
        </Card>
      ) : (
        <Card className="gap-0 overflow-clip p-0 pb-16">
          <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2.5">
            <Button variant="outline" size="sm" onClick={expandAll}>
              Rozwiń wszystko
            </Button>
            <Button variant="outline" size="sm" onClick={() => setExpanded(new Set())} disabled={expanded.size === 0}>
              Zwiń wszystko
            </Button>
            <div className="ml-auto flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
              {(['REGION', 'FAMILY', 'SUBFAMILY'] as const).map((level) => (
                <span key={level} className="inline-flex items-center gap-1.5">
                  <span className={cn('size-2 rounded-full', LEVEL_STYLES[level].dot)} />
                  {LEVEL_STYLES[level].label}
                </span>
              ))}
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-xs text-muted-foreground">
                  <th className="w-10 px-2 py-2" />
                  <th className="px-2 py-2 text-left font-medium">Nomenklatura</th>
                  {multi &&
                    months.map((month, index) => (
                      <th key={month} className="px-2 py-2 text-right font-medium whitespace-nowrap">
                        {monthLabel(month)}
                        <div className="font-normal">{report.deliveries[index]} PZ</div>
                      </th>
                    ))}
                  <th className="px-2 py-2 text-right font-medium whitespace-nowrap">
                    {multi ? 'Razem' : monthLabel(months[0])}
                    {!multi && <div className="font-normal">{report.deliveries[0]} PZ</div>}
                  </th>
                </tr>
              </thead>
              <tbody>
                {visible.map((row) => {
                  const level = row.level
                  const style = level ? LEVEL_STYLES[level] : null
                  const hasChildren = (children.get(row.code) ?? []).length > 0 && row.code !== ''
                  const open = expanded.has(row.code)
                  const inherited = row.code !== '' && coveredByParent(row.code)
                  const indent = level === 'FAMILY' ? 'pl-6' : level === 'SUBFAMILY' ? 'pl-11' : 'pl-2'
                  return (
                    <tr
                      key={row.code || 'brak'}
                      className={cn(
                        'border-b last:border-b-0',
                        style ? (open ? style.open : style.row) : 'bg-muted/40',
                      )}
                    >
                      <td className="px-2 py-1.5 text-center">
                        <input
                          type="checkbox"
                          aria-label={`Zaznacz do sumy: ${row.name}`}
                          className="size-4 accent-foreground"
                          checked={selected.has(row.code) || inherited}
                          disabled={inherited}
                          onChange={() => toggleSelected(row.code)}
                        />
                      </td>
                      <td className={cn('relative py-1.5 pr-2', indent, style && barClass, style?.bar)}>
                        <button
                          type="button"
                          disabled={!hasChildren}
                          onClick={() => toggleExpanded(row.code)}
                          className="flex items-center gap-1.5 text-left disabled:cursor-default"
                        >
                          <ChevronRightIcon
                            className={cn(
                              'size-4 shrink-0 text-muted-foreground transition-transform duration-150',
                              open && 'rotate-90',
                              !hasChildren && 'invisible',
                            )}
                          />
                          {row.code && (
                            <span className="font-mono text-xs text-muted-foreground tabular-nums">
                              {displayCode(row.code)}
                            </span>
                          )}
                          <span className={cn(level === 'REGION' && 'font-medium')}>{row.name}</span>
                        </button>
                      </td>
                      {multi &&
                        row.values.map((value, index) => (
                          <Fragment key={months[index]}>
                            {amountCell(value, `${row.name}, ${monthLabel(months[index])}`)}
                          </Fragment>
                        ))}
                      {amountCell(row.total, `${row.name}, razem`, true)}
                    </tr>
                  )
                })}
              </tbody>
              <tfoot>
                <tr className="border-t-2 bg-muted font-semibold">
                  <td />
                  <td className="px-2 py-2">Razem dostawca</td>
                  {multi &&
                    report.totals.map((value, index) => (
                      <Fragment key={months[index]}>
                        {amountCell(value, `razem dostawca, ${monthLabel(months[index])}`, true)}
                      </Fragment>
                    ))}
                  {amountCell(report.total, 'razem dostawca', true)}
                </tr>
              </tfoot>
            </table>
          </div>
        </Card>
      )}

      {counted.length > 0 && (
        <div className="sticky bottom-4 z-10 mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border bg-popover px-4 py-3 shadow-lg">
          <span className="text-sm text-muted-foreground">
            Zaznaczone: {counted.length} {counted.length === 1 ? 'pozycja' : counted.length < 5 ? 'pozycje' : 'pozycji'}
          </span>
          {multi &&
            selectedTotals.map((value, index) => (
              <span key={months[index]} className="inline-flex items-center gap-1 text-sm">
                <span className="text-muted-foreground">{monthLabel(months[index])}:</span>
                <Money value={value} label={`suma zaznaczonych, ${monthLabel(months[index])}`} />
              </span>
            ))}
          <span className="inline-flex items-center gap-1 text-base">
            <span className="text-muted-foreground">{multi ? 'Razem:' : 'Suma:'}</span>
            <Money value={selectedTotal} label="suma zaznaczonych" strong />
            <span className="text-muted-foreground">zł</span>
          </span>
          <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setSelected(new Set())}>
            Wyczyść zaznaczenie
          </Button>
        </div>
      )}
    </>
  )
}

/** Kafelek z kwotą i przyciskiem kopiowania. */
function AmountTile({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <Card className={cn('gap-1 p-4', strong && 'bg-foreground text-background ring-0')}>
      <p className={cn('text-sm', strong ? 'text-background/70' : 'text-muted-foreground')}>{label}</p>
      <div className="flex items-center gap-1">
        <span className="text-2xl font-semibold tracking-tight tabular-nums">{formatMoney(value)} zł</span>
        <CopyButton
          value={copyValue(value)}
          label={`Kopiuj: ${label}`}
          className={cn(strong && 'text-background/70 hover:bg-background/15 hover:text-background')}
        />
      </div>
    </Card>
  )
}
