import { useState } from 'react'

import { SearchInput } from '@/components/SearchInput'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { type KsefFilters, useKsefInvoices, useKsefOverview } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import type { KsefInvoice } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

const SORT = { key: 'issueDate', direction: 'desc' } as const

/** Faktury zakupowe sklepu z KSeF (bez korekt), najnowsze na górze; wyszukiwanie po numerze, dostawcy i NIP-ie. */
export function InvoicePickerDialog({
  storeId,
  open,
  onOpenChange,
  onPick,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (invoice: KsefInvoice) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {open && <PickerBody storeId={storeId} onPick={onPick} />}
      </DialogContent>
    </Dialog>
  )
}

function PickerBody({ storeId, onPick }: { storeId: string; onPick: (invoice: KsefInvoice) => void }) {
  const [query, setQuery] = useState('')
  const debounced = useDebouncedValue(query.trim(), 300)
  const filters: KsefFilters = {
    query: debounced,
    from: '',
    to: '',
    entered: 'ALL',
    kind: 'INVOICES',
    internalId: 'ALL',
    showSkipped: false,
  }
  const overview = useKsefOverview(storeId)
  const invoices = useKsefInvoices(storeId, filters, SORT)
  const list = invoices.data?.pages.flatMap((page) => page.invoices) ?? []
  const loadMoreRef = useLoadMore<HTMLLIElement>(
    () => invoices.fetchNextPage(),
    Boolean(invoices.hasNextPage) && !invoices.isFetchingNextPage,
    list.length,
  )

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Wczytaj fakturę z KSeF</DialogTitle>
        <DialogDescription>
          Pozycje faktury trafią do tabeli: znane artykuły z danymi z kartoteki, nowe do uzupełnienia.
        </DialogDescription>
      </DialogHeader>

      <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer faktury, dostawca, NIP" />

      <div
        className={cn(
          'max-h-[55svh] min-h-32 overflow-y-auto rounded-xl border transition-opacity duration-150',
          invoices.isPlaceholderData && 'opacity-60',
        )}
      >
        {overview.data && !overview.data.configured ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            Sklep nie ma połączenia z KSeF. Połącz go w zakładce Faktury KSeF → Ustawienia.
          </p>
        ) : invoices.isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać faktur.</p>
        ) : invoices.isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : list.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Nie znaleziono faktury.</p>
        ) : (
          <ul className="divide-y">
            {list.map((invoice) => (
              <li key={invoice.id}>
                <button
                  type="button"
                  onClick={() => onPick(invoice)}
                  className="flex w-full items-center gap-4 px-4 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted"
                >
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2 text-sm font-medium">
                      <span className="truncate">{invoice.invoiceNumber}</span>
                      {invoice.entered && (
                        <span className="shrink-0 rounded-md bg-emerald-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200">
                          wprowadzona
                        </span>
                      )}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {formatDate(invoice.issueDate)} · {invoice.sellerName ?? invoice.sellerNip}
                    </span>
                  </span>
                  <span className="text-right text-sm font-medium">{formatMoney(invoice.netAmount)} zł</span>
                </button>
              </li>
            ))}
            <li ref={loadMoreRef} aria-hidden />
          </ul>
        )}
      </div>
    </>
  )
}
