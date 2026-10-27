import { useState } from 'react'
import { FileDiffIcon, LinkIcon, PackageIcon, UnlinkIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useCorrectionCandidates, useDeliveryCorrections } from '@/features/documents/api'
import { bonNumber, documentPaths, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { CorrectionCandidate, Document, DocumentSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Korekty przyjęcia PZ (błędu sklepu i od dostawcy) albo - w korekcie - przyjęcie, którego dotyczy. Przypięta korekta
 * zmienia wartości przyjęcia i wydruk bonu; faktury korekt od dostawcy pojawiają się przy przyjęciu.
 */
export function DeliveryCorrections({ storeId, document }: { storeId: string; document: Document }) {
  if (document.correctable) return <DeliveryCorrectionList storeId={storeId} document={document} />
  if (document.attachableToDelivery) return <CorrectedDelivery storeId={storeId} document={document} />
  return null
}

/** Przyjęcie: przypięte korekty z odpięciem i przypinanie kolejnych. */
function DeliveryCorrectionList({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { attach, detach } = useDeliveryCorrections(storeId, document.type, document.id)
  const [picking, setPicking] = useState(false)

  return (
    <section aria-labelledby="delivery-corrections" className="mb-6">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 id="delivery-corrections" className="text-sm font-semibold">
          Korekty
        </h3>
        {canEdit && (
          <Button variant="outline" size="sm" onClick={() => setPicking(true)}>
            <LinkIcon />
            Przypnij korektę
          </Button>
        )}
      </div>

      {document.corrections.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Brak korekt. Przypnij korektę błędu sklepu albo od dostawcy - wartości przyjęcia i wydruk bonu przeliczą się
          razem z nią.
        </p>
      ) : (
        <Card className="gap-0 divide-y py-0">
          {document.corrections.map((correction) => (
            <div key={correction.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 tabular-nums">
              <FileDiffIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <Link
                  to={documentPaths.document(correction.type, correction.id)}
                  className="font-medium underline-offset-4 hover:underline"
                >
                  {correction.typeSingular} {correction.number}
                </Link>
                <div className="text-xs text-muted-foreground">
                  {formatDocumentDate(correction.documentDate)} · {correction.lineCount} poz.
                  {correction.linkedBy && ` · przypiął(-ęła) ${correction.linkedBy}`}
                </div>
              </div>
              <div className="text-right text-sm">
                <div className="font-medium">{signedMoney(correction.purchaseValue)} zł</div>
                <div className="text-xs text-muted-foreground">zakup netto</div>
              </div>
              {canEdit && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Odepnij korektę ${correction.number}`}
                  title="Odepnij"
                  loading={detach.isPending && detach.variables === correction.id}
                  onClick={() =>
                    detach.mutate(correction.id, {
                      onSuccess: () =>
                        toast.success(`Odpięto korektę ${correction.number}`, {
                          action: { label: 'Przywróć', onClick: () => attach.mutate(correction.id) },
                        }),
                      onError: (error) => toast.error(errorMessage(error, 'Nie udało się odpiąć korekty')),
                    })
                  }
                >
                  <UnlinkIcon />
                </Button>
              )}
            </div>
          ))}
        </Card>
      )}

      <CorrectionPickerDialog storeId={storeId} document={document} open={picking} onOpenChange={setPicking} />
    </section>
  )
}

/** Korekta: przyjęcie, do którego jest przypięta, albo przypięcie do przyjęcia. */
function CorrectedDelivery({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { attach, detach } = useDeliveryCorrections(storeId, document.type, document.id)
  const [picking, setPicking] = useState(false)
  const delivery = document.correctionOf

  return (
    <section aria-labelledby="corrected-delivery" className="mb-6">
      <h3 id="corrected-delivery" className="mb-2 text-sm font-semibold">
        Przyjęcie towaru, którego dotyczy
      </h3>
      {delivery ? (
        <Card className="flex-row flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 tabular-nums">
          <PackageIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1">
            <Link
              to={documentPaths.document('PZ', delivery.id)}
              className="font-medium underline-offset-4 hover:underline"
            >
              Bon {bonNumber(delivery.number)}
            </Link>
            <div className="text-xs text-muted-foreground">
              {formatDocumentDate(delivery.documentDate)}
              {delivery.supplierName && ` · ${delivery.supplierName}`} · nr wewn. {delivery.number}
            </div>
          </div>
          {canEdit && (
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Odepnij od bonu ${bonNumber(delivery.number)}`}
              title="Odepnij"
              loading={detach.isPending}
              onClick={() =>
                detach.mutate(delivery.id, {
                  onSuccess: () =>
                    toast.success(`Odpięto od bonu ${bonNumber(delivery.number)}`, {
                      action: { label: 'Przywróć', onClick: () => attach.mutate(delivery.id) },
                    }),
                  onError: (error) => toast.error(errorMessage(error, 'Nie udało się odpiąć korekty')),
                })
              }
            >
              <UnlinkIcon />
            </Button>
          )}
        </Card>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm text-muted-foreground">
            Nieprzypięta. Przypnij ją do bonu - wartości przyjęcia i wydruk bonu przeliczą się razem z nią.
          </p>
          {canEdit && (
            <Button variant="outline" size="sm" onClick={() => setPicking(true)}>
              <LinkIcon />
              Przypnij do przyjęcia
            </Button>
          )}
        </div>
      )}
      <CorrectionPickerDialog storeId={storeId} document={document} open={picking} onOpenChange={setPicking} />
    </section>
  )
}

/** Okno wyboru drugiej strony z przypięciem i toastem „Cofnij”. */
function CorrectionPickerDialog({
  storeId,
  document,
  open,
  onOpenChange,
}: {
  storeId: string
  document: Document
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { attach, detach } = useDeliveryCorrections(storeId, document.type, document.id)
  const forDelivery = document.correctable
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        {open && (
          <CorrectionPicker
            storeId={storeId}
            document={document}
            linking={attach.isPending}
            onPick={(candidate) =>
              attach.mutate(candidate.id, {
                onSuccess: () => {
                  onOpenChange(false)
                  toast.success(
                    forDelivery
                      ? `Przypięto korektę ${candidate.number}`
                      : `Przypięto do bonu ${bonNumber(candidate.number)}`,
                    {
                      description: 'Wartości przyjęcia i wydruk bonu są już po korekcie.',
                      action: { label: 'Cofnij', onClick: () => detach.mutate(candidate.id) },
                    },
                  )
                },
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przypiąć korekty')),
              })
            }
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function CorrectionPicker({
  storeId,
  document,
  linking,
  onPick,
}: {
  storeId: string
  document: Document
  linking: boolean
  onPick: (candidate: CorrectionCandidate) => void
}) {
  const [query, setQuery] = useState('')
  const { data: candidates = [], isPending, isError, isPlaceholderData } = useCorrectionCandidates(
    storeId,
    document.type,
    document.id,
    query,
    true,
  )
  const forDelivery = document.correctable

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">
          {forDelivery
            ? `Przypnij korektę do bonu ${bonNumber(document.number)}`
            : `Przypnij ${document.typeSingular.toLowerCase()} ${document.number} do przyjęcia`}
        </DialogTitle>
        <DialogDescription>
          {forDelivery
            ? 'Korekty tego dostawcy z roku po przyjęciu, najpierw te z największą liczbą wspólnych artykułów.'
            : 'Przyjęcia tego dostawcy ze 120 dni przed korektą, najpierw te z największą liczbą wspólnych artykułów.'}
        </DialogDescription>
      </DialogHeader>

      <SearchInput
        value={query}
        onChange={setQuery}
        placeholder={forDelivery ? 'Szukaj: numer korekty' : 'Szukaj: numer bonu, nr dok. dostawcy'}
      />

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
            {forDelivery ? 'Brak wolnych korekt od tego dostawcy.' : 'Brak przyjęć od tego dostawcy.'}
          </p>
        ) : (
          <ul className="divide-y">
            {candidates.map((candidate) => (
              <li key={candidate.id}>
                <button
                  type="button"
                  disabled={linking}
                  onClick={() => onPick(candidate)}
                  className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-left tabular-nums transition-colors duration-150 outline-none hover:bg-muted focus-visible:bg-muted disabled:cursor-default disabled:opacity-50"
                >
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      {candidate.type === 'PZ'
                        ? `Bon ${bonNumber(candidate.number)}`
                        : `${candidate.typeSingular} ${candidate.number}`}
                      {candidate.commonItems > 0 && (
                        <span className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200">
                          {commonItemsLabel(candidate.commonItems)}
                        </span>
                      )}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {[
                        formatDocumentDate(candidate.documentDate),
                        `${candidate.lineCount} poz.`,
                        candidate.supplierDocumentNumber && `dok. dostawcy ${candidate.supplierDocumentNumber}`,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </span>
                  <span className="text-right text-sm font-medium">{signedMoney(candidate.purchaseValue)} zł</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}

/** Plakietka w wierszu listy: przyjęcie z korektami albo korekta przypięta do bonu. */
export function CorrectionBadge({ document }: { document: DocumentSummary }) {
  const label = document.correctionTotals
    ? document.correctionTotals.count === 1
      ? 'Korekta'
      : `Korekty: ${document.correctionTotals.count}`
    : document.correctionOf
      ? `Bon ${bonNumber(document.correctionOf.number)}`
      : null
  if (!label) return null
  return (
    <span className="rounded-md bg-violet-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-violet-800 ring-1 ring-violet-200 dark:bg-violet-950/40 dark:text-violet-200 dark:ring-violet-800">
      {label}
    </span>
  )
}

/** "1 wspólny artykuł", "2 wspólne artykuły", "5 wspólnych artykułów". */
function commonItemsLabel(count: number) {
  if (count === 1) return '1 wspólny artykuł'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)
    ? `${count} wspólne artykuły`
    : `${count} wspólnych artykułów`
}

/** Kwota korekty ze znakiem: "+382,08", "−1 146,24". */
function signedMoney(value: number) {
  return value > 0 ? `+${formatMoney(value)}` : value < 0 ? `−${formatMoney(-value)}` : formatMoney(0)
}
