import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronDownIcon, FolderOpenIcon, RefreshCwIcon, TagIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ApiError } from '@/lib/api'
import { ensureFolderAccess, folderSavingSupported, pickFolder } from '@/lib/saved-folders'
import { formatReportDate } from '@/lib/report-freshness'
import { cn } from '@/lib/utils'

import { checkPriceFiles, priceFolderKey, usePriceFileOverview, usePriceFileWatch } from './price-files'

/**
 * Stan śledzenia cen z kasy (pliki TRACEART) w nagłówku wprowadzania danych: wybór folderu, zgoda na odczyt po
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
  const choose = () => withBusy(async () => (await pickFolder(key, 'read')) !== null)
  const allow = () => withBusy(() => ensureFolderAccess(key, 'read'))
  const checkNow = () => withBusy(async () => true)

  if (access === 'missing') {
    return (
      <Button
        variant="outline"
        onClick={choose}
        loading={busy}
        title="Folder, w którym system sklepu zapisuje pliki TRACEART ze zmianami cen na kasie"
      >
        <FolderOpenIcon />
        Ceny z kasy: wybierz folder
      </Button>
    )
  }
  if (access === 'prompt' || access === 'denied') {
    return (
      <Button
        variant="outline"
        onClick={allow}
        loading={busy}
        className="border-amber-400 bg-amber-100 text-amber-950 hover:bg-amber-200 dark:border-amber-700 dark:bg-amber-950/60 dark:text-amber-100"
        title="Przeglądarka potrzebuje zgody na odczyt folderu z plikami cen (wybierz „Zezwalaj przy każdej wizycie”)"
      >
        <TagIcon />
        Ceny z kasy: zezwól na odczyt
      </Button>
    )
  }

  const failing = watch.error !== null
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="outline"
            loading={busy || (watch.checking && access === 'unknown')}
            className={cn(
              failing &&
                'border-red-400 bg-red-100 text-red-950 hover:bg-red-200 dark:border-red-800 dark:bg-red-950/60 dark:text-red-100',
            )}
            title={watch.error ?? 'Ceny z plików TRACEART sprawdzane co 10 minut'}
          />
        }
      >
        <TagIcon />
        Ceny z kasy
        <span className={cn('text-muted-foreground', failing && 'text-current')}>
          {failing ? 'błąd' : latest ? formatReportDate(latest.fileTime) : 'brak plików'}
        </span>
        <ChevronDownIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80">
        <DropdownMenuGroup>
          <DropdownMenuLabel>Ceny z kasy (pliki TRACEART)</DropdownMenuLabel>
          <div className="space-y-1 px-2 pb-2 text-sm">
            {watch.error && <p className="font-medium text-destructive">{watch.error}</p>}
            {latest ? (
              <p>
                Ostatni plik: {formatReportDate(latest.fileTime)}, zmienione ceny: {latest.updatedCount} z{' '}
                {latest.articleCount}
              </p>
            ) : (
              <p className="text-muted-foreground">Jeszcze nie wczytano żadnego pliku.</p>
            )}
            {overview.data && overview.data.activePromotions > 0 && (
              <p>Artykuły w promocji z kasy: {overview.data.activePromotions}</p>
            )}
            <p className="text-xs text-muted-foreground">
              Folder sprawdzamy co 10 minut, gdy aplikacja jest otwarta. Cena z pliku nowszego niż raport kartoteki
              zastępuje cenę sprzedaży; promocja trafia do kolumny „Cena promo.”.
            </p>
          </div>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={checkNow} disabled={watch.checking}>
          <RefreshCwIcon />
          Sprawdź teraz
        </DropdownMenuItem>
        <DropdownMenuItem onClick={choose}>
          <FolderOpenIcon />
          Zmień folder
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
