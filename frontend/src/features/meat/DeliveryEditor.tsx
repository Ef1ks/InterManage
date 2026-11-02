import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { PlusIcon, Trash2Icon, XIcon } from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField, TextField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { useSuppliers } from '@/features/documents/api'
import {
  meatPaths,
  scanFileUrl,
  scanKey,
  useDeleteMeatDelivery,
  useMeatArticles,
  useMeatDelivery,
  useRestoreMeatDelivery,
  useSaveMeatDelivery,
  type MeatDeliveryForm,
} from '@/features/meat/api'
import { formatPrice, parsePrice } from '@/features/meat/format'
import { SearchPicker } from '@/features/meat/SearchPicker'
import { ApiError } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import type {
  MeatArticle,
  MeatDelivery,
  MeatLineMatch,
  MeatScanResult,
  StoreSummary,
  Supplier,
} from '@/lib/types'
import { cn } from '@/lib/utils'

/** Wybór „bez artykułu”: pozycja to sam zakup (np. surowiec), raport pokaże jej cenę zakupu bez sprzedaży. */
const NO_ARTICLE: MeatArticle = {
  id: 'bez-artykulu',
  itm8: '',
  ean: '',
  name: 'Bez artykułu – sam zakup',
  brand: null,
  subfamilyCode: null,
  salePrice: null,
}

type EditorLine = {
  key: string
  documentName: string
  documentCode: string | null
  price: string
  /** {@link NO_ARTICLE} = świadomie bez artykułu; null = jeszcze nie wybrano. */
  article: MeatArticle | null
  /** Skąd przypisanie, dopóki pracownik go nie zmienił. */
  match: MeatLineMatch | null
  handwrittenCorrection: boolean
  note: string | null
}

type Draft = {
  scanId: string | null
  scanContentType: string | null
  supplierId: string | null
  supplierInfo: MeatScanResult | null
  documentNumber: string
  deliveryDate: string
  lines: EditorLine[]
}

const priceText = (value: number) => String(value).replace('.', ',')

function fromScan(scan: MeatScanResult): Draft {
  return {
    scanId: scan.scanId,
    scanContentType: scan.contentType,
    supplierId: scan.supplier?.id ?? null,
    supplierInfo: scan,
    documentNumber: scan.documentNumber ?? '',
    deliveryDate: scan.deliveryDate,
    lines: scan.lines.map((line) => ({
      key: crypto.randomUUID(),
      documentName: line.documentName,
      documentCode: line.documentCode,
      price: priceText(line.unitPrice),
      article: line.noArticle ? NO_ARTICLE : line.article,
      match: line.match === 'NONE' ? null : line.match,
      handwrittenCorrection: line.handwrittenCorrection,
      note: line.note,
    })),
  }
}

function fromDelivery(delivery: MeatDelivery): Draft {
  return {
    scanId: delivery.scanId,
    scanContentType: delivery.scanContentType,
    supplierId: delivery.supplier.id,
    supplierInfo: null,
    documentNumber: delivery.documentNumber,
    deliveryDate: delivery.deliveryDate,
    lines: delivery.lines.map((line) => ({
      key: line.id,
      documentName: line.documentName,
      documentCode: line.documentCode,
      price: priceText(line.unitPrice),
      article: line.article ?? NO_ARTICLE,
      match: null,
      handwrittenCorrection: line.handwrittenCorrection,
      note: line.note,
    })),
  }
}

const emptyLine = (): EditorLine => ({
  key: crypto.randomUUID(),
  documentName: '',
  documentCode: null,
  price: '',
  article: null,
  match: null,
  handwrittenCorrection: false,
  note: null,
})

/**
 * Dokument WZ do sprawdzenia i zapisu: nowy ze skanu (odczyt w pamięci zapytań, `?skan=`), nowy wpisany ręcznie
 * albo zapisany (`/wz/:id`). Skan stoi obok pozycji, żeby porównać ceny z dokumentem.
 */
