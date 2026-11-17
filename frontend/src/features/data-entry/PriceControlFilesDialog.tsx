import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { CheckIcon, FolderSearchIcon, SaveIcon, Trash2Icon, Undo2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDateTime } from '@/features/ksef/format'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, folderSavingSupported, saveToFolder } from '@/lib/saved-folders'
import type { PriceControlFileItem } from '@/lib/types'
import { cn } from '@/lib/utils'

import { type PriceControlAction, usePriceControlChange, usePriceControlFiles } from './api'
import { checkPriceControlFiles, type FolderStatus, usePriceControlWatch } from './price-control-watch'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

const FOLDER_STATUS: Record<FolderStatus, { label: string; className: string }> = {
  waiting: {
    label: 'w folderze, czeka na program sklepu',
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  missing: {
    label: 'nie ma w folderze, zapisz ponownie',
    className: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
}

/**
 * Zmiany cen z wprowadzania danych i dostawy z bazy: wszystkie niewgrane do programu sklepu i ostatnie wgrane. Wgranie
 * potwierdza folder plików protokołów (program zmienia końcówkę pliku po wczytaniu), a gdy przeglądarka go nie widzi,
 * oznacza się je ręcznie. Usunięcie z listy to znacznik z „Przywróć”.
 */
export function PriceControlFilesDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const files = usePriceControlFiles(storeId, open)
  const change = usePriceControlChange(storeId)
  const watch = usePriceControlWatch()
  const protoFolder = useProtoFolder(storeId)
  const [saving, setSaving] = useState<string | null>(null)

  const pending = files.data?.pending ?? []
  const loaded = files.data?.loaded ?? []
  const statuses = watch.storeId === storeId ? watch.statuses : {}

  /** Kliknięcie daje zgodę na folder (okno zgody działa tylko po geście), potem sprawdzenie. */
  const checkFolder = async () => {
    if (!(await ensureFolderAccess(protoFolder.key, 'read'))) {
      toast.error('Brak dostępu do folderu protokołów', {
        description: 'Wybierz folder w ustawieniach zapisu (koło zębate we wprowadzaniu danych).',
      })
      return
    }
    await checkPriceControlFiles(storeId, queryClient)
  }

  /**
   * Ta sama treść pod nazwą z bieżącą godziną (serwer zapamiętuje nową nazwę): program sklepu nie wczytuje drugi raz
   * nazwy, którą już kiedyś widział. Zgodę na folder bierzemy od razu po kliknięciu, dopiero potem żądanie.
   */
  const saveAgain = async (file: PriceControlFileItem) => {
    setSaving(file.id)
    try {
      const folderReady = await ensureFolderAccess(protoFolder.key)
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/price-control-files/${file.id}/resave`,
        {},
        file.fileName,
      )
      if (folderReady) {
        await saveToFolder(protoFolder.key, fileName, blob)
        toast.success('Zapisano zmianę cen w folderze')
      } else {
        saveBlob(blob, fileName)
      }
      void checkPriceControlFiles(storeId, queryClient)
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się zapisać zmiany cen'))
    } finally {
      setSaving(null)
    }
  }

  const run = (file: PriceControlFileItem, action: PriceControlAction) =>
    change.mutate(
      { id: file.id, action },
      {
        onSuccess: () => {
          const undo: Partial<Record<PriceControlAction, [string, string, PriceControlAction]>> = {
            delete: ['Usunięto zmianę cen z listy', 'Przywróć', 'restore'],
            'mark-loaded': ['Oznaczono jako wgraną', 'Cofnij', 'unmark-loaded'],
            'unmark-loaded': ['Zmiana cen wróciła do niewgranych', 'Cofnij', 'mark-loaded'],
          }
          const entry = undo[action]
          if (!entry) return
          toast(entry[0], {
            action: {
              label: entry[1],
              onClick: () =>
                change.mutate(
                  { id: file.id, action: entry[2] },
                  { onError: (error) => toast.error(errorMessage(error, 'Nie udało się cofnąć zmiany')) },
                ),
            },
          })
        },
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się zmienić zmiany cen')),
      },
    )

  const title = (file: PriceControlFileItem) =>
    `${file.supplierName ?? file.supplierCode ?? 'Bez dostawcy'}${file.invoiceNumber ? ` · ${file.invoiceNumber}` : ''}`

  const details = (file: PriceControlFileItem) =>
    `${formatDateTime(file.createdAt)} · cen: ${file.lineCount}${file.createdBy ? ` · ${file.createdBy}` : ''}`

  const loadedNote = (file: PriceControlFileItem) => {
    if (!file.loadedAt) return ''
    if (file.loadedManually) {
      return `oznaczona ręcznie ${formatDateTime(file.loadedAt)}${file.loadedBy ? ` · ${file.loadedBy}` : ''}`
    }
    const times = file.processedCount && file.processedCount > 1 ? ` · wczytana ${file.processedCount} razy` : ''
    return `wgrana ${formatDateTime(file.loadedAt)}${times}`
  }

  const actionButtons = (file: PriceControlFileItem) => (
    <div className="flex shrink-0 gap-1">
      <Button
        variant="outline"
        size="sm"
        loading={saving === file.id}
        disabled={saving !== null}
        onClick={() => void saveAgain(file)}
        title="Zapisz plik zmiany cen ponownie w folderze protokołów"
      >
        <SaveIcon />
        Zapisz ponownie
      </Button>
      {file.loadedAt === null ? (
        <Button
          variant="outline"
          size="sm"
          disabled={change.isPending}
          onClick={() => run(file, 'mark-loaded')}
          title="Program sklepu wczytał ją na innym komputerze"
        >
          <CheckIcon />
          Wgrana
        </Button>
      ) : (
        <Button
          variant="ghost"
          size="icon-sm"
          disabled={change.isPending}
          onClick={() => run(file, 'unmark-loaded')}
          aria-label="Przywróć do niewgranych"
          title="Przywróć do niewgranych"
        >
          <Undo2Icon />
        </Button>
      )}
      <Button
        variant="ghost"
        size="icon-sm"
        disabled={change.isPending}
        onClick={() => run(file, 'delete')}
        aria-label="Usuń z listy"
        title="Usuń z listy"
      >
        <Trash2Icon />
      </Button>
    </div>
  )

  const folderNote = () => {
    if (!folderSavingSupported) return 'Ta przeglądarka nie sprawdza folderu: oznaczaj wgrane zmiany cen ręcznie.'
    if (watch.storeId !== storeId || watch.access === 'unknown') return null
    if (watch.access === 'missing') return 'Nie wybrano folderu protokołów: wybierz go w ustawieniach zapisu.'
    if (watch.access !== 'granted') return 'Przeglądarka czeka na zgodę na odczyt folderu: kliknij „Sprawdź folder”.'
    if (watch.error) return watch.error
    return null
  }
  const note = folderNote()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="grid-cols-[minmax(0,1fr)] gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Zmiany cen</DialogTitle>
          <DialogDescription>
            Zmiana cen jest wgrana, gdy program sklepu wczyta jej plik z folderu protokołów. Folder sprawdza się sam co
            5 minut.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">
            {watch.storeId === storeId && watch.checkedAt
              ? `Sprawdzono ${formatDateTime(new Date(watch.checkedAt).toISOString())}`
              : 'Folderu jeszcze nie sprawdzono'}
          </p>
          {folderSavingSupported && (
            <Button variant="outline" size="sm" loading={watch.checking} onClick={() => void checkFolder()}>
              <FolderSearchIcon />
              Sprawdź folder
            </Button>
          )}
        </div>
        {note && (
          <p
            role="alert"
            className="rounded-lg border border-amber-300 bg-amber-100 px-3 py-2 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100"
          >
            {note}
          </p>
        )}

        <section aria-label="Niewgrane" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Niewgrane ({pending.length})</h3>
          <ul className="max-h-[36vh] divide-y overflow-y-auto rounded-lg border">
            {pending.map((file) => {
              const status = statuses[file.id]
              return (
                <li key={file.id} className="flex items-center gap-3 px-3 py-2 text-sm">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{title(file)}</p>
                    <p className="truncate text-xs text-muted-foreground">{details(file)}</p>
                    {status && (
                      <span
                        className={cn(
                          'mt-1 inline-block rounded-md px-1.5 py-0.5 text-xs font-medium',
                          FOLDER_STATUS[status].className,
                        )}
                      >
                        {FOLDER_STATUS[status].label}
                      </span>
                    )}
                  </div>
                  {actionButtons(file)}
                </li>
              )
            })}
            {pending.length === 0 && (
              <li className="px-3 py-6 text-center text-sm text-muted-foreground">
                {files.isLoading ? 'Wczytywanie…' : 'Wszystkie zmiany cen są wgrane.'}
              </li>
            )}
          </ul>
        </section>

        {loaded.length > 0 && (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">Ostatnie wgrane ({loaded.length})</summary>
            <ul className="mt-2 max-h-[30vh] divide-y overflow-y-auto rounded-lg border">
              {loaded.map((file) => (
                <li key={file.id} className="flex items-center gap-3 px-3 py-2">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{title(file)}</p>
                    <p className="truncate text-xs text-muted-foreground">{details(file)}</p>
                    <p className="truncate text-xs font-medium text-emerald-800 dark:text-emerald-300">
                      {loadedNote(file)}
                    </p>
                  </div>
                  {actionButtons(file)}
                </li>
              ))}
            </ul>
          </details>
        )}
      </DialogContent>
    </Dialog>
  )
}

/**
 * Baner nad ekranem, gdy w sklepie są niewgrane zmiany cen: pracownik od razu wie, że program sklepu czegoś nie
 * wczytał, i jednym kliknięciem widzi listę.
 */
export function PriceControlPendingBanner({ storeId, count }: { storeId: string; count: number }) {
  const [open, setOpen] = useState(false)
  if (count === 0) return null
  return (
    <>
      <div
        role="status"
        className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-100 px-4 py-2.5 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100"
      >
        <span className="font-medium">Zmiany cen czekające na wgranie do programu sklepu: {count}</span>
        <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
          Pokaż zmiany cen
        </Button>
      </div>
      <PriceControlFilesDialog storeId={storeId} open={open} onOpenChange={setOpen} />
    </>
  )
}
