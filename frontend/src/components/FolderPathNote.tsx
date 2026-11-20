import { Link } from 'react-router'

import { useCurrentUser } from '@/features/auth/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { cn } from '@/lib/utils'

/**
 * Ścieżki folderów ustala aplikacja (Ustawienia sklepu → Ścieżki folderów): tam wpisuje się własną ścieżkę sklepu albo
 * przywraca domyślną. Samą ścieżkę widzi tylko właściciel, więc okna zapisu pokazują w jej miejscu ten dopisek.
 */
export function FolderPathNote({ className }: { className?: string }) {
  return (
    <p className={cn('text-xs text-muted-foreground', className)}>
      Ścieżkę folderu ustala aplikacja. Inna w tym sklepie:{' '}
      <Link to={storeSettingsPaths.folders} className="font-medium underline underline-offset-2">
        Ustawienia sklepu → Ścieżki folderów
      </Link>
      .
    </p>
  )
}

/** Opis „ścieżkę skopiujemy do schowka”: z samą ścieżką tylko dla właściciela. */
export function ClipboardPathHint({ path }: { path: string | null | undefined }) {
  const owner = useCurrentUser().owner
  if (!path) return null
  return (
    <span className="block text-xs text-muted-foreground">
      {owner ? (
        <>
          Ścieżkę <span className="font-mono">{path}</span> skopiujemy do schowka.
        </>
      ) : (
        'Ścieżkę folderu skopiujemy do schowka.'
      )}{' '}
      W oknie wyboru wklej ją (Ctrl+V) w pasek adresu u góry, naciśnij Enter i kliknij „Wybierz folder”.
    </span>
  )
}
