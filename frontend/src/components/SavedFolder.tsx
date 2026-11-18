import { useState } from 'react'
import { FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { StoreLocationField } from '@/components/StoreLocationField'
import { Button } from '@/components/ui/button'
import { useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { folderSavingSupported, useSavedFolder, type SavedFolderSpec } from '@/lib/saved-folders'
import { folderLocationKey, useStoreLocations } from '@/lib/store-locations'
import { cn } from '@/lib/utils'

/**
 * Wybrany folder na plik danego rodzaju z przyciskiem zmiany. Folder zostaje zapamiętany w przeglądarce, więc
 * wybiera się go raz. Ścieżka folderu jest wspólna dla sklepu (na dysku sieciowym jest taka sama na każdym komputerze):
 * wpisuje się ją raz, a przy wyborze folderu trafia do schowka i wkleja się ją w pasek adresu okna wyboru.
 */
export function SavedFolder({
  folder,
  className,
  onPicked,
  pathFieldWhenMissing = false,
}: {
  folder: SavedFolderSpec
  className?: string
  /** Po wybraniu folderu, np. żeby zdjąć ostrzeżenie o jego braku. */
  onPicked?: () => void
  /** Pole ścieżki sklepu tylko, gdy sklep jej jeszcze nie ma (np. w kolejce plików: ścieżka jest już ustawiona). */
  pathFieldWhenMissing?: boolean
}) {
  const { name, loaded, pick } = useSavedFolder(folder.key)
  const [picking, setPicking] = useState(false)
  const store = useCurrentStore()
  const locations = useStoreLocations(folder.pathInSettings ? undefined : store?.id)
  const locationKey = folderLocationKey(folder.key)
  const path = (folder.pathInSettings ? undefined : locations.data?.[locationKey]) || folder.suggestedPath

  if (!folderSavingSupported) {
    return (
      <p className={cn('text-sm text-muted-foreground', className)}>
        Zapis do wybranego folderu działa w Chrome i Edge. Tutaj plik trzeba pobrać ręcznie.
      </p>
    )
  }

  const choose = async () => {
    // Bez await: schowek nie zużywa gestu kliknięcia, którego potrzebuje okno wyboru folderu.
    if (path) navigator.clipboard?.writeText(path).catch(() => {})
    setPicking(true)
    try {
      const handle = await pick()
      if (handle) {
        toast.success(`${folder.label} będzie zapisywany w folderze ${handle.name}`)
        onPicked?.()
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wybrać folderu')
    } finally {
      setPicking(false)
    }
  }

  return (
    <div className={cn('grid gap-2.5 rounded-xl border bg-muted/40 px-3 py-2.5', className)}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        {name ? (
          <FolderIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        ) : (
          <FolderOpenIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        )}
        <div className="min-w-0 flex-1 text-sm">
          {!loaded ? (
            <span className="text-muted-foreground">Sprawdzanie folderu…</span>
          ) : name ? (
            <>
              {folder.label} zapisze się w folderze <span className="font-medium">{name}</span>
            </>
          ) : (
            <>
              <span className="font-medium">Wybierz folder na: {folder.label.toLowerCase()}</span>
              {path && (
                <span className="block text-xs text-muted-foreground">
                  Ścieżkę <span className="font-mono">{path}</span> skopiujemy do schowka. W oknie wyboru wklej ją
                  (Ctrl+V) w pasek adresu u góry, naciśnij Enter i kliknij „Wybierz folder”.
                </span>
              )}
            </>
          )}
        </div>
        {/* Bez czekania na odczyt zapamiętanego folderu: wybór nowego go nie potrzebuje. */}
        <Button type="button" variant="outline" size="sm" loading={picking} onClick={choose}>
          {loaded && !name ? 'Wybierz folder' : 'Zmień'}
        </Button>
      </div>
      {!folder.pathInSettings && store && !(pathFieldWhenMissing && path) && (
        <StoreLocationField
          storeId={store.id}
          locationKey={locationKey}
          label="Ścieżka folderu w sklepie"
          className="font-mono"
          placeholderPath={folder.suggestedPath}
          hint={
            folder.suggestedPath
              ? `Wspólna dla wszystkich komputerów sklepu. Bez wpisu: ${folder.suggestedPath}`
              : 'Wspólna dla wszystkich komputerów sklepu, np. P:\\Dokumenty\\XML'
          }
        />
      )}
    </div>
  )
}
