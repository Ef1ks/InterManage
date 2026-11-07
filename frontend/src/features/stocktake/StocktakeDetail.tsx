import { useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { LineChartIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useCan } from '@/features/auth/api'
import { formatMoney, formatQuantity } from '@/features/documents/format'
import { nomenclatureName, StatTile } from '@/features/sales/report-parts'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { useSalesReportsOverview, type SalesChartTarget } from '@/features/sales/api'
import { useDeleteStocktake, useRestoreStocktake, useStocktake } from '@/features/stocktake/api'
import { stocktakePath } from '@/features/stocktake/paths'
import { REASON_ORDER, REASONS } from '@/features/stocktake/reasons'
import { StocktakeUpload } from '@/features/stocktake/StocktakeUpload'
import { formatLongDate } from '@/lib/dates'
import type {
  Stocktake,
  StocktakeBucket,
  StocktakeLine,
  StocktakePair,
  StocktakeReason,
  StoreSummary,
} from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const numberFormat = new Intl.NumberFormat('pl-PL')
const PAGE_SIZE = 100
const ALL = 'wszystkie'
const NO_NOMENCLATURE_FILTER: string[] = []

type View = 'recount' | 'pairs' | 'system' | 'production'
const VIEW_PARAMS: Record<View, string> = {
  recount: 'przeliczenie',
  pairs: 'plus-minus',
  system: 'stan-w-systemie',
  production: 'produkcja',
}
const VIEW_BUCKETS: Partial<Record<View, StocktakeBucket>> = {
  recount: 'RECOUNT',
  system: 'SYSTEM_STOCK',
  production: 'PRODUCTION',
}

const viewOf = (param: string | null): View =>
  (Object.keys(VIEW_PARAMS) as View[]).find((view) => VIEW_PARAMS[view] === param) ?? 'recount'

const money = (value: number) => `${formatMoney(value)} zł`

function matches(line: StocktakeLine, words: string[]) {
  const text = `${line.name} ${line.brand ?? ''} ${line.ean ?? ''} ${line.itm8}`.toLowerCase()
  return words.every((word) => text.includes(word))
}

export function StocktakeDetail({ store }: { store: StoreSummary }) {
  const { id = '' } = useParams()
  const stocktake = useStocktake(store.id, id)

  if (stocktake.isPending) return <p className="text-sm text-muted-foreground">Wczytywanie…</p>
  if (stocktake.isError) {
    return (
      <>
        <BackLink to={stocktakePath}>Inwentury</BackLink>
        <FullPageMessage title="Nie znaleziono inwentury" description="Mogła zostać usunięta." inline />
      </>
    )
  }
  return <Loaded store={store} stocktake={stocktake.data} />
}

function Loaded({ store, stocktake }: { store: StoreSummary; stocktake: Stocktake }) {
  const [params, setParams] = useSearchParams()
  const { text, setText, debounced } = useSearchParamText('szukaj')
  const [chart, setChart] = useState<SalesChartTarget | null>(null)
  const canEdit = useCan('INVENTORY', 'EDIT')
  const overview = useSalesReportsOverview(store.id)

  const view = viewOf(params.get('widok'))
  const region = params.get('rejon') ?? ALL
  const reason = (params.get('powod') as StocktakeReason | null) ?? null

  const setParam = (name: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(name, value)
        else next.delete(name)
        return next
      },
      { replace: true },
    )

  const bucket = (key: StocktakeBucket) => stocktake.buckets.find((item) => item.bucket === key)
  const regions = useMemo(() => {
    const found = new Map<string, string>()
    for (const line of stocktake.lines) {
      if (line.regionCode) found.set(line.regionCode, nomenclatureName(line.regionName ?? line.regionCode))
    }
    return [...found].sort(([a], [b]) => a.localeCompare(b))
  }, [stocktake.lines])

  const words = debounced.toLowerCase().split(/\s+/).filter(Boolean)
  const shown = useMemo(
    () =>
      stocktake.lines.filter(
        (line) => (region === ALL || line.regionCode === region) && (words.length === 0 || matches(line, words)),
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- słowa pochodzą z `debounced`
    [stocktake.lines, region, debounced],
  )
  const viewBucket = VIEW_BUCKETS[view]
  const inView = viewBucket ? shown.filter((line) => line.bucket === viewBucket) : []
  const reasonCounts = useMemo(() => {
    const counts = new Map<StocktakeReason, number>()
    for (const line of shown) line.reasons.forEach((item) => counts.set(item, (counts.get(item) ?? 0) + 1))
    return counts
  }, [shown])
  const lines = reason ? inView.filter((line) => line.reasons.includes(reason)) : inView

  const byItm8 = useMemo(() => new Map(stocktake.lines.map((line) => [line.itm8, line])), [stocktake.lines])
  const shownItm8 = useMemo(() => new Set(shown.map((line) => line.itm8)), [shown])
  const pairs = stocktake.pairs.filter((pair) => shownItm8.has(pair.plusItm8) || shownItm8.has(pair.minusItm8))

  const tabs: { view: View; label: string; count: number }[] = [
    { view: 'recount', label: 'Do przeliczenia', count: bucket('RECOUNT')?.lineCount ?? 0 },
    { view: 'pairs', label: 'Plus / minus', count: stocktake.pairs.length },
    { view: 'system', label: 'Błędny stan w systemie', count: bucket('SYSTEM_STOCK')?.lineCount ?? 0 },
    { view: 'production', label: 'Produkcja', count: bucket('PRODUCTION')?.lineCount ?? 0 },
  ]
  const onlyDepartments = !stocktake.imports.some((item) => item.wholeStore)
  const openChart = (line: StocktakeLine) =>
    setChart({ kind: 'article', itm8: line.itm8, name: line.name, ean: line.ean, brand: line.brand })
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const chartOf = lastSaleDate ? openChart : undefined
  const filterKey = `${view}|${region}|${reason}|${debounced}`

  return (
    <div data-wide-page className="grid gap-5">
      <BackLink to={stocktakePath}>Inwentury</BackLink>
      <PageHeader
        title={stocktake.name}
        description={`Nr ${stocktake.number} · ${formatLongDate(stocktake.stocktakeDate)} · ${
          stocktake.salesThrough
            ? `sprzedaż po inwenturze do ${formatLongDate(stocktake.salesThrough)}`
            : 'bez sprzedaży po inwenturze'
        }`}
        actions={canEdit ? <DeleteButton store={store} stocktake={stocktake} /> : undefined}
      />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile
          tone="strong"
          label="Do przeliczenia"
          value={numberFormat.format(bucket('RECOUNT')?.lineCount ?? 0)}
          detail={money(bucket('RECOUNT')?.absoluteValue ?? 0)}
        />
        <StatTile
          label="Różnice razem bez znaku"
          value={money(stocktake.absoluteValue)}
          detail={`netto ${money(stocktake.netValue)}`}
        />
        <StatTile
          label="Pary plus / minus"
          value={numberFormat.format(stocktake.pairs.length)}
          detail={`w tym mocne: ${stocktake.pairs.filter((pair) => pair.strength === 'SWAP_STRONG').length}`}
        />
        <StatTile
          tone={stocktake.absoluteValue < stocktake.firstAbsoluteValue ? 'good' : undefined}
          label="Efekt przeliczeń"
          value={money(stocktake.absoluteValue - stocktake.firstAbsoluteValue)}
          detail={`już bez różnicy: ${numberFormat.format(stocktake.resolvedCount)}`}
        />
      </div>

      {onlyDepartments && (
        <Alert>
          <AlertDescription>
            Wgrane są wydruki działów. Artykuł, którego różnica zniknęła, dalej jest na liście, bo z wydruku działu nie
            wiadomo, co obejmował. Zniknie po wgraniu raportu całego sklepu.
          </AlertDescription>
        </Alert>
      )}

      <div className="flex gap-1 overflow-x-auto border-b">
        {tabs.map((tab) => (
          <button
            key={tab.view}
            type="button"
            onClick={() => setParam('widok', tab.view === 'recount' ? null : VIEW_PARAMS[tab.view])}
            className={cn(
              '-mb-px shrink-0 border-b-2 px-3 py-2 text-sm whitespace-nowrap transition-colors duration-150',
              view === tab.view
                ? 'border-foreground font-medium text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {tab.label} <span className="tabular-nums opacity-70">{numberFormat.format(tab.count)}</span>
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <SearchInput
          value={text}
          onChange={setText}
          placeholder="Szukaj artykułu, marki, EAN-u, ITM8"
          className="min-w-64 flex-1"
        />
        <Select
          value={region}
          onValueChange={(value) => setParam('rejon', value === ALL ? null : (value as string))}
          items={[
            { value: ALL, label: 'Wszystkie działy' },
            ...regions.map(([code, name]) => ({ value: code, label: `${code} ${name}` })),
          ]}
        >
          <SelectTrigger className="w-64">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>Wszystkie działy</SelectItem>
            {regions.map(([code, name]) => (
              <SelectItem key={code} value={code}>
                {code} {name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {view === 'recount' && (
        <div className="flex flex-wrap gap-2">
          {REASON_ORDER.filter((item) => reasonCounts.has(item)).map((item) => (
            <button
              key={item}
              type="button"
              title={REASONS[item].hint}
              onClick={() => setParam('powod', reason === item ? null : item)}
              className={cn(
                'rounded-full px-3 py-1 text-sm transition-opacity duration-150',
                REASONS[item].tone,
                reason && reason !== item && 'opacity-50',
              )}
            >
              {REASONS[item].label}{' '}
              <span className="tabular-nums">{numberFormat.format(reasonCounts.get(item) ?? 0)}</span>
            </button>
          ))}
        </div>
      )}

      {view === 'pairs' ? (
        <PairList key={filterKey} pairs={pairs} byItm8={byItm8} onChart={chartOf} />
      ) : (
        <>
          <ViewNote view={view} />
          <LineTable key={filterKey} lines={lines} onChart={chartOf} />
        </>
      )}

      <History stocktake={stocktake} />
      {canEdit && <StocktakeUpload storeId={store.id} title="Wgraj kolejny wydruk" />}

      {lastSaleDate && (
        <SalesChartDialog
          storeId={store.id}
          kind="sales"
          target={chart}
          filter={NO_NOMENCLATURE_FILTER}
          basis="gross"
          lastSaleDate={lastSaleDate}
          onClose={() => setChart(null)}
        />
      )}
    </div>
  )
}

function ViewNote({ view }: { view: View }) {
  if (view === 'system') {
    return (
      <p className="text-sm text-muted-foreground">
        Stan w systemie jest ujemny, więc policzona ilość jest raczej dobra. Sprawdź, czy nie brakuje dokumentu dostawy.
      </p>
    )
  }
  if (view === 'production') {
    return (
      <p className="text-sm text-muted-foreground">
        Mięso, drób i garmaż: surowiec bez rozchodu i wyroby bez przychodu. To nierozliczona produkcja, nie błąd liczenia.
      </p>
    )
  }
  return null
}

function useShownRows<T>(rows: T[]) {
  const [count, setCount] = useState(PAGE_SIZE)
  const more = useLoadMore<HTMLDivElement>(() => setCount((value) => value + PAGE_SIZE), count < rows.length, count)
  return { visible: rows.slice(0, count), more }
}

function LineTable({ lines, onChart }: { lines: StocktakeLine[]; onChart?: (line: StocktakeLine) => void }) {
  const { visible, more } = useShownRows(lines)
  if (lines.length === 0) return <p className="text-sm text-muted-foreground">Nic tu nie ma.</p>
  return (
    <Card className="gap-0 overflow-x-auto p-0">
      <table className="w-full text-sm">
        <thead className="text-left text-muted-foreground">
          <tr className="border-b">
            <th className="px-3 py-2 font-medium">Artykuł</th>
            <th className="px-3 py-2 text-right font-medium">Policzono</th>
            <th className="px-3 py-2 text-right font-medium">W systemie</th>
            <th className="px-3 py-2 text-right font-medium">Różnica</th>
            <th className="px-3 py-2 text-right font-medium">Kwota</th>
            <th className="px-3 py-2 text-right font-medium">Sprzedano po</th>
            <th className="px-3 py-2 font-medium">Powód</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((line) => (
            <tr key={line.itm8} className="border-b last:border-0">
              <td className="min-w-72 px-3 py-2">
                <ArticleName line={line} onChart={onChart} />
              </td>
              <Numbers line={line} />
              <td className="px-3 py-2 text-right tabular-nums">
                {line.soldAfter > 0 ? formatQuantity(line.soldAfter) : '–'}
                {line.deliveredAfter > 0 && (
                  <p className="text-xs whitespace-nowrap text-muted-foreground">
                    dostawa {formatQuantity(line.deliveredAfter)}
                  </p>
                )}
              </td>
              <td className="px-3 py-2">
                <div className="flex min-w-44 flex-wrap gap-1">
                  {line.reasons.map((reason) => (
                    <span
                      key={reason}
                      title={REASONS[reason].hint}
                      className={cn('rounded-full px-2 py-0.5 text-xs whitespace-nowrap', REASONS[reason].tone)}
                    >
                      {REASONS[reason].label}
                    </span>
                  ))}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div ref={more} />
    </Card>
  )
}

function ArticleName({ line, onChart }: { line: StocktakeLine; onChart?: (line: StocktakeLine) => void }) {
  return (
    <div className="flex items-start gap-1.5">
      <div className="min-w-0 flex-1">
        <p className="font-medium">{line.name}</p>
        <p className="text-xs text-muted-foreground">
          {[line.brand, line.ean ?? line.itm8, line.regionCode].filter(Boolean).join(' · ')}
        </p>
      </div>
      {onChart && (
        <button
          type="button"
          title="Sprzedaż, stan i dostawy artykułu"
          aria-label={`Sprzedaż artykułu ${line.name}`}
          onClick={() => onChart(line)}
          className="rounded-md p-1 text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground"
        >
          <LineChartIcon className="size-4" aria-hidden />
        </button>
      )}
    </div>
  )
}

function Numbers({ line }: { line: StocktakeLine }) {
  const changed = Math.abs(line.value - line.firstValue) >= 0.005
  return (
    <>
      <td className="px-3 py-2 text-right tabular-nums">{formatQuantity(line.realQuantity)}</td>
      <td className="px-3 py-2 text-right tabular-nums">{formatQuantity(line.theoreticalQuantity)}</td>
      <td
        className={cn(
          'px-3 py-2 text-right font-medium tabular-nums',
          line.differenceQuantity < 0 ? 'text-red-700 dark:text-red-300' : 'text-emerald-700 dark:text-emerald-300',
        )}
      >
        {line.differenceQuantity > 0 && '+'}
        {formatQuantity(line.differenceQuantity)}
      </td>
      <td className="px-3 py-2 text-right whitespace-nowrap tabular-nums">
        {money(line.value)}
        {changed && <p className="text-xs text-muted-foreground">było {money(line.firstValue)}</p>}
      </td>
    </>
  )
}

function PairList({
  pairs,
  byItm8,
  onChart,
}: {
  pairs: StocktakePair[]
  byItm8: Map<string, StocktakeLine>
  onChart?: (line: StocktakeLine) => void
}) {
  const { visible, more } = useShownRows(pairs)
  if (pairs.length === 0) return <p className="text-sm text-muted-foreground">Nie ma par plus / minus.</p>
  return (
    <div className="grid gap-2">
      <p className="text-sm text-muted-foreground">
        Dwa podobne artykuły z przeciwnymi różnicami: pewnie policzono jeden zamiast drugiego. Na próbie mocna para
        okazywała się pomyłką mniej więcej 2 razy na 3, a możliwa raz na 3, więc sprawdź oba na półce.
      </p>
      {visible.map((pair) => {
        const plus = byItm8.get(pair.plusItm8)
        const minus = byItm8.get(pair.minusItm8)
        if (!plus || !minus) return null
        return (
          <Card key={`${pair.plusItm8}-${pair.minusItm8}`} className="gap-2 p-3">
            <div className="flex items-center justify-between gap-2">
              <span className={cn('rounded-full px-2 py-0.5 text-xs', REASONS[pair.strength].tone)}>
                {REASONS[pair.strength].label}
              </span>
              <span className="text-sm text-muted-foreground tabular-nums">
                razem {money(plus.value + minus.value)}
              </span>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full table-fixed text-sm">
                <colgroup>
                  <col className="w-80 lg:w-auto" />
                  <col className="w-24" />
                  <col className="w-24" />
                  <col className="w-24" />
                  <col className="w-32" />
                </colgroup>
                <thead className="text-xs text-muted-foreground">
                  <tr>
                    <th className="sr-only">Artykuł</th>
                    <th className="px-3 text-right font-normal">Policzono</th>
                    <th className="px-3 text-right font-normal">W systemie</th>
                    <th className="px-3 text-right font-normal">Różnica</th>
                    <th className="px-3 text-right font-normal">Kwota</th>
                  </tr>
                </thead>
                <tbody>
                  {[plus, minus].map((line) => (
                    <tr key={line.itm8}>
                      <td className="py-1 pr-3">
                        <ArticleName line={line} onChart={onChart} />
                      </td>
                      <Numbers line={line} />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )
      })}
      <div ref={more} />
    </div>
  )
}

function History({ stocktake }: { stocktake: Stocktake }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-muted-foreground">Wgrane wydruki: {stocktake.imports.length}</summary>
      <ul className="mt-2 grid gap-1">
        {stocktake.imports.map((item) => (
          <li key={item.id} className="text-muted-foreground">
            {item.fileName} · {item.wholeStore ? 'cały sklep' : (item.selection ?? 'dział')} ·{' '}
            {numberFormat.format(item.lineCount)} artykułów · nowe {item.createdCount}, zmienione {item.updatedCount}
            {item.wholeStore && `, bez różnicy ${item.resolvedCount}`}
            {item.importedBy && ` · ${item.importedBy}`}
          </li>
        ))}
      </ul>
    </details>
  )
}

function DeleteButton({ store, stocktake }: { store: StoreSummary; stocktake: Stocktake }) {
  const navigate = useNavigate()
  const remove = useDeleteStocktake(store.id)
  const restore = useRestoreStocktake(store.id)

  return (
    <Button
      variant="outline"
      loading={remove.isPending}
      onClick={() =>
        remove.mutate(stocktake.id, {
          onSuccess: () => {
            navigate(stocktakePath)
            toast.success(`Usunięto inwenturę ${stocktake.name}`, {
              action: { label: 'Przywróć', onClick: () => restore.mutate(stocktake.id) },
            })
          },
          onError: () => toast.error('Nie udało się usunąć inwentury'),
        })
      }
    >
      <Trash2Icon aria-hidden />
      Usuń
    </Button>
  )
}