export function DeliveryEditor({ store }: { store: StoreSummary }) {
  const { deliveryId } = useParams()
  const [searchParams] = useSearchParams()
  const scanId = searchParams.get('skan')
  const queryClient = useQueryClient()
  const scan = scanId ? queryClient.getQueryData<MeatScanResult>(scanKey(store.id, scanId)) : undefined
  const delivery = useMeatDelivery(store.id, deliveryId)

  if (deliveryId) {
    if (delivery.isPending) return <EditorHeader title="Dokument WZ" />
    if (delivery.isError) {
      return (
        <>
          <EditorHeader title="Dokument WZ" />
          <FullPageMessage title="Nie znaleziono dokumentu" description={delivery.error.message} inline />
        </>
      )
    }
    return <Editor key={delivery.data.id} store={store} initial={fromDelivery(delivery.data)} saved={delivery.data} />
  }
  if (scanId && !scan) {
    return (
      <>
        <EditorHeader title="Nowy dokument WZ" />
        <FullPageMessage
          title="Odczyt skanu wygasł"
          description="Po odświeżeniu strony odczytu już nie ma. Wgraj zdjęcie albo skan jeszcze raz."
          inline
        />
      </>
    )
  }
  const initial: Draft = scan
    ? fromScan(scan)
    : {
        scanId: null,
        scanContentType: null,
        supplierId: null,
        supplierInfo: null,
        documentNumber: '',
        deliveryDate: todayIso(),
        lines: [emptyLine()],
      }
  return <Editor key={scanId ?? 'nowy'} store={store} initial={initial} saved={null} />
}

function EditorHeader({ title, description }: { title: string; description?: string }) {
  return (
    <>
      <BackLink to={meatPaths.deliveries}>Dokumenty WZ</BackLink>
      <PageHeader title={title} description={description} />
    </>
  )
}

