import { useState } from 'react'
import { GiftIcon, TruckIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, SegmentedField, SelectField } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import {
  type RegisterGratis,
  useDocumentGratis,
  useGratisSupplierSuggestions,
  useSuggestedSupplier,
} from '@/features/documents/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { AddSupplierForm, SupplierPicker } from '@/features/documents/SupplierPicker'
import { ApiError } from '@/lib/api'
import { addMonths, currentMonth, formatMonth } from '@/lib/dates'
import type { Document, DocumentGratis, DocumentSummary, GratisKind, GratisSupplierSuggestion } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Pozycja-znacznik z systemu sklepu, np. "GRATIS SPOŻYWKA MIX" (jak {@code DocumentLine.isGratisItem} w backendzie). */
const isGratisItem = (name: string | null) => name?.toUpperCase().includes('GRATIS') ?? false

const GRATIS_KINDS: { value: GratisKind; label: string }[] = [
  { value: 'TRADE', label: 'Za handel / ekspozycję' },
  { value: 'LOSSES', label: 'Pokrycie strat' },
]
const kindLabel = (kind: GratisKind) => (kind === 'TRADE' ? 'za handel / ekspozycję' : 'pokrycie strat')

/** Pierwszy miesiąc, za który liczymy gratisy (jak {@code GratisReportService.FIRST_MONTH}). */
export const FIRST_GRATIS_MONTH = '2026-01'

/** Na liście: od kogo jest gratis albo podpowiedź, że dokument z pozycją GRATIS może nim być. */
export function GratisBadge({ document }: { document: DocumentSummary }) {
  if (document.gratis) {
    return (
      <Badge
        variant="outline"
        title={`Gratis od ${document.gratis.supplierName} · ${kindLabel(document.gratis.kind)} · ${formatMonth(document.gratis.effectiveMonth).toLowerCase()}`}
        className="max-w-48 border-emerald-300/70 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300"
      >
        <GiftIcon aria-hidden />
        <span className="truncate">Gratis · {document.gratis.supplierName}</span>
      </Badge>
    )
  }
  if (document.gratisCandidate) {
    return (
      <Badge
        variant="outline"
        title="W dokumencie jest pozycja GRATIS"
        className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300"
      >
        <GiftIcon aria-hidden />
        Może być gratisem
      </Badge>
    )
  }
  return null
}

/**
 * Gratis w szczegółach przeniesienia stanów: kto i od jakiego dostawcy go zarejestrował, albo przycisk rejestracji.
 * Cofnięcie niczego nie kasuje - toast pozwala od razu przywrócić.
 */
