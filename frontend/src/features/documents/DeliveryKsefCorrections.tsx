import { DownloadIcon, FileClockIcon, LinkIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { useDeliveryKsefCorrectionLinks, useDeliveryKsefCorrections } from '@/features/documents/api'
import { bonNumber, documentPaths, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { useDownloadInvoice } from '@/features/ksef/api'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { DeliveryKsefCorrection, Document } from '@/lib/types'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Korekty z KSeF faktury przyjęcia, których nie ma jeszcze w przyjęciu: do raportów trafiają przez korektę od dostawcy
 * (WZ) przypiętą do przyjęcia, więc przy każdej są podpowiedzi WZ, a „Przypnij” wiąże fakturę z WZ i WZ z przyjęciem.
 * Korekty już ujęte widać wyżej wśród faktur („z korekty …”). XML korekt pobiera się w tle, stąd licznik oczekujących.
 */
export function DeliveryKsefCorrections({ storeId, document }: { storeId: string; document: Document }) {
  const canView = useCan('KSEF_INVOICES', 'VIEW')
  const enabled = canView && document.type === 'PZ' && document.ksefInvoices.some((invoice) => !invoice.correction)
  const { data } = useDeliveryKsefCorrections(storeId, document.id, enabled)
  if (!enabled || !data) return null
  const open = data.corrections.filter((correction) => !correction.documents.some((linked) => linked.attached))
  if (open.length === 0 && data.pendingCount === 0) return null

  return (
    <section aria-labelledby="ksef-corrections" className="mt-4">
      <h3 id="ksef-corrections" className="mb-2 text-sm font-semibold">
        Korekty z KSeF do wprowadzenia
      </h3>
      {open.length > 0 && (
        <Card className="gap-0 divide-y py-0">
          {open.map((correction) => (
            <CorrectionRow key={correction.id} storeId={storeId} document={document} correction={correction} />
          ))}
        </Card>
      )}
      {data.pendingCount > 0 && (
        <p className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
          <FileClockIcon aria-hidden className="size-3.5" />
          {open.length === 0 ? 'Brak korekt tej faktury. ' : ''}
          {pendingText(data.pendingCount)} na odczyt z KSeF - pojawią się tu, jeśli dotyczą tej faktury.
        </p>
      )}
    </section>
  )
}

function CorrectionRow({
  storeId,
  document,
  correction,
}: {
  storeId: string
  document: Document
  correction: DeliveryKsefCorrection
}) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const download = useDownloadInvoice(storeId)
  const { link, unlink } = useDeliveryKsefCorrectionLinks(storeId, document.id)
  // Faktura jest już przy WZ, która nie jest przypięta do tego przyjęcia.
  const linked = correction.documents[0]

  const pin = (documentId: string, number: string) =>
    link.mutate(
      { invoiceId: correction.id, documentId },
      {
        onSuccess: ({ attached }) =>
          toast.success(`Przypięto korektę ${correction.invoiceNumber}`, {
            description: `Korekta od dostawcy ${number} jest przy bonie ${bonNumber(document.number)}.`,
            action: {
              label: 'Cofnij',
              onClick: () =>
                unlink.mutate(
                  { invoiceId: correction.id, documentId, detach: attached },
                  { onError: (error) => toast.error(errorMessage(error, 'Nie udało się cofnąć')) },
                ),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się przypiąć korekty')),
      },
    )

  return (
    <div className="px-4 py-3 tabular-nums">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <div className="font-medium">{correction.invoiceNumber}</div>
          <div className="text-xs text-muted-foreground">
            {[correction.sellerName, formatDate(correction.issueDate)].filter(Boolean).join(' · ')} · koryguje{' '}
            {correction.correctedInvoiceNumber}
          </div>
        </div>
        <div className="text-right text-sm">
          <div className="font-medium">{formatMoney(correction.grossAmount)} zł brutto</div>
          <div className="text-xs text-muted-foreground">{formatMoney(correction.netAmount)} zł netto</div>
        </div>
        <Button
          variant="outline"
          size="sm"
          loading={download.isPending}
          onClick={() =>
            download.mutate(
              { invoice: correction, format: 'pdf' },
              { onError: (error) => toast.error(errorMessage(error, 'Nie udało się pobrać faktury')) },
            )
          }
        >
          <DownloadIcon />
          PDF
        </Button>
      </div>

      <div className="mt-2 rounded-md bg-muted/50 px-3 py-2 text-sm">
        {linked ? (
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="min-w-0 flex-1">
              Faktura jest przypięta do korekty od dostawcy{' '}
              <Link
                to={documentPaths.document(linked.type, linked.id)}
                className="font-medium underline-offset-4 hover:underline"
              >
                {linked.number}
              </Link>
              {linked.otherDeliveryBon
                ? `, która jest przy bonie ${bonNumber(linked.otherDeliveryBon)}.`
                : ', która nie jest przypięta do żadnego przyjęcia.'}
            </span>
            {canEdit && !linked.otherDeliveryBon && (
              <Button
                variant="outline"
                size="sm"
                loading={link.isPending}
                onClick={() => pin(linked.id, linked.number)}
              >
                <LinkIcon />
                Przypnij do bonu
              </Button>
            )}
          </div>
        ) : correction.candidates.length === 0 ? (
          <span className="text-muted-foreground">
            Brak korekty od dostawcy o zbliżonej kwocie. Przypnij ją, gdy pojawi się w raporcie Wejścia/Wyjścia.
          </span>
        ) : (
          <>
            <div className="mb-1 text-xs text-muted-foreground">Pasująca korekta od dostawcy</div>
            <ul className="space-y-1">
              {correction.candidates.map((candidate) => (
                <li key={candidate.id} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="min-w-0 flex-1">
                    <Link
                      to={documentPaths.document('WZ', candidate.id)}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {candidate.number}
                    </Link>{' '}
                    <span className="text-muted-foreground">
                      · {formatDocumentDate(candidate.documentDate)} · {formatMoney(candidate.purchaseValue)} zł netto
                      {candidate.difference > 0 && ` · różnica ${formatMoney(candidate.difference)} zł`}
                      {candidate.attached && ' · przypięta do bonu'}
                    </span>
                  </span>
                  {canEdit && (
                    <Button
                      variant="outline"
                      size="sm"
                      loading={link.isPending && link.variables?.documentId === candidate.id}
                      onClick={() => pin(candidate.id, candidate.number)}
                    >
                      <LinkIcon />
                      Przypnij
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </div>
  )
}

/** "1 korekta dostawcy czeka", "3 korekty dostawcy czekają", "5 korekt dostawcy czeka". */
function pendingText(count: number) {
  const lastTwo = count % 100
  const last = count % 10
  if (count === 1) return '1 korekta dostawcy czeka'
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)
    ? `${count} korekty dostawcy czekają`
    : `${count} korekt dostawcy czeka`
}
