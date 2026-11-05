import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { TagIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api'
import { DEFAULT_FOLDER } from '@/lib/proto-files'
import { ensureFolderAccess, folderSavingSupported, pickFolder } from '@/lib/saved-folders'
import { formatReportDate } from '@/lib/report-freshness'
import { cn } from '@/lib/utils'

import { checkPriceFiles, priceFolderKey, usePriceFileOverview, usePriceFileWatch } from './price-files'

/**
 * Stan śledzenia cen z kasy (pliki TRACEART) w ustawieniach wprowadzania danych: wybór folderu, zgoda na odczyt po
 * ponownym otwarciu przeglądarki, ostatni wczytany plik i ręczne sprawdzenie.
 */
export function PriceFileStatus({ storeId }: { storeId: string }) {
  const watch = usePriceFileWatch()
  const overview = usePriceFileOverview(storeId)
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)

  if (!folderSavingSupported) return null

  const key = priceFolderKey(storeId)
  const access = watch.storeId === storeId ? watch.access : 'unknown'
  const latest = overview.data?.latest[0] ?? null
  const needsAccess = access === 'prompt' || access === 'denied'
  const failing = watch.error !== null

  const withBusy = async (action: () => Promise<boolean>) => {
    setBusy(true)
    try {
      if (await action()) await checkPriceFiles(storeId, queryClient)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się użyć folderu')
    } finally {
      setBusy(false)
    }
  }

  // Okno wyboru i okno zgody działają tylko zaraz po kliknięciu, więc najpierw one, potem sprawdzenie.
  const choose = () => {
    // Domyślna ścieżka do schowka (bez await, żeby nie zużyć gestu kliknięcia): wkleja się ją w pasek adresu okna.
    navigator.clipboard?.writeText(DEFAULT_FOLDER).catch(() => {})
    return withBusy(async () => (await pickFolder(key, 'read')) !== null)
  }
  const allow = () => withBusy(() => ensureFolderAccess(key, 'read'))
  const checkNow = () => withBusy(async () => true)

  return (
    <div className="grid gap-1.5">
      <p className="text-sm font-medium">Ceny z kasy</p>
      <div
        className={cn(
          'flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border bg-muted/40 px-3 py-2.5 text-sm',
          needsAccess &&
            'border-amber-400 bg-amber-200 text-amber-950 dark:border-amber-700 dark:bg-amber-900 dark:text-amber-50',
          !needsAccess &&
            failing &&
            'border-red-400 bg-red-200 text-red-950 dark:border-red-700 dark:bg-red-900 dark:text-red-50',
        )}
      >
        <TagIcon aria-hidden className="size-4 shrink-0 opacity-70" />
        <div className="min-w-0 flex-1">
          {access === 'missing' ? (
            <>
              <span className="font-medium">Wybierz folder z plikami cen</span>
              <span className="block text-xs text-muted-foreground">
                Ścieżkę <span className="font-mono">{DEFAULT_FOLDER}</span> skopiujemy do schowka. W oknie wyboru wklej
                ją (Ctrl+V) w pasek adresu u góry, naciśnij Enter i kliknij „Wybierz folder”.
              </span>
            </>
          ) : needsAccess ? (
            <>
              <span className="font-medium">Przeglądarka potrzebuje zgody na odczyt folderu</span>
              <span className="block text-xs">Wybierz „Zezwalaj przy każdej wizycie”.</span>
            </>
          ) : access === 'unknown' ? (
            <span className="text-muted-foreground">Sprawdzanie folderu…</span>
          ) : (
            <>
              {watch.error && <span className="block font-medium">{watch.error}</span>}
              {watch.progress && (
                <span className="block tabular-nums">
                  Wczytywanie historii: {watch.progress.done} / {watch.progress.total}
                </span>
              )}
              {latest ? (
                <span className="block tabular-nums">
                  Ostatni plik: {formatReportDate(latest.fileTime)}, zmienione ceny: {latest.updatedCount} z{' '}
                  {latest.articleCount}
                </span>
              ) : (
                <span className={cn('block', !failing && 'text-muted-foreground')}>
                  Jeszcze nie wczytano żadnego pliku.
                </span>
              )}
              {overview.data && overview.data.activePromotions > 0 && (
                <span className="block tabular-nums">Artykuły w promocji z kasy: {overview.data.activePromotions}</span>
              )}
            </>
          )}
        </div>
        {access === 'missing' ? (
          <Button type="button" variant="outline" size="sm" loading={busy} onClick={choose}>
            Wybierz folder
          </Button>
        ) : needsAccess ? (
          <Button type="button" variant="outline" size="sm" loading={busy} onClick={allow}>
            Zezwól na odczyt
          </Button>
        ) : (
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              loading={busy || watch.checking}
              disabled={access === 'unknown'}
              onClick={checkNow}
            >
              Sprawdź teraz
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={choose}>
              Zmień
            </Button>
          </div>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        Folder i jego podfolder BACKUP sprawdzamy od razu po wejściu i co 10 minut, gdy aplikacja jest otwarta; pliki z
        dni bez aplikacji wczytują się same, od najstarszego. Cena z pliku nowszego niż raport kartoteki zastępuje cenę
        sprzedaży; w trakcie promocji cena sprzedaży to cena promocyjna, na pomarańczowo.
      </p>
    </div>
  )
}
