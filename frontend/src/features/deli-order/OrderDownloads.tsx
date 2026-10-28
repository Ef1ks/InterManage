import { useState } from 'react'
import { BarcodeIcon, DownloadIcon, FileSpreadsheetIcon, FileTextIcon, FolderInputIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { PROTO_FOLDER, orderExportPath } from '@/features/deli-order/api'
import { ApiError, downloadFile } from '@/lib/api'
import { folderSavingSupported, saveApiFileToFolder, useSavedFolder } from '@/lib/saved-folders'

type Format = 'pdf' | 'xlsx' | 'lst' | 'proto' | 'folder'

const FORMATS: { format: Exclude<Format, 'folder'>; label: string; hint: string; icon: typeof FileTextIcon }[] = [
  { format: 'pdf', label: 'PDF do wydruku', hint: 'Kody kreskowe i miejsce na ilości', icon: FileTextIcon },
  { format: 'xlsx', label: 'Excel', hint: 'EAN, nazwa, marka, ilość', icon: FileSpreadsheetIcon },
  { format: 'lst', label: 'Lista EAN (.lst)', hint: 'Jeden kod w linii', icon: BarcodeIcon },
  { format: 'proto', label: 'Plik PROTO6', hint: 'EAN z ilością dla programu sklepu', icon: BarcodeIcon },
]

/**
 * Pobieranie pliku zamówienia. W wierszu listy sama ikona, w szczegółach przycisk z napisem.
 * Pliki pomijają pozycje z ilością 0.
 */
export function OrderDownloads({ storeId, orderId, compact }: { storeId: string; orderId: string; compact?: boolean }) {
  const [pending, setPending] = useState<Format | null>(null)
  const folder = useSavedFolder(PROTO_FOLDER.key)

  const download = async (format: Exclude<Format, 'folder'>) => {
    setPending(format)
    try {
      await downloadFile(orderExportPath(storeId, orderId, format), format === 'proto' ? 'zamowienie.Traite0' : `zamowienie.${format}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku')
    } finally {
      setPending(null)
    }
  }

  // Bez wybranego folderu najpierw okno wyboru (zapamiętane na kolejne razy), potem zapis.
  const saveToFolder = async () => {
    setPending('folder')
    try {
      if (!folder.name) {
        if (PROTO_FOLDER.suggestedPath) navigator.clipboard?.writeText(PROTO_FOLDER.suggestedPath).catch(() => {})
        if (!(await folder.pick())) return
      }
      const fileName = await saveApiFileToFolder(PROTO_FOLDER.key, orderExportPath(storeId, orderId, 'proto'), 'zamowienie.Traite0')
      toast.success(`Zapisano ${fileName}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku')
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
        {folderSavingSupported && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={saveToFolder} className="items-start gap-2.5 py-2">
              <FolderInputIcon className="mt-0.5" />
              <span className="grid">
                <span>{folder.name ? `Zapisz PROTO6 w ${folder.name}` : 'Zapisz PROTO6 w folderze…'}</span>
                <span className="text-xs text-muted-foreground">
                  {folder.name
                    ? 'Folder zapamiętany w przeglądarce'
                    : `Wybierz folder raz, np. ${PROTO_FOLDER.suggestedPath} (ścieżka w schowku)`}
                </span>
              </span>
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
