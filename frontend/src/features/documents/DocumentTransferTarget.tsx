import { useState } from 'react'
import { ArrowRightLeftIcon, CheckCircle2Icon, StoreIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { Form, FormActions } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useTransferTarget } from '@/features/documents/api'
import { useStorePartners } from '@/features/store-settings/api'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { Document, DocumentSummary, StoreSummary, TransferTarget } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Na liście ruchów międzymagazynowych: dla którego sklepu i czy już go wprowadził. */
export function TransferTargetBadge({ document }: { document: DocumentSummary }) {
  const target = document.transferTarget
  if (!target) return null
  return (
    <Badge
      variant="outline"
      title={target.enteredAt ? 'Sklep wprowadził dokument u siebie' : 'Sklep jeszcze nie wprowadził dokumentu'}
      className={cn(
        'max-w-56',
        target.enteredAt
          ? 'border-emerald-300/70 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300'
          : 'border-sky-300/70 bg-sky-50 text-sky-800 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-300',
      )}
    >
      {target.enteredAt ? <CheckCircle2Icon aria-hidden /> : <ArrowRightLeftIcon aria-hidden />}
      <span className="truncate">Dla {storeLabel(target.store)}</span>
    </Badge>
  )
}

/**
 * Ruch międzymagazynowy dla sklepu zaprzyjaźnionego: sklep docelowy widzi go w Dokumentach jako dokument do
 * wprowadzenia u siebie. Cofnięcie niczego nie kasuje - toast pozwala od razu przywrócić.
 */
