import { useState } from 'react'
import { ClipboardCheckIcon, PlusIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import {
  documentCardPdfPath,
  incomingCardPdfPath,
  useDocumentProductionCards,
  useIncomingProductionCards,
  useLinkProductionCard,
  useProductionCardCandidates,
} from '@/features/deli-production/api'
import { quantityFormat } from '@/features/deli-production/format'
import { PdfButtons } from '@/features/deli-production/PdfButtons'
import { ApiError } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'
import type { Document, ProductionCardSummary } from '@/lib/types'

const onError = (fallback: string) => (error: unknown) =>
  toast.error(error instanceof ApiError ? error.message : fallback)

/**
 * Karty produkcji wyrobów jadących ruchem międzymagazynowym: sklep wysyłający je przypina, a sklep docelowy widzi
 * je przy dokumencie do wprowadzenia i drukuje. Odpięcie ma toast „Przywróć” (ponowne przypięcie).
 */
export function DocumentProductionCards({ storeId, document }: { storeId: string; document: Document }) {
  const type = document.type.toLowerCase()
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { data: cards } = useDocumentProductionCards(storeId, type, document.id)
  const { link, unlink } = useLinkProductionCard(storeId, type, document.id)
  const [open, setOpen] = useState(false)

  if (!cards || (cards.length === 0 && !canEdit)) return null

  const remove = (card: ProductionCardSummary) =>
    unlink.mutate(card.id, {
      onSuccess: () =>
        toast.success(`Odpięto kartę z ${formatShortDate(card.productionDate)}`, {
          action: {
            label: 'Przywróć',
            onClick: () => link.mutate(card.id, { onError: onError('Nie udało się przypiąć karty') }),
          },
        }),
      onError: onError('Nie udało się odpiąć karty'),
    })

  return (
    <section aria-labelledby="production-cards" className="mb-6">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 id="production-cards" className="text-sm font-semibold">
          Karty produkcji
        </h3>
        {canEdit && (
          <Button variant="outline" onClick={() => setOpen(true)}>
            <PlusIcon />
            Przypnij kartę produkcji
          </Button>
        )}
      </div>
      {cards.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-3 text-sm text-muted-foreground">
          Jadą tym dokumentem wyroby garmażu? Przypnij ich kartę produkcji, a sklep docelowy ją wydrukuje.
        </p>
      ) : (
        <CardList
          cards={cards}
          pdfPath={(card) => documentCardPdfPath(storeId, type, document.id, card.id)}
          onRemove={canEdit ? remove : undefined}
        />
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="text-lg">Przypnij kartę produkcji</DialogTitle>
            <DialogDescription>Karty sklepu z dwóch tygodni przed dokumentem do dwóch dni po nim.</DialogDescription>
          </DialogHeader>
          {open && (
            <Candidates
              storeId={storeId}
              type={type}
              documentId={document.id}
              linking={link.isPending ? link.variables : undefined}
              onPick={(card) =>
                link.mutate(card.id, {
                  onSuccess: () => {
                    toast.success(`Przypięto kartę z ${formatShortDate(card.productionDate)}`)
                    setOpen(false)
                  },
                  onError: onError('Nie udało się przypiąć karty'),
                })
              }
            />
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}

function Candidates({
  storeId,
  type,
  documentId,
  linking,
  onPick,
}: {
  storeId: string
  type: string
  documentId: string
  linking: string | undefined
  onPick: (card: ProductionCardSummary) => void
}) {
  const { data: candidates, isPending } = useProductionCardCandidates(storeId, type, documentId, true)

  if (isPending) return <p className="py-6 text-center text-sm text-muted-foreground">Wczytywanie kart…</p>
  if (!candidates || candidates.length === 0) {
    return (
      <p className="py-6 text-center text-sm text-muted-foreground">
        Brak kart z tego okresu. Zapisz kartę w Karty produkcji i wróć tutaj.
      </p>
    )
  }
  return (
    <ul className="grid gap-2">
      {candidates.map((card) => (
        <li key={card.id}>
          <button
            type="button"
            disabled={linking != null}
            onClick={() => onPick(card)}
            className="flex w-full items-center gap-3 rounded-lg border bg-card px-3 py-2.5 text-left text-sm transition-colors hover:bg-muted/60 disabled:opacity-60"
          >
            <ClipboardCheckIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
            <span className="font-medium tabular-nums">{formatShortDate(card.productionDate)}</span>
            <span className="text-muted-foreground tabular-nums">
              {card.lineCount} poz. · {quantityFormat.format(card.totalQuantity)}
            </span>
            {card.createdBy && <span className="ml-auto truncate text-muted-foreground">{card.createdBy}</span>}
          </button>
        </li>
      ))}
    </ul>
  )
}

/** Karty przy dokumencie do wprowadzenia (sklep docelowy): tylko wydruk. */
export function IncomingProductionCards({ storeId, transferId }: { storeId: string; transferId: string }) {
  const { data: cards } = useIncomingProductionCards(storeId, transferId)
  if (!cards || cards.length === 0) return null
  return (
    <section aria-labelledby="incoming-production-cards" className="mb-6">
      <h3 id="incoming-production-cards" className="mb-2 text-sm font-semibold">
        Karty produkcji wyrobów z tego dokumentu
      </h3>
      <CardList cards={cards} pdfPath={(card) => incomingCardPdfPath(storeId, transferId, card.id)} />
    </section>
  )
}

function CardList({
  cards,
  pdfPath,
  onRemove,
}: {
  cards: ProductionCardSummary[]
  pdfPath: (card: ProductionCardSummary) => string
  onRemove?: (card: ProductionCardSummary) => void
}) {
  return (
    <ul className="divide-y rounded-xl border bg-card">
      {cards.map((card) => (
        <li key={card.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2">
          <ClipboardCheckIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
          <span className="text-sm font-medium tabular-nums">Karta z {formatShortDate(card.productionDate)}</span>
          <span className="text-sm text-muted-foreground tabular-nums">
            {card.lineCount} poz. · {quantityFormat.format(card.totalQuantity)}
          </span>
          <div className="ml-auto flex items-center gap-1">
            <PdfButtons compact path={pdfPath(card)} fileName={`karta-produkcji-${card.productionDate}.pdf`} />
            {onRemove && (
              <Button variant="ghost" size="icon" aria-label="Odepnij kartę" title="Odepnij" onClick={() => onRemove(card)}>
                <XIcon />
              </Button>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}
