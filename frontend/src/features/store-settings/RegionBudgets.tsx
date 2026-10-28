import { Fragment, useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useRegionBudgets, useSaveRegionBudgets } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import type { RegionBudget } from '@/lib/types'

type Draft = Record<string, { margin: string; loss: string }>

const numberFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2, useGrouping: false })
const toText = (value: number | null) => (value == null ? '' : numberFormat.format(value))

/** Tekst z pola na liczbę: przecinek albo kropka, puste = bez budżetu; {@code undefined} = błędna wartość. */
function parse(text: string, min: number): number | null | undefined {
  const trimmed = text.trim().replace(',', '.').replace('%', '')
  if (trimmed === '') return null
  if (!/^-?\d{1,3}(\.\d{1,2})?$/.test(trimmed)) return undefined
  const value = Number(trimmed)
  return value < min || value > 100 ? undefined : value
}

/**
 * Budżet rejonów do podsumowania sprzedaży i strat: docelowa marża (% od sprzedaży netto) i najwyższe straty
 * (% od sprzedaży brutto). Rejony ułożone w grupach jak w raporcie; zapis wysyła tylko zmienione wiersze.
 */
export function RegionBudgets({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = useRegionBudgets(storeId)
  const save = useSaveRegionBudgets(storeId)
  const [draft, setDraft] = useState<Draft>({})

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać budżetu</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }

  const valueOf = (budget: RegionBudget) => ({
    margin: draft[budget.regionCode]?.margin ?? toText(budget.marginPercent),
    loss: draft[budget.regionCode]?.loss ?? toText(budget.maxLossPercent),
  })
  const changed = (data ?? []).filter((budget) => {
    const value = valueOf(budget)
    return parse(value.margin, -100) !== budget.marginPercent || parse(value.loss, 0) !== budget.maxLossPercent
  })
  const invalid = changed.filter((budget) => {
    const value = valueOf(budget)
    return parse(value.margin, -100) === undefined || parse(value.loss, 0) === undefined
  })

  const edit = (budget: RegionBudget, field: 'margin' | 'loss', text: string) =>
    setDraft((current) => ({ ...current, [budget.regionCode]: { ...valueOf(budget), [field]: text } }))

  const submit = () =>
    save.mutate(
      changed.map((budget) => {
        const value = valueOf(budget)
        return {
          regionCode: budget.regionCode,
          marginPercent: parse(value.margin, -100) ?? null,
          maxLossPercent: parse(value.loss, 0) ?? null,
        }
      }),
      {
        onSuccess: () => {
          setDraft({})
          toast.success('Zapisano budżet rejonów')
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać budżetu'),
      },
    )

  const groups = [...new Set((data ?? []).map((budget) => budget.group))]

  return (
    <Card className="gap-0 py-0">
      <div className="overflow-x-auto">
        <Table className="tabular-nums">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="pl-4">Rejon</TableHead>
              <TableHead className="w-36 text-right">
                Marża %<span className="block text-xs font-normal text-muted-foreground">cel, od netto</span>
              </TableHead>
              <TableHead className="w-36 pr-4 text-right">
                Max straty %<span className="block text-xs font-normal text-muted-foreground">od brutto</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={3}>
                  <div aria-hidden className="h-40 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
                </TableCell>
              </TableRow>
            ) : (
              groups.map((group) => (
                <Fragment key={group}>
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={3} className="bg-muted/60 pt-3 pb-1.5 pl-4 text-xs font-semibold tracking-wide uppercase">
                      {group}
                    </TableCell>
                  </TableRow>
                  {data
                    .filter((budget) => budget.group === group)
                    .map((budget) => {
                      const value = valueOf(budget)
                      const label = `${budget.regionCode} ${budget.name ?? ''}`.trim()
                      return (
                        <TableRow key={budget.regionCode}>
                          <TableCell className="py-1.5 pl-4">
                            <span className="mr-2 text-xs text-muted-foreground">{budget.regionCode}</span>
                            <span className={budget.name ? undefined : 'text-muted-foreground'}>
                              {budget.name ?? 'Brak nazwy w nomenklaturze'}
                            </span>
                          </TableCell>
                          {/* Siatka do wpisywania liczb: pola bez pływającej etykiety, opisane nagłówkiem kolumny. */}
                          <TableCell className="py-1.5 text-right">
                            <Input
                              inputMode="decimal"
                              aria-label={`Marża % ${label}`}
                              aria-invalid={parse(value.margin, -100) === undefined}
                              value={value.margin}
                              onChange={(event) => edit(budget, 'margin', event.target.value)}
                              placeholder="–"
                              className="ml-auto w-24 text-right"
                            />
                          </TableCell>
                          <TableCell className="py-1.5 pr-4 text-right">
                            <Input
                              inputMode="decimal"
                              aria-label={`Max straty % ${label}`}
                              aria-invalid={parse(value.loss, 0) === undefined}
                              value={value.loss}
                              onChange={(event) => edit(budget, 'loss', event.target.value)}
                              placeholder="–"
                              className="ml-auto w-24 text-right"
                            />
                          </TableCell>
                        </TableRow>
                      )
                    })}
                </Fragment>
              ))
            )}
          </TableBody>
        </Table>
      </div>
      <div className="sticky bottom-0 flex flex-wrap items-center justify-end gap-3 border-t bg-card px-4 py-3">
        {invalid.length > 0 ? (
          <p className="mr-auto text-sm text-destructive">
            Popraw wartości: liczba z najwyżej dwoma miejscami po przecinku, marża od -100 do 100, straty od 0 do 100.
          </p>
        ) : changed.length > 0 ? (
          <p className="mr-auto text-sm text-muted-foreground">Zmienione rejony: {changed.length}</p>
        ) : null}
        {changed.length > 0 && (
          <Button variant="ghost" size="lg" onClick={() => setDraft({})}>
            Anuluj
          </Button>
        )}
        <Button size="lg" loading={save.isPending} disabled={changed.length === 0 || invalid.length > 0} onClick={submit}>
          Zapisz budżet
        </Button>
      </div>
    </Card>
  )
}
