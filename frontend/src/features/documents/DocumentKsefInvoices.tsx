import { useState } from 'react'
import { DownloadIcon, LinkIcon, UnlinkIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { useCan } from '@/features/auth/api'
import { useDocumentKsefLinks, useKsefInvoiceCandidates } from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { InvoiceAmountCheck } from '@/features/documents/InvoiceAmountCheck'
import { InvoiceComparisonPanel } from '@/features/documents/InvoiceComparisonPanel'
import { useDownloadInvoice } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { Document, KsefInvoiceCandidate, LinkedKsefInvoice } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Przyjęcie PZ ma najwyżej jedną fakturę (faktury jego korekt od dostawcy się nie liczą); korekta od dostawcy może
 * mieć kilka.
 */
const canAddInvoice = (target: { type: string; ksefInvoices: LinkedKsefInvoice[] }) =>
  target.type !== 'PZ' || target.ksefInvoices.every((invoice) => invoice.viaCorrection != null)

/** Dokument, do którego przypinamy fakturę - pełny ze szczegółów albo wiersz listy. */
type PickerTarget = {
  type: string
  id: string
  number: string
  typeSingular: string
  purchaseValue: number
  supplierName: string | null
  ksefInvoices: LinkedKsefInvoice[]
}

/**
 * Faktura z KSeF przyjęcia albo korekty od dostawcy: dopasowana po numerze dokumentu dostawcy albo przypięta ręcznie.
 * Przypięcie oznacza fakturę jako wprowadzoną, a PDF faktury jest od razu pod ręką.
 */
export function DocumentKsefInvoices({ storeId, document }: { storeId: string; document: Document }) {
  const canEditDocuments = useCan('DOCUMENTS', 'EDIT')
  const canViewKsef = useCan('KSEF_INVOICES', 'VIEW')
  const canLink = canEditDocuments && canViewKsef
  const { link, unlink } = useDocumentKsefLinks(storeId, document.type, document.id)
  const [picking, setPicking] = useState(false)
  if (!document.ksefLinkable) return null
  const own = document.ksefInvoices.filter((invoice) => invoice.viaCorrection == null)

  const onUnlink = (invoice: LinkedKsefInvoice) =>
    unlink.mutate(invoice.id, {
      onSuccess: () =>
        toast.success(`Odpięto fakturę ${invoice.invoiceNumber}`, {
          action: { label: 'Przywróć', onClick: () => link.mutate(invoice.id) },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się odpiąć faktury')),
    })

  return (
    <section aria-labelledby="ksef-invoices" className="mb-6">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 id="ksef-invoices" className="text-sm font-semibold">
          {document.ksefInvoices.length > 1 ? 'Faktury z KSeF' : 'Faktura z KSeF'}
        </h3>
        {canLink && canAddInvoice(document) && (
          <Button variant="outline" size="sm" onClick={() => setPicking(true)}>
            <LinkIcon />
            Przypnij fakturę
          </Button>
        )}
      </div>

      {own.length === 0 && (
        <p className={cn('text-sm text-muted-foreground', document.ksefInvoices.length > 0 && 'mb-2')}>
          Brak faktury z KSeF.{' '}
          {document.type === 'PZ'
            ? 'Faktura połączy się sama, gdy numer dokumentu dostawcy będzie taki jak na fakturze - albo przypnij ją ręcznie.'
            : 'Przypnij fakturę korygującą od dostawcy.'}
        </p>
      )}
      {document.ksefInvoices.length > 0 && (
        <Card className="gap-0 divide-y py-0">
          {document.ksefInvoices.map((invoice) => (
            <LinkedInvoiceRow
              key={invoice.id}
              storeId={storeId}
              invoice={invoice}
              onUnlink={
                canLink && !invoice.matchedByNumber && invoice.viaCorrection == null ? () => onUnlink(invoice) : undefined
              }
              unlinking={unlink.isPending && unlink.variables === invoice.id}
            />
          ))}
        </Card>
      )}

      <InvoiceAmountCheck storeId={storeId} documentId={document.id} check={document.invoiceAmountCheck} />
      <InvoiceComparisonPanel storeId={storeId} documentId={document.id} check={document.invoiceAmountCheck} />

      <KsefInvoicePickerDialog storeId={storeId} target={document} open={picking} onOpenChange={setPicking} />
    </section>
  )
}

/** Okno wyboru faktury z przypięciem i toastem „Cofnij” - ze szczegółów dokumentu i prosto z listy. */
function KsefInvoicePickerDialog({
  storeId,
  target,
  open,
  onOpenChange,
}: {
  storeId: string
  target: PickerTarget
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { link, unlink } = useDocumentKsefLinks(storeId, target.type, target.id)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {open && (
          <KsefInvoicePicker
            storeId={storeId}
            document={target}
            linking={link.isPending}
            onPick={(candidate) =>
              link.mutate(candidate.id, {
                onSuccess: () => {
                  onOpenChange(false)
                  toast.success(`Przypięto fakturę ${candidate.invoiceNumber}`, {
                    description: `${target.typeSingular} ${target.number} · faktura w KSeF jest wprowadzona.`,
                    action: { label: 'Cofnij', onClick: () => unlink.mutate(candidate.id) },
                  })
                },
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przypiąć faktury')),
              })
            }
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function LinkedInvoiceRow({
  storeId,
  invoice,
  onUnlink,
  unlinking,
}: {
  storeId: string
  invoice: LinkedKsefInvoice
  onUnlink?: () => void
  unlinking: boolean
}) {
  const canView = useCan('KSEF_INVOICES', 'VIEW')
  const download = useDownloadInvoice(storeId)
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 tabular-nums">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {invoice.invoiceNumber}
          {invoice.correction && (
            <span className="rounded-md bg-muted px-1.5 py-0.5 text-[0.7rem] font-medium text-muted-foreground">
              Korekta
            </span>
          )}
        </div>
        <div className="text-xs text-muted-foreground">
          {[invoice.sellerName, invoice.issueDate && formatDate(invoice.issueDate)].filter(Boolean).join(' · ')}
          {' · '}
          {invoice.viaCorrection
            ? `z korekty ${invoice.viaCorrection}`
            : invoice.matchedByNumber
              ? 'dopasowana po numerze'
              : 'przypięta ręcznie'}
        </div>
      </div>
      <div className="text-right text-sm">
        <div className="font-medium">{formatMoney(invoice.grossAmount ?? 0)} zł brutto</div>
        <div className="text-xs text-muted-foreground">{formatMoney(invoice.netAmount ?? 0)} zł netto</div>
      </div>
      <div className="flex gap-1">
        {canView && (
          <Button
            variant="outline"
            size="sm"
            loading={download.isPending}
            onClick={() =>
              download.mutate(
                { invoice, format: 'pdf' },
                { onError: (error) => toast.error(errorMessage(error, 'Nie udało się pobrać faktury')) },
              )
            }
          >
            <DownloadIcon />
            PDF
          </Button>
        )}
        {onUnlink && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Odepnij fakturę ${invoice.invoiceNumber}`}
            title="Odepnij"
            loading={unlinking}
            onClick={onUnlink}
          >
            <UnlinkIcon />
          </Button>
        )}
      </div>
    </div>
  )
}

/** Wybór faktury: domyślnie kwota ±10% wartości dokumentu, najpierw ten sam dostawca i najbliższa kwota. */
function KsefInvoicePicker({
  storeId,
  document,
  linking,
  onPick,
}: {
  storeId: string
  document: PickerTarget
  linking: boolean
  onPick: (candidate: KsefInvoiceCandidate) => void
}) {
  const [query, setQuery] = useState('')
  const [amountRange, setAmountRange] = useState(true)
  const { data: candidates = [], isPending, isError, isPlaceholderData } = useKsefInvoiceCandidates(
    storeId,
    document.type,
    document.id,
    query,
    amountRange,
    true,
  )
  const value = Math.abs(document.purchaseValue)
  const linked = new Set(document.ksefInvoices.map((invoice) => invoice.id))

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">
          Przypnij fakturę do: {document.typeSingular.toLowerCase()} {document.number}
        </DialogTitle>
        <DialogDescription className="tabular-nums">
          Wartość zakupu {formatMoney(document.purchaseValue)} zł
          {document.supplierName && ` · ${document.supplierName}`}. Najpierw faktury tego dostawcy, od najbliższej kwoty.
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <SearchInput
          className="min-w-0 flex-1 basis-64"
          value={query}
          onChange={setQuery}
          placeholder="Szukaj: nr faktury, dostawca, NIP"
        />
        <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground tabular-nums">
          <Switch checked={amountRange} onCheckedChange={setAmountRange} />
          Kwota {formatMoney(value * 0.9)}–{formatMoney(value * 1.1)} zł
        </label>
      </div>

      <div
        className={cn(
          'max-h-[50svh] min-h-32 overflow-y-auto rounded-xl border transition-opacity duration-150',
          isPlaceholderData && 'opacity-60',
        )}
      >
        {isError ? (
          <p className="px-4 py-6 text-center text-sm text-destructive">Nie udało się wczytać faktur z KSeF.</p>
        ) : isPending ? (
          <div aria-hidden className="m-3 h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : candidates.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">
            {amountRange
              ? 'Żadna faktura nie ma kwoty w tym zakresie. Wyłącz zakres kwot albo wyszukaj po numerze.'
              : 'Nie znaleziono faktury.'}
          </p>
        ) : (
          <ul className="divide-y">
            {candidates.map((candidate) => {
              const already = linked.has(candidate.id)
              return (
                <li key={candidate.id}>
                  <button
                    type="button"
                    disabled={already || linking}
                    onClick={() => onPick(candidate)}
                    className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted disabled:cursor-default disabled:opacity-50 disabled:hover:bg-transparent"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
                        {candidate.invoiceNumber}
                        {candidate.correction && <Tag>Korekta</Tag>}
                        {candidate.sameSupplier && <Tag tone="positive">Ten dostawca</Tag>}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {[candidate.sellerName, candidate.issueDate && formatDate(candidate.issueDate)]
                          .filter(Boolean)
                          .join(' · ')}
                        {candidate.documents.length > 0 && ` · już przy: ${candidate.documents.join(', ')}`}
                        {already && ' · przypięta do tego dokumentu'}
                      </span>
                    </span>
                    <span className="text-right text-sm">
                      <span className="block font-medium">{formatMoney(candidate.netAmount ?? 0)} zł netto</span>
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

function Tag({ children, tone }: { children: string; tone?: 'positive' }) {
  return (
    <span
      className={cn(
        'rounded-md px-1.5 py-0.5 text-[0.7rem] font-medium',
        tone === 'positive'
          ? 'bg-emerald-50 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200'
          : 'bg-muted text-muted-foreground',
      )}
    >
      {children}
    </span>
  )
}

/**
 * Faktury KSeF w wierszu listy przyjęć i korekt: numer z pobraniem PDF i przypięcie faktury bez otwierania dokumentu.
 * Kliknięcia nie otwierają wiersza.
 */
export function DocumentRowKsef({ storeId, target }: { storeId: string; target: PickerTarget }) {
  const canEditDocuments = useCan('DOCUMENTS', 'EDIT')
  const canViewKsef = useCan('KSEF_INVOICES', 'VIEW')
  const [picking, setPicking] = useState(false)
  const empty = target.ksefInvoices.length === 0
  return (
    <div
      className="mt-1 flex flex-wrap items-center gap-1"
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <KsefInvoiceChips storeId={storeId} invoices={target.ksefInvoices} />
      {canEditDocuments && canViewKsef && canAddInvoice(target) && (
        <>
          <Button
            variant="ghost"
            size={empty ? 'xs' : 'icon-xs'}
            className="text-muted-foreground"
            aria-label={`Przypnij fakturę z KSeF: ${target.typeSingular.toLowerCase()} ${target.number}`}
            title="Przypnij fakturę z KSeF"
            onClick={() => setPicking(true)}
          >
            <LinkIcon />
            {empty && 'Przypnij fakturę'}
          </Button>
          <KsefInvoicePickerDialog storeId={storeId} target={target} open={picking} onOpenChange={setPicking} />
        </>
      )}
    </div>
  )
}

/** Faktury KSeF w wierszu listy dokumentów: numer i pobranie PDF bez otwierania dokumentu. */
function KsefInvoiceChips({ storeId, invoices }: { storeId: string; invoices: LinkedKsefInvoice[] }) {
  const canView = useCan('KSEF_INVOICES', 'VIEW')
  const download = useDownloadInvoice(storeId)
  if (invoices.length === 0) return null
  return (
    <>
      {invoices.map((invoice) => (
        <span
          key={invoice.id}
          className="inline-flex items-center gap-1 rounded-md bg-sky-50 py-0.5 pr-0.5 pl-1.5 text-[0.7rem] font-medium text-sky-800 ring-1 ring-sky-200 dark:bg-sky-950/40 dark:text-sky-200 dark:ring-sky-800"
          title={`Faktura z KSeF ${invoice.ksefNumber}${
            invoice.viaCorrection
              ? ` (z korekty ${invoice.viaCorrection})`
              : invoice.matchedByNumber
                ? ' (dopasowana po numerze)'
                : ''
          }`}
        >
          KSeF {invoice.invoiceNumber}
          {canView && (
            <button
              type="button"
              aria-label={`Pobierz fakturę ${invoice.invoiceNumber} (PDF)`}
              className="flex size-5 items-center justify-center rounded transition-colors hover:bg-sky-100 dark:hover:bg-sky-900"
              onClick={(event) => {
                event.stopPropagation()
                download.mutate(
                  { invoice, format: 'pdf' },
                  { onError: (error) => toast.error(errorMessage(error, 'Nie udało się pobrać faktury')) },
                )
              }}
              onKeyDown={(event) => event.stopPropagation()}
            >
              <DownloadIcon className="size-3" />
            </button>
          )}
        </span>
      ))}
    </>
  )
}
