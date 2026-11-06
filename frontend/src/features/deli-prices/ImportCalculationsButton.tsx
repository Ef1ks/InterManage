import { useMemo, useState } from 'react'
import { ListPlusIcon, LoaderCircleIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useFinishedProducts, useImportCalculations } from '@/features/deli-prices/api'
import { calculationsWord, formatMoney } from '@/features/deli-prices/format'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { FinishedProduct } from '@/lib/types'

/** Nomenklatura wyrobów, które wchodzą do automatycznego importu; ta sama co na serwerze. */
const IMPORT_NOMENCLATURE = '36'

/** Automatyczny import: wyroby z nomenklatury 36 bez kalkulacji trafiają na listę jako kalkulacje bez przepisu. */
export function ImportCalculationsButton({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <ListPlusIcon />
        Automatyczny import
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-2xl">
          {open && <ImportForm storeId={storeId} onClose={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Na start zaznaczone wszystko, co jest aktywne w kartotece; wyszukiwarka zawęża, co zaznacza „Zaznacz widoczne”. */
const checkedAtStart = (product: FinishedProduct) => product.active !== false

function ImportForm({ storeId, onClose }: { storeId: string; onClose: () => void }) {
  const { data: products, isPending, isError } = useFinishedProducts(storeId)
  const { run, undo } = useImportCalculations(storeId)
  const [query, setQuery] = useState('')
  // Wyroby ze zmienionym zaznaczeniem względem startowego.
  const [changed, setChanged] = useState<Set<string>>(() => new Set())

  const candidates = useMemo(
    () =>
      (products ?? []).filter(
        (product) => product.calculationId === null && product.nomenclature?.startsWith(IMPORT_NOMENCLATURE),
      ),
    [products],
  )
  const visible = useMemo(
    () =>
      candidates.filter((product) => matchesSearch([product.name, product.brand, product.ean, product.itm8], query)),
    [candidates, query],
  )
  const isChecked = (product: FinishedProduct) => checkedAtStart(product) !== changed.has(product.productId)
  const selected = candidates.filter(isChecked)
  const allVisibleChecked = visible.length > 0 && visible.every(isChecked)

  const toggle = (product: FinishedProduct) =>
    setChanged((current) => {
      const next = new Set(current)
      if (next.has(product.productId)) next.delete(product.productId)
      else next.add(product.productId)
      return next
    })
  const setVisible = (checked: boolean) =>
    setChanged((current) => {
      const next = new Set(current)
      visible.forEach((product) => {
        if (checkedAtStart(product) === checked) next.delete(product.productId)
        else next.add(product.productId)
      })
      return next
    })

  const submit = () =>
    run.mutate(
      selected.map((product) => product.productId),
      {
        onSuccess: ({ ids, skipped }) => {
          onClose()
          toast.success(`Dodano ${ids.length} ${calculationsWord(ids.length)} bez przepisu`, {
            description: skipped > 0 ? `Pominięto wyroby, które mają już kalkulację: ${skipped}.` : undefined,
            action: {
              label: 'Cofnij',
              onClick: () =>
                undo.mutate(ids, {
                  onSuccess: () => toast.success('Cofnięto import'),
                  onError: () => toast.error('Nie udało się cofnąć importu'),
                }),
            },
          })
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się dodać kalkulacji'),
      },
    )

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Automatyczny import kalkulacji</DialogTitle>
        <DialogDescription>
          Wyroby z nomenklatury 36, które nie mają jeszcze kalkulacji. Odznacz te, których nie chcesz dodawać. Trafią na
          listę z oznaczeniem „bez przepisu”, a cenę policzymy po dodaniu składników.
        </DialogDescription>
      </DialogHeader>

      {isPending ? (
        <p className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
          Wczytuję wyroby…
        </p>
      ) : isError ? (
        <p className="py-10 text-center text-sm text-destructive">Nie udało się wczytać wyrobów</p>
      ) : candidates.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted-foreground">
          Wszystkie wyroby z nomenklatury 36 mają już kalkulację.
        </p>
      ) : (
        <>
          <SearchInput value={query} onChange={setQuery} placeholder="Szukaj wyrobu: nazwa, marka, EAN, ITM8" />
          <div className="max-h-[50vh] overflow-y-auto overscroll-contain rounded-lg border">
            <label className="sticky top-0 z-10 flex cursor-pointer items-center gap-3 border-b bg-popover px-3 py-2 text-sm font-medium">
              <input
                type="checkbox"
                checked={allVisibleChecked}
                disabled={visible.length === 0}
                onChange={(event) => setVisible(event.target.checked)}
                className="size-4 cursor-pointer accent-foreground"
              />
              {query.trim() === '' ? 'Zaznacz wszystkie' : 'Zaznacz wszystkie znalezione'} ({visible.length})
            </label>
            {visible.length === 0 ? (
              <p className="px-3 py-8 text-center text-sm text-muted-foreground">Żaden wyrób nie pasuje.</p>
            ) : (
              <ul className="divide-y">
                {visible.map((product) => (
                  <li key={product.productId}>
                    <label className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-muted">
                      <input
                        type="checkbox"
                        checked={isChecked(product)}
                        onChange={() => toggle(product)}
                        className="size-4 shrink-0 cursor-pointer accent-foreground"
                      />
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-x-2 text-sm font-medium">
                          <span className="min-w-0 truncate">{product.name ?? 'Bez nazwy'}</span>
                          {product.brand && <span className="font-normal text-muted-foreground">{product.brand}</span>}
                          {product.active === false && <Badge variant="secondary">nieaktywny</Badge>}
                        </span>
                        <span className="block truncate text-xs text-muted-foreground tabular-nums">
                          EAN {product.ean} · NOM {product.nomenclature}
                        </span>
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                        {formatMoney(product.salePrice)}
                      </span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onClose} disabled={run.isPending}>
          Anuluj
        </Button>
        <Button size="lg" loading={run.isPending} disabled={selected.length === 0} onClick={submit}>
          Dodaj {selected.length} {calculationsWord(selected.length)}
        </Button>
      </FormActions>
    </>
  )
}
