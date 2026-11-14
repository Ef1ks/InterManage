import { DownloadIcon, ExternalLinkIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { useCan } from '@/features/auth/api'
import { bonNumber, documentPaths } from '@/features/documents/format'
import { useDownloadCompanyDeliveryNote } from '@/features/ksef/api'
import { ApiError } from '@/lib/api'
import type { KsefInvoiceDocument } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * „Zakończona”: faktura ma już dokument w systemie sklepu firmy (przyjęcie PZ albo korekta), więc nie wprowadza się
 * jej drugi raz. Każdy dokument z numerem bonu jako linkiem: dokument tego sklepu otwiera się w aplikacji (z
 * `DOCUMENTS:VIEW`), przyjęcie innego sklepu firmy pobiera jego bon dostawy (PDF), a obok stoi numer tego sklepu.
 * `newTab`: link otwiera się w nowej karcie (z okna wczytywania, żeby nie zgubić tabeli).
 */
export function CompletedInvoiceBadge({
  storeId,
  invoiceId,
  documents,
  size = 'sm',
  newTab = false,
}: {
  storeId: string
  invoiceId: string
  documents: KsefInvoiceDocument[]
  size?: 'sm' | 'lg'
  newTab?: boolean
}) {
  const canSeeDocuments = useCan('DOCUMENTS', 'VIEW')
  const download = useDownloadCompanyDeliveryNote(storeId)
  if (documents.length === 0) return null

  const linkClass =
    'inline-flex items-center gap-0.5 rounded font-semibold underline decoration-emerald-950/40 underline-offset-2 transition-colors hover:decoration-current dark:decoration-emerald-50/40'

  return (
    <span
      className={cn(
        'inline-flex flex-wrap items-center gap-x-1.5 rounded-md bg-emerald-300 px-2 py-0.5 text-emerald-950 tabular-nums dark:bg-emerald-800 dark:text-emerald-50',
        size === 'lg' ? 'text-sm font-semibold' : 'text-xs font-medium',
      )}
      title="Faktura jest już w systemie sklepu"
    >
      <span>Zakończona</span>
      {documents.map((document) => {
        const own = document.storeId === storeId
        const kind = document.type === 'PZ' ? 'w PZ' : document.typeSingular.toLowerCase()
        const number = bonNumber(document.number)
        const label = `${document.typeSingular} ${document.number}`
        let link: ReactNode = number
        if (own && canSeeDocuments) {
          link = (
            <Link
              to={documentPaths.document(document.type, document.id)}
              target={newTab ? '_blank' : undefined}
              className={linkClass}
              title={`Otwórz: ${label}`}
            >
              {number}
              {newTab && <ExternalLinkIcon className="size-3" />}
            </Link>
          )
        } else if (!own && document.type === 'PZ') {
          link = (
            <button
              type="button"
              className={linkClass}
              title={`Pobierz bon dostawy ${number} ze sklepu ${document.store} (PDF)`}
              disabled={download.isPending}
              onClick={() =>
                download.mutate(
                  { invoiceId, document },
                  {
                    onError: (error) =>
                      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać bonu'),
                  },
                )
              }
            >
              {number}
              <DownloadIcon className="size-3" />
            </button>
          )
        }
        return (
          <span key={document.id} className="inline-flex items-center gap-1">
            <span aria-hidden>·</span>
            {kind} {link}
            {!own && <span>· sklep {document.store}</span>}
          </span>
        )
      })}
    </span>
  )
}
