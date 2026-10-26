import { useState } from 'react'
import { BarcodeIcon, DownloadIcon, FileSpreadsheetIcon, FileTextIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ApiError, downloadFile } from '@/lib/api'

type Format = 'pdf' | 'xlsx' | 'lst'

const FORMATS: { format: Format; label: string; hint: string; icon: typeof FileTextIcon }[] = [
  { format: 'pdf', label: 'PDF do wydruku', hint: 'Kody kreskowe i miejsce na ilości', icon: FileTextIcon },
  { format: 'xlsx', label: 'Excel', hint: 'EAN, nazwa, marka, ilość', icon: FileSpreadsheetIcon },
  { format: 'lst', label: 'Lista EAN (.lst)', hint: 'Jeden kod w linii', icon: BarcodeIcon },
]

/**
 * Pobieranie pliku zamówienia. W wierszu listy sama ikona, w szczegółach przycisk z napisem.
 * Pliki pomijają pozycje z ilością 0.
 */
export function OrderDownloads({ storeId, orderId, compact }: { storeId: string; orderId: string; compact?: boolean }) {
  const [pending, setPending] = useState<Format | null>(null)

  const download = async (format: Format) => {
    setPending(format)
    try {
      await downloadFile(`/stores/${storeId}/deli-order/orders/${orderId}/export/${format}`, `zamowienie.${format}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku')
    } finally {
      setPending(null)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          compact ? (
            <Button variant="ghost" size="icon-sm" aria-label="Pobierz zamówienie" title="Pobierz" loading={pending !== null} />
          ) : (
            <Button variant="outline" size="xl" loading={pending !== null} />
          )
        }
      >
        <DownloadIcon />
        {!compact && 'Pobierz'}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        {FORMATS.map(({ format, label, hint, icon: Icon }) => (
          <DropdownMenuItem key={format} onClick={() => download(format)} className="items-start gap-2.5 py-2">
            <Icon className="mt-0.5" />
            <span className="grid">
              <span>{label}</span>
              <span className="text-xs text-muted-foreground">{hint}</span>
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
