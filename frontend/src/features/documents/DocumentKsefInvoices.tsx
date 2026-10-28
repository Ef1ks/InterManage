import { useRef, useState } from 'react'
import {
  DownloadIcon,
  FileXIcon,
  LinkIcon,
  PaperclipIcon,
  PlusIcon,
  PrinterIcon,
  Trash2Icon,
  UnlinkIcon,
  XIcon,
} from 'lucide-react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { DateField, Form, FormActions, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { useCan } from '@/features/auth/api'
import {
  manualInvoiceFilePath,
  useDocumentKsefLinks,
  useKsefInvoiceCandidates,
  useManualInvoices,
  useSuggestedSupplier,
} from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { InvoiceAmountCheck } from '@/features/documents/InvoiceAmountCheck'
import { InvoiceComparisonPanel } from '@/features/documents/InvoiceComparisonPanel'
import { AddSupplierForm, SupplierPicker } from '@/features/documents/SupplierPicker'
import { useDownloadInvoice } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import { ApiError, downloadFile, printFile } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { applyServerErrors } from '@/lib/form-errors'
import type { Document, KsefInvoiceCandidate, LinkedKsefInvoice, ManualInvoice } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Przyjęcie PZ ma najwyżej jedną fakturę - z KSeF albo spoza (faktury jego korekt od dostawcy się nie liczą);
 * korekta może mieć kilka.
 */
const canAddInvoice = (target: { type: string; ksefInvoices: LinkedKsefInvoice[]; manualInvoices: ManualInvoice[] }) =>
  target.type !== 'PZ' ||
  (target.manualInvoices.length === 0 && target.ksefInvoices.every((invoice) => invoice.viaCorrection != null))

/** Dokument, do którego przypinamy fakturę - pełny ze szczegółów albo wiersz listy. */
type PickerTarget = {
  type: string
  id: string
  number: string
  typeSingular: string
  purchaseValue: number
  supplierCode: string | null
  supplierName: string | null
  ksefInvoices: LinkedKsefInvoice[]
  manualInvoices: ManualInvoice[]
}

/**
 * Faktury przyjęcia albo korekty: z KSeF (dopasowana po numerze dokumentu dostawcy albo przypięta ręcznie; przypięcie
 * oznacza ją jako wprowadzoną, PDF jest od razu pod ręką) i spoza KSeF, wpisane ręcznie (dostawca z listy i numer).
 */
