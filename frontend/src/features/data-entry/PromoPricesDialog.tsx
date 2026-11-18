import { useState } from 'react'
import { ClipboardCopyIcon, ListPlusIcon, Trash2Icon } from 'lucide-react'
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
import { todayIso } from '@/lib/dates'
import type { PromoPriceItem } from '@/lib/types'
import { cn } from '@/lib/utils'

import { type PromoPriceAdd, useAddPromoPrices, usePromoPriceChange, usePromoPrices } from './api'
import { buildPromoPriceMacro } from './mercalys-macro'

const ROW_GRID = 'grid grid-cols-[auto_minmax(0,1.6fr)_minmax(0,1fr)_5.5rem_5.5rem_1.75rem] items-center gap-3'

const VAT_LABELS: Record<string, string> = { '1': '23%', '2': '8%', '6': '5%' }

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Artykuły w promocji z bieżącej tabeli, do dopisania ręcznie (dokument bez zapisu pliku XML). */
  fromTable: PromoPriceAdd | null
}

/**
 * Ceny zwykłe artykułów w promocji z wielu dokumentów (zapis pliku XML dopisuje je sam), jedno makro dla wszystkich:
 * taryfa od dziś z ceną sugerowaną i stawką VAT. Jak przy zamykaniu faktur makro idzie do schowka, a wygenerowane
 * zostają na liście do ponownego skopiowania; usunięcie to znacznik z „Przywróć”.
 */
export function PromoPricesDialog({ storeId, open, onOpenChange, fromTable }: Props) {
  const prices = usePromoPrices(storeId, open)
  const change = usePromoPriceChange(storeId)
  const add = useAddPromoPrices(storeId)
  // Zaznaczone jest wszystko, więc pamiętamy tylko odznaczone.
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())

  const items = prices.data ?? []
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

  const copyMacro = async (picked: PromoPriceItem[]) => {
    try {
      await navigator.clipboard.writeText(
        buildPromoPriceMacro(
          // Od najstarszego: w kolejności dokumentów.
          [...picked]
            .reverse()
            .map((item) => ({ ean: item.ean, itm8: item.itm8, price: item.salePrice, vatCode: item.vatCode })),
          todayIso(),
        ),
      )
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return false
    }
    return true
  }

  const generate = async () => {
    if (!(await copyMacro(pickedPending))) return
    change.mutate(
      { action: 'generated', ids: pickedPending.map((item) => item.id) },
      {
        onSuccess: () => {
          toast.success(`Makro w schowku, artykułów: ${pickedPending.length}`, {
            description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
          })
          onOpenChange(false)
        },
        onError: (error) =>
          toast.error(errorMessage(error, 'Makro jest w schowku, ale nie udało się oznaczyć artykułów')),
      },
    )
  }

  const copyAgain = async () => {
    if (!(await copyMacro(pickedGenerated))) return
    toast.success(`Makro ponownie w schowku, artykułów: ${pickedGenerated.length}`, {
      description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
    })
    onOpenChange(false)
  }

  const remove = (picked: PromoPriceItem[]) => {
    const ids = picked.map((item) => item.id)
    if (ids.length === 0) return
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(ids.length === 1 ? `Usunięto ${picked[0].name}` : `Usunięto artykuły: ${ids.length}`, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate(
                  { action: 'restore', ids },
                  { onError: () => toast.error('Nie udało się przywrócić artykułów') },
                ),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć artykułów')),
      },
    )
  }

  const addFromTable = () => {
    if (!fromTable) return
    add.mutate(fromTable, {
      onSuccess: () => toast.success(`Dodano z tabeli: ${fromTable.items.length}`),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się dodać artykułów')),
    })
  }

  const row = (item: PromoPriceItem) => (
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
        <span className="min-w-0">
          <span className="block truncate font-medium" title={item.name}>
            {item.name}
          </span>
          <span className="block truncate text-xs text-muted-foreground tabular-nums">{item.ean}</span>
        </span>
        <span className="min-w-0 truncate text-xs text-muted-foreground" title={item.document ?? undefined}>
          {item.document ?? 'bez dokumentu'}
        </span>
        <span
          className="text-right text-xs text-orange-700 tabular-nums dark:text-orange-300"
          title="Cena promocyjna na kasie"
        >
          {item.promoPrice !== null ? formatMoney(item.promoPrice) : '–'}
          {item.promoTo && <span className="block text-muted-foreground">do {formatDate(item.promoTo)}</span>}
        </span>
        <span className="text-right tabular-nums" title={`Cena zwykła od dziś, VAT ${VAT_LABELS[item.vatCode] ?? ''}`}>
          <span className="block font-medium">{formatMoney(item.salePrice)}</span>
          <span className="block text-xs text-muted-foreground">VAT {VAT_LABELS[item.vatCode] ?? item.vatCode}</span>
        </span>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń ${item.name}`}
          title="Usuń z listy"
          disabled={change.isPending}
          onClick={(event) => {
            event.preventDefault()
            remove([item])
          }}
        >
          <Trash2Icon />
        </Button>
      </label>
    </li>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Ceny artykułów w promocji</DialogTitle>
          <DialogDescription>
            Zmiana cen z pliku nie obejmuje artykułów w trwającej promocji. Makro wpisze każdemu w taryfie cenę zwykłą
            od dziś (cena sugerowana z tabeli) i jego stawkę VAT. Artykuły dochodzą tu same przy zapisie pliku XML,
            artykułów z cadencier nie ma.
          </DialogDescription>
        </DialogHeader>

        <section aria-label="Do wprowadzenia" className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-medium">Do wprowadzenia ({pending.length})</h3>
            {fromTable && fromTable.items.length > 0 && (
              <Button variant="outline" size="sm" loading={add.isPending} onClick={addFromTable}>
                <ListPlusIcon />
                Dodaj z tabeli ({fromTable.items.length})
              </Button>
            )}
          </div>
          <div className="max-h-[32vh] overflow-y-auto rounded-lg border">
            <ul aria-label="Artykuły do wprowadzenia">{pending.map(row)}</ul>
            {pending.length === 0 && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {prices.isLoading
                  ? 'Wczytywanie…'
                  : 'Artykuły w promocji pojawią się po zapisaniu pliku XML dokumentu, który je ma.'}
              </p>
            )}
          </div>
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              disabled={pickedPending.length === 0 || change.isPending}
              onClick={() => remove(pickedPending)}
            >
              <Trash2Icon />
              Usuń zaznaczone ({pickedPending.length})
            </Button>
            <Button disabled={pickedPending.length === 0 || change.isPending} onClick={() => void generate()}>
              <ClipboardCopyIcon />
              Generuj makro ({pickedPending.length})
            </Button>
          </div>
        </section>

        {generated.length > 0 && (
          <section aria-label="Wygenerowane" className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">Wygenerowane ({generated.length})</h3>
            <div className="max-h-[24vh] overflow-y-auto rounded-lg border">
              <ul aria-label="Artykuły wygenerowane">{generated.map(row)}</ul>
            </div>
            <p className="text-xs text-muted-foreground">
              Makro zniknęło ze schowka albo przerwało się? Zaznacz niewprowadzone artykuły i skopiuj je ponownie.
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
                onClick={() => remove(pickedGenerated)}
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
