import { Link2Icon, Link2OffIcon, SearchIcon } from 'lucide-react'
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
import { formatPrice, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError } from '@/lib/api'
import type { CorrectionDeliveryLine, DataEntryCatalogArticle, DataEntryCatalogProduct } from '@/lib/types'
import { cn } from '@/lib/utils'

import { lookupProducts, useLinkProduct, useLinkSuggestions, useUnlinkProduct } from './api'
import { CatalogListDialog } from './CatalogListDialog'
import { bonLineSuggestions } from './correction-macro'
import type { DataEntryRow, RowLink } from './rows'

/**
 * Wyszukiwanie w liście towarów z nazwy z faktury: początki pierwszych dwóch słów, bo kartoteka skraca nazwy
 * („SZYNKA KRUCHA” → „szy kru”). Idzie do pola Szukaj, w którym słowo trafia też w markę: dostawcy piszą markę
 * w nazwie („KABANOSY TARCZYNSKI” → „kab tar”), a rodzaj mięsa jest w kartotece tylko w marce („FILET Z KURCZAKA” →
 * „fil kur” → FILET SWIEZY KL A, marka KURCZAK). Opis kraju pochodzenia pomijany.
 */
function searchFilter(text: string) {
  return text
    .toLowerCase()
    .replace(/\bkr\.?\s*poch\b.*$/, '')
    .split(/[^\p{L}]+/u)
    .filter((word) => word.length >= 3)
    .slice(0, 2)
    .map((word) => word.slice(0, 3))
    .join(' ')
}

export type LinkTarget = { row: DataEntryRow; sellerNip: string | null; sellerName: string | null }

/**
 * Przypisanie pozycji bez EAN-u (np. „JABŁKO” z faktury) albo spoza kartoteki do artykułu z kartoteki: propozycje
 * podobnych nazw, a gdy żadna nie pasuje, wyszukiwanie w liście towarów. Z fakturą przypięcie jest zapamiętane dla
 * dostawcy (NIP) i kodu z kolumny EAN, więc kolejne faktury wczytują ten artykuł od razu; bez faktury dotyczy tylko
 * tabeli. Przy korekcie z wybranym bonem na górze są jego pozycje (`bonLines`): mięso ma na fakturze inny kod i nazwę.
 */
