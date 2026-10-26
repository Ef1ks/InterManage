import { useState } from 'react'
import { RefreshCwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions, SegmentedField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useRefreshPrices } from '@/features/deli-prices/api'
import { formatChange, formatMoney } from '@/features/deli-prices/format'
import { ApiError } from '@/lib/api'
import type { PriceChange, RefreshChoice } from '@/lib/types'
import { cn } from '@/lib/utils'

type Props = {
  storeId: string
  /** Jedna kalkulacja (kreator); bez niej - wszystkie w sklepie (lista). */
  calculationId?: string
  disabled?: boolean
  title?: string
}

/**
 * "Odśwież ceny": składniki dostają ceny z ostatniej dostawy PZ (a bez niej z kartoteki). Najpierw podgląd - kalkulacje
 * z własną ceną, której wyliczona się zmienia, i te, których wyliczona cena zmienia się o 10% i więcej, trafiają
 * na listę do akceptacji. Gdy nic nie wymaga decyzji, ceny odświeżają się od razu.
 */
export function RefreshPricesButton({ storeId, calculationId, disabled, title }: Props) {
  const { preview, apply } = useRefreshPrices(storeId)
  const [changes, setChanges] = useState<PriceChange[] | null>(null)
  const pending = preview.isPending || apply.isPending

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się odświeżyć cen')

  const applyRefresh = (decisions: { calculationId: string; choice: RefreshChoice }[], all: PriceChange[]) =>
    apply.mutate(
      { calculationId: calculationId ?? null, decisions },
      {
        onSuccess: () => {
          setChanges(null)
          const changed = all.filter((change) => change.previousCalculatedPrice !== change.newCalculatedPrice).length
          toast.success(
            changed === 0
              ? 'Ceny są aktualne - nic się nie zmieniło'
              : calculationId
                ? 'Odświeżono ceny składników'
                : `Odświeżono ceny ${all.length} kalkulacji, zmieniło się ${changed}`,
          )
        },
        onError,
      },
    )

  const start = () =>
    preview.mutate(calculationId, {
      onSuccess: (result) => {
        if (result.some((change) => change.needsDecision)) setChanges(result)
        else applyRefresh([], result)
      },
      onError,
    })

  return (
    <>
      <Button variant="outline" loading={pending && changes === null} disabled={disabled} title={title} onClick={start}>
        <RefreshCwIcon />
        Odśwież ceny
      </Button>
      <Dialog open={changes !== null} onOpenChange={(open) => !open && !apply.isPending && setChanges(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-2xl">
          {changes && (
            <Decisions
              changes={changes}
              saving={apply.isPending}
              onConfirm={(decisions) => applyRefresh(decisions, changes)}
              onCancel={() => setChanges(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Własna cena domyślnie zostaje, wyliczona po dużej zmianie domyślnie przechodzi na nową. */
const defaultChoice = (change: PriceChange): RefreshChoice => (change.manualPrice != null ? 'KEEP' : 'ACCEPT')

function Decisions({
  changes,
  saving,
  onConfirm,
  onCancel,
}: {
  changes: PriceChange[]
  saving: boolean
  onConfirm: (decisions: { calculationId: string; choice: RefreshChoice }[]) => void
  onCancel: () => void
}) {
  const asked = changes.filter((change) => change.needsDecision)
  const silent = changes.length - asked.length
  const [choices, setChoices] = useState<Record<string, RefreshChoice>>(() =>
    Object.fromEntries(asked.map((change) => [change.calculationId, defaultChoice(change)])),
  )
  const setAll = (choice: RefreshChoice) =>
    setChoices(Object.fromEntries(asked.map((change) => [change.calculationId, choice])))

  return (
    <div className="grid gap-4">
      <DialogHeader>
        <DialogTitle className="text-lg">Nowe ceny do akceptacji</DialogTitle>
        <DialogDescription>
          Wybierz, którą cenę zostawić. Wyroby z własną ceną i te, których cena zmienia się o 10% i więcej, czekają tu na
          decyzję
          {silent > 0 && `; pozostałe (${silent}) odświeżą się bez pytania`}.
        </DialogDescription>
      </DialogHeader>

      {asked.length > 1 && (
        <div className="flex flex-wrap gap-2">
          <Button variant="ghost" size="sm" onClick={() => setAll('KEEP')}>
            Zostaw wszystkie obecne
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setAll('ACCEPT')}>
            Ustaw wszystkie nowe
          </Button>
        </div>
      )}

      <ul className="-mx-2 max-h-[55vh] divide-y overflow-y-auto overscroll-contain px-2">
        {asked.map((change) => (
          <li
            key={change.calculationId}
            className="grid gap-x-4 gap-y-2 py-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center"
          >
            <div className="min-w-0">
              <div className="truncate text-sm font-medium">{change.name}</div>
              <div className="text-xs text-muted-foreground tabular-nums">
                {change.manualPrice != null
                  ? `Twoja cena ${formatMoney(change.manualPrice)} · wyliczona była ${formatMoney(change.previousCalculatedPrice)}, teraz ${formatMoney(change.newCalculatedPrice)}`
                  : `Wyliczona ${formatMoney(change.previousCalculatedPrice)} → ${formatMoney(change.newCalculatedPrice)}`}
                {change.changePercent != null && (
                  <span
                    className={cn(
                      'ml-1.5 font-medium',
                      change.changePercent > 0 ? 'text-amber-700 dark:text-amber-400' : 'text-emerald-700 dark:text-emerald-400',
                    )}
                  >
                    {formatChange(change.changePercent)}
                  </span>
                )}
              </div>
            </div>
            <SegmentedField
              label={`Cena: ${change.name}`}
              options={[
                { value: 'KEEP', label: `Zostaw ${formatMoney(change.currentPrice)}` },
                { value: 'ACCEPT', label: `Ustaw ${formatMoney(change.newCalculatedPrice)}` },
              ]}
              value={choices[change.calculationId]}
              onChange={(choice) => setChoices((current) => ({ ...current, [change.calculationId]: choice }))}
              className="tabular-nums"
            />
          </li>
        ))}
      </ul>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel} disabled={saving}>
          Anuluj
        </Button>
        <Button
          size="lg"
          loading={saving}
          onClick={() =>
            onConfirm(asked.map((change) => ({ calculationId: change.calculationId, choice: choices[change.calculationId] })))
          }
        >
          Zatwierdź ceny
        </Button>
      </FormActions>
    </div>
  )
}