export function DocumentGratisSection({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const [open, setOpen] = useState(false)
  const { register, cancel, restore } = useDocumentGratis(storeId, document.type.toLowerCase(), document.id)

  if (!document.gratisAllowed || (!canEdit && !document.gratis)) return null

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const restoreGratis = () =>
    restore.mutate(undefined, { onSuccess: () => toast.success('Przywrócono gratis'), onError })

  const cancelGratis = () =>
    cancel.mutate(undefined, {
      onSuccess: () =>
        toast.success('Cofnięto rejestrację gratisu', { action: { label: 'Przywróć', onClick: restoreGratis } }),
      onError,
    })

  return (
    <section aria-labelledby="document-gratis" className="mb-6">
      {document.gratis ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-emerald-300/70 bg-emerald-50/60 px-4 py-3 dark:border-emerald-500/30 dark:bg-emerald-500/5">
          <GiftIcon aria-hidden className="size-5 shrink-0 text-emerald-700 dark:text-emerald-400" />
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="document-gratis" className="font-semibold">
              Gratis od {document.gratis.supplierName}
              {document.gratis.supplierCode && (
                <span className="ml-1.5 font-normal text-muted-foreground tabular-nums">{document.gratis.supplierCode}</span>
              )}
            </h3>
            <p className="text-muted-foreground tabular-nums">
              {formatMoney(document.saleValueGross)} zł brutto · {kindLabel(document.gratis.kind)} · w raporcie za{' '}
              {formatMonth(document.gratis.effectiveMonth).toLowerCase()}
              {document.gratis.reportMonth && ' (wybrany ręcznie)'}
            </p>
            <p className="text-muted-foreground">{registeredLine(document.gratis)}</p>
          </div>
          {canEdit && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setOpen(true)}>
                Zmień
              </Button>
              <Button variant="ghost" loading={cancel.isPending} onClick={cancelGratis}>
                Cofnij
              </Button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3">
          <GiftIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="document-gratis" className="font-semibold">
              {document.gratisCandidate ? 'To przeniesienie może być gratisem' : 'Gratis od dostawcy?'}
            </h3>
            <p className="text-muted-foreground">
              {document.gratisCandidate
                ? 'W dokumencie jest pozycja GRATIS. Jeśli to gratis, wskaż dostawcę, od którego przyszedł.'
                : 'Jeśli towar z tego dokumentu to gratis, wskaż dostawcę, od którego przyszedł.'}
            </p>
          </div>
          <Button onClick={() => setOpen(true)}>
            <GiftIcon />
            Zarejestruj jako gratis
          </Button>
        </div>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-3xl">
          {open && (
            <GratisDialogBody
              storeId={storeId}
              document={document}
              saving={register.isPending}
              onSubmit={(request) =>
                register.mutate(request, {
                  onSuccess: (saved) => {
                    toast.success(`Zarejestrowano gratis od ${saved.gratis?.supplierName ?? 'dostawcy'}`)
                    setOpen(false)
                  },
                  onError,
                })
              }
              onCancel={() => setOpen(false)}
            />
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}

function registeredLine(gratis: DocumentGratis) {
  const date = dateFormat.format(new Date(gratis.registeredAt))
  return gratis.registeredBy ? `zarejestrował(a) ${gratis.registeredBy}, ${date}` : `zarejestrowano ${date}`
}

/** Dostawca, od którego najczęściej są linie dokumentu (bez pozycji GRATIS, którą wystawia sam sklep). */
function suggestedSupplierCode(document: Document) {
  const counts = new Map<string, number>()
  for (const line of document.lines) {
    if (line.supplierCode && !isGratisItem(line.name)) {
      counts.set(line.supplierCode, (counts.get(line.supplierCode) ?? 0) + 1)
    }
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null
}

type DialogBodyProps = {
  storeId: string
  document: Document
  saving: boolean
  onSubmit: (request: RegisterGratis) => void
  onCancel: () => void
}

/**
 * Wybór dostawcy z listy sklepu (z podpowiedzią z linii dokumentu i ostatnimi dostawcami artykułów obok) albo dodanie
 * nowego, spoza listy; do tego za co jest gratis i którego miesiąca dotyczy (domyślnie miesiąc daty dokumentu).
 */
function GratisDialogBody({ storeId, document, saving, onSubmit, onCancel }: DialogBodyProps) {
  const suggested = useSuggestedSupplier(storeId, suggestedSupplierCode(document))
  const [adding, setAdding] = useState<string | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  const [kind, setKind] = useState<GratisKind>(document.gratis?.kind ?? 'TRADE')
  const documentMonth = document.documentDate.slice(0, 7)
  const [month, setMonth] = useState(document.gratis?.effectiveMonth ?? documentMonth)
  const selectedId = picked ?? document.gratis?.supplierId ?? suggested?.id ?? null

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

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        if (selectedId) onSubmit({ supplierId: selectedId, kind, reportMonth: month })
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{document.gratis ? 'Zmień dostawcę gratisu' : 'Zarejestruj jako gratis'}</DialogTitle>
        <DialogDescription className="tabular-nums">
          {document.typeSingular} {document.number} · gratis o wartości {formatMoney(document.saleValueGross)} zł brutto
          (sprzedaż brutto dokumentu).
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_15rem]">
        <SupplierPicker
          storeId={storeId}
          selectedId={selectedId}
          onSelect={setPicked}
          suggested={suggested}
          onAdd={setAdding}
        />
        <RecentSuppliers storeId={storeId} document={document} selectedId={selectedId} onSelect={setPicked} />
      </div>

      <div className="grid gap-4 sm:grid-cols-2 sm:items-center">
        <SegmentedField label="Za co jest gratis" options={GRATIS_KINDS} value={kind} onChange={setKind} />
        <SelectField
          label="Miesiąc gratisu w raporcie"
          options={monthOptions(documentMonth)}
          value={month}
          onChange={setMonth}
        />
      </div>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saving} disabled={!selectedId}>
          {document.gratis ? 'Zapisz zmiany' : 'Zarejestruj gratis'}
        </Button>
      </FormActions>
    </Form>
  )
}

/** Miesiące do wyboru od najnowszego: od stycznia 2026 do bieżącego, zawsze z miesiącem daty dokumentu. */
function monthOptions(documentMonth: string) {
  const last = documentMonth > currentMonth() ? documentMonth : currentMonth()
  const options = []
  for (let month = last; month >= FIRST_GRATIS_MONTH; month = addMonths(month, -1)) {
    options.push({
      value: month,
      label: month === documentMonth ? `${formatMonth(month)} · data dokumentu` : formatMonth(month),
    })
  }
  return options
}

/**
 * Ostatni dostawcy artykułów z dokumentu (z przyjęć PZ), od najświeższej dostawy. Przy bazie (538, 88110) marka artykułów,
 * bo gratis daje producent. Klik wybiera dostawcę z listy sklepu.
 */
function RecentSuppliers({
  storeId,
  document,
  selectedId,
  onSelect,
}: {
  storeId: string
  document: Document
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const { data, isPending, isError } = useGratisSupplierSuggestions(storeId, document.type.toLowerCase(), document.id)

  return (
    <section aria-labelledby="recent-suppliers" className="grid content-start gap-2">
      <h3 id="recent-suppliers" className="flex items-center gap-1.5 text-sm font-medium">
        <TruckIcon aria-hidden className="size-4 text-muted-foreground" />
        Ostatnio przywozili te artykuły
      </h3>
      {isPending ? (
        <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : isError ? (
        <p className="text-sm text-muted-foreground">Nie udało się wczytać dostawców z przyjęć.</p>
      ) : data.length === 0 ? (
        <p className="text-sm text-muted-foreground">Artykułów z tego dokumentu nie było jeszcze w przyjęciach.</p>
      ) : (
        <ul className="grid gap-1">
          {data.map((suggestion) => (
            <li key={`${suggestion.supplierCode}-${suggestion.brand ?? ''}`}>
              <RecentSupplier
                suggestion={suggestion}
                selected={suggestion.supplierId != null && suggestion.supplierId === selectedId}
                onSelect={onSelect}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function RecentSupplier({
  suggestion,
  selected,
  onSelect,
}: {
  suggestion: GratisSupplierSuggestion
  selected: boolean
  onSelect: (id: string) => void
}) {
  const { supplierId } = suggestion
  const products = suggestion.productCount === 1 ? '1 artykuł' : `${suggestion.productCount} art.`
  return (
    <button
      type="button"
      disabled={supplierId == null}
      aria-pressed={selected}
      title={supplierId == null ? 'Tego dostawcy nie ma na liście dostawców sklepu' : undefined}
      onClick={() => supplierId && onSelect(supplierId)}
      className={cn(
        'grid w-full gap-0.5 rounded-lg border px-3 py-2 text-left text-sm transition-colors duration-150 outline-none',
        'hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-default disabled:opacity-60 disabled:hover:bg-transparent',
        selected && 'border-foreground/30 bg-muted',
      )}
    >
      <span className="truncate font-medium">{suggestion.supplierName ?? `Dostawca ${suggestion.supplierCode}`}</span>
      {suggestion.brand && <span className="truncate text-xs font-medium">marka {suggestion.brand}</span>}
      <span className="truncate text-xs text-muted-foreground tabular-nums">
        {suggestion.supplierCode} · ostatnio {formatDocumentDate(suggestion.lastDeliveryDate).slice(0, 10)} · {products}
      </span>
    </button>
  )
}
