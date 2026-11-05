import { useState } from 'react'
import { CheckCircle2Icon, LinkIcon, UnlinkIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useTransferReceipt, useTransferReceiptCandidates } from '@/features/documents/api'
import { commonItemsLabel } from '@/features/documents/DeliveryCorrections'
import { documentPaths, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { IncomingTransfer } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Dokument tego sklepu przypięty do ruchu międzymagazynowego: dopiero on mówi sklepowi wysyłającemu, że towar jest
 * wprowadzony. To MM na plus z raportu dokumentów, więc można go przypiąć po jego wgraniu.
 */
export function TransferReceiptSection({ storeId, transfer }: { storeId: string; transfer: IncomingTransfer }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { attach, detach } = useTransferReceipt(storeId, transfer.id)
  const [picking, setPicking] = useState(false)
  const receipt = transfer.receipt

  const unlink = () => {
    if (!receipt) return
    detach.mutate(undefined, {
      onSuccess: () =>
        toast.success(`Odpięto dokument ${receipt.number}`, {
          action: { label: 'Przywróć', onClick: () => attach.mutate(receipt.documentId) },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się odpiąć dokumentu')),
    })
  }

  return (
    <section
      aria-labelledby="transfer-receipt"
      className={cn(
        'mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border px-4 py-3',
        receipt
          ? 'border-emerald-300/70 bg-emerald-50/60 dark:border-emerald-500/30 dark:bg-emerald-500/5'
          : 'border-amber-300 bg-amber-50/60 dark:border-amber-500/40 dark:bg-amber-500/5',
      )}
    >
      {receipt ? (
        <CheckCircle2Icon aria-hidden className="size-5 shrink-0 text-emerald-700 dark:text-emerald-400" />
      ) : (
        <LinkIcon aria-hidden className="size-5 shrink-0 text-amber-700 dark:text-amber-400" />
      )}
      <div className="min-w-0 flex-1 text-sm">
        {receipt ? (
          <>
            <h3 id="transfer-receipt" className="font-semibold">
              Potwierdzone dokumentem{' '}
              <Link
                to={documentPaths.document('MM', receipt.documentId)}
                className="underline-offset-4 hover:underline"
              >
                {receipt.number}
              </Link>
            </h3>
            <p className="text-muted-foreground tabular-nums">
              {formatDocumentDate(receipt.documentDate)} · zakup {formatMoney(receipt.purchaseValue)} zł
              {receipt.linkedBy && ` · przypiął(-ęła) ${receipt.linkedBy}`}
            </p>
          </>
        ) : (
          <>
            <h3 id="transfer-receipt" className="font-semibold">
              Przypnij swój dokument przyjęcia
            </h3>
            <p className="text-muted-foreground">
              Dopóki go nie przypniesz, sklep {storeLabel(transfer.fromStore)} widzi ten dokument jako czekający. To
              ruch międzymagazynowy na plus z raportu dokumentów tego sklepu.
            </p>
          </>
        )}
      </div>
      {canEdit &&
        (receipt ? (
          <Button variant="ghost" loading={detach.isPending} onClick={unlink}>
            <UnlinkIcon />
            Odepnij
          </Button>
        ) : (
          <Button onClick={() => setPicking(true)}>
            <LinkIcon />
            Przypnij dokument
          </Button>
        ))}

      <Dialog open={picking} onOpenChange={setPicking}>
        <DialogContent className="gap-4 p-6 sm:max-w-2xl">
          {picking && (
            <ReceiptPicker
              storeId={storeId}
              transfer={transfer}
              linking={attach.isPending}
              onPick={(documentId, number) =>
                attach.mutate(documentId, {
                  onSuccess: () => {
                    setPicking(false)
                    toast.success(`Przypięto dokument ${number}`, {
                      description: `Sklep ${storeLabel(transfer.fromStore)} widzi, że towar jest wprowadzony.`,
                      action: { label: 'Cofnij', onClick: () => detach.mutate() },
                    })
                  },
                  onError: (error) => toast.error(errorMessage(error, 'Nie udało się przypiąć dokumentu')),
                })
              }
            />
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}

function ReceiptPicker({
  storeId,
  transfer,
  linking,
  onPick,
}: {
  storeId: string
  transfer: IncomingTransfer
  linking: boolean
  onPick: (documentId: string, number: string) => void
}) {
  const [query, setQuery] = useState('')
  const {
    data: candidates = [],
    isPending,
    isError,
    isPlaceholderData,
  } = useTransferReceiptCandidates(storeId, transfer.id, query)

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Dokument przyjęcia do {transfer.number}</DialogTitle>
        <DialogDescription>
          Ruchy międzymagazynowe na plus tego sklepu z 30 dni po dokumencie, najpierw te z największą liczbą wspólnych
          artykułów. Brak dokumentu? Wgraj raport dokumentów w Imporcie danych.
        </DialogDescription>
      </DialogHeader>

      <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer dokumentu" />

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
            Brak pasujących dokumentów w tym sklepie.
          </p>
        ) : (
          <ul className="divide-y">
            {candidates.map((candidate) => (
              <li key={candidate.id}>
                <button
                  type="button"
                  disabled={linking}
                  onClick={() => onPick(candidate.id, candidate.number)}
                  className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted disabled:cursor-default disabled:opacity-50"
                >
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      {candidate.number}
                      {candidate.commonItems > 0 && (
                        <span className="rounded-md bg-emerald-200 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-100">
                          {commonItemsLabel(candidate.commonItems)}
                        </span>
                      )}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {formatDocumentDate(candidate.documentDate)} · {candidate.lineCount} poz.
                    </span>
                  </span>
                  <span className="text-right text-sm font-medium">{formatMoney(candidate.purchaseValue)} zł</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}
