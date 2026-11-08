import { useState } from 'react'
import { ShoppingCartIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { MAX_QUANTITY, ORDER_EAN, type OrderLine } from '@/features/data-entry/order-file'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { ZeroStockItem } from '@/lib/types'
import { cn } from '@/lib/utils'

const DEFAULT_QUANTITY = '1'

const validQuantity = (quantity: number) => Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_QUANTITY

type Props = {
  storeId: string
  items: ZeroStockItem[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Plik zamówienia z raportu zerówek: na start wszystko zaznaczone, po 1 sztuce. Każde otwarcie zaczyna od nowa. */
export function ZeroStockOrderDialog({ storeId, items, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Stwórz zamówienie</DialogTitle>
          <DialogDescription>
            Odznacz artykuły, których nie chcesz zamawiać, i popraw ilości. Plik trafi do folderu protokołów.
          </DialogDescription>
        </DialogHeader>
        {open && <OrderList storeId={storeId} items={items} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function OrderList({ storeId, items, onDone }: { storeId: string; items: ZeroStockItem[]; onDone: () => void }) {
  const folder = useProtoFolder(storeId)
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set())
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  const orderable = items.filter((item) => item.ean && ORDER_EAN.test(item.ean))
  const chosen = orderable.filter((item) => !unchecked.has(item.itm8))
  const quantityOf = (item: ZeroStockItem) => quantities[item.itm8] ?? DEFAULT_QUANTITY
  const badQuantity = (item: ZeroStockItem) => !validQuantity(Number(quantityOf(item)))

  // Ten sam EAN przy dwóch artykułach to jedna linia pliku.
  const byEan = new Map<string, OrderLine>()
  for (const item of chosen) {
    const key = item.ean!.replace(/^0+/, '')
    const known = byEan.get(key)
    if (known) known.quantity += Number(quantityOf(item))
    else byEan.set(key, { ean: item.ean!, quantity: Number(quantityOf(item)) })
  }
  const lines = [...byEan.values()]
  const blocked = lines.length === 0 || lines.some((line) => !validQuantity(line.quantity))

  const setAll = (checked: boolean) => setUnchecked(checked ? new Set() : new Set(orderable.map((item) => item.itm8)))

  const toggle = (itm8: string, checked: boolean) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (checked) next.delete(itm8)
      else next.add(itm8)
      return next
    })

  const save = async () => {
    setSaving(true)
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/reports/zero-stock/order`,
        { lines },
        'COMMANDE.traite0',
      )
      if (folderReady) await saveToFolder(folder.key, fileName, blob)
      else saveBlob(blob, fileName)
      toast.success('Zapisano zamówienie', {
        description: [
          fileName,
          `Pozycji: ${lines.length}`,
          !folderReady && 'pobrany: folder protokołów nie jest wybrany w tej przeglądarce',
        ]
          .filter(Boolean)
          .join(', '),
        duration: 10_000,
      })
      onDone()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zamówienia')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <label className="flex w-fit cursor-pointer items-center gap-2 text-sm">
        <input
          type="checkbox"
          className="size-4 accent-primary"
          checked={chosen.length === orderable.length}
          ref={(input) => {
            if (input) input.indeterminate = chosen.length > 0 && chosen.length < orderable.length
          }}
          onChange={(event) => setAll(event.target.checked)}
        />
        Zaznacz wszystkie
        <span className="text-muted-foreground tabular-nums">
          {chosen.length} z {orderable.length}
        </span>
      </label>

      <ul className="max-h-[55vh] divide-y overflow-y-auto rounded-lg border">
        {items.map((item) => {
          const usable = orderable.includes(item)
          const checked = usable && !unchecked.has(item.itm8)
          return (
            <li key={item.itm8} className="flex items-center gap-3 px-3 py-1.5 text-sm">
              <label
                className={cn(
                  'flex min-w-0 flex-1 items-center gap-3',
                  usable ? 'cursor-pointer' : 'cursor-not-allowed opacity-60',
                )}
              >
                <input
                  type="checkbox"
                  className="size-4 shrink-0 accent-primary"
                  checked={checked}
                  disabled={!usable}
                  onChange={(event) => toggle(item.itm8, event.target.checked)}
                />
                <span className="w-28 shrink-0 text-muted-foreground tabular-nums">{item.ean ?? 'bez EAN-u'}</span>
                <span className="min-w-0 truncate" title={item.name ?? undefined}>
                  {item.name}
                  {item.brand && <span className="text-muted-foreground"> · {item.brand}</span>}
                </span>
              </label>
              <ArticleStatsButton article={item} skipTab />
              <Input
                aria-label={`Ilość: ${item.name}`}
                inputMode="numeric"
                className="h-8 w-16 shrink-0 text-right tabular-nums"
                value={quantityOf(item)}
                disabled={!checked}
                aria-invalid={checked && badQuantity(item)}
                onFocus={(event) => event.target.select()}
                onChange={(event) => setQuantities((current) => ({ ...current, [item.itm8]: event.target.value }))}
              />
            </li>
          )
        })}
      </ul>

      <div className="flex flex-wrap items-center justify-end gap-3">
        {lines.some((line) => !validQuantity(line.quantity)) && (
          <p className="text-sm text-destructive">Ilość to liczba całkowita od 1 do {MAX_QUANTITY}.</p>
        )}
        <Button size="lg" loading={saving} disabled={blocked} onClick={() => void save()}>
          <ShoppingCartIcon />
          Zapisz zamówienie ({lines.length})
        </Button>
      </div>
    </>
  )
}
