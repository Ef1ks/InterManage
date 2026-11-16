import { RefreshCwIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { folderSavingSupported } from '@/lib/saved-folders'

import { athosFolderSpec, type AthosWatch, comrnisFolderSpec, useAthosStatus } from './athos-history'
import { FolderRow } from './FolderRow'

const dateTime = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—'

/**
 * Foldery ATHOS na serwerze sklepu, wybrane raz w przeglądarce (ścieżki wspólne dla sklepu): appres\comrnis, do
 * którego „Wyślij do ATHOS” zapisuje zamówienia, i appres\athos2000 z dziennikiem Histo.Log, z którego liczą się
 * statusy „Do wysłania” / „Wysłane”. Pod spodem stan odczytów dziennika.
 */
export function AthosDialog({
  storeId,
  open,
  onOpenChange,
  watch,
  onCheck,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  watch: AthosWatch
  onCheck: () => void
}) {
  const status = useAthosStatus(storeId)
  const data = status.data

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>ATHOS</DialogTitle>
          <DialogDescription>
            Foldery na serwerze sklepu, jak w eMEA. Wybierz je raz na każdym komputerze, ścieżki zapamiętuje sklep.
          </DialogDescription>
        </DialogHeader>

        {!folderSavingSupported ? (
          <p className="text-sm text-muted-foreground">Foldery działają w Chrome i Edge.</p>
        ) : (
          <div className="grid gap-5">
            <section className="grid gap-2">
              <h3 className="text-sm font-semibold">Zamówienia do ATHOS</h3>
              <FolderRow
                storeId={storeId}
                spec={comrnisFolderSpec(storeId)}
                mode="readwrite"
                pathLabel="Ścieżka folderu comrnis w sklepie"
                pathHint="Np. \\SR10023\appres\comrnis. Tu trafia plik R… z „Wyślij do ATHOS”."
                doneText="Zamówienia zapisują się w folderze"
              />
            </section>

            <section className="grid gap-2">
              <h3 className="text-sm font-semibold">Statusy z ATHOS (dziennik Histo.Log)</h3>
              <FolderRow
                storeId={storeId}
                spec={athosFolderSpec(storeId)}
                mode="read"
                pathLabel="Ścieżka folderu athos2000 w sklepie"
                pathHint="Np. \\SR10023\appres\athos2000. Aplikacja tylko czyta z niego Histo.Log."
                doneText="Dziennik czytany z folderu"
                onPicked={onCheck}
              />

              {watch.access === 'prompt' && (
                <p className="rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900/70 dark:text-amber-50">
                  Przeglądarka czeka na zgodę na odczyt folderu. Kliknij „Sprawdź teraz”.
                </p>
              )}
              {watch.error && (
                <p className="rounded-lg bg-red-200 px-3 py-2 text-sm text-red-950 dark:bg-red-900/70 dark:text-red-50">
                  {watch.error}
                </p>
              )}

              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
                <dt className="text-muted-foreground">Ostatni odczyt</dt>
                <dd>
                  {data?.read ? (
                    <>
                      {dateTime(data.importedAt)}
                      {data.importedBy && <span className="text-muted-foreground"> · {data.importedBy}</span>}
                    </>
                  ) : (
                    'jeszcze nie odczytano'
                  )}
                </dd>
                <dt className="text-muted-foreground">Plik zmieniony</dt>
                <dd>{dateTime(data?.fileModified ?? null)}</dd>
                <dt className="text-muted-foreground">Wysyłki MEA</dt>
                <dd className="tabular-nums">
                  {data?.sendCount ?? 0}
                  {data?.read && data.addedCount > 0 && (
                    <span className="text-muted-foreground"> (ostatnio nowych {data.addedCount})</span>
                  )}
                </dd>
                <dt className="text-muted-foreground">Ostatnia wysyłka</dt>
                <dd>{dateTime(data?.lastSentAt ?? null)}</dd>
              </dl>

              <div className="flex justify-end">
                <Button onClick={onCheck} loading={watch.checking}>
                  <RefreshCwIcon />
                  Sprawdź teraz
                </Button>
              </div>
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