export function DocumentKsefInvoices({ storeId, document }: { storeId: string; document: Document }) {
  const canEditDocuments = useCan('DOCUMENTS', 'EDIT')
  const canViewKsef = useCan('KSEF_INVOICES', 'VIEW')
  const canLink = canEditDocuments && canViewKsef
  const { link, unlink } = useDocumentKsefLinks(storeId, document.type, document.id)
  const manual = useManualInvoices(storeId, document.type.toLowerCase(), document.id)
  const [picking, setPicking] = useState(false)
  if (!document.ksefLinkable && !document.manualInvoiceAllowed) return null
  const own = document.ksefInvoices.filter((invoice) => invoice.viaCorrection == null)
  const count = document.ksefInvoices.length + document.manualInvoices.length
  // Bez KSeF (korekta błędu sklepu albo brak dostępu do faktur KSeF) okno od razu wpisuje fakturę spoza KSeF.
  const ksefPicker = document.ksefLinkable && canViewKsef

  const restoreManual = (invoice: ManualInvoice) =>
    manual.restore.mutate(invoice.id, {
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić faktury')),
    })
  const onRemoveManual = (invoice: ManualInvoice) =>
    manual.remove.mutate(invoice.id, {
      onSuccess: () =>
        toast.success(`Usunięto fakturę ${invoice.invoiceNumber}`, {
          action: { label: 'Przywróć', onClick: () => restoreManual(invoice) },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć faktury')),
    })

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
          {count > 1 ? 'Faktury' : 'Faktura'}
        </h3>
        {canEditDocuments && canAddInvoice(document) && (
          <Button variant="outline" size="sm" onClick={() => setPicking(true)}>
            {ksefPicker ? <LinkIcon /> : <PlusIcon />}
            {ksefPicker ? 'Przypnij fakturę' : 'Dodaj fakturę spoza KSeF'}
          </Button>
        )}
      </div>

      {own.length === 0 && document.manualInvoices.length === 0 && (
        <p className={cn('text-sm text-muted-foreground', count > 0 && 'mb-2')}>
          Brak faktury.{' '}
          {document.type === 'PZ'
            ? 'Faktura z KSeF połączy się sama, gdy numer dokumentu dostawcy będzie taki jak na fakturze. Możesz ją też przypiąć ręcznie albo wpisać fakturę spoza KSeF.'
            : document.ksefLinkable
              ? 'Przypnij fakturę korygującą z KSeF albo wpisz fakturę spoza KSeF.'
              : 'Jeśli dostawca wystawił fakturę korygującą, wpisz jej numer - trafi do raportu przyjęć.'}
        </p>
      )}
      {count > 0 && (
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
          {document.manualInvoices.map((invoice) => (
            <ManualInvoiceRow
              key={invoice.id}
              storeId={storeId}
              document={document}
              invoice={invoice}
              canEdit={canEditDocuments}
              onRemove={canEditDocuments ? () => onRemoveManual(invoice) : undefined}
              removing={manual.remove.isPending && manual.remove.variables === invoice.id}
            />
          ))}
        </Card>
      )}

      <InvoiceAmountCheck storeId={storeId} documentId={document.id} check={document.invoiceAmountCheck} />
      <InvoiceComparisonPanel storeId={storeId} documentId={document.id} check={document.invoiceAmountCheck} />

      <KsefInvoicePickerDialog
        storeId={storeId}
        target={document}
        ksef={ksefPicker}
        allowManual={document.manualInvoiceAllowed}
        open={picking}
        onOpenChange={setPicking}
      />
    </section>
  )
}

function ManualInvoiceRow({
  storeId,
  document,
  invoice,
  canEdit,
  onRemove,
  removing,
}: {
  storeId: string
  document: Document
  invoice: ManualInvoice
  canEdit: boolean
  onRemove?: () => void
  removing: boolean
}) {
  const manual = useManualInvoices(storeId, document.type.toLowerCase(), document.id)
  const [busy, setBusy] = useState<'download' | 'print' | null>(null)
  const filePath = manualInvoiceFilePath(storeId, document.type, document.id, invoice.id)
  const file = invoice.file
  const isPdf = file?.contentType === 'application/pdf'

  const run = async (kind: 'download' | 'print') => {
    setBusy(kind)
    try {
      if (kind === 'print') await printFile(filePath)
      else await downloadFile(filePath, file?.fileName ?? 'faktura')
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się pobrać pliku faktury'))
    } finally {
      setBusy(null)
    }
  }
  const attach = (picked: File) =>
    manual.attachFile.mutate(
      { invoiceId: invoice.id, file: picked },
      {
        onSuccess: () => toast.success(file ? 'Wymieniono plik faktury' : 'Dołączono plik faktury'),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się dołączyć pliku')),
      },
    )
  const removeFile = () =>
    manual.removeFile.mutate(invoice.id, {
      onSuccess: () =>
        toast.success(`Usunięto plik faktury ${invoice.invoiceNumber}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              manual.restoreFile.mutate(invoice.id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić pliku')),
              }),
          },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć pliku')),
    })

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 tabular-nums">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {invoice.invoiceNumber}
          <Tag>Spoza KSeF</Tag>
        </div>
        <div className="text-xs text-muted-foreground">
          {[invoice.supplierName, invoice.issueDate && formatDate(invoice.issueDate)].filter(Boolean).join(' · ')}
          {' · wpisana ręcznie'}
          {invoice.createdBy && `, ${invoice.createdBy}`}
        </div>
        {file && (
          <div className="mt-0.5 truncate text-xs text-muted-foreground" title={file.fileName}>
            <PaperclipIcon aria-hidden className="mr-1 inline size-3" />
            {file.fileName} · {formatFileSize(file.sizeBytes)}
          </div>
        )}
      </div>
      {file && (
        <div className="flex gap-1">
          <Button variant="outline" size="sm" loading={busy === 'download'} onClick={() => run('download')}>
            <DownloadIcon />
            {isPdf ? 'PDF' : 'Plik'}
          </Button>
          {isPdf && (
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Drukuj fakturę ${invoice.invoiceNumber}`}
              title="Drukuj"
              loading={busy === 'print'}
              onClick={() => run('print')}
            >
              <PrinterIcon />
            </Button>
          )}
        </div>
      )}
      {canEdit && (
        <InvoiceFileButton
          label={file ? 'Wymień plik' : 'Dołącz plik'}
          iconOnly={Boolean(file)}
          loading={manual.attachFile.isPending}
          onFile={attach}
        />
      )}
      {canEdit && file && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń plik faktury ${invoice.invoiceNumber}`}
          title="Usuń plik"
          loading={manual.removeFile.isPending}
          onClick={removeFile}
        >
          <FileXIcon />
        </Button>
      )}
      {onRemove && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń fakturę ${invoice.invoiceNumber}`}
          title="Usuń"
          loading={removing}
          onClick={onRemove}
        >
          <Trash2Icon />
        </Button>
      )}
    </div>
  )
}

/**
 * Okno wyboru faktury z przypięciem i toastem „Cofnij” - ze szczegółów dokumentu i prosto z listy. Pod listą faktur
 * z KSeF jest wpisanie faktury spoza KSeF; bez KSeF ({@code ksef} false) okno od razu je pokazuje.
 */
