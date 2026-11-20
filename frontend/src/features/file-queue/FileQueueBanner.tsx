import { InboxIcon } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useCurrentStore } from '@/features/stores/current-store'
import { onFileQueued, setFileQueueEnabled, setFileQueueStore, useFileQueueEnabled } from '@/lib/file-queue'

import { fileQueueKey, useFileQueueCount } from './api'
import { FileQueueDialog } from './FileQueueDialog'

const filesLabel = (count: number) =>
  count === 1
    ? '1 plik'
    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
      ? `${count} pliki`
      : `${count} plików`

/**
 * Pasek pod nagłówkiem aplikacji: kolejka plików bieżącego sklepu włączona w tej przeglądarce (pliki nie idą do
 * folderów) albo pliki w kolejce czekające na zapis na komputerze sklepu. Ustawia też sklep, do którego kolejki
 * trafiają pliki (`lib/file-queue`).
 */
export function FileQueueBanner() {
  const store = useCurrentStore()
  const storeId = store?.id
  const queryClient = useQueryClient()
  const enabled = useFileQueueEnabled(storeId)
  const { data: count = 0 } = useFileQueueCount(storeId)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    setFileQueueStore(storeId ?? null)
  }, [storeId])

  useEffect(
    () =>
      onFileQueued((queuedStoreId, { viaAgent }) => {
        void queryClient.invalidateQueries({ queryKey: fileQueueKey(queuedStoreId) })
        // Plik dla agenta sklepu: potwierdzenie (albo zapas z przeglądarki) pokazuje lib/agent-save.
        if (viaAgent) return
        toast.info('Plik czeka w kolejce sklepu', {
          description: 'Zapisze się w folderze sklepu po „Zapisz wszystkie” na jego komputerze.',
        })
      }),
    [queryClient],
  )

  if (!storeId || (!enabled && count === 0)) return null
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-violet-300 bg-violet-100 px-4 py-2.5 text-violet-950 md:px-6 dark:border-violet-800 dark:bg-violet-950/70 dark:text-violet-50">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <InboxIcon className="size-5 shrink-0 text-violet-700 dark:text-violet-300" aria-hidden />
        <p className="min-w-0 text-sm">
          {enabled ? (
            <>
              <span className="font-semibold">Pliki sklepu {store.number} idą do kolejki.</span>{' '}
              <span className="text-violet-900 dark:text-violet-200">
                {count > 0 ? `Czeka ${filesLabel(count)}. ` : ''}
                Na koniec zapisz je na komputerze sklepu.
              </span>
            </>
          ) : (
            <>
              <span className="font-semibold">
                W kolejce sklepu {store.number} czeka {filesLabel(count)}.
              </span>{' '}
              <span className="text-violet-900 dark:text-violet-200">Zapisz je w folderach sklepu.</span>
            </>
          )}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {enabled && (
          <Button variant="ghost" onClick={() => setFileQueueEnabled(storeId, false)}>
            Wyłącz kolejkę
          </Button>
        )}
        <Button variant="outline" className="bg-background/70" onClick={() => setOpen(true)}>
          {enabled ? 'Kolejka' : 'Zapisz wszystkie'}
          {count > 0 && ` (${count})`}
        </Button>
      </div>
      <FileQueueDialog storeId={storeId} open={open} onOpenChange={setOpen} />
    </div>
  )
}
