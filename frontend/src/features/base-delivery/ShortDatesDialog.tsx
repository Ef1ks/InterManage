import { DownloadIcon, PrinterIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatDocumentDate, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError, downloadFile, printFile } from '@/lib/api'
import type { BaseDelivery } from '@/lib/types'

import { shortDatesPdfPath } from './api'

type Props = {
  storeId: string
  delivery: BaseDelivery | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

const daysBetween = (from: string, to: string) =>
  Math.round((new Date(`${to}T00:00:00`).getTime() - new Date(`${from}T00:00:00`).getTime()) / 86_400_000)

/**
 * Raport krótkich terminów całego dokumentu: towar przyjęty z terminem przed dniem dostawy + dni, które baza ma
 * zostawić sklepowi. Kartkę (PDF A4) drukuje się dla osoby, która wyjaśnia to z bazą.
 */
export function ShortDatesDialog({ storeId, delivery, open, onOpenChange }: Props) {
  const [pending, setPending] = useState<'print' | 'download' | null>(null)
  const lines = delivery?.lines.filter((line) => line.shortDated) ?? []

  const run = async (kind: 'print' | 'download') => {
    if (!delivery) return
    setPending(kind)
    try {
      const path = shortDatesPdfPath(storeId, delivery.id)
      if (kind === 'print') await printFile(path)
      else await downloadFile(path, `krotkie-terminy-${delivery.number}.pdf`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować raportu')
    } finally {
      setPending(null)
    }
  }

  return (
    <Dialog open={open && delivery !== null} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Krótkie terminy</DialogTitle>
          <DialogDescription>
            Cały bon {delivery?.number}: towar z terminem krótszym niż dzień dostawy + dni dla sklepu (towar, który nie
            przyjechał, pomijamy).
          </DialogDescription>
        </DialogHeader>

        {lines.length === 0 ? (
          <p className="rounded-lg bg-emerald-200 px-3 py-2 text-sm font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
            Wszystkie artykuły dostawy mają wymagany termin.
          </p>
        ) : (
          <div className="max-h-[55vh] overflow-auto rounded-lg border">
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-muted text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-2 py-1.5 font-medium">Nośnik</th>
                  <th className="px-2 py-1.5 font-medium">ITM8</th>
                  <th className="px-2 py-1.5 font-medium">Artykuł</th>
                  <th className="px-2 py-1.5 text-right font-medium">Ilość</th>
                  <th className="px-2 py-1.5 font-medium">Termin</th>
                  <th className="px-2 py-1.5 font-medium">Wymagany od</th>
                  <th className="px-2 py-1.5 text-right font-medium">Brakuje</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {lines.map((line) => (
                  <tr key={line.id}>
                    <td className="px-2 py-1.5">{line.carrier}</td>
                    <td className="px-2 py-1.5">{line.itm8}</td>
                    <td className="max-w-64 px-2 py-1.5">
                      <span className="flex items-center gap-1">
                        <span className="min-w-0 truncate" title={line.name}>
                          {line.name}
                        </span>
                        {line.product?.itm8 && (
                          <ArticleStatsButton
                            article={{ itm8: line.product.itm8, name: line.name, ean: line.ean, brand: line.brand }}
                          />
                        )}
                      </span>
                    </td>
                    <td className="px-2 py-1.5 text-right">{formatQuantity(line.quantity)}</td>
                    <td className="px-2 py-1.5">
                      <span className="rounded bg-red-300 px-1 font-medium text-red-950 dark:bg-red-800 dark:text-red-50">
                        {formatDocumentDate(line.bestBefore ?? '')}
                      </span>
                    </td>
                    <td className="px-2 py-1.5">{formatDocumentDate(line.requiredBestBefore ?? '')}</td>
                    <td className="px-2 py-1.5 text-right font-medium">
                      {daysBetween(line.bestBefore ?? '', line.requiredBestBefore ?? '')} dni
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <DialogFooter>
          <Button
            variant="outline"
            loading={pending === 'download'}
            disabled={pending !== null}
            onClick={() => run('download')}
          >
            <DownloadIcon />
            Pobierz PDF
          </Button>
          <Button loading={pending === 'print'} disabled={pending !== null} onClick={() => run('print')}>
            <PrinterIcon />
            Drukuj
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