function Editor({ store, initial, saved }: { store: StoreSummary; initial: Draft; saved: MeatDelivery | null }) {
  const navigate = useNavigate()
  const canEdit = useCan('MEAT_PURCHASES', 'EDIT')
  const [draft, setDraft] = useState(initial)
  const [showErrors, setShowErrors] = useState(false)
  const [formError, setFormError] = useState<{ message: string; duplicateId?: string } | null>(null)
  const { data: suppliers = [] } = useSuppliers(store.id)
  const { data: articles = [] } = useMeatArticles(store.id)
  const save = useSaveMeatDelivery(store.id, saved?.id)
  const remove = useDeleteMeatDelivery(store.id)
  const restore = useRestoreMeatDelivery(store.id)

  const supplier = suppliers.find((item) => item.id === draft.supplierId) ?? null
  const articleItems = useMemo(() => [NO_ARTICLE, ...articles], [articles])
  const deleted = saved?.deletedAt != null
  const readOnly = !canEdit || deleted

  /** Każda zmiana formularza chowa błąd poprzedniego zapisu. */
  const edit = (change: (current: Draft) => Draft) => {
    setFormError(null)
    setDraft(change)
  }

  const updateLine = (key: string, change: Partial<EditorLine>) =>
    edit((current) => ({
      ...current,
      lines: current.lines.map((line) => (line.key === key ? { ...line, ...change } : line)),
    }))

  const undecided = draft.lines.filter((line) => line.article == null).length
  const invalidLines = draft.lines.filter((line) => !line.documentName.trim() || parsePrice(line.price) == null)
  const missingHeader = !draft.supplierId || !draft.documentNumber.trim() || !draft.deliveryDate

  const submit = () => {
    if (missingHeader || invalidLines.length > 0 || draft.lines.length === 0) {
      setShowErrors(true)
      return
    }
    const info = draft.supplierInfo
    const form: MeatDeliveryForm = {
      scanId: saved ? null : draft.scanId,
      supplierId: draft.supplierId!,
      documentNumber: draft.documentNumber.trim(),
      deliveryDate: draft.deliveryDate,
      vetNumber: info?.readVetNumber ?? null,
      bdo: info?.readBdo ?? null,
      nip: info?.readNip ?? null,
      lines: draft.lines.map((line) => ({
        documentName: line.documentName.trim(),
        documentCode: line.documentCode,
        unitPrice: parsePrice(line.price)!,
        productId: line.article && line.article !== NO_ARTICLE ? line.article.id : null,
        handwrittenCorrection: line.handwrittenCorrection,
        note: line.note,
      })),
    }
    save.mutate(form, {
      onSuccess: () => {
        toast.success(saved ? 'Zapisano zmiany' : 'Zapisano dokument WZ')
        navigate(meatPaths.deliveries)
      },
      onError: (error) =>
        setFormError({
          message: error instanceof ApiError ? error.message : 'Nie udało się zapisać',
          duplicateId:
            error instanceof ApiError && typeof error.problem.duplicateId === 'string'
              ? error.problem.duplicateId
              : undefined,
        }),
    })
  }

  const deleteDelivery = () => {
    if (!saved) return
    remove.mutate(saved.id, {
      onSuccess: () => {
        navigate(meatPaths.deliveries)
        toast.success('Usunięto dokument WZ', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(saved.id) },
        })
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć'),
    })
  }

  return (
    <>
      <EditorHeader
        title={saved ? `WZ ${saved.documentNumber}` : 'Nowy dokument WZ'}
        description={
          saved ? `${saved.supplier.name} · wprowadził ${saved.createdBy ?? 'nieznany'}` : 'Sprawdź odczyt i zapisz'
        }
      />
      {deleted && (
        <div className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950/60 dark:text-amber-100">
          Dokument jest usunięty i nie liczy się w porównaniu cen.
          {canEdit && (
            <Button size="sm" variant="outline" loading={restore.isPending} onClick={() => restore.mutate(saved.id)}>
              Przywróć
            </Button>
          )}
        </div>
      )}

      <div className={cn('grid gap-6', draft.scanId && 'lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]')}>
        {draft.scanId && <ScanPreview storeId={store.id} scanId={draft.scanId} contentType={draft.scanContentType} />}

        <div className="min-w-0 space-y-5">
          <section className="space-y-3 rounded-xl border bg-card p-4">
            <h2 className="text-sm font-semibold">Dostawca i dokument</h2>
            <SupplierField
              suppliers={suppliers}
              supplier={supplier}
              info={draft.supplierInfo}
              onChange={(next) => edit((current) => ({ ...current, supplierId: next.id }))}
              invalid={showErrors && !draft.supplierId}
              disabled={readOnly}
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField
                label="Numer dokumentu"
                value={draft.documentNumber}
                onChange={(event) => edit((current) => ({ ...current, documentNumber: event.target.value }))}
                error={showErrors && !draft.documentNumber.trim() ? 'Wpisz numer dokumentu' : undefined}
                disabled={readOnly}
              />
              <DateField
                label="Data dostawy (wysyłki)"
                value={draft.deliveryDate}
                onChange={(value) => edit((current) => ({ ...current, deliveryDate: value }))}
                max={todayIso()}
                error={showErrors && !draft.deliveryDate ? 'Wybierz datę' : undefined}
              />
            </div>
            {draft.supplierInfo?.duplicateId && (
              <p className="rounded-lg bg-amber-100 px-3 py-2 text-sm text-amber-950 dark:bg-amber-950/60 dark:text-amber-100">
                Ten dokument tego dostawcy jest już wprowadzony.{' '}
                <Link className="font-medium underline" to={meatPaths.delivery(draft.supplierInfo.duplicateId)}>
                  Otwórz zapisany
                </Link>
              </p>
            )}
          </section>

          <section className="space-y-3 rounded-xl border bg-card p-4">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="text-sm font-semibold">Pozycje · cena netto za kg</h2>
              {undecided > 0 && (
                <p className="text-xs font-medium text-amber-700 dark:text-amber-300">
                  {undecided === 1 ? '1 pozycja' : `${undecided} pozycje`} bez wybranego artykułu
                </p>
              )}
            </div>
            <ul className="divide-y">
              {draft.lines.map((line) => (
                <LineRow
                  key={line.key}
                  line={line}
                  articles={articleItems}
                  showErrors={showErrors}
                  readOnly={readOnly}
                  onChange={(change) => updateLine(line.key, change)}
                  onRemove={
                    draft.lines.length > 1
                      ? () =>
                          edit((current) => ({
                            ...current,
                            lines: current.lines.filter((item) => item.key !== line.key),
                          }))
                      : undefined
                  }
                />
              ))}
            </ul>
            {!readOnly && (
              <Button
                variant="outline"
                onClick={() => edit((current) => ({ ...current, lines: [...current.lines, emptyLine()] }))}
              >
                <PlusIcon />
                Dodaj pozycję
              </Button>
            )}
          </section>

          {formError && (
            <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {formError.message}
              {formError.duplicateId && (
                <>
                  {' '}
                  <Link className="font-medium underline" to={meatPaths.delivery(formError.duplicateId)}>
                    Otwórz zapisany
                  </Link>
                </>
              )}
            </p>
          )}

          {!readOnly && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Button size="xl" loading={save.isPending} onClick={submit}>
                {saved ? 'Zapisz zmiany' : 'Zapisz dokument'}
              </Button>
              {saved && (
                <Button variant="ghost" loading={remove.isPending} onClick={deleteDelivery}>
                  <Trash2Icon />
                  Usuń dokument
                </Button>
              )}
            </div>
          )}
        </div>
      </div>
    </>
  )
}

