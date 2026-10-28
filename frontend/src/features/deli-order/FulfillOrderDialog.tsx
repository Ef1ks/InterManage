import { useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { CheckCheckIcon, CornerDownLeftIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { PROTO_FOLDER, orderExportPath, useFulfillOrder } from '@/features/deli-order/api'
import { groupItems } from '@/features/deli-order/group-items'
import { QuantityInput, formatQuantity, parseQuantity } from '@/features/deli-order/QuantityInput'
import { ApiError, downloadFile } from '@/lib/api'
import { ensureFolderAccess, saveApiFileToFolder } from '@/lib/saved-folders'
import type { DeliOrder } from '@/lib/types'

type Props = {
  storeId: string
  order: DeliOrder
  /** Ilości wpisane na stronie zamówienia, jeszcze niezapisane: okno startuje od nich. */
  initial: Record<string, string>
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Wpisywanie zrealizowanych ilości: ilość, Enter, następne pole. Na końcu Enter potwierdza. */
export function FulfillOrderDialog({ open, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        {open && <FulfillForm {...props} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function FulfillForm({ storeId, order, initial, onDone }: Omit<Props, 'open' | 'onOpenChange'> & { onDone: () => void }) {
  const fulfill = useFulfillOrder(storeId, order.id)
  const [draft, setDraft] = useState(initial)
  const [submitting, setSubmitting] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)

  const parsed = new Map(order.items.map((item) => [item.id, parseQuantity(draft[item.id] ?? '', item.unit)]))
  const errors = [...parsed.values()].filter(({ error }) => error).length

  const inputs = () => [...(listRef.current?.querySelectorAll('input') ?? [])]

  // Enter przechodzi do następnego pola, Shift+Enter wraca; po ostatnim fokus trafia na „Potwierdź”.
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Enter' || !(event.target instanceof HTMLInputElement)) return
    event.preventDefault()
    const all = inputs()
    const next = all[all.indexOf(event.target) + (event.shiftKey ? -1 : 1)]
    if (next) next.focus()
    else if (!event.shiftKey) confirmRef.current?.focus()
  }

  // Zaznaczona ilość: wpisanie liczby od razu ją zastępuje, sam Enter zostawia.
  const onFocus = (event: FocusEvent<HTMLDivElement>) => {
    if (event.target instanceof HTMLInputElement) event.target.select()
  }

  const confirm = async () => {
    setSubmitting(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(PROTO_FOLDER.key)
      await fulfill.mutateAsync(order.items.map((item) => ({ id: item.id, quantity: parsed.get(item.id)!.value! })))
      onDone()
      if (!folderReady) {
        toast.success('Zamówienie zrealizowane')
        return
      }
      const path = orderExportPath(storeId, order.id, 'proto')
      try {
        const fileName = await saveApiFileToFolder(PROTO_FOLDER.key, path, 'zamowienie.Traite0')
        toast.success('Zamówienie zrealizowane', { description: `Zapisano ${fileName}` })
      } catch (error) {
        toast.error('Zamówienie zrealizowane, ale pliku PROTO6 nie zapisano', {
          description: error instanceof ApiError ? error.message : undefined,
          action: { label: 'Pobierz', onClick: () => downloadFile(path, 'zamowienie.Traite0') },
          duration: 15_000,
        })
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się potwierdzić realizacji')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Zrealizowane ilości</DialogTitle>
        <DialogDescription>
          Wpisz, ile wydajesz, i naciśnij{' '}
          <kbd className="inline-flex items-center gap-1 rounded border bg-muted px-1.5 font-sans text-xs">
            Enter <CornerDownLeftIcon className="size-3" />
          </kbd>{' '}
          - przejdziesz do następnego produktu. Brak towaru to 0.
        </DialogDescription>
      </DialogHeader>

      <div ref={listRef} onKeyDown={onKeyDown} onFocus={onFocus} className="grid gap-4">
        {groupItems(order.items).map(({ section, categories }) => (
          <section key={section} aria-label={section} className="grid gap-3">
            <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{section}</h3>
            {categories.map(({ category, items }) => (
              <div key={category} className="grid gap-1">
                <h4 className="text-sm font-semibold">{category}</h4>
                {items.map((item) => {
                  const error = parsed.get(item.id)?.error
                  return (
                    <div key={item.id} className="flex items-center gap-3 py-1">
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{item.name ?? `EAN ${item.ean}`}</div>
                        <div className="text-xs text-muted-foreground tabular-nums">
                          zamówiono {formatQuantity(item.orderedQuantity, item.unit)}
                          {error && <span className="text-destructive"> · {error}</span>}
                        </div>
                      </div>
                      <QuantityInput
                        unit={item.unit}
                        value={draft[item.id] ?? ''}
                        invalid={Boolean(error)}
                        label={item.name ?? item.ean}
                        autoFocus={item.id === order.items[0]?.id}
                        onChange={(next) => setDraft((current) => ({ ...current, [item.id]: next }))}
                      />
                    </div>
                  )
                })}
              </div>
            ))}
          </section>
        ))}
      </div>

      <SavedFolder folder={PROTO_FOLDER} />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button ref={confirmRef} type="button" size="lg" loading={submitting} disabled={errors > 0} onClick={confirm}>
          <CheckCheckIcon />
          {errors > 0 ? `Popraw ilości (${errors})` : 'Potwierdź zrealizowanie'}
        </Button>
      </FormActions>
    </>
  )
}
