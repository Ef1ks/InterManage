import { useState } from 'react'
import { ClipboardCopyIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { InvoiceClosing } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useInvoiceClosingChange, useInvoiceClosings } from './api'
import { buildDeliveryMacro } from './mercalys-macro'

const ROW_GRID = 'grid grid-cols-[auto_minmax(0,1.2fr)_minmax(0,1.5fr)_5rem_6rem] items-center gap-3'

const toMacroDocument = (item: InvoiceClosing) => ({ number: item.documentNumber, supplierCode: item.supplierCode })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Faktury z plików XML zapisanych na dysku. Skrypt idzie do schowka i dopiero wtedy faktury stają się wygenerowane.
 * Schowek łatwo nadpisać, więc wygenerowane zostają na liście: można je skopiować ponownie, a usuwa je osoba, która
 * sprawdziła zamknięcie.
 */
export function CloseInvoicesDialog({ storeId, open, onOpenChange }: Props) {
  const closings = useInvoiceClosings(storeId, open)
  const change = useInvoiceClosingChange(storeId)
  // Zaznaczone jest wszystko, więc pamiętamy tylko odznaczone.
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())

  const items = closings.data ?? []
  const pending = items.filter((item) => item.generatedAt === null)
  const generated = items.filter((item) => item.generatedAt !== null)
  const pickedPending = pending.filter((item) => !unchecked.has(item.id))
  const pickedGenerated = generated.filter((item) => !unchecked.has(item.id))

  const toggle = (id: string) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  const copyScript = async (picked: InvoiceClosing[]) => {
    try {
      await navigator.clipboard.writeText(buildDeliveryMacro(picked.map(toMacroDocument)))
    } catch {
      toast.error('Nie udało się skopiować skryptu do schowka')
      return false
    }
    return true
  }

  const generate = async () => {
    if (!(await copyScript(pickedPending))) return
    change.mutate(
      { action: 'generated', ids: pickedPending.map((item) => item.id) },
      {
        onSuccess: () => {
          toast.success(`Skrypt w schowku, faktur: ${pickedPending.length}`, {
            description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
          })
          onOpenChange(false)
        },
        onError: (error) =>
          toast.error(errorMessage(error, 'Skrypt jest w schowku, ale nie udało się oznaczyć faktur')),
      },
    )
  }

  const copyAgain = async () => {
    if (!(await copyScript(pickedGenerated))) return
    toast.success(`Skrypt ponownie w schowku, faktur: ${pickedGenerated.length}`, {
      description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
    })
    onOpenChange(false)
  }

  const removeGenerated = () => {
    const ids = pickedGenerated.map((item) => item.id)
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(`Usunięto wygenerowane: ${ids.length}`, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate(
                  { action: 'restore', ids },
                  { onError: () => toast.error('Nie udało się przywrócić faktur') },
                ),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć faktur')),
      },
    )
  }

  const row = (item: InvoiceClosing) => (
    <li key={item.id} className="border-b last:border-b-0">
      <label
        className={cn(
          ROW_GRID,
          'cursor-pointer px-3 py-1.5 text-sm hover:bg-accent',
          'has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
        )}
      >
        <input
          type="checkbox"
          checked={!unchecked.has(item.id)}
          onChange={() => toggle(item.id)}
          className="size-4 accent-blue-600"
        />
        <span className="min-w-0 truncate font-medium" title={item.documentNumber}>
          {item.documentNumber}
        </span>
        <span className="min-w-0 truncate" title={item.supplierName ?? undefined}>
          {item.supplierCode && (
            <span className="mr-2 text-xs text-muted-foreground tabular-nums">{item.supplierCode}</span>
          )}
          {item.supplierName}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">{formatDate(item.documentDate)}</span>
        <span className="text-right tabular-nums">{formatMoney(item.netValue)}</span>
      </label>
    </li>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Zamknij faktury</DialogTitle>
          <DialogDescription>
            Skrypt zintegruje pliki PZ, utworzy kody, wpisze numery faktur w nagłówkach i zamknie dostawy. Mercalys musi
            być uruchomiony, może zostać na pasku zadań.
          </DialogDescription>
        </DialogHeader>

        <section aria-label="Do zamknięcia" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Do zamknięcia ({pending.length})</h3>
          <div className="max-h-[32vh] overflow-y-auto rounded-lg border">
            <ul aria-label="Faktury do zamknięcia">{pending.map(row)}</ul>
            {pending.length === 0 && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {closings.isLoading ? 'Wczytywanie…' : 'Nowe faktury pojawią się po zapisaniu pliku XML.'}
              </p>
            )}
          </div>
          <div className="flex justify-end">
            <Button disabled={pickedPending.length === 0 || change.isPending} onClick={() => void generate()}>
              <ClipboardCopyIcon />
              Generuj skrypt ({pickedPending.length})
            </Button>
          </div>
        </section>

        {generated.length > 0 && (
          <section aria-label="Wygenerowane" className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">Wygenerowane ({generated.length})</h3>
            <div className="max-h-[24vh] overflow-y-auto rounded-lg border">
              <ul aria-label="Faktury wygenerowane">{generated.map(row)}</ul>
            </div>
            <p className="text-xs text-muted-foreground">
              Skrypt zniknął ze schowka albo przerwał się? Zaznacz niezamknięte faktury i skopiuj go ponownie.
            </p>
          </section>
        )}

        <DialogFooter className="sm:justify-between">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          {generated.length > 0 && (
            <div className="flex flex-wrap justify-end gap-2">
              <Button variant="outline" disabled={pickedGenerated.length === 0} onClick={() => void copyAgain()}>
                <ClipboardCopyIcon />
                Skopiuj ponownie ({pickedGenerated.length})
              </Button>
              <Button
                variant="outline"
                disabled={pickedGenerated.length === 0 || change.isPending}
                onClick={removeGenerated}
              >
                <Trash2Icon />
                Usuń wygenerowane ({pickedGenerated.length})
              </Button>
            </div>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
