import { useState } from 'react'
import { DownloadIcon, PrinterIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { ApiError, downloadFile, printFile } from '@/lib/api'

/** Drukuj (okno drukowania bez zapisu na dysk) i Pobierz PDF z API. `compact` = same ikony, np. w wierszu listy. */
export function PdfButtons({
  path,
  fileName,
  compact,
  printLabel = 'Drukuj',
}: {
  path: string
  fileName: string
  compact?: boolean
  printLabel?: string
}) {
  const [busy, setBusy] = useState<'print' | 'download' | null>(null)

  const run = async (kind: 'print' | 'download') => {
    setBusy(kind)
    try {
      if (kind === 'print') await printFile(path)
      else await downloadFile(path, fileName)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku')
    } finally {
      setBusy(null)
    }
  }

  if (compact) {
    return (
      <div className="relative z-10 flex justify-end gap-1">
        <Button
          variant="ghost"
          size="icon"
          aria-label={printLabel}
          title={printLabel}
          loading={busy === 'print'}
          onClick={() => run('print')}
        >
          <PrinterIcon />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Pobierz PDF"
          title="Pobierz PDF"
          loading={busy === 'download'}
          onClick={() => run('download')}
        >
          <DownloadIcon />
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-wrap gap-2">
      <Button variant="outline" loading={busy === 'print'} onClick={() => run('print')}>
        <PrinterIcon />
        {printLabel}
      </Button>
      <Button variant="outline" loading={busy === 'download'} onClick={() => run('download')}>
        <DownloadIcon />
        Pobierz PDF
      </Button>
    </div>
  )
}