/**
 * Skan obok formularza: zdjęcie albo PDF w ramce; na telefonie nad formularzem i niższy. PDF idzie przez adres blob,
 * bo backend nie pozwala osadzać swoich odpowiedzi w ramkach (X-Frame-Options), jak przy drukowaniu faktur.
 */
function ScanPreview({ storeId, scanId, contentType }: { storeId: string; scanId: string; contentType: string | null }) {
  const url = scanFileUrl(storeId, scanId)
  const pdf = contentType === 'application/pdf'
  const [pdfUrl, setPdfUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!pdf) return
    let objectUrl: string | null = null
    let cancelled = false
    fetch(url, { credentials: 'same-origin' })
      .then((response) => (response.ok ? response.blob() : Promise.reject(new Error(String(response.status)))))
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
        setPdfUrl(objectUrl)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [pdf, url])

  return (
    <div className="lg:sticky lg:top-20 lg:self-start">
      <div className="overflow-hidden rounded-xl border bg-muted">
        {pdf ? (
          pdfUrl ? (
            <iframe title="Skan dokumentu WZ" src={pdfUrl} className="h-[60svh] w-full lg:h-[calc(100svh-7rem)]" />
          ) : (
            <div className="h-[60svh] w-full animate-pulse lg:h-[calc(100svh-7rem)] motion-reduce:animate-none" />
          )
        ) : (
          <a href={url} target="_blank" rel="noreferrer" className="block max-h-[60svh] overflow-auto lg:max-h-[calc(100svh-7rem)]">
            <img src={url} alt="Skan dokumentu WZ" className="w-full" />
          </a>
        )}
      </div>
    </div>
  )
}

const SUPPLIER_MATCH: Record<NonNullable<MeatScanResult['supplierMatch']>, string> = {
  MARK: 'Rozpoznany po numerze z dokumentu (weterynaryjnym, BDO albo NIP), zapamiętanym przy poprzednim WZ.',
  NIP: 'Rozpoznany po NIP-ie z dokumentu.',
  MODEL: 'Propozycja AI. Sprawdź, czy to ten dostawca.',
}

function SupplierField({
  suppliers,
  supplier,
  info,
  onChange,
  invalid,
  disabled,
}: {
  suppliers: Supplier[]
  supplier: Supplier | null
  info: MeatScanResult | null
  onChange: (supplier: Supplier) => void
  invalid: boolean
  disabled: boolean
}) {
  const read = info && [info.readSupplierName, info.readNip && `NIP ${info.readNip}`, info.readVetNumber && `wet. ${info.readVetNumber}`]
    .filter(Boolean)
    .join(' · ')
  const untouched = info?.supplier != null && supplier?.id === info.supplier.id
  return (
    <div className="space-y-1.5">
      {disabled ? (
        <p className="text-sm font-medium">{supplier?.name}</p>
      ) : (
        <SearchPicker
          label="Dostawca mięsa"
          items={suppliers}
          value={supplier}
          onChange={onChange}
          itemKey={(item) => item.id}
          itemLabel={(item) => item.name}
          searchFields={(item) => [item.name, item.code, item.nip, item.keyword]}
          renderItem={(item) => (
            <span className="block min-w-0">
              <span className="block truncate font-medium">{item.name}</span>
              <span className="block truncate text-xs text-muted-foreground tabular-nums">
                {[item.code && `kod ${item.code}`, item.nip && `NIP ${item.nip}`].filter(Boolean).join(' · ')}
              </span>
            </span>
          )}
          placeholder="Dostawca mięsa (nie SCA PR): nazwa, kod, NIP"
          emptyText="Nie ma takiego dostawcy na liście sklepu"
          invalid={invalid || (info != null && !supplier)}
        />
      )}
      {invalid && <p className="text-xs text-destructive">Wybierz dostawcę</p>}
      {info && (
        <p
          className={cn(
            'text-xs',
            untouched && info.supplierMatch === 'MODEL' ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground',
          )}
        >
          {!info.supplier
            ? `Nie rozpoznano dostawcy${read ? ` (na dokumencie: ${read})` : ''}. Wybierz go raz, kolejne WZ rozpoznają się same.`
            : untouched && info.supplierMatch
              ? SUPPLIER_MATCH[info.supplierMatch]
              : read
                ? `Na dokumencie: ${read}`
                : null}
        </p>
      )}
    </div>
  )
}

