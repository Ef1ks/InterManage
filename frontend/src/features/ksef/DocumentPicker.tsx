import { useState } from 'react'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { bonNumber, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { useDocumentCandidates, useInvoiceDocumentLinks } from '@/features/ksef/api'
import { ApiError } from '@/lib/api'
import type { KsefInvoice } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Przypięcie przyjęcia albo korekty od dostawcy do faktury z KSeF: domyślnie dokumenty z wartością zakupu ±10% kwoty
 * netto faktury, najpierw tego dostawcy i od najbliższej kwoty. Faktura staje się wprowadzona.
 */
export function DocumentPicker({
  storeId,
  invoice,
  open,
  onOpenChange,
}: {
  storeId: string
  invoice: KsefInvoice
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {open && <PickerBody storeId={storeId} invoice={invoice} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function PickerBody({ storeId, invoice, onDone }: { storeId: string; invoice: KsefInvoice; onDone: () => void }) {
  const [query, setQuery] = useState('')
  const [amountRange, setAmountRange] = useState(true)
  const { data: candidates = [], isPending, isError, isPlaceholderData } = useDocumentCandidates(
    storeId,
    invoice.id,
    query,
    amountRange,
    true,
  )
  const { link, unlink } = useInvoiceDocumentLinks(storeId)
  const value = Math.abs(invoice.netAmount ?? 0)
  const attached = new Set(invoice.deliveries.map((delivery) => delivery.id))

  const pick = (documentId: string, label: string) =>
    link.mutate(
      { invoiceId: invoice.id, documentId },
      {
        onSuccess: () => {
          onDone()
          toast.success(`Przypięto: ${label}`, {
            description: `Faktura ${invoice.invoiceNumber} jest wprowadzona.`,
            action: { label: 'Cofnij', onClick: () => unlink.mutate({ invoiceId: invoice.id, documentId }) },
          })
        },
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się przypiąć dokumentu'),
      },
    )

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przypnij dokument do faktury {invoice.invoiceNumber}</DialogTitle>
        <DialogDescription className="tabular-nums">
          {invoice.sellerName ?? 'Bez nazwy'} · {formatMoney(invoice.netAmount ?? 0)} zł netto. Przyjęcia i korekty od
          dostawcy, najpierw tego dostawcy i od najbliższej kwoty.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <SearchInput
          className="min-w-0 flex-1 basis-64"
          value={query}
          onChange={setQuery}
          placeholder="Szukaj: numer, dostawca, nr dok. dostawcy"
        />
        <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground tabular-nums">
          <Switch checked={amountRange} onCheckedChange={setAmountRange} />
          Wartość {formatMoney(value * 0.9)}–{formatMoney(value * 1.1)} zł
        </label>
      </div>

      <div
        className={cn(
          'max-h-[50svh] min-h-32 overflow-y-auto rounded-xl border transition-opacity duration-150',
          isPlaceholderData && 'opacity-60',
        )}
      >
        {isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać dokumentów.</p>
        ) : isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : candidates.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            {amountRange
              ? 'Żaden dokument nie ma wartości w tym zakresie. Wyłącz zakres albo wyszukaj po numerze.'
              : 'Nie znaleziono dokumentu.'}
          </p>
        ) : (
          <ul className="divide-y">
            {candidates.map((candidate) => {
              const already = attached.has(candidate.id)
              const label = `${candidate.typeSingular.toLowerCase()} ${candidate.number}`
              return (
                <li key={candidate.id}>
                  <button
                    type="button"
                    disabled={already || link.isPending}
                    onClick={() => pick(candidate.id, label)}
                    className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted disabled:cursor-default disabled:opacity-50 disabled:hover:bg-transparent"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
                        {candidate.typeSingular}{' '}
                        {candidate.type === 'PZ' ? `bon ${bonNumber(candidate.number)}` : candidate.number}
                        {candidate.sameSupplier && (
                          <span className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200">
                            Ten dostawca
                          </span>
                        )}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {[
                          formatDocumentDate(candidate.documentDate),
                          candidate.supplierName,
                          candidate.supplierDocumentNumber && `nr dok. ${candidate.supplierDocumentNumber}`,
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                        {already && ' · już przy tej fakturze'}
                      </span>
                    </span>
                    <span className="text-right text-sm">
                      <span className="block font-medium">{formatMoney(candidate.purchaseValue)} zł</span>
                      <span className="block text-xs text-muted-foreground">
                        {candidate.difference === 0 ? 'ta sama kwota' : `różnica ${formatMoney(candidate.difference)} zł`}
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </>
  )
}