function KsefInvoicePickerDialog({
  storeId,
  target,
  ksef,
  allowManual,
  open,
  onOpenChange,
}: {
  storeId: string
  target: PickerTarget
  ksef: boolean
  allowManual: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { link, unlink } = useDocumentKsefLinks(storeId, target.type, target.id)
  const [manual, setManual] = useState(false)
  const changeOpen = (next: boolean) => {
    onOpenChange(next)
    if (!next) setManual(false)
  }
  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {open && (manual || !ksef) && (
          <ManualInvoiceForm
            storeId={storeId}
            target={target}
            onBack={ksef ? () => setManual(false) : undefined}
            onCancel={() => changeOpen(false)}
            onSaved={() => changeOpen(false)}
          />
        )}
        {open && ksef && !manual && (
          <KsefInvoicePicker
            storeId={storeId}
            document={target}
            onManual={allowManual ? () => setManual(true) : undefined}
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
  onManual,
}: {
  storeId: string
  document: PickerTarget
  linking: boolean
  onPick: (candidate: KsefInvoiceCandidate) => void
  onManual?: () => void
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
      {onManual && (
        <Button variant="ghost" className="justify-self-start" onClick={onManual}>
          <PlusIcon />
          Faktury nie ma w KSeF? Wpisz ją ręcznie
        </Button>
      )}
    </>
  )
}

type ManualInvoiceValues = { invoiceNumber: string; issueDate: string }

/**
 * Faktura spoza KSeF (dostawca bez KSeF, faktura papierowa): dostawca z listy dostawców sklepu (podpowiedź: dostawca
 * dokumentu) albo dodany ręcznie, numer faktury i data wystawienia. Numer idzie do raportu przyjęć.
 */
function ManualInvoiceForm({
  storeId,
  target,
  onBack,
  onCancel,
  onSaved,
}: {
  storeId: string
  target: PickerTarget
  onBack?: () => void
  onCancel: () => void
  onSaved: () => void
}) {
  const { add, remove, attachFile } = useManualInvoices(storeId, target.type.toLowerCase(), target.id)
  const suggested = useSuggestedSupplier(storeId, target.supplierCode)
  const [file, setFile] = useState<File | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  const [adding, setAdding] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const selectedId = picked ?? suggested?.id ?? null
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<ManualInvoiceValues>({ defaultValues: { invoiceNumber: '', issueDate: '' } })

  if (adding != null) {
    return (
      <AddSupplierForm
        storeId={storeId}
        initialName={adding}
        onAdded={(supplier) => {
          setPicked(supplier.id)
          setAdding(null)
        }}
        onBack={() => setAdding(null)}
      />
    )
  }

  const onSubmit = handleSubmit((values) => {
    if (!selectedId) return
    setFormError(null)
    const number = values.invoiceNumber.trim()
    add.mutate(
      { supplierId: selectedId, invoiceNumber: number, issueDate: values.issueDate || null },
      {
        onSuccess: (saved) => {
          onSaved()
          const created = saved.manualInvoices.find((invoice) => invoice.invoiceNumber === number)
          toast.success(`Zapisano fakturę ${number}`, {
            description: `${target.typeSingular} ${target.number} · faktura spoza KSeF.`,
            action: created ? { label: 'Cofnij', onClick: () => remove.mutate(created.id) } : undefined,
          })
          // Faktura jest już zapisana; nieudany plik da się dołączyć jeszcze raz przy fakturze.
          if (file && created && !created.file) {
            attachFile.mutate(
              { invoiceId: created.id, file },
              { onError: (error) => toast.error(errorMessage(error, 'Faktura zapisana, ale nie udało się dołączyć pliku')) },
            )
          }
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['invoiceNumber', 'issueDate'])),
      },
    )
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">
          Faktura spoza KSeF: {target.typeSingular.toLowerCase()} {target.number}
        </DialogTitle>
        <DialogDescription>
          Dla dostawcy, który nie wystawia faktur w KSeF. Wybierz dostawcę i wpisz numer z faktury - trafi do raportu
          przyjęć.
        </DialogDescription>
      </DialogHeader>

      <SupplierPicker
        storeId={storeId}
        selectedId={selectedId}
        onSelect={setPicked}
        suggested={suggested}
        onAdd={setAdding}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="Numer faktury"
          autoComplete="off"
          error={errors.invoiceNumber?.message}
          {...register('invoiceNumber', {
            validate: (value) => value.trim() !== '' || 'Podaj numer faktury',
            maxLength: { value: 60, message: 'Maksymalnie 60 znaków' },
          })}
        />
        <Controller
          name="issueDate"
          control={control}
          render={({ field }) => (
            <DateField
              label="Data wystawienia (opcjonalnie)"
              value={field.value}
              max={todayIso()}
              shortcuts={[{ label: 'Dziś', value: todayIso() }]}
              error={errors.issueDate?.message}
              onChange={field.onChange}
            />
          )}
        />
      </div>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <InvoiceFileButton label={file ? 'Zmień plik' : 'Dołącz plik faktury (PDF, JPG, PNG)'} onFile={setFile} />
        {file ? (
          <span className="inline-flex min-w-0 items-center gap-1 text-muted-foreground">
            <span className="truncate" title={file.name}>
              {file.name} · {formatFileSize(file.size)}
            </span>
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              aria-label="Nie dołączaj pliku"
              title="Nie dołączaj pliku"
              onClick={() => setFile(null)}
            >
              <XIcon />
            </Button>
          </span>
        ) : (
          <span className="text-muted-foreground">opcjonalnie, do {MAX_FILE_MB} MB</span>
        )}
      </div>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onBack ?? onCancel}>
          {onBack ? 'Wróć do faktur z KSeF' : 'Anuluj'}
        </Button>
        <Button type="submit" size="lg" loading={add.isPending} disabled={!selectedId}>
          Zapisz fakturę
        </Button>
      </FormActions>
    </Form>
  )
}

/** Największy plik faktury, jak `DocumentManualInvoiceService.MAX_FILE_BYTES`. */
const MAX_FILE_MB = 10
const ACCEPTED_FILES = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png'

function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} kB`
    : `${(bytes / 1024 / 1024).toLocaleString('pl-PL', { maximumFractionDigits: 1 })} MB`
}

/** Wybór pliku faktury (PDF albo zdjęcie); za duży plik odrzuca od razu, typ po treści sprawdza serwer. */
function InvoiceFileButton({
  label,
  iconOnly,
  loading,
  onFile,
}: {
  label: string
  iconOnly?: boolean
  loading?: boolean
  onFile: (file: File) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <input
        ref={input}
        type="file"
        accept={ACCEPTED_FILES}
        className="hidden"
        onChange={(event) => {
          const picked = event.target.files?.[0]
          event.target.value = ''
          if (!picked) return
          if (picked.size > MAX_FILE_MB * 1024 * 1024) {
            toast.error(`Plik jest za duży (najwyżej ${MAX_FILE_MB} MB)`)
            return
          }
          onFile(picked)
        }}
      />
      <Button
        type="button"
        variant={iconOnly ? 'ghost' : 'outline'}
        size={iconOnly ? 'icon-sm' : 'sm'}
        aria-label={label}
        title={label}
        loading={loading}
        onClick={() => input.current?.click()}
      >
        <PaperclipIcon />
        {!iconOnly && label}
      </Button>
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
  const empty = target.ksefInvoices.length === 0 && target.manualInvoices.length === 0
  return (
    <div
      className="mt-1 flex flex-wrap items-center gap-1"
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <KsefInvoiceChips storeId={storeId} invoices={target.ksefInvoices} />
      {target.manualInvoices.map((invoice) =>
        invoice.file ? (
          <button
            key={invoice.id}
            type="button"
            className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-[0.7rem] font-medium text-muted-foreground hover:bg-muted/70 hover:text-foreground"
            title={`Faktura spoza KSeF od ${invoice.supplierName} · pobierz ${invoice.file.fileName}`}
            onClick={() =>
              downloadFile(
                manualInvoiceFilePath(storeId, target.type, target.id, invoice.id),
                invoice.file?.fileName ?? 'faktura',
              ).catch((error) => toast.error(errorMessage(error, 'Nie udało się pobrać pliku faktury')))
            }
          >
            <PaperclipIcon aria-hidden className="size-3" />
            {invoice.invoiceNumber}
          </button>
        ) : (
          <span
            key={invoice.id}
            className="inline-flex items-center rounded-md bg-muted px-1.5 py-0.5 text-[0.7rem] font-medium text-muted-foreground"
            title={`Faktura spoza KSeF od ${invoice.supplierName}`}
          >
            {invoice.invoiceNumber}
          </span>
        ),
      )}
      {canEditDocuments && canAddInvoice(target) && (
        <>
          <Button
            variant="ghost"
            size={empty ? 'xs' : 'icon-xs'}
            className="text-muted-foreground"
            aria-label={`Przypnij fakturę: ${target.typeSingular.toLowerCase()} ${target.number}`}
            title={canViewKsef ? 'Przypnij fakturę z KSeF albo wpisz spoza KSeF' : 'Wpisz fakturę spoza KSeF'}
            onClick={() => setPicking(true)}
          >
            <LinkIcon />
            {empty && 'Przypnij fakturę'}
          </Button>
          <KsefInvoicePickerDialog
            storeId={storeId}
            target={target}
            ksef={canViewKsef}
            allowManual
            open={picking}
            onOpenChange={setPicking}
          />
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
