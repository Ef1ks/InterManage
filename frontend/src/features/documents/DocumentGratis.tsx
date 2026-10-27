import { useState } from 'react'
import { GiftIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useDocumentGratis, useSuggestedSupplier } from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { AddSupplierForm, SupplierPicker } from '@/features/documents/SupplierPicker'
import { ApiError } from '@/lib/api'
import type { Document, DocumentGratis, DocumentSummary } from '@/lib/types'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Pozycja-znacznik z systemu sklepu, np. "GRATIS SPOŻYWKA MIX" (jak {@code DocumentLine.isGratisItem} w backendzie). */
const isGratisItem = (name: string | null) => name?.toUpperCase().includes('GRATIS') ?? false

/** Na liście: od kogo jest gratis albo podpowiedź, że dokument z pozycją GRATIS może nim być. */
export function GratisBadge({ document }: { document: DocumentSummary }) {
  if (document.gratis) {
    return (
      <Badge
        variant="outline"
        title={`Gratis od ${document.gratis.supplierName}`}
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
              {formatMoney(document.saleValueGross)} zł brutto · {registeredLine(document.gratis)}
            </p>
          </div>
          {canEdit && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setOpen(true)}>
                Zmień dostawcę
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
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {open && (
            <GratisDialogBody
              storeId={storeId}
              document={document}
              saving={register.isPending}
              onSubmit={(supplierId) =>
                register.mutate(supplierId, {
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
  onSubmit: (supplierId: string) => void
  onCancel: () => void
}

/** Wybór dostawcy z listy sklepu (z podpowiedzią z linii dokumentu) albo dodanie nowego, spoza listy. */
function GratisDialogBody({ storeId, document, saving, onSubmit, onCancel }: DialogBodyProps) {
  const suggested = useSuggestedSupplier(storeId, suggestedSupplierCode(document))
  const [adding, setAdding] = useState<string | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
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
        if (selectedId) onSubmit(selectedId)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{document.gratis ? 'Zmień dostawcę gratisu' : 'Zarejestruj jako gratis'}</DialogTitle>
        <DialogDescription className="tabular-nums">
          {document.typeSingular} {document.number} · gratis o wartości {formatMoney(document.saleValueGross)} zł brutto
          (sprzedaż brutto dokumentu).
        </DialogDescription>
      </DialogHeader>

      <SupplierPicker
        storeId={storeId}
        selectedId={selectedId}
        onSelect={setPicked}
        suggested={suggested}
        onAdd={setAdding}
      />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saving} disabled={!selectedId}>
          {document.gratis ? 'Zapisz dostawcę' : 'Zarejestruj gratis'}
        </Button>
      </FormActions>
    </Form>
  )
}
