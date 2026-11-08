import { useState } from 'react'
import { Link2OffIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCalculationLinks, useCalculationLinkSuggestions, usePriceProductSearch } from '@/features/deli-prices/api'
import { formatMoney } from '@/features/deli-prices/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError } from '@/lib/api'
import type { PartnerCalculation } from '@/lib/types'

type Props = {
  storeId: string
  partnerStoreId: string
  target: PartnerCalculation | null
  onClose: () => void
}

/**
 * Przypisanie wyrobu sąsiada do naszego artykułu: propozycje podobnych nazw jak we wprowadzaniu danych, a gdy żadna
 * nie pasuje, wyszukiwarka w kartotece.
 */
export function PartnerLinkDialog({ storeId, partnerStoreId, target, onClose }: Props) {
  return (
    <Dialog open={target !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {target && (
          <LinkContent
            key={target.calculationId}
            storeId={storeId}
            partnerStoreId={partnerStoreId}
            target={target}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function LinkContent({
  storeId,
  partnerStoreId,
  target,
  onClose,
}: {
  storeId: string
  partnerStoreId: string
  target: PartnerCalculation
  onClose: () => void
}) {
  const [query, setQuery] = useState('')
  const suggestions = useCalculationLinkSuggestions(storeId, target.name)
  const search = usePriceProductSearch(storeId, query)
  const { link, unlink, restore } = useCalculationLinks(storeId, partnerStoreId)
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const searching = search.longEnough
  const loading = searching ? search.isPending : suggestions.isPending
  const items = searching ? (search.data ?? []) : (suggestions.data ?? [])

  const pick = (itm8: string) => {
    setPending(itm8)
    setError(null)
    link.mutate(
      { sourceProductId: target.sourceProductId, itm8 },
      {
        onSuccess: onClose,
        onError: (failure) => {
          setError(failure instanceof ApiError ? failure.message : 'Nie udało się przypisać artykułu')
          setPending(null)
        },
      },
    )
  }

  const removeLink = () => {
    const current = target.link
    if (!current) return
    unlink.mutate(current.id, {
      onSuccess: () => {
        onClose()
        toast.success('Odpięto artykuł', {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(current.id, {
                onError: (failure) =>
                  toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się przywrócić'),
              }),
          },
        })
      },
      onError: (failure) => setError(failure instanceof ApiError ? failure.message : 'Nie udało się odpiąć'),
    })
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przypisz do naszego artykułu</DialogTitle>
        <DialogDescription>
          Przypisanie zostanie zapamiętane, a cena tego wyrobu pójdzie do eksportu z naszym EAN-em.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 rounded-xl border bg-muted px-4 py-3">
        <div className="min-w-0">
          <p className="text-xs text-muted-foreground">Wyrób sklepu zaprzyjaźnionego</p>
          <p className="text-base font-semibold break-words">{target.name}</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-muted-foreground">Cena sprzedaży</p>
          <p className="text-base font-semibold tabular-nums">{formatMoney(target.salePrice)}</p>
        </div>
      </div>

      {target.link && (
        <div className="flex items-center gap-3 rounded-xl bg-sky-200 px-3.5 py-2.5 text-sm text-sky-950 dark:bg-sky-900 dark:text-sky-50">
          <span className="min-w-0 flex-1">
            Przypisane do <span className="font-medium">{target.link.name}</span>. Wybierz inny artykuł albo odepnij.
          </span>
          <Button variant="outline" size="sm" onClick={removeLink} loading={unlink.isPending}>
            <Link2OffIcon />
            Odepnij
          </Button>
        </div>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      <SearchInput value={query} onChange={setQuery} placeholder="Szukaj w kartotece: nazwa, marka, EAN" />

      <div className="max-h-[45svh] min-h-32 overflow-y-auto overscroll-contain rounded-xl border">
        {loading ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : items.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            {searching ? 'Nic nie pasuje.' : 'Brak podobnych artykułów. Poszukaj w kartotece.'}
          </p>
        ) : (
          <ul className="divide-y">
            {items.map((item) => {
              const current = target.link?.itm8 === item.itm8
              return (
                <li key={item.itm8} className="flex items-center gap-4 px-4 py-2.5">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{item.name}</span>
                    <span className="block truncate text-xs text-muted-foreground tabular-nums">
                      {[item.brand, `EAN ${item.ean}`].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                  <ArticleStatsButton article={item} />
                  <Button
                    size="sm"
                    variant={current ? 'outline' : 'default'}
                    onClick={() => pick(item.itm8)}
                    loading={pending === item.itm8}
                    disabled={current || (pending !== null && pending !== item.itm8)}
                  >
                    {current ? 'Przypisane' : 'Przypisz'}
                  </Button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </>
  )
}
