import { useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { PlusIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useAddOrderItems, useSupplierForm } from '@/features/deli-order/api'
import { ProductSearchField } from '@/features/deli-order/ProductSearchField'
import { QuantityInput, UnitToggle, parseQuantity } from '@/features/deli-order/QuantityInput'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { DeliOrder, OrderUnit, ProductSearchResult } from '@/lib/types'

/** Kategoria pozycji dopisanych spoza formularza (`DeliOrderService.EXTRA_CATEGORY`). */
const EXTRA_CATEGORY = 'Spoza formularza'

type Props = {
  storeId: string
  order: DeliOrder
  /** Dział i kategoria zamówienia (nazwy z chwili złożenia), do których dopisujemy. */
  section: string
  category: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Dopisanie do wysłanego zamówienia produktów, których wcześniej nie zamówiono: z tej samej kategorii formularza
 * dostawcy, a w kategorii "Spoza formularza" z kartoteki dostawcy.
 */
export function AddOrderItemsDialog({ open, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        {open && <AddForm {...props} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

type Extra = { product: ProductSearchResult; unit: OrderUnit; quantity: string }

function AddForm({ storeId, order, section, category, onDone }: Omit<Props, 'open' | 'onOpenChange'> & { onDone: () => void }) {
  const form = useSupplierForm(storeId, order.formStore.id)
  const add = useAddOrderItems(storeId, order.id)
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [extras, setExtras] = useState<Extra[]>([])
  const listRef = useRef<HTMLDivElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)

  const extrasMode = category === EXTRA_CATEGORY
  const formSection = form.data?.sections.find((s) => s.name === section)
  const formCategory = formSection?.categories.find((c) => c.name === category)
  // Produkt, który już jest w tej kategorii zamówienia, zmienia dostawca - tu go nie dopisujemy.
  const ordered = new Set(order.items.filter((item) => item.categoryName === category).map((item) => item.productId))
  const candidates = (formCategory?.products ?? []).filter((product) => !ordered.has(product.productId))

  const lines = candidates
    .filter((product) => (quantities[product.id] ?? '').trim() !== '')
    .map((product) => ({ product, ...parseQuantity(quantities[product.id], product.orderUnit) }))
  const extraLines = extras
    .filter((extra) => extra.quantity.trim() !== '')
    .map((extra) => ({ extra, ...parseQuantity(extra.quantity, extra.unit) }))
  const errors = [...lines, ...extraLines].filter((line) => line.error).length
  const filled = lines.filter((line) => !line.error && line.value! > 0)
  const filledExtras = extraLines.filter((line) => !line.error && line.value! > 0)
  const count = filled.length + filledExtras.length

  const submit = () => {
    if (errors > 0 || count === 0) return
    add.mutate(
      {
        items: filled.map(({ product, value }) => ({ itemId: product.id, quantity: value! })),
        extras: filledExtras.map(({ extra, value }) => ({
          productId: extra.product.id,
          sectionId: formSection!.id,
          unit: extra.unit,
          quantity: value!,
        })),
      },
      {
        onSuccess: () => {
          toast.success(count === 1 ? 'Dopisano produkt do zamówienia' : `Dopisano ${count} produkty do zamówienia`)
          onDone()
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się dopisać produktów'),
      },
    )
  }

  // Enter przechodzi do następnego pola, Shift+Enter wraca; po ostatnim fokus trafia na „Dopisz”.
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Enter' || !(event.target instanceof HTMLInputElement) || event.target.role === 'combobox') return
    event.preventDefault()
    const all = [...(listRef.current?.querySelectorAll<HTMLInputElement>('input[inputmode]') ?? [])]
    const next = all[all.indexOf(event.target) + (event.shiftKey ? -1 : 1)]
    if (next) next.focus()
    else if (!event.shiftKey) confirmRef.current?.focus()
  }
  const onFocus = (event: FocusEvent<HTMLDivElement>) => {
    if (event.target instanceof HTMLInputElement && event.target.inputMode) event.target.select()
  }

  let body
  if (form.isPending) {
    body = <div aria-hidden className="h-32 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  } else if (form.isError || !formSection || (!extrasMode && !formCategory)) {
    body = (
      <p className="rounded-xl border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
        {form.isError
          ? 'Nie udało się wczytać formularza dostawcy.'
          : `Tej ${extrasMode ? 'sekcji' : 'kategorii'} nie ma już w formularzu sklepu ${storeLabel(order.formStore)}.`}
      </p>
    )
  } else if (extrasMode) {
    body = (
      <div className="divide-y rounded-xl border">
        {extras.map((extra) => {
          const label = extra.product.name ?? extra.product.ean
          const error = extraLines.find((line) => line.extra.product.id === extra.product.id)?.error
          const update = (change: Partial<Extra>) =>
            setExtras((current) => current.map((e) => (e.product.id === extra.product.id ? { ...e, ...change } : e)))
          return (
            <div key={extra.product.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
              <ProductName name={extra.product.name} brand={extra.product.brand} ean={extra.product.ean} error={error} />
              <UnitToggle value={extra.unit} label={label} onChange={(unit) => update({ unit })} />
              <QuantityInput
                unit={extra.unit}
                value={extra.quantity}
                invalid={Boolean(error)}
                label={label}
                autoFocus
                onChange={(quantity) => update({ quantity })}
              />
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń ${label}`}
                title="Usuń"
                onClick={() => setExtras((current) => current.filter((e) => e.product.id !== extra.product.id))}
              >
                <XIcon />
              </Button>
            </div>
          )
        })}
        <div className="p-3">
          <ProductSearchField
            source={{ storeId, supplierId: order.formStore.id }}
            addedProductIds={new Set([...ordered, ...extras.map((extra) => extra.product.id)])}
            placeholder="Szukaj: nazwa, marka, EAN lub ITM8"
            onAdd={(product) =>
              setExtras((current) => [
                ...current,
                { product, unit: product.unit?.toLowerCase() === 'kg' ? 'KG' : 'SZT', quantity: '' },
              ])
            }
          />
        </div>
      </div>
    )
  } else if (candidates.length === 0) {
    body = (
      <p className="rounded-xl border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
        Wszystkie produkty z tej kategorii są już w zamówieniu.
      </p>
    )
  } else {
    body = (
      <div className="divide-y rounded-xl border">
        {candidates.map((product, index) => {
          const error = lines.find((line) => line.product.id === product.id)?.error
          return (
            <div key={product.id} className="flex items-center gap-4 px-4 py-2.5">
              <ProductName name={product.name} brand={product.brand} ean={product.ean} error={error} />
              <QuantityInput
                unit={product.orderUnit}
                value={quantities[product.id] ?? ''}
                invalid={Boolean(error)}
                label={product.name ?? product.ean}
                autoFocus={index === 0}
                onChange={(next) => setQuantities((current) => ({ ...current, [product.id]: next }))}
              />
            </div>
          )
        })}
      </div>
    )
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Dopisz do: {category}</DialogTitle>
        <DialogDescription>
          {extrasMode
            ? `Produkty z kartoteki sklepu ${storeLabel(order.formStore)} trafią do działu ${section}.`
            : 'Wpisz ilość przy produktach, których zabrakło w zamówieniu. Puste pola pomijamy.'}
        </DialogDescription>
      </DialogHeader>
      <div ref={listRef} onKeyDown={onKeyDown} onFocus={onFocus}>
        {body}
      </div>
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button
          ref={confirmRef}
          type="button"
          size="lg"
          loading={add.isPending}
          disabled={errors > 0 || count === 0}
          onClick={submit}
        >
          <PlusIcon />
          {errors > 0 ? `Popraw ilości (${errors})` : count > 0 ? `Dopisz (${count})` : 'Dopisz'}
        </Button>
      </FormActions>
    </>
  )
}

function ProductName({ name, brand, ean, error }: { name: string | null; brand: string | null; ean: string; error?: string }) {
  return (
    <div className="min-w-0 flex-1">
      <div className="truncate text-sm font-medium">{name ?? 'Bez nazwy'}</div>
      <div className="truncate text-xs text-muted-foreground tabular-nums">
        {brand && `${brand} · `}EAN {ean}
      </div>
      {error && <div className="text-xs text-destructive">{error}</div>}
    </div>
  )
}
