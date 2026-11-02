import { RotateCcwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDateTime } from '@/features/ksef/format'
import { ApiError, downloadFile } from '@/lib/api'
import { usePriceChangeFolder, useProductFileFolder, useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, type SavedFolderSpec, saveApiFileToFolder } from '@/lib/saved-folders'
import { cn } from '@/lib/utils'

import { usePriceControlFiles } from './api'
import { XML_FOLDER } from './xml-save'

/**
 * Ustawienia zapisu wprowadzania danych: foldery w tej przeglądarce na plik XML faktury, plik nowych artykułów
 * (CADBEZP), protokoły (pliki PROTO) i zmianę cen (CTRLPRIX), wybrane raz, więc „Zapisz” zapisuje od razu bez pytania
 * o miejsce. Do tego ostatnie zmiany cen z ponownym zapisem. Nazw plików z programu sklepu pracownik nie widzi.
 */
export function SaveSettingsDialog({
  storeId,
  open,
  onOpenChange,
  missing = [],
  withXml = true,
  extraFolders = [],
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Foldery, których zabrakło przy ostatnim „Zapisz” (wyróżnione). */
  missing?: SavedFolderSpec[]
  /** Folder pliku XML faktury (dostawa z bazy go nie zapisuje). */
  withXml?: boolean
  /** Foldery innych plików ekranu (np. „Sprawdź PLU” w dostawie z bazy), na końcu listy. */
  extraFolders?: { folder: SavedFolderSpec; note: string }[]
}) {
  const productFolder = useProductFileFolder(storeId)
  const protoFolder = useProtoFolder(storeId)
  const priceChangeFolder = usePriceChangeFolder(storeId)
  const priceControlFiles = usePriceControlFiles(storeId, open)
  const isMissing = (folder: SavedFolderSpec) => missing.some((item) => item.key === folder.key)

  /** Ponowny zapis pliku CTRLPRIX z historii w folderze zmiany cen (bez folderu: pobranie). */
  const saveAgain = async (id: string) => {
    const path = `/stores/${storeId}/data-entry/price-control-files/${id}/file`
    try {
      if (await ensureFolderAccess(priceChangeFolder.key)) {
        const fileName = await saveApiFileToFolder(priceChangeFolder.key, path, 'CTRLPRIX.traite0')
        toast.success(`Zapisano ${fileName}`)
      } else {
        await downloadFile(path, 'CTRLPRIX.traite0')
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany cen')
    }
  }

  const folders = [
    ...(withXml ? [{ folder: XML_FOLDER, note: 'Plik dokumentu dla programu sklepu.' }] : []),
    { folder: productFolder, note: 'Nowe i zmienione artykuły, zapisywane razem z plikiem XML.' },
    {
      folder: protoFolder,
      note: 'Likwidacje, potrzeby sklepu i inne protokoły; ten sam folder co w garmażu i przyjęciach MM.',
    },
    {
      folder: priceChangeFolder,
      note: 'Zmienione ceny sprzedaży artykułów z kartoteki, zapisywane razem z plikiem XML.',
    },
    ...extraFolders,
  ]

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Ustawienia zapisu</DialogTitle>
          <DialogDescription>
            Foldery wybiera się raz w tej przeglądarce. Potem „Zapisz” od razu zapisuje pliki na miejscu.
          </DialogDescription>
        </DialogHeader>

        {missing.length > 0 && (
          <p
            role="alert"
            className="rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm font-medium text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100"
          >
            Wybierz zaznaczone foldery i kliknij „Zapisz” jeszcze raz.
          </p>
        )}

        <div className="grid gap-4">
          {folders.map(({ folder, note }) => (
            <div key={folder.key} className="grid gap-1.5">
              <p className="text-sm font-medium">{folder.label}</p>
              <SavedFolder
                folder={folder}
                className={cn(
                  isMissing(folder) && 'border-amber-400 bg-amber-100/60 dark:border-amber-600 dark:bg-amber-950/40',
                )}
              />
              <p className="text-xs text-muted-foreground">{note}</p>
            </div>
          ))}
        </div>

        {priceControlFiles.data && priceControlFiles.data.length > 0 && (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">
              Ostatnie zmiany cen ({priceControlFiles.data.length})
            </summary>
            <ul className="mt-2 divide-y rounded-lg border">
              {priceControlFiles.data.map((file) => (
                <li key={file.id} className="flex items-center gap-3 px-3 py-2">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">
                      {file.supplierName ?? file.supplierCode ?? 'Dostawca'}
                      {file.invoiceNumber ? ` · ${file.invoiceNumber}` : ''}
                    </p>
                    <p className="truncate text-xs text-muted-foreground">
                      {formatDateTime(file.createdAt)} · cen: {file.lineCount}
                      {file.createdBy ? ` · ${file.createdBy}` : ''}
                    </p>
                  </div>
                  <Button variant="outline" size="sm" onClick={() => saveAgain(file.id)} title={file.fileName}>
                    <RotateCcwIcon />
                    Zapisz ponownie
                  </Button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </DialogContent>
    </Dialog>
  )
}