export function LinkArticleDialog({
  storeId,
  target,
  onClose,
  onLinked,
  onUnlinked,
  inTable,
  lastSaleDate,
  bonLines,
}: {
  storeId: string
  target: LinkTarget | null
  onClose: () => void
  onLinked: (row: DataEntryRow, product: DataEntryCatalogProduct, link: RowLink | null) => void
  onUnlinked: (row: DataEntryRow) => void
  inTable: Set<string>
  lastSaleDate: string | null
  bonLines?: CorrectionDeliveryLine[]
}) {
  return (
    <Dialog open={target !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        {target && (
          <LinkContent
            key={target.row.id}
            storeId={storeId}
            target={target}
            onLinked={onLinked}
            onUnlinked={onUnlinked}
            inTable={inTable}
            lastSaleDate={lastSaleDate}
            bonLines={bonLines}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function LinkContent({
  storeId,
  target,
  onLinked,
  onUnlinked,
  inTable,
  lastSaleDate,
  bonLines,
}: {
  storeId: string
  target: LinkTarget
  onLinked: (row: DataEntryRow, product: DataEntryCatalogProduct, link: RowLink | null) => void
  onUnlinked: (row: DataEntryRow) => void
  inTable: Set<string>
  lastSaleDate: string | null
  bonLines?: CorrectionDeliveryLine[]
}) {
  const { row, sellerNip, sellerName } = target
  // Przypięty wiersz ma już EAN artykułu; szukamy po nazwie z faktury albo przypiętym kodzie.
  const code = row.link?.code ?? row.ean
  const text = row.invoiceName ?? code
  const suggestions = useLinkSuggestions(storeId, text, sellerNip, true)
  const link = useLinkProduct(storeId)
  const unlink = useUnlinkProduct(storeId)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const pick = async (item: Pick<DataEntryCatalogArticle, 'itm8' | 'ean'>) => {
    setPending(item.itm8)
    setError(null)
    try {
      if (sellerNip) {
        const saved = await link.mutateAsync({ sellerNip, code, itm8: item.itm8 })
        onLinked(row, saved.product, { id: saved.id, code: saved.code })
      } else {
        const key = item.ean.replace(/^0+/, '')
        const product = (await lookupProducts(storeId, [item.ean])).find((found) => found.ean === key)
        if (!product) throw new Error()
        onLinked(row, product, null)
      }
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Nie udało się przypisać artykułu')
      setPending(null)
    }
  }

  const removeLink = () => {
    if (!row.link) return
    setError(null)
    unlink.mutate(row.link.id, {
      onSuccess: () => onUnlinked(row),
      onError: (failure) => setError(failure instanceof ApiError ? failure.message : 'Nie udało się odpiąć'),
    })
  }

  const list = suggestions.data ?? []
  const fromBon = bonLines
    ? bonLineSuggestions(bonLines, row.invoiceName ?? row.name, row.before?.unitNet ?? row.unitNet)
    : []

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przypisz do artykułu</DialogTitle>
        <DialogDescription>
          {sellerNip
            ? `Przypięcie zostanie zapamiętane: kolejne faktury ${sellerName ?? 'tego dostawcy'} wczytają ten artykuł od razu.`
            : 'Bez faktury z KSeF przypisanie dotyczy tylko tej tabeli (nie zostanie zapamiętane).'}
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 rounded-xl border bg-muted px-4 py-3">
        <div className="min-w-0">
          <p className="text-xs text-muted-foreground">Pozycja z faktury</p>
          <p className="text-base font-semibold break-words">{row.invoiceName || row.name || code}</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-muted-foreground">Cena zakupu netto z faktury</p>
          <p className="text-base font-semibold tabular-nums">
            {row.unitNet === null ? '–' : `${formatPrice(row.unitNet)} zł`}
            {row.unitNet !== null && row.invoiceUnit && (
              <span className="font-normal text-muted-foreground"> / {row.invoiceUnit}</span>
            )}
          </p>
        </div>
      </div>

      {row.link && (
        <div className="flex items-center gap-3 rounded-xl bg-sky-200 px-3.5 py-2.5 text-sm text-sky-950 dark:bg-sky-900 dark:text-sky-50">
          <Link2Icon className="size-4 shrink-0" aria-hidden />
          <span className="min-w-0 flex-1">
            Przypięte do <span className="font-medium">{row.name}</span>. Wybierz inny artykuł albo odepnij.
          </span>
          <Button variant="outline" size="sm" onClick={removeLink} loading={unlink.isPending}>
            <Link2OffIcon />
            Odepnij
          </Button>
        </div>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      {fromBon.length > 0 && (
        <div className="max-h-[30svh] overflow-y-auto rounded-xl border">
          <p className="sticky top-0 bg-popover px-4 py-2 text-xs font-medium text-muted-foreground">Pozycje z bonu</p>
          <ul className="divide-y">
            {fromBon.map((line) => {
              const itm8 = line.articleItm8 ?? ''
              return (
                <li key={line.itm8} className="flex items-center gap-4 px-4 py-2">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{line.name}</span>
                    <span className="block truncate text-xs text-muted-foreground tabular-nums">
                      {[line.brand, `EAN ${line.ean}`].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                  {itm8 && <ArticleStatsButton article={{ itm8, name: line.name, ean: line.ean, brand: line.brand }} />}
                  <span className="text-right text-sm whitespace-nowrap tabular-nums">
                    {line.quantity !== null && formatQuantity(line.quantity)}
                    {line.purchasePrice !== null && ` × ${formatPrice(line.purchasePrice)} zł`}
                  </span>
                  <Button
                    size="sm"
                    onClick={() => pick({ itm8, ean: line.articleEan ?? '' })}
                    loading={pending === itm8}
                    disabled={pending !== null && pending !== itm8}
                  >
                    Przypisz
                  </Button>
                </li>
              )
            })}
          </ul>
        </div>
      )}

      <div className="max-h-[50svh] min-h-32 overflow-y-auto rounded-xl border">
        {suggestions.isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać propozycji.</p>
        ) : suggestions.isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : list.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            Brak podobnych artykułów. Poszukaj w liście towarów.
          </p>
        ) : (
          <ul className="divide-y">
            {list.map((item) => {
              const current = row.known && row.itm8 === item.itm8
              return (
                <li key={item.itm8} className={cn('flex items-center gap-4 px-4 py-2.5', current && 'bg-muted/60')}>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{item.name}</span>
                    <span className="block truncate text-xs text-muted-foreground tabular-nums">
                      {[item.brand, `EAN ${item.ean}`, item.supplier].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                  <ArticleStatsButton article={item} />
                  <span className="text-right text-xs text-muted-foreground tabular-nums">
                    {item.purchasePrice !== null && (
                      <span className="block text-sm text-foreground" title="Cena zakupu netto z kartoteki">
                        zakup {formatPrice(item.purchasePrice)} zł
                      </span>
                    )}
                    {(item.stock !== null || item.rotation !== null) && (
                      <span className="block whitespace-nowrap">
                        {[
                          item.stock !== null && `stan ${formatQuantity(item.stock)}`,
                          item.rotation !== null && `rotacja ${formatQuantity(item.rotation)}`,
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                      </span>
                    )}
                  </span>
                  <Button
                    size="sm"
                    variant={current ? 'outline' : 'default'}
                    onClick={() => pick(item)}
                    loading={pending === item.itm8}
                    disabled={current || (pending !== null && pending !== item.itm8)}
                  >
                    {current ? 'Przypięte' : 'Przypisz'}
                  </Button>
                </li>
              )
            })}
          </ul>
        )}
      </div>

      <DialogFooter>
        <Button variant="outline" onClick={() => setCatalogOpen(true)}>
          <SearchIcon />
          Szukaj w liście towarów
        </Button>
      </DialogFooter>

      <CatalogListDialog
        storeId={storeId}
        open={catalogOpen}
        onOpenChange={setCatalogOpen}
        inTable={inTable}
        title="Wybierz artykuł do przypisania"
        initialQuery={searchFilter(text)}
        onPick={(item) => {
          setCatalogOpen(false)
          void pick(item)
        }}
        lastSaleDate={lastSaleDate}
      />
    </>
  )
}
