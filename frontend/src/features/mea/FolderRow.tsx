import { FolderIcon, FolderOpenIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { ClipboardPathHint, FolderPathNote } from '@/components/FolderPathNote'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api'
import { type FolderMode, useSavedFolder } from '@/lib/saved-folders'
import { folderLocationKey, useStoreLocations } from '@/lib/store-locations'

/**
 * Folder na serwerze sklepu wybrany raz w przeglądarce: ścieżka wspólna dla sklepu (pole), a przy wyborze trafia ona
 * do schowka, żeby wkleić ją w pasek adresu okna wyboru. `mode` mówi, czy aplikacja tylko czyta, czy też zapisuje.
 */
export function FolderRow({
  storeId,
  spec,
  mode,
  doneText,
  onPicked,
}: {
  storeId: string
  spec: { key: string; label: string; suggestedPath: string }
  mode: FolderMode
  /** Dawne pole ścieżki (ścieżkę ustala teraz aplikacja, Ustawienia sklepu → Ścieżki folderów). */
  pathLabel?: string
  pathHint?: string
  /** Opis wybranego folderu, np. „Dziennik czytany z folderu”. */
  doneText: string
  onPicked?: () => void
}) {
  const { name, loaded, pick } = useSavedFolder(spec.key, mode)
  const locations = useStoreLocations(storeId)
  const path = locations.data?.[folderLocationKey(spec.key)] || spec.suggestedPath
  const [picking, setPicking] = useState(false)

  const choose = async () => {
    // Bez await: schowek nie zużywa gestu kliknięcia, którego potrzebuje okno wyboru folderu.
    if (path) navigator.clipboard?.writeText(path).catch(() => {})
    setPicking(true)
    try {
      const handle = await pick()
      if (handle) {
        toast.success(`${spec.label}: folder ${handle.name}`)
        onPicked?.()
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wybrać folderu')
    } finally {
      setPicking(false)
    }
  }

  return (
    <div className="grid gap-2">
      <FolderPathNote />
      <div className="flex flex-wrap items-center gap-3 rounded-xl border bg-muted/40 px-3 py-2.5 text-sm">
        {name ? (
          <FolderIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        ) : (
          <FolderOpenIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        )}
        <span className="min-w-0 flex-1">
          {!loaded ? (
            <span className="text-muted-foreground">Sprawdzanie folderu…</span>
          ) : name ? (
            <>
              {doneText} <span className="font-medium">{name}</span>
            </>
          ) : (
            <>
              <span className="font-medium">Wybierz folder</span>
              <ClipboardPathHint path={path} />
            </>
          )}
        </span>
        <Button variant="outline" size="sm" onClick={choose} loading={picking}>
          {name ? 'Zmień folder' : 'Wybierz folder'}
        </Button>
      </div>
    </div>
  )
}
