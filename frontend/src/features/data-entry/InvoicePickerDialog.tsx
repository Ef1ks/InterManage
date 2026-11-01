import { ArrowLeftIcon, CheckIcon } from 'lucide-react'
import { useState } from 'react'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { type KsefFilters, useKsefInvoices, useKsefOverview } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { DataEntryInvoice, KsefInvoice } from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

import { useInvoiceLines } from './api'

const SORT = { key: 'issueDate', direction: 'desc' } as const

/**
 * Wczytanie faktury w dwóch krokach: wybór faktury z KSeF (bez korekt, najnowsze na górze), potem skąd wziąć EAN
 * pozycji (GTIN, indeks dostawcy, dodatkowy opis, nazwa albo automatycznie) z podglądem, co trafi do tabeli.
 */
export function InvoicePickerDialog({
  storeId,
  open,
  onOpenChange,
  onConfirm,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (invoice: DataEntryInvoice) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        {/* Kroki montowane tylko na czas otwarcia: każde otwarcie zaczyna od listy faktur. */}
        {open && <Steps storeId={storeId} onConfirm={onConfirm} />}
      </DialogContent>
    </Dialog>
  )
}

function Steps({ storeId, onConfirm }: { storeId: string; onConfirm: (invoice: DataEntryInvoice) => void }) {
  const [picked, setPicked] = useState<KsefInvoice | null>(null)
  return picked ? (
    <SourceStep storeId={storeId} invoice={picked} onBack={() => setPicked(null)} onConfirm={onConfirm} />
  ) : (
    <ListStep storeId={storeId} onPick={setPicked} />
  )
}

function ListStep({ storeId, onPick }: { storeId: string; onPick: (invoice: KsefInvoice) => void }) {
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
        <DialogDescription>Wybierz fakturę, a w następnym kroku pole, z którego wziąć EAN pozycji.</DialogDescription>
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

const positions = (count: number) =>
  count === 1 ? 'pozycja' : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 'pozycje' : 'pozycji'

function SourceStep({
  storeId,
  invoice,
  onBack,
  onConfirm,
}: {
  storeId: string
  invoice: KsefInvoice
  onBack: () => void
  onConfirm: (invoice: DataEntryInvoice) => void
}) {
  const [source, setSource] = useState('AUTO')
  const lines = useInvoiceLines(storeId, invoice.id, source)
  const data = lines.data
  const total = data?.lines.length ?? 0
  const sourceLabels = new Map(data?.sources.map((item) => [item.key, item.label]))

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Faktura {invoice.invoiceNumber}</DialogTitle>
        <DialogDescription>
          {invoice.sellerName ?? invoice.sellerNip} · {formatDate(invoice.issueDate)}. Skąd wziąć EAN, po którym szukamy
          artykułu w kartotece?
        </DialogDescription>
      </DialogHeader>

      {lines.isError ? (
        <p className="rounded-xl border px-4 py-6 text-center text-sm text-destructive">
          {lines.error instanceof ApiError ? lines.error.message : 'Nie udało się wczytać faktury.'}
        </p>
      ) : !data ? (
        <div aria-hidden className="h-48 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <>
          <div role="radiogroup" aria-label="Źródło EAN" className="grid gap-2 sm:grid-cols-2">
            {data.sources.map((item) => {
              const checked = item.key === source
              return (
                <button
                  key={item.key}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  onClick={() => setSource(item.key)}
                  className={cn(
                    'flex items-start gap-3 rounded-xl border px-3.5 py-2.5 text-left transition-colors duration-150 outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/30',
                    checked && 'border-foreground bg-muted/60',
                  )}
                >
                  <span
                    aria-hidden
                    className={cn(
                      'mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border',
                      checked && 'border-foreground bg-foreground text-background',
                    )}
                  >
                    {checked && <CheckIcon className="size-3" />}
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">
                      {item.label}
                      {item.key === 'AUTO' && <span className="font-normal text-muted-foreground"> (zalecane)</span>}
                    </span>
                    <span className="block text-xs text-muted-foreground tabular-nums">
                      {item.key === 'AUTO'
                        ? `Pierwszy kod znany w kartotece z dowolnego pola · ${item.matched} z ${total} w kartotece`
                        : `Kod ma ${item.filled} z ${total} ${positions(total)} · ${item.matched} w kartotece`}
                    </span>
                  </span>
                </button>
              )
            })}
          </div>

          <div
            className={cn(
              'max-h-[38svh] overflow-y-auto rounded-xl border transition-opacity duration-150',
              lines.isPlaceholderData && 'opacity-60',
            )}
          >
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">Pozycja z faktury</th>
                  <th className="px-3 py-2 text-left font-medium">EAN</th>
                  <th className="px-3 py-2 text-left font-medium">Kartoteka</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {data.lines.map((line, index) => (
                  <tr key={index}>
                    <td className="max-w-80 truncate px-3 py-1.5" title={line.invoiceName ?? undefined}>
                      {line.invoiceName}
                    </td>
                    <td className="px-3 py-1.5 whitespace-nowrap">
                      {line.ean ??
                        (line.fallbackCode ? (
                          <span
                            className="text-muted-foreground italic"
                            title={`Nie EAN, kod z faktury: ${line.fallbackSource ?? ''}`}
                          >
                            {line.fallbackCode}
                          </span>
                        ) : (
                          <span className="text-muted-foreground">brak</span>
                        ))}
                      {line.ean && data.source === 'AUTO' && line.eanSource && line.eanSource !== 'GTIN' && (
                        <span className="ml-1.5 text-xs text-muted-foreground">
                          z: {sourceLabels.get(line.eanSource) ?? line.eanSource}
                        </span>
                      )}
                    </td>
                    <td className="max-w-64 truncate px-3 py-1.5 text-muted-foreground">
                      {line.product ? (
                        <span className="text-foreground">{line.product.name}</span>
                      ) : line.ean || line.fallbackCode ? (
                        'nowy artykuł'
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <DialogFooter>
        <Button variant="ghost" onClick={onBack}>
          <ArrowLeftIcon />
          Inna faktura
        </Button>
        <Button onClick={() => data && onConfirm(data)} disabled={!data || lines.isPlaceholderData}>
          Wczytaj do tabeli
        </Button>
      </DialogFooter>
    </>
  )
}
