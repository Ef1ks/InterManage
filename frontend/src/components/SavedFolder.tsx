import { useState } from 'react'
import { FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api'
import { folderSavingSupported, useSavedFolder, type SavedFolderSpec } from '@/lib/saved-folders'
import { cn } from '@/lib/utils'

/**
 * Wybrany folder na plik danego rodzaju z przyciskiem zmiany. Folder zostaje zapamiętany w przeglądarce, więc
 * wybiera się go raz. Gdy rodzaj ma podpowiedzianą ścieżkę, przy wyborze trafia ona do schowka: folder za skrótem
 * (np. N:\avisoconfig na zablokowanym dysku N) otwiera się, wklejając ją w pasek adresu okna wyboru.
 */
export function SavedFolder({ folder, className }: { folder: SavedFolderSpec; className?: string }) {
  const { name, loaded, pick } = useSavedFolder(folder.key)
  const [picking, setPicking] = useState(false)

  if (!folderSavingSupported) {
    return (
      <p className={cn('text-sm text-muted-foreground', className)}>
        Zapis do wybranego folderu działa w Chrome i Edge. Tutaj plik trzeba pobrać ręcznie.
      </p>
    )
  }

  const choose = async () => {
    // Bez await: schowek nie zużywa gestu kliknięcia, którego potrzebuje okno wyboru folderu.
    if (folder.suggestedPath) navigator.clipboard?.writeText(folder.suggestedPath).catch(() => {})
    setPicking(true)
    try {
      const handle = await pick()
      if (handle) toast.success(`${folder.label} będzie zapisywany w folderze ${handle.name}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wybrać folderu')
    } finally {
      setPicking(false)
    }
  }

  return (
    <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border bg-muted/40 px-3 py-2.5', className)}>
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
            {folder.suggestedPath && (
              <span className="block text-xs text-muted-foreground">
                Ścieżkę <span className="font-mono">{folder.suggestedPath}</span> skopiujemy do schowka. W oknie wyboru
                wklej ją (Ctrl+V) w pasek adresu u góry, naciśnij Enter i kliknij „Wybierz folder”.
              </span>
            )}
          </>
        )}
      </div>
      <Button type="button" variant="outline" size="sm" loading={picking} disabled={!loaded} onClick={choose}>
        {name ? 'Zmień' : 'Wybierz folder'}
      </Button>
    </div>
  )
}