function LineRow({
  line,
  articles,
  showErrors,
  readOnly,
  onChange,
  onRemove,
}: {
  line: EditorLine
  articles: MeatArticle[]
  showErrors: boolean
  readOnly: boolean
  onChange: (change: Partial<EditorLine>) => void
  onRemove?: () => void
}) {
  const priceInvalid = showErrors && parsePrice(line.price) == null
  const nameInvalid = showErrors && !line.documentName.trim()
  return (
    <li className="grid gap-2 py-3 first:pt-0 last:pb-0 sm:grid-cols-[minmax(0,1fr)_7rem] sm:items-start">
      <div className="min-w-0 space-y-2">
        {readOnly ? (
          <p className="text-sm font-medium">{line.documentName}</p>
        ) : (
          <input
            aria-label="Nazwa z dokumentu"
            value={line.documentName}
            onChange={(event) => onChange({ documentName: event.target.value })}
            placeholder="Nazwa z dokumentu"
            className={cn(
              'h-9 w-full rounded-lg border border-transparent bg-transparent px-2 -mx-2 text-sm font-medium outline-none hover:border-input focus:border-ring focus:ring-3 focus:ring-ring/30 dark:focus:bg-input/30',
              nameInvalid && 'border-destructive',
            )}
          />
        )}
        {readOnly ? (
          <p className="text-sm text-muted-foreground">
            {line.article && line.article !== NO_ARTICLE
              ? `${line.article.name} · ${line.article.brand ?? ''}`
              : 'Bez artykułu – sam zakup'}
          </p>
        ) : (
          <SearchPicker
            label="Artykuł z kartoteki"
            items={articles}
            value={line.article}
            onChange={(article) => onChange({ article, match: null })}
            itemKey={(item) => item.id}
            itemLabel={(item) => (item === NO_ARTICLE ? item.name ?? '' : `${item.name} · ${item.brand ?? ''}`)}
            searchFields={(item) => [item.name, item.brand, item.ean, item.itm8]}
            renderItem={(item) =>
              item === NO_ARTICLE ? (
                <span className="font-medium text-muted-foreground">{item.name}</span>
              ) : (
                <span className="grid grid-cols-[1fr_auto] items-center gap-x-3">
                  <span className="min-w-0 truncate">
                    <span className="font-medium">{item.name}</span>
                    {item.brand && <span className="ml-2 text-muted-foreground">{item.brand}</span>}
                  </span>
                  <span className="text-xs text-muted-foreground tabular-nums">{formatPrice(item.salePrice)}</span>
                </span>
              )
            }
            placeholder="Wybierz artykuł: nazwa, gatunek, EAN"
            emptyText="Nie ma takiego mięsa w kartotece"
            invalid={line.article == null}
          />
        )}
        <LineBadges line={line} />
      </div>
      <div className="flex items-start gap-1">
        <label className="flex-1">
          <span className="sr-only">Cena netto za kg</span>
          <input
            inputMode="decimal"
            value={line.price}
            readOnly={readOnly}
            onChange={(event) => onChange({ price: event.target.value })}
            aria-invalid={priceInvalid || undefined}
            className={cn(
              'h-10 w-full rounded-lg border border-input bg-background px-2.5 text-right text-sm font-medium tabular-nums outline-none focus:border-ring focus:ring-3 focus:ring-ring/30 dark:bg-input/30',
              priceInvalid && 'border-destructive',
              readOnly && 'border-transparent bg-transparent',
            )}
          />
          <span className="mt-0.5 block text-right text-xs text-muted-foreground">zł/kg netto</span>
        </label>
        {onRemove && !readOnly && (
          <Button variant="ghost" size="icon" aria-label="Usuń pozycję" onClick={onRemove} className="mt-1">
            <XIcon />
          </Button>
        )}
      </div>
    </li>
  )
}

function LineBadges({ line }: { line: EditorLine }) {
  const badges: { text: string; title?: string; className: string }[] = []
  if (line.match === 'LINK') {
    badges.push({
      text: 'Zapamiętane u dostawcy',
      className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
    })
  }
  if (line.match === 'MODEL') {
    badges.push({ text: 'Propozycja AI', className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100' })
  }
  if (line.handwrittenCorrection) {
    badges.push({
      text: 'Poprawka odręczna',
      title: line.note ?? undefined,
      className: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
    })
  }
  if (line.documentCode) {
    badges.push({ text: `kod ${line.documentCode}`, className: 'bg-muted text-muted-foreground' })
  }
  if (badges.length === 0 && !line.note) return null
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {badges.map((badge) => (
        <span key={badge.text} title={badge.title} className={cn('rounded-md px-1.5 py-0.5 text-xs font-medium', badge.className)}>
          {badge.text}
        </span>
      ))}
      {line.note && <span className="text-xs text-muted-foreground">{line.note}</span>}
    </div>
  )
}
