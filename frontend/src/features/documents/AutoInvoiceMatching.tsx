import { useState } from 'react'
import { LoaderCircleIcon, WandSparklesIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { useAutoInvoiceLinks, useAutoInvoiceMatches } from '@/features/documents/api'
import { bonNumber, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { AutoInvoiceMatch } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/** „1 fakturę”, „2 faktury”, „5 faktur” (biernik, jak w „Przypięto …”). */
function invoicesWord(count: number) {
  if (count === 1) return 'fakturę'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'faktury' : 'faktur'
}

/** „1 korektę”, „2 korekty”, „5 korekt” (biernik). */
function correctionsWord(count: number) {
  if (count === 1) return 'korektę'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'korekty' : 'korekt'
}

const pairOf = (match: AutoInvoiceMatch) => ({ documentId: match.documentId, invoiceId: match.invoiceId })

/** Teksty okna: przyjęcia dostają faktury, korekty od dostawcy faktury korygujące. */
const TEXTS = {
  pz: {
    description:
      'Przyjęcia bez faktury i faktury z KSeF od tego samego dostawcy, z kwotą netto różną najwyżej o 10 zł ' +
      '(wystawione do 45 dni przed albo po przyjęciu). Odznacz pary, których nie chcesz przypinać.',
    loading: 'Szukam faktur do przyjęć…',
    empty: 'Żadne przyjęcie bez faktury nie ma faktury tego dostawcy z podobną kwotą.',
    document: 'Nr dok. w przyjęciu',
    pairLabel: (match: AutoInvoiceMatch) =>
      `Przypnij fakturę ${match.invoiceNumber} do przyjęcia ${match.documentNumber}`,
  },
  wz: {
    description:
      'Korekty od dostawcy bez faktury i faktury korygujące z KSeF od tego samego dostawcy, z kwotą netto różną ' +
      'najwyżej o 10 zł (wystawione do 45 dni przed albo po korekcie). Odznacz pary, których nie chcesz przypinać.',
    loading: 'Szukam faktur korygujących do korekt…',
    empty: 'Żadna korekta od dostawcy bez faktury nie ma faktury korygującej tego dostawcy z podobną kwotą.',
    document: 'Korekta',
    pairLabel: (match: AutoInvoiceMatch) =>
      `Przypnij fakturę korygującą ${match.invoiceNumber} do korekty ${match.documentNumber}`,
  },
}

/**
 * Przycisk nad listą przyjęć albo korekt od dostawcy i okno automatycznego przypisania faktur KSeF: dokumenty bez
 * faktury i niewprowadzone faktury (do korekt: faktury korygujące) tego samego dostawcy z kwotą netto różną najwyżej
 * o 10 zł. Wszystkie propozycje są zaznaczone, odznacza się te, których nie chcemy; zatwierdzenie przypina resztę,
 * a toast pozwala cofnąć całość.
 */
export function AutoInvoiceMatching({ storeId, typeCode }: { storeId: string; typeCode: 'pz' | 'wz' }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const canViewKsef = useCan('KSEF_INVOICES', 'VIEW')
  const [open, setOpen] = useState(false)
  if (!canEdit || !canViewKsef) return null
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <WandSparklesIcon />
        Przypisz faktury automatycznie
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-5xl">
          {open && <AutoInvoiceMatchingContent storeId={storeId} typeCode={typeCode} onDone={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

function AutoInvoiceMatchingContent({
  storeId,
  typeCode,
  onDone,
}: {
  storeId: string
  typeCode: 'pz' | 'wz'
  onDone: () => void
}) {
  const texts = TEXTS[typeCode]
  const { data: matches, isPending, isError, error } = useAutoInvoiceMatches(storeId, typeCode, true)
  const { link, undo } = useAutoInvoiceLinks(storeId, typeCode)
  // Odznaczone propozycje; nowe (po odświeżeniu) są zaznaczone.
  const [rejected, setRejected] = useState<Set<string>>(() => new Set())
  const all = matches ?? []
  const accepted = all.filter((match) => !rejected.has(match.documentId))
  const toggle = (documentId: string) =>
    setRejected((current) => {
      const next = new Set(current)
      if (next.has(documentId)) next.delete(documentId)
      else next.add(documentId)
      return next
    })
  const allAccepted = all.length > 0 && accepted.length === all.length

  const accept = () => {
    const pairs = accepted.map(pairOf)
    link.mutate(pairs, {
      onSuccess: (linked) => {
        onDone()
        const count = linked.length
        const attached = linked.reduce((sum, pair) => sum + (pair.attached?.length ?? 0), 0)
        toast.success(`Przypięto ${count} ${invoicesWord(count)}`, {
          description:
            attached > 0
              ? `Faktury w KSeF są wprowadzone. Do przyjęć trafiło też ${attached} ${correctionsWord(attached)} ` +
                'od dostawcy (według faktur, które korygują).'
              : 'Faktury w KSeF są wprowadzone.',
          action: {
            label: 'Cofnij',
            onClick: () =>
              undo.mutate(linked, {
                onSuccess: () => toast.success('Cofnięto automatyczne przypisanie'),
                onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się cofnąć przypisania')),
              }),
          },
        })
      },
      onError: (failure) => toast.error(errorMessage(failure, 'Nie udało się przypiąć faktur')),
    })
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Automatyczne przypisanie faktur</DialogTitle>
        <DialogDescription>{texts.description}</DialogDescription>
      </DialogHeader>

      {isPending ? (
        <p className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
          {texts.loading}
        </p>
      ) : isError ? (
        <p className="py-10 text-center text-sm text-destructive">
          {errorMessage(error, 'Nie udało się znaleźć propozycji')}
        </p>
      ) : all.length === 0 ? (
        <div className="py-10 text-center">
          <p className="font-medium">Brak propozycji</p>
          <p className="mt-1 text-sm text-muted-foreground">{texts.empty}</p>
        </div>
      ) : (
        <div className="max-h-[60vh] overflow-auto rounded-lg border">
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-background">
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-10 pl-4">
                  <input
                    type="checkbox"
                    aria-label="Zaznacz wszystkie"
                    className="size-4 cursor-pointer accent-foreground"
                    checked={allAccepted}
                    ref={(input) => {
                      if (input) input.indeterminate = accepted.length > 0 && !allAccepted
                    }}
                    onChange={() =>
                      setRejected(allAccepted ? new Set(all.map((match) => match.documentId)) : new Set())
                    }
                  />
                </TableHead>
                <TableHead>{texts.document}</TableHead>
                <TableHead>Dostawca</TableHead>
                <TableHead>Faktura</TableHead>
                <TableHead className="text-right">Zakup netto zł</TableHead>
                <TableHead className="text-right">Faktura netto zł</TableHead>
                <TableHead className="pr-4 text-right">Różnica zł</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {all.map((match) => {
                const checked = !rejected.has(match.documentId)
                return (
                  <TableRow
                    key={match.documentId}
                    onClick={() => toggle(match.documentId)}
                    className={cn('cursor-pointer tabular-nums', !checked && 'text-muted-foreground')}
                  >
                    <TableCell className="pl-4">
                      <input
                        type="checkbox"
                        aria-label={texts.pairLabel(match)}
                        className="size-4 cursor-pointer accent-foreground"
                        checked={checked}
                        onClick={(event) => event.stopPropagation()}
                        onChange={() => toggle(match.documentId)}
                      />
                    </TableCell>
                    <TableCell>
                      {typeCode === 'pz' ? (
                        <div
                          className="font-medium"
                          title={`Bon ${bonNumber(match.documentNumber)} · numer wewnętrzny ${match.documentNumber}`}
                        >
                          {match.supplierDocumentNumber ?? <span className="text-muted-foreground">brak nr dok.</span>}
                        </div>
                      ) : (
                        <div className="font-medium">{match.documentNumber}</div>
                      )}
                      <div className="text-xs text-muted-foreground">{formatDocumentDate(match.documentDate)}</div>
                    </TableCell>
                    <TableCell className="max-w-56 truncate" title={match.supplierName ?? undefined}>
                      {match.supplierName ?? match.sellerName}
                    </TableCell>
                    <TableCell>
                      <div className="font-medium">{match.invoiceNumber}</div>
                      <div className="text-xs text-muted-foreground">{formatDate(match.issueDate)}</div>
                    </TableCell>
                    <TableCell className="text-right">{formatMoney(match.purchaseValue)}</TableCell>
                    <TableCell className="text-right">{formatMoney(match.netAmount)}</TableCell>
                    <TableCell className="pr-4 text-right">
                      {match.difference === 0 ? (
                        <span className="text-muted-foreground">zgodne</span>
                      ) : (
                        formatMoney(match.difference)
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground tabular-nums" aria-live="polite">
          {all.length > 0 && `Zaznaczono ${accepted.length} z ${all.length}`}
        </p>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="ghost" onClick={onDone}>
            Anuluj
          </Button>
          <Button size="xl" disabled={accepted.length === 0} loading={link.isPending} onClick={accept}>
            Zaakceptuj automatyczne przypisania
          </Button>
        </div>
      </div>
    </>
  )
}
