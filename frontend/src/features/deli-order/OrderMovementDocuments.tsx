import { useState } from 'react'
import { LinkIcon, PlusIcon, TruckIcon, XIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { useDocumentCandidates, useLinkOrderDocument, useUnlinkOrderDocument } from '@/features/deli-order/api'
import { documentPaths, formatDocumentDate } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { DeliOrder } from '@/lib/types'

const MOVEMENT_TYPE = 'MM'

/**
 * Dokumenty ruchu międzymagazynowego, na których jedzie zamówienie. Dostawca (Edycja) przypina swój dokument
 * z okolic daty dostawy albo wyszukany po numerze; zamawiający tylko je widzi.
 */
export function OrderMovementDocuments({ storeId, order }: { storeId: string; order: DeliOrder }) {
  const canEdit = useCan('DELI_ORDERS', 'EDIT')
  const canViewDocuments = useCan('DOCUMENTS')
  // Dokument wystawia i przypina sklep, który wysyła towar; tylko on może też otworzyć go w module Dokumenty.
  const supplier = order.formStore.id === storeId
  const canLink = supplier && canEdit
  const canOpenDocuments = supplier && canViewDocuments
  const [picking, setPicking] = useState(false)
  const unlink = useUnlinkOrderDocument(storeId, order.id)
  const link = useLinkOrderDocument(storeId, order.id)

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const remove = (documentId: string, number: string) =>
    unlink.mutate(documentId, {
      onSuccess: () =>
        toast.success(`Odpięto dokument ${number}`, {
          action: { label: 'Przywróć', onClick: () => link.mutate(documentId, { onError }) },
        }),
      onError,
    })

  if (!canLink && order.documents.length === 0) {
    return (
      <p className="mb-6 flex items-center gap-2 text-sm text-muted-foreground">
        <TruckIcon aria-hidden className="size-4" />
        {supplier
          ? 'Do zamówienia nie przypięto jeszcze dokumentu ruchu międzymagazynowego.'
          : 'Dostawca nie przypiął jeszcze dokumentu ruchu międzymagazynowego, na którym jedzie zamówienie.'}
      </p>
    )
  }

  return (
    <section aria-labelledby="movement-documents" className="mb-6">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 id="movement-documents" className="text-base font-semibold">
            Ruch międzymagazynowy
          </h3>
          <p className="text-sm text-muted-foreground">
            {supplier
              ? 'Dokumenty z systemu sklepu, na których wysyłasz to zamówienie.'
              : 'Dokumenty, na których dostawca wysłał to zamówienie.'}
          </p>
        </div>
        {canLink && !picking && (
          <Button variant="outline" onClick={() => setPicking(true)}>
            <PlusIcon />
            Przypnij dokument
          </Button>
        )}
      </div>

      {order.documents.length > 0 && (
        <Card className="mb-3 gap-0 divide-y py-0">
          {order.documents.map((document) => (
            <div key={document.documentId} className="flex items-center gap-3 px-4 py-2.5 text-sm tabular-nums">
              <LinkIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                {canOpenDocuments ? (
                  <Link
                    to={documentPaths.document(MOVEMENT_TYPE, document.documentId)}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {document.number}
                  </Link>
                ) : (
                  <span className="font-medium">{document.number}</span>
                )}
                <span className="text-muted-foreground">
                  {' '}
                  · {formatDocumentDate(document.documentDate)} · linii: {document.lineCount}
                  {document.linkedBy && ` · przypiął(a) ${document.linkedBy}`}
                </span>
              </div>
              {canLink && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Odepnij dokument ${document.number}`}
                  title="Odepnij"
                  disabled={unlink.isPending}
                  onClick={() => remove(document.documentId, document.number)}
                >
                  <XIcon />
                </Button>
              )}
            </div>
          ))}
        </Card>
      )}

      {canLink && order.documents.length === 0 && !picking && (
        <p className="text-sm text-muted-foreground">Nie przypięto jeszcze żadnego dokumentu.</p>
      )}

      {picking && <DocumentPicker storeId={storeId} order={order} onClose={() => setPicking(false)} />}
    </section>
  )
}

/** Wybór dokumentu: podpowiedzi z okolic daty dostawy, a po wpisaniu - wyszukiwanie po numerze. */
function DocumentPicker({ storeId, order, onClose }: { storeId: string; order: DeliOrder; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const { data: candidates, isPending } = useDocumentCandidates(storeId, order.id, query, true)
  const link = useLinkOrderDocument(storeId, order.id)

  const choose = (documentId: string, number: string) =>
    link.mutate(documentId, {
      onSuccess: () => {
        toast.success(`Przypięto dokument ${number}`)
        onClose()
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przypiąć dokumentu'),
    })

  return (
    <Card className="gap-3 p-4 animate-in fade-in-0 duration-200 motion-reduce:animate-none">
      <div className="flex items-center gap-2">
        <SearchInput
          className="flex-1"
          value={query}
          onChange={setQuery}
          placeholder="Szukaj dokumentu ruchu międzymagazynowego po numerze"
        />
        <Button variant="ghost" size="icon" aria-label="Zamknij wybór dokumentu" onClick={onClose}>
          <XIcon />
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {query.trim()
          ? 'Wyniki wyszukiwania, najnowsze na górze.'
          : 'Dokumenty z tygodnia przed datą dostawy do dwóch dni po niej. Nie ma właściwego? Wpisz jego numer.'}
      </p>
      <div className="grid gap-1">
        {isPending ? (
          <div aria-hidden className="h-24 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : candidates?.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">
            {query.trim()
              ? 'Żaden dokument ruchu międzymagazynowego nie pasuje.'
              : 'Brak dokumentów z okolic daty dostawy. Sprawdź, czy wgrano aktualny raport Wejścia/Wyjścia.'}
          </p>
        ) : (
          candidates?.map((candidate) => (
            <div
              key={candidate.documentId}
              className="flex items-center gap-3 rounded-lg px-2 py-1.5 text-sm tabular-nums hover:bg-muted/60"
            >
              <div className="min-w-0 flex-1">
                <span className="font-medium">{candidate.number}</span>
                <span className="text-muted-foreground">
                  {' '}
                  · {formatDocumentDate(candidate.documentDate)} · linii: {candidate.lineCount}
                </span>
              </div>
              {candidate.linked ? (
                <span className="text-xs text-muted-foreground">przypięty</span>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  loading={link.isPending && link.variables === candidate.documentId}
                  disabled={link.isPending}
                  onClick={() => choose(candidate.documentId, candidate.number)}
                >
                  Przypnij
                </Button>
              )}
            </div>
          ))
        )}
      </div>
    </Card>
  )
}
