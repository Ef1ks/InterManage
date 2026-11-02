import { useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import type { BaseDelivery } from '@/lib/types'

type Props = {
  delivery: BaseDelivery | null
  /** Nośniki zaznaczone na start (te w tabeli). */
  initial: string[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (carriers: string[]) => void
}

/**
 * Wybór nośników (palet) dokumentu do tabeli: wybrane zastępują to, co jest w tabeli. Z jednego nośnika robi się
 * np. protokół MM dla innego sklepu.
 */
export function CarrierDialog({ delivery, initial, open, onOpenChange, onConfirm }: Props) {
  return (
    <Dialog open={open && delivery !== null} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {delivery && (
          <CarrierChoice
            key={`${delivery.id}:${initial.join()}`}
            delivery={delivery}
            initial={initial}
            onCancel={() => onOpenChange(false)}
            onConfirm={onConfirm}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function CarrierChoice({
  delivery,
  initial,
  onCancel,
  onConfirm,
}: {
  delivery: BaseDelivery
  initial: string[]
  onCancel: () => void
  onConfirm: (carriers: string[]) => void
}) {
  const [chosen, setChosen] = useState(() => new Set(initial))
  const all = chosen.size === delivery.carriers.length
  const toggle = (carrier: string) =>
    setChosen((current) => {
      const next = new Set(current)
      if (next.has(carrier)) next.delete(carrier)
      else next.add(carrier)
      return next
    })
  const lines = delivery.carriers
    .filter((carrier) => chosen.has(carrier.number))
    .reduce((sum, c) => sum + c.lineCount, 0)

  return (
    <>
      <DialogHeader>
        <DialogTitle>Nośniki do tabeli</DialogTitle>
        <DialogDescription>
          Bon {delivery.number} z {formatDocumentDate(delivery.deliveryDate)}. Wybrane nośniki zastąpią wiersze w
          tabeli.
        </DialogDescription>
      </DialogHeader>

      <ul className="grid gap-1" aria-label="Nośniki">
        <li>
          <label className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1.5 text-sm font-medium hover:bg-accent">
            <input
              type="checkbox"
              checked={all}
              onChange={() => setChosen(all ? new Set() : new Set(delivery.carriers.map((carrier) => carrier.number)))}
              className="size-4 accent-blue-600"
            />
            Wszystkie nośniki
          </label>
        </li>
        {delivery.carriers.map((carrier) => (
          <li key={carrier.number}>
            <label className="flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm hover:bg-accent has-checked:border-blue-600 has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60">
              <input
                type="checkbox"
                checked={chosen.has(carrier.number)}
                onChange={() => toggle(carrier.number)}
                className="size-4 accent-blue-600"
              />
              <span className="font-medium tabular-nums">{carrier.number}</span>
              <span className="ml-auto text-muted-foreground tabular-nums">
                {carrier.lineCount} {carrier.lineCount === 1 ? 'linia' : 'linii'} · {formatMoney(carrier.netValue)} zł
                netto
              </span>
            </label>
          </li>
        ))}
      </ul>

      <DialogFooter>
        <Button variant="ghost" onClick={onCancel}>
          Anuluj
        </Button>
        <Button disabled={chosen.size === 0} onClick={() => onConfirm([...chosen])}>
          Otwórz w tabeli ({lines} {lines === 1 ? 'linia' : 'linii'})
        </Button>
      </DialogFooter>
    </>
  )
}