export function DocumentTransferTargetSection({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const [open, setOpen] = useState(false)
  const { set, cancel, restore } = useTransferTarget(storeId, document.type.toLowerCase(), document.id)
  const target = document.transferTarget

  if (!document.transferTargetAllowed || (!canEdit && !target)) return null

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const cancelTarget = () =>
    cancel.mutate(undefined, {
      onSuccess: () =>
        toast.success('Cofnięto wskazanie sklepu', {
          action: {
            label: 'Przywróć',
            onClick: () => restore.mutate(undefined, { onSuccess: () => toast.success('Przywrócono'), onError }),
          },
        }),
      onError,
    })

  return (
    <section aria-labelledby="transfer-target" className="mb-6">
      {target ? (
        <div
          className={cn(
            'flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border px-4 py-3',
            target.enteredAt
              ? 'border-emerald-300/70 bg-emerald-50/60 dark:border-emerald-500/30 dark:bg-emerald-500/5'
              : 'border-sky-300/70 bg-sky-50/60 dark:border-sky-500/30 dark:bg-sky-500/5',
          )}
        >
          {target.enteredAt ? (
            <CheckCircle2Icon aria-hidden className="size-5 shrink-0 text-emerald-700 dark:text-emerald-400" />
          ) : (
            <ArrowRightLeftIcon aria-hidden className="size-5 shrink-0 text-sky-700 dark:text-sky-400" />
          )}
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="transfer-target" className="font-semibold">
              Wystawiony dla {storeLabel(target.store)}
            </h3>
            <p className="text-muted-foreground tabular-nums">{statusLine(target)}</p>
          </div>
          {canEdit && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setOpen(true)}>
                Zmień sklep
              </Button>
              <Button variant="ghost" loading={cancel.isPending} onClick={cancelTarget}>
                Cofnij
              </Button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3">
          <ArrowRightLeftIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1 text-sm">
            <h3 id="transfer-target" className="font-semibold">
              Dla którego sklepu jest ten dokument?
            </h3>
            <p className="text-muted-foreground">
              Wskaż sklep zaprzyjaźniony, a zobaczy ten dokument w Dokumentach jako do wprowadzenia u siebie.
            </p>
          </div>
          <Button onClick={() => setOpen(true)}>
            <StoreIcon />
            Wskaż sklep
          </Button>
        </div>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {open && (
            <TargetDialogBody
              storeId={storeId}
              document={document}
              saving={set.isPending}
              onSubmit={(targetStoreId) =>
                set.mutate(targetStoreId, {
                  onSuccess: (saved) => {
                    toast.success(
                      saved.transferTarget
                        ? `${storeLabel(saved.transferTarget.store)} zobaczy dokument do wprowadzenia`
                        : 'Zapisano',
                    )
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

function statusLine(target: TransferTarget) {
  const created = target.createdBy
    ? `wskazał(a) ${target.createdBy}, ${dateFormat.format(new Date(target.createdAt))}`
    : `wskazano ${dateFormat.format(new Date(target.createdAt))}`
  if (!target.enteredAt) return `Sklep jeszcze go nie wprowadził · ${created}`
  const entered = dateFormat.format(new Date(target.enteredAt))
  return `Wprowadzony w sklepie ${entered}${target.enteredBy ? ` (${target.enteredBy})` : ''} · ${created}`
}

type DialogBodyProps = {
  storeId: string
  document: Document
  saving: boolean
  onSubmit: (storeId: string) => void
  onCancel: () => void
}

/**
 * Wybór sklepu zaprzyjaźnionego. Podpowiada sklep, do którego jadą zamówienia garmażu przypięte do dokumentu.
 */
function TargetDialogBody({ storeId, document, saving, onSubmit, onCancel }: DialogBodyProps) {
  const { data, isPending } = useStorePartners(storeId)
  const partners: StoreSummary[] = (data?.partners ?? [])
    .filter((partner) => partner.acceptedAt)
    .map((partner) => partner.store)
  const suggested = partners.find((store) => document.deliOrders.some((order) => order.storeNumber === store.number))
  const [picked, setPicked] = useState<string | null>(null)
  const selectedId = picked ?? document.transferTarget?.store.id ?? suggested?.id ?? null

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        if (selectedId) onSubmit(selectedId)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{document.transferTarget ? 'Zmień sklep' : 'Wskaż sklep'}</DialogTitle>
        <DialogDescription className="tabular-nums">
          {document.typeSingular} {document.number}. Sklep zobaczy dokument z liniami i oznaczy go, gdy wprowadzi go u
          siebie.
        </DialogDescription>
      </DialogHeader>

      {isPending ? (
        <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
      ) : partners.length === 0 ? (
        <div className="rounded-lg bg-muted/70 px-4 py-3 text-sm">
          <p className="font-medium">Ten sklep nie ma jeszcze sklepów zaprzyjaźnionych</p>
          <p className="text-muted-foreground">
            Zaproś sklep w{' '}
            <Link to="/ustawienia-sklepu/sklepy-zaprzyjaznione" className="font-medium text-foreground underline underline-offset-4">
              Ustawieniach sklepu
            </Link>
            . Gdy zaakceptuje, będzie można go tu wybrać.
          </p>
        </div>
      ) : (
        <div role="radiogroup" aria-label="Sklep zaprzyjaźniony" className="grid gap-2">
          {partners.map((store) => {
            const checked = store.id === selectedId
            return (
              <button
                key={store.id}
                type="button"
                role="radio"
                aria-checked={checked}
                onClick={() => setPicked(store.id)}
                className={cn(
                  'flex items-center gap-3 rounded-lg border px-3 py-2.5 text-left text-sm transition-colors duration-150 outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50',
                  checked && 'border-foreground/40 bg-muted/60',
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    'flex size-4 shrink-0 items-center justify-center rounded-full border',
                    checked && 'border-foreground',
                  )}
                >
                  {checked && <span className="size-2 rounded-full bg-foreground" />}
                </span>
                <span className="min-w-0 flex-1 font-medium">{storeLabel(store)}</span>
                {store.id === suggested?.id && (
                  <span className="text-xs text-muted-foreground">zamówienie garmażu</span>
                )}
              </button>
            )
          })}
        </div>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={saving} disabled={!selectedId || partners.length === 0}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
