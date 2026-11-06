import { useDeferredValue, useState } from 'react'
import { FileTextIcon, PlusIcon, ReceiptTextIcon, RefreshCwIcon, Trash2Icon, XIcon } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  useAddWz,
  useAttachToCheck,
  useBakeryBons,
  useBakeryCheck,
  useDeleteBakeryCheck,
  useDetachFromCheck,
  useInvoiceCandidates,
  useRestoreBakeryCheck,
  useSyncWz,
  type CheckLink,
} from '@/features/bakery-check/api'
import { ComparisonPanel } from '@/features/bakery-check/ComparisonPanel'
import { bakeryPath } from '@/features/bakery-check/paths'
import { WzCard } from '@/features/bakery-check/WzCard'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'
import type { BakeryCheck } from '@/lib/types'

const errorText = (failure: unknown, fallback: string) => (failure instanceof ApiError ? failure.message : fallback)

/** Weryfikacja: bony i faktury u góry, kafelki WZ, które można zwijać, a na dole podsumowanie całości. */
export function CheckEditor({ storeId }: { storeId: string }) {
  const { checkId = '' } = useParams()
  const { data: check, isPending, isError } = useBakeryCheck(storeId, checkId)
  const navigate = useNavigate()
  const addWz = useAddWz(storeId, checkId)
  const sync = useSyncWz(storeId, checkId)
  const remove = useDeleteBakeryCheck(storeId)
  const restore = useRestoreBakeryCheck(storeId)
  const [picking, setPicking] = useState<'bon' | 'invoice' | null>(null)
  const [added, setAdded] = useState<string | null>(null)

  if (isPending) return <FullPageMessage title="Ładowanie…" inline />
  if (isError) {
    return (
      <>
        <BackLink to={bakeryPath}>Weryfikacja piekarni</BackLink>
        <FullPageMessage title="Nie znaleziono weryfikacji" description="Mogła zostać usunięta." inline />
      </>
    )
  }

  const notify = (result: BakeryCheck) => result.notices.forEach((notice) => toast.warning(notice))

  const deleteCheck = () => {
    remove.mutate(checkId, {
      onSuccess: () => {
        navigate(`${bakeryPath}?dostawca=${check.supplierId}`)
        toast('Usunięto weryfikację', { action: { label: 'Przywróć', onClick: () => restore.mutate(checkId) } })
      },
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się usunąć weryfikacji')),
    })
  }

  const hasPinnedInvoice = check.invoices.some((invoice) => invoice.pinned)
  // Pierwszy niewpisany WZ otwarty, reszta zwinięta: siedem otwartych tabel to za dużo przewijania.
  const firstEmpty = check.wz.find((wz) => wz.lines.length === 0)?.id

  return (
    <div data-wide-page>
      <BackLink to={`${bakeryPath}?dostawca=${check.supplierId}`}>Weryfikacja piekarni</BackLink>
      <PageHeader
        title={check.supplierName}
        description="Weryfikacja WZ, faktury z korektami i bonu po korektach."
        actions={
          <Button variant="outline" onClick={deleteCheck} loading={remove.isPending}>
            <Trash2Icon />
            Usuń weryfikację
          </Button>
        }
      />

      <Documents storeId={storeId} check={check} onPick={setPicking} />

      <section className="mt-8">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">Dokumenty WZ ({check.wz.length})</h2>
          <div className="flex flex-wrap gap-2">
            {hasPinnedInvoice && (
              <Button
                variant="outline"
                onClick={() =>
                  sync.mutate(undefined, {
                    onSuccess: notify,
                    onError: (failure) => toast.error(errorText(failure, 'Nie udało się odczytać faktury')),
                  })
                }
                loading={sync.isPending}
              >
                <RefreshCwIcon />
                Odczytaj WZ z faktury
              </Button>
            )}
            <Button
              onClick={() =>
                addWz.mutate(undefined, {
                  onSuccess: (wz) => setAdded(wz.id),
                  onError: (failure) => toast.error(errorText(failure, 'Nie udało się dodać WZ')),
                })
              }
              loading={addWz.isPending}
            >
              <PlusIcon />
              Dodaj WZ
            </Button>
          </div>
        </div>
        {check.wz.length === 0 ? (
          <p className="rounded-xl border px-4 py-6 text-center text-sm text-muted-foreground">
            {hasPinnedInvoice
              ? 'Faktura nie podaje numerów WZ. Dodaj dokumenty przyciskiem „Dodaj WZ”.'
              : 'Przypnij bon albo fakturę, a kafelki WZ powstaną same. Możesz też dodać je ręcznie.'}
          </p>
        ) : (
          <div className="grid gap-2">
            {check.wz.map((wz) => (
              <WzCard
                key={wz.id}
                storeId={storeId}
                checkId={checkId}
                wz={wz}
                articles={check.articles}
                scanReading={check.scanReading}
                defaultOpen={wz.id === firstEmpty || wz.id === added}
              />
            ))}
          </div>
        )}
      </section>

      <section className="mt-10">
        <h2 className="mb-3 text-lg font-semibold">Podsumowanie</h2>
        <ComparisonPanel storeId={storeId} checkId={checkId} />
      </section>

      <Dialog open={picking !== null} onOpenChange={(open) => !open && setPicking(null)}>
        <DialogContent className="gap-4 p-6 sm:max-w-2xl">
          {picking === 'bon' && (
            <BonPicker storeId={storeId} check={check} onDone={() => setPicking(null)} onNotices={notify} />
          )}
          {picking === 'invoice' && (
            <InvoicePicker storeId={storeId} check={check} onDone={() => setPicking(null)} onNotices={notify} />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Documents({
  storeId,
  check,
  onPick,
}: {
  storeId: string
  check: BakeryCheck
  onPick: (kind: 'bon' | 'invoice') => void
}) {
  const attach = useAttachToCheck(storeId, check.id)
  const detach = useDetachFromCheck(storeId, check.id)

  const unpin = (link: CheckLink, label: string) => {
    detach.mutate(link, {
      onSuccess: () => toast(`Odpięto ${label}`, { action: { label: 'Przywróć', onClick: () => attach.mutate(link) } }),
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się odpiąć')),
    })
  }

  return (
    <div className="grid gap-x-8 gap-y-4 rounded-xl border bg-card px-4 py-3 lg:grid-cols-2">
      <div className="min-w-0">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-medium">Bon po korektach</h2>
          <Button variant="ghost" size="sm" onClick={() => onPick('bon')}>
            <PlusIcon />
            Przypnij bon
          </Button>
        </div>
        {check.bons.length === 0 ? (
          <p className="text-sm text-muted-foreground">Bez bonu podsumowanie porówna tylko WZ z fakturą.</p>
        ) : (
          <ul className="divide-y">
            {check.bons.map((bon) => (
              <li key={bon.id} className="flex items-center gap-3 py-1.5 text-sm">
                <FileTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <Link to={`/dokumenty/pz/${bon.id}`} className="font-medium tabular-nums hover:underline">
                  Bon {bon.bonNumber}
                </Link>
                <span className="min-w-0 flex-1 truncate text-muted-foreground tabular-nums">
                  {[
                    formatDocumentDate(bon.documentDate),
                    `${formatMoney(bon.correctedValue ?? bon.purchaseValue)} zł`,
                    bon.correctionCount > 0 && `korekty: ${bon.correctionCount}`,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => unpin({ kind: 'documents', id: bon.id }, `bon ${bon.bonNumber}`)}
                  aria-label={`Odepnij bon ${bon.bonNumber}`}
                  title="Odepnij"
                >
                  <XIcon />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="min-w-0">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-medium">Faktura i korekty</h2>
          <Button variant="ghost" size="sm" onClick={() => onPick('invoice')}>
            <PlusIcon />
            Przypnij fakturę
          </Button>
        </div>
        {check.invoices.length === 0 ? (
          <p className="text-sm text-muted-foreground">Przypnij fakturę, żeby porównać z nią WZ.</p>
        ) : (
          <ul className="divide-y">
            {check.invoices.map((invoice) => (
              <li key={invoice.id} className="flex items-center gap-3 py-1.5 text-sm">
                <ReceiptTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="font-medium tabular-nums">{invoice.invoiceNumber}</span>
                <span className="min-w-0 flex-1 truncate text-muted-foreground tabular-nums">
                  {[
                    formatShortDate(invoice.issueDate),
                    `${formatMoney(invoice.netAmount)} zł`,
                    invoice.correction && !invoice.pinned && 'korekta, dołączona sama',
                    invoice.correction && invoice.pinned && 'korekta',
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
                {invoice.pinned && (
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => unpin({ kind: 'invoices', id: invoice.id }, `fakturę ${invoice.invoiceNumber}`)}
                    aria-label={`Odepnij fakturę ${invoice.invoiceNumber}`}
                    title="Odepnij"
                  >
                    <XIcon />
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

type PickerProps = {
  storeId: string
  check: BakeryCheck
  onDone: () => void
  onNotices: (check: BakeryCheck) => void
}

function BonPicker({ storeId, check, onDone, onNotices }: PickerProps) {
  const { data, isPending, isError } = useBakeryBons(storeId, check.supplierId)
  const attach = useAttachToCheck(storeId, check.id)
  const pinned = new Set(check.bons.map((bon) => bon.id))
  const available = (data ?? []).filter((bon) => !pinned.has(bon.id))
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przypnij bon</DialogTitle>
        <DialogDescription>Z bonem przyjdzie jego faktura i kafelki WZ, których jeszcze nie ma.</DialogDescription>
      </DialogHeader>
      <div className="max-h-[60svh] overflow-y-auto rounded-xl border">
        {isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać bonów.</p>
        ) : available.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Nie ma bonów do przypięcia.</p>
        ) : (
          <ul className="divide-y">
            {available.map((bon) => (
              <li key={bon.id} className="flex items-center gap-3 px-4 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium tabular-nums">Bon {bon.bonNumber}</span>
                  <span className="block truncate text-xs text-muted-foreground tabular-nums">
                    {[
                      formatDocumentDate(bon.documentDate),
                      bon.invoices.join(', ') || bon.supplierDocumentNumber,
                      `${formatMoney(bon.correctedValue ?? bon.purchaseValue)} zł`,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </span>
                <Button
                  size="sm"
                  disabled={attach.isPending || (bon.checkId !== null && bon.checkId !== check.id)}
                  title={bon.checkId ? 'Ten bon jest już w innej weryfikacji' : undefined}
                  onClick={() =>
                    attach.mutate(
                      { kind: 'documents', id: bon.id },
                      {
                        onSuccess: (result) => {
                          onNotices(result)
                          onDone()
                        },
                        onError: (failure) => toast.error(errorText(failure, 'Nie udało się przypiąć bonu')),
                      },
                    )
                  }
                >
                  Przypnij
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}

function InvoicePicker({ storeId, check, onDone, onNotices }: PickerProps) {
  const [query, setQuery] = useState('')
  const deferred = useDeferredValue(query)
  const { data, isPending, isError } = useInvoiceCandidates(storeId, check.id, deferred, true)
  const attach = useAttachToCheck(storeId, check.id)
  const pinned = new Set(check.invoices.map((invoice) => invoice.id))
  const available = (data ?? []).filter((invoice) => !pinned.has(invoice.id))
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przypnij fakturę</DialogTitle>
        <DialogDescription>
          Faktury dostawcy z ostatnich 120 dni. Korekty faktury dołączają się same, gdy ich XML jest odczytany.
        </DialogDescription>
      </DialogHeader>
      <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: numer faktury" />
      <div className="max-h-[55svh] overflow-y-auto rounded-xl border">
        {isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać faktur.</p>
        ) : available.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Nie ma faktur do przypięcia.</p>
        ) : (
          <ul className="divide-y">
            {available.map((invoice) => (
              <li key={invoice.id} className="flex items-center gap-3 px-4 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium tabular-nums">{invoice.invoiceNumber}</span>
                  <span className="block truncate text-xs text-muted-foreground tabular-nums">
                    {[
                      formatShortDate(invoice.issueDate),
                      `${formatMoney(invoice.netAmount)} zł`,
                      invoice.correction &&
                        (invoice.correctedInvoices?.length
                          ? `koryguje ${invoice.correctedInvoices.join(', ')}`
                          : 'korekta'),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </span>
                <Button
                  size="sm"
                  disabled={attach.isPending}
                  onClick={() =>
                    attach.mutate(
                      { kind: 'invoices', id: invoice.id },
                      {
                        onSuccess: (result) => {
                          onNotices(result)
                          onDone()
                        },
                        onError: (failure) => toast.error(errorText(failure, 'Nie udało się przypiąć faktury')),
                      },
                    )
                  }
                >
                  Przypnij
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}
