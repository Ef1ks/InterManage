import { useState } from 'react'
import { PencilIcon, PlusIcon, TagIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { SupplierPicker } from '@/features/documents/SupplierPicker'
import { useSupplierSalePriceActions, useSupplierSalePrices } from '@/features/store-settings/api'
import { ApiError } from '@/lib/api'
import type { SupplierSalePrice } from '@/lib/types'

const moneyText = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false })

/** Cena z pola: przecinek albo kropka, „zł” pomijane; {@code null} = pusta albo błędna. */
function parsePrice(text: string): number | null {
  const trimmed = text.replace(/\s|zł/gi, '').replace(',', '.')
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(trimmed)) return null
  const value = Number(trimmed)
  return value > 0 ? value : null
}

type Editing = { supplierId: string | null; price: string }

/**
 * Stałe ceny sprzedaży dostawców: wszystkie artykuły od dostawcy sklep sprzedaje za jedną cenę brutto. We
 * wprowadzaniu danych to cena sugerowana artykułów dokumentu tego dostawcy, wpisywana sama jako cena sprzedaży.
 */
export function SupplierSalePrices({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = useSupplierSalePrices(storeId)
  const { save, remove } = useSupplierSalePriceActions(storeId)
  const [editing, setEditing] = useState<Editing | null>(null)

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const removePrice = (price: SupplierSalePrice) =>
    remove.mutate(price.supplierId, {
      onSuccess: () =>
        toast.success(`Usunięto stałą cenę dostawcy ${price.supplierName}`, {
          action: {
            label: 'Przywróć',
            onClick: () => save.mutate({ supplierId: price.supplierId, salePrice: price.salePrice }, { onError }),
          },
        }),
      onError,
    })

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać cen dostawców</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }

  const prices = data ?? []

  return (
    <Card className="gap-0 py-0">
      {isPending ? (
        <div aria-hidden className="m-4 h-16 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : prices.length === 0 ? (
        <div className="flex flex-col items-center gap-1 px-6 py-8 text-center">
          <TagIcon aria-hidden className="mb-1 size-6 text-muted-foreground" />
          <p className="font-medium">Żaden dostawca nie ma stałej ceny</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Dodaj dostawcę, którego wszystkie artykuły sprzedajecie za tę samą cenę.
          </p>
        </div>
      ) : (
        <ul className="divide-y">
          {prices.map((price) => (
            <li key={price.supplierId} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{price.supplierName}</p>
                <p className="truncate text-sm text-muted-foreground">
                  {price.supplierCode ? `nr ${price.supplierCode}` : 'bez numeru'}
                  {price.createdBy ? ` · ustawił(a) ${price.createdBy}` : ''}
                </p>
              </div>
              <span className="text-base font-semibold tabular-nums">{formatMoney(price.salePrice)}</span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Zmień cenę dostawcy ${price.supplierName}`}
                onClick={() => setEditing({ supplierId: price.supplierId, price: moneyText.format(price.salePrice) })}
              >
                <PencilIcon />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Usuń stałą cenę dostawcy ${price.supplierName}`}
                onClick={() => removePrice(price)}
              >
                <Trash2Icon />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="border-t px-4 py-3">
        <Button variant="outline" onClick={() => setEditing({ supplierId: null, price: '' })}>
          <PlusIcon />
          Dodaj dostawcę
        </Button>
      </div>

      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-xl">
          {editing && (
            <PriceForm
              storeId={storeId}
              initial={editing}
              existing={prices}
              saving={save.isPending}
              onCancel={() => setEditing(null)}
              onSave={(supplierId, salePrice) =>
                save.mutate(
                  { supplierId, salePrice },
                  {
                    onSuccess: () => {
                      setEditing(null)
                      toast.success('Zapisano stałą cenę dostawcy')
                    },
                    onError,
                  },
                )
              }
            />
          )}
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function PriceForm({
  storeId,
  initial,
  existing,
  saving,
  onCancel,
  onSave,
}: {
  storeId: string
  initial: Editing
  existing: SupplierSalePrice[]
  saving: boolean
  onCancel: () => void
  onSave: (supplierId: string, salePrice: number) => void
}) {
  const [supplierId, setSupplierId] = useState(initial.supplierId)
  const [text, setText] = useState(initial.price)
  const [touched, setTouched] = useState(false)
  const price = parsePrice(text)
  const current = existing.find((item) => item.supplierId === supplierId)

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        setTouched(true)
        if (supplierId && price !== null) onSave(supplierId, price)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{initial.supplierId ? 'Zmień stałą cenę' : 'Stała cena dostawcy'}</DialogTitle>
        <DialogDescription>
          Wszystkie artykuły od tego dostawcy dostaną we wprowadzaniu danych tę cenę jako cenę sugerowaną i cenę
          sprzedaży.
        </DialogDescription>
      </DialogHeader>

      {!initial.supplierId && (
        <SupplierPicker storeId={storeId} selectedId={supplierId} onSelect={setSupplierId} suggested={null} />
      )}

      <TextField
        label="Cena sprzedaży brutto (zł)"
        inputMode="decimal"
        autoComplete="off"
        value={text}
        onChange={(event) => setText(event.target.value)}
        error={touched && price === null ? 'Podaj cenę większą od zera, najwyżej dwa miejsca po przecinku' : undefined}
        hint={current && !initial.supplierId ? `Dostawca ma już cenę ${formatMoney(current.salePrice)}, zapis ją zmieni.` : undefined}
      />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saving} disabled={!supplierId}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
