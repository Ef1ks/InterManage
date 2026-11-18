import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'

import { SegmentedField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { AI_FEATURE_LABELS, tokenFormat } from '@/features/ai-usage/format'
import { api } from '@/lib/api'
import { addDays, addMonths, currentMonth, formatShortDate, todayIso } from '@/lib/dates'
import type { AiUsageReport } from '@/lib/types'
import { cn } from '@/lib/utils'
import { LoadingPanel } from '@/components/LoadingPanel'

type Period = 'month' | 'previous' | 'days30' | 'year'

const PERIODS: { value: Period; label: string }[] = [
  { value: 'month', label: 'Ten miesiąc' },
  { value: 'previous', label: 'Poprzedni miesiąc' },
  { value: 'days30', label: '30 dni' },
  { value: 'year', label: '12 miesięcy' },
]

function rangeOf(period: Period): { from: string; to: string } {
  const today = todayIso()
  const month = currentMonth()
  switch (period) {
    case 'month':
      return { from: `${month}-01`, to: today }
    case 'previous': {
      const previous = addMonths(month, -1)
      return { from: `${previous}-01`, to: addDays(`${month}-01`, -1) }
    }
    case 'days30':
      return { from: addDays(today, -29), to: today }
    case 'year':
      return { from: addDays(today, -365), to: today }
  }
}

export function useAiUsage(storeId: string, range: { from: string; to: string }, enabled = true) {
  return useQuery({
    queryKey: ['stores', storeId, 'ai-usage', range],
    queryFn: () => api<AiUsageReport>(`/stores/${storeId}/ai-usage?${new URLSearchParams(range)}`),
    enabled,
  })
}

/**
 * Zużycie tokenów AI sklepu według modelu i funkcji: wejście (instrukcja, zapytanie, plik), odpowiedź, myślenie
 * modelu i suma. Liczy każde udane zapytanie do modelu od wprowadzenia licznika.
 */
export function AiUsagePanel({ storeId }: { storeId: string }) {
  const [period, setPeriod] = useState<Period>('month')
  const range = rangeOf(period)
  const usage = useAiUsage(storeId, range)

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <SegmentedField label="Okres" options={PERIODS} value={period} onChange={setPeriod} />
        <p className="text-sm text-muted-foreground tabular-nums">
          {formatShortDate(range.from)} – {formatShortDate(range.to)}
        </p>
      </div>

      {usage.isError ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">Nie udało się wczytać zużycia</p>
          <Button variant="outline" onClick={() => usage.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !usage.data ? (
        <LoadingPanel className="h-40 rounded-xl" />
      ) : usage.data.rows.length === 0 ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">W tym okresie sklep nie korzystał z AI</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Licznik liczy zapytania od dziś: „Uzupełnij” we wprowadzaniu danych i odczyt WZ w zakupie mięsa.
          </p>
        </Card>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <Tile label="Tokeny razem" value={tokenFormat.format(usage.data.total.totalTokens)} strong />
            <Tile label="Zapytania do modelu" value={tokenFormat.format(usage.data.total.requests)} />
            <Tile
              label="Wejście / odpowiedź"
              value={`${tokenFormat.format(usage.data.total.promptTokens)} / ${tokenFormat.format(usage.data.total.outputTokens + usage.data.total.thoughtTokens)}`}
            />
          </div>
          <Card className="overflow-x-auto py-0">
            <Table className="tabular-nums">
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="pl-4">Model</TableHead>
                  <TableHead>Do czego</TableHead>
                  <TableHead className="text-right">Zapytania</TableHead>
                  <TableHead className="text-right" title="Instrukcja, zapytanie i plik wysłane do modelu">
                    Wejście
                  </TableHead>
                  <TableHead className="text-right" title="W tym z pamięci podręcznej modelu (tańsze)">
                    z pamięci
                  </TableHead>
                  <TableHead className="text-right">Odpowiedź</TableHead>
                  <TableHead className="text-right">Myślenie</TableHead>
                  <TableHead className="pr-4 text-right">Razem</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {usage.data.rows.map((row) => (
                  <TableRow key={`${row.model}:${row.feature}`}>
                    <TableCell className="pl-4 font-medium">{row.model}</TableCell>
                    <TableCell className="text-muted-foreground">{AI_FEATURE_LABELS[row.feature]}</TableCell>
                    <TableCell className="text-right">{tokenFormat.format(row.requests)}</TableCell>
                    <TableCell className="text-right">{tokenFormat.format(row.promptTokens)}</TableCell>
                    <TableCell className="text-right text-muted-foreground">
                      {tokenFormat.format(row.cachedTokens)}
                    </TableCell>
                    <TableCell className="text-right">{tokenFormat.format(row.outputTokens)}</TableCell>
                    <TableCell className="text-right">{tokenFormat.format(row.thoughtTokens)}</TableCell>
                    <TableCell className="pr-4 text-right font-semibold">
                      {tokenFormat.format(row.totalTokens)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell className="pl-4 font-semibold" colSpan={2}>
                    Razem
                  </TableCell>
                  <TableCell className="text-right">{tokenFormat.format(usage.data.total.requests)}</TableCell>
                  <TableCell className="text-right">{tokenFormat.format(usage.data.total.promptTokens)}</TableCell>
                  <TableCell className="text-right">{tokenFormat.format(usage.data.total.cachedTokens)}</TableCell>
                  <TableCell className="text-right">{tokenFormat.format(usage.data.total.outputTokens)}</TableCell>
                  <TableCell className="text-right">{tokenFormat.format(usage.data.total.thoughtTokens)}</TableCell>
                  <TableCell className="pr-4 text-right font-semibold">
                    {tokenFormat.format(usage.data.total.totalTokens)}
                  </TableCell>
                </TableRow>
              </TableFooter>
            </Table>
          </Card>
        </>
      )}
    </div>
  )
}

function Tile({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Card className={cn('gap-1 p-4', strong && 'bg-foreground text-background ring-0')}>
      <p className={cn('text-sm', strong ? 'text-background/70' : 'text-muted-foreground')}>{label}</p>
      <p className="text-2xl font-semibold tracking-tight tabular-nums">{value}</p>
    </Card>
  )
}
