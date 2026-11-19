import { DownloadIcon, PrinterIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
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
import { cn } from '@/lib/utils'

import { shortDatesPdfPath } from './api'

type Props = {
  storeId: string
  delivery: BaseDelivery | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

const daysBetween = (from: string, to: string) =>
  Math.round((new Date(`${to}T00:00:00`).getTime() - new Date(`${from}T00:00:00`).getTime()) / 86_400_000)

/** Termin do wklejenia w Mercalys i w reklamacji: DD/MM/RRRR. */
const slashDate = (iso: string) => iso.split('-').reverse().join('/')

const days = (count: number) => (count === 1 ? 'dzień' : 'dni')

/** Najczęstsze dni dla sklepu (DLV) w dokumencie: tyle baza zwykle zostawia; pojedyncze artykuły mają inne. */
function standardDlv(delivery: BaseDelivery | null): number | null {
  const counts = new Map<number, number>()
  for (const line of delivery?.lines ?? []) {
    const value = line.daysForStore
    if (value && value > 0) counts.set(value, (counts.get(value) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0]?.[0] ?? null
}

/**
 * Raport krótkich terminów całego dokumentu: towar przyjęty z terminem przed dniem dostawy + dni, które baza ma
 * zostawić sklepowi. Kartkę (PDF A4) drukuje się dla osoby, która wyjaśnia to z bazą.
 */
export function ShortDatesDialog({ storeId, delivery, open, onOpenChange }: Props) {
  const [pending, setPending] = useState<'print' | 'download' | null>(null)
  const lines = delivery?.lines.filter((line) => line.shortDated) ?? []
  const dlv = standardDlv(delivery)

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
      <DialogContent className="gap-5 p-6 sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Krótkie terminy</DialogTitle>
          <DialogDescription>
            Towar z terminem krótszym niż dzień dostawy + dni dla sklepu (DLV). Pomijamy towar, który nie przyjechał, i
            owoce z warzywami (1401).
          </DialogDescription>
        </DialogHeader>

        {delivery && (
          <dl className="flex flex-wrap gap-2 text-sm">
            <div className="flex items-center gap-2 rounded-lg border bg-muted/50 py-1 pr-1 pl-3">
              <dt className="text-muted-foreground">Nr BL</dt>
              <dd className="flex items-center font-semibold tabular-nums">
                {delivery.number}
                <CopyButton value={delivery.number} label="Kopiuj numer BL" />
              </dd>
            </div>
            <div className="flex items-center gap-2 rounded-lg border bg-muted/50 px-3 py-1">
              <dt className="text-muted-foreground">Dostawa</dt>
              <dd className="font-semibold tabular-nums">{formatDocumentDate(delivery.deliveryDate)}</dd>
            </div>
            {dlv !== null && (
              <div
                className="flex items-center gap-2 rounded-lg border bg-muted/50 px-3 py-1"
                title="Najczęstsze dni dla sklepu w tym dokumencie; artykuły z innymi dniami mają je w kolumnie DLV"
              >
                <dt className="text-muted-foreground">Standardowo DLV</dt>
                <dd className="font-semibold tabular-nums">
                  {dlv} {days(dlv)}
                </dd>
              </div>
            )}
          </dl>
        )}

        {lines.length === 0 ? (
          <p className="rounded-lg bg-emerald-200 px-3 py-2 text-sm font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
            Wszystkie artykuły dostawy mają wymagany termin.
          </p>
        ) : (
          <div className="max-h-[55vh] overflow-auto rounded-lg border">
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-muted text-left text-xs whitespace-nowrap text-muted-foreground">
                <tr>
                  <th className="px-2 py-1.5 font-medium">Nośnik</th>
                  <th className="px-2 py-1.5 font-medium">ITM8</th>
                  <th className="px-2 py-1.5 font-medium">Artykuł</th>
                  <th className="px-2 py-1.5 text-right font-medium">Ilość</th>
                  <th className="px-2 py-1.5 font-medium">Termin</th>
                  <th className="px-2 py-1.5 text-right font-medium">DLV</th>
                  <th className="px-2 py-1.5 font-medium">Wymagany od</th>
                  <th className="px-2 py-1.5 text-right font-medium">Brakuje</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {lines.map((line) => (
                  <tr key={line.id}>
                    <td className="px-2 py-1.5">{line.carrier}</td>
                    <td className="px-2 py-1">
                      <span className="flex items-center gap-0.5">
                        {line.itm8}
                        <CopyButton value={line.itm8} label={`Kopiuj ITM8 ${line.itm8}`} />
                      </span>
                    </td>
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
                    <td className="px-2 py-1">
                      <span className="flex items-center gap-0.5">
                        <span className="rounded bg-red-300 px-1 font-medium text-red-950 dark:bg-red-800 dark:text-red-50">
                          {formatDocumentDate(line.bestBefore ?? '')}
                        </span>
                        {line.bestBefore && (
                          <CopyButton
                            value={slashDate(line.bestBefore)}
                            label={`Kopiuj termin ${slashDate(line.bestBefore)}`}
                          />
                        )}
                      </span>
                    </td>
                    <td
                      className={cn(
                        'px-2 py-1.5 text-right',
                        dlv !== null && line.daysForStore !== dlv && 'font-semibold text-amber-700 dark:text-amber-300',
                      )}
                      title={
                        dlv !== null && line.daysForStore !== dlv ? 'Inne niż standardowo w tym dokumencie' : undefined
                      }
                    >
                      {line.daysForStore}
                    </td>
                    <td className="px-2 py-1.5">{formatDocumentDate(line.requiredBestBefore ?? '')}</td>
                    <td className="px-2 py-1.5 text-right font-medium">
                      {daysBetween(line.bestBefore ?? '', line.requiredBestBefore ?? '')}{' '}
                      {days(daysBetween(line.bestBefore ?? '', line.requiredBestBefore ?? ''))}
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
