import { CheckIcon, SendIcon, Trash2Icon, TriangleAlertIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { api, ApiError, fetchNamedFile } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { ensureFolderAccess, folderSavingSupported, saveToFolder } from '@/lib/saved-folders'
import type { MeaQueueItem } from '@/lib/types'

import { useAthosQueue, useAthosQueueRemoval } from './api'
import { comrnisFolderSpec } from './athos-history'
import { FolderRow } from './FolderRow'
import { meaDocumentPath } from './paths'
import { reservationClosed } from './reservation'

const shortTime = (iso: string) =>
  new Date(iso).toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

const shortDate = (iso: string) => new Date(iso).toLocaleDateString('pl-PL', { day: 'numeric', month: 'numeric' })

/** Wynik wysyłki jednego pliku w tym oknie: zapisany albo błąd z opisem. */
type Result = { ok: true } | { ok: false; message: string }

/**
 * Kolejka ATHOS sklepu: pliki zamówień MEA zrobione na dowolnym komputerze czekają w bazie, a tutaj, na komputerze
 * z dostępem do folderu comrnis, „Wyślij wszystkie” zapisuje je po kolei. Każdy plik dopiero po zapisie w folderze
 * liczy się jako wysłany; nieudany zostaje w kolejce. Pliki MEA po końcu rezerwacji są pomijane, bo ATHOS ich nie
 * przyjmie (można je tylko usunąć).
 */
export function AthosQueueDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const queue = useAthosQueue(storeId)
  const { remove, restore } = useAthosQueueRemoval(storeId)
  const [sending, setSending] = useState(false)
  const [results, setResults] = useState<Record<string, Result>>({})
  const spec = comrnisFolderSpec(storeId)
  const today = todayIso()
  const items = queue.data ?? []
  const closed = (item: MeaQueueItem) => reservationClosed(item.reservationTo, today)
  const sendable = items.filter((item) => !closed(item))

  const sendAll = async () => {
    setSending(true)
    try {
      // Zgoda na zapis tylko zaraz po kliknięciu, więc przed pierwszym żądaniem do serwera.
      if (!(await ensureFolderAccess(spec.key, 'readwrite'))) {
        toast.error('Wybierz folder comrnis i zezwól na zapis, żeby wysłać zamówienia do ATHOS')
        return
      }
      const next: Record<string, Result> = {}
      let saved = 0
      for (const item of sendable) {
        try {
          const { fileName, blob } = await fetchNamedFile(
            `/stores/${storeId}/mea/athos-queue/${item.id}/file`,
            item.fileName,
          )
          await saveToFolder(spec.key, fileName, blob)
          saved++
          try {
            await api(`/stores/${storeId}/mea/athos-orders/${item.id}/saved`, { method: 'POST' })
            next[item.id] = { ok: true }
          } catch {
            next[item.id] = {
              ok: false,
              message: 'Plik jest w folderze, ale aplikacja nie zapisała, że MEA wysłano. Nie wysyłaj go drugi raz.',
            }
          }
        } catch (error) {
          next[item.id] = {
            ok: false,
            message: error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku w folderze',
          }
        }
        setResults((current) => ({ ...current, [item.id]: next[item.id] }))
      }
      const failed = Object.values(next).filter((result) => !result.ok).length
      if (failed === 0) toast.success(`Wysłano do ATHOS ${saved} ${saved === 1 ? 'plik' : 'pliki'} MEA`)
      else toast.warning(`Wysłano ${saved} z ${sendable.length}. Nieudane zostały w kolejce, opis przy pliku.`)
      void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'mea'] })
    } finally {
      setSending(false)
    }
  }

  const removeItem = (item: MeaQueueItem) =>
    remove.mutate(item.id, {
      onSuccess: () =>
        toast(`Usunięto MEA ${item.number} z kolejki`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(item.id, {
                onError: (error) =>
                  toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić zamówienia'),
              }),
          },
        }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć z kolejki'),
    })

  return (
    <Dialog
      open={open}
      onOpenChange={(value) => {
        if (!value) setResults({})
        onOpenChange(value)
      }}
    >
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Kolejka ATHOS</DialogTitle>
          <DialogDescription>
            Zamówienia MEA tego sklepu zrobione na dowolnym komputerze. Na komputerze z dostępem do ATHOS „Wyślij
            wszystkie” zapisze je w folderze comrnis, skąd ATHOS sam je weźmie.
          </DialogDescription>
        </DialogHeader>

        {queue.isPending ? (
          <p className="py-4 text-sm text-muted-foreground">Wczytywanie kolejki…</p>
        ) : items.length === 0 ? (
          <p className="rounded-xl border bg-muted/40 px-4 py-6 text-center text-sm text-muted-foreground">
            {Object.values(results).some((result) => result.ok)
              ? 'Wszystkie pliki wysłane. Kolejka jest pusta.'
              : 'Kolejka jest pusta. Zamówienie trafia tu z dokumentu MEA: „Wyślij do ATHOS” → „Dodaj do kolejki”.'}
          </p>
        ) : (
          <ul className="grid max-h-[50dvh] gap-2 overflow-auto">
            {items.map((item) => {
              const result = results[item.id]
              const isClosed = closed(item)
              return (
                <li key={item.id} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-baseline gap-x-2">
                      <Link
                        to={meaDocumentPath(item.number)}
                        onClick={() => onOpenChange(false)}
                        className="font-semibold hover:underline"
                      >
                        MEA {item.number}
                      </Link>
                      <span className="truncate text-muted-foreground">{item.title}</span>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      <span className="font-medium text-foreground tabular-nums">{item.colis} COLI</span> ·{' '}
                      {item.positions} poz. · {shortTime(item.queuedAt)}
                      {item.createdBy && ` · ${item.createdBy}`}
                      {item.reservationTo && !isClosed && ` · rezerwacja do ${shortDate(item.reservationTo)}`}
                    </div>
                    {isClosed && (
                      <p className="mt-1 flex items-center gap-1.5 text-xs font-medium text-red-700 dark:text-red-300">
                        <TriangleAlertIcon className="size-3.5 shrink-0" />
                        Rezerwacja zakończyła się {item.reservationTo && shortDate(item.reservationTo)}. ATHOS nie
                        przyjmie już tego zamówienia, wysyłka je pomija.
                      </p>
                    )}
                    {result && !result.ok && (
                      <p className="mt-1 text-xs font-medium text-red-700 dark:text-red-300">{result.message}</p>
                    )}
                  </div>
                  {result?.ok ? (
                    <CheckIcon aria-label="Wysłano" className="size-5 shrink-0 text-emerald-600" />
                  ) : (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Usuń MEA ${item.number} z kolejki`}
                      title="Usuń z kolejki"
                      onClick={() => removeItem(item)}
                      disabled={sending}
                    >
                      <Trash2Icon />
                    </Button>
                  )}
                </li>
              )
            })}
          </ul>
        )}

        {folderSavingSupported ? (
          <FolderRow
            storeId={storeId}
            spec={spec}
            mode="readwrite"
            pathLabel="Ścieżka folderu comrnis w sklepie"
            pathHint="Np. \\SR10023\appres\comrnis"
            doneText="Zamówienia zapiszą się w folderze"
          />
        ) : (
          <p className="text-sm text-muted-foreground">Zapis do folderu ATHOS działa w Chrome i Edge.</p>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          <Button
            size="xl"
            onClick={sendAll}
            loading={sending}
            disabled={!folderSavingSupported || sendable.length === 0}
          >
            <SendIcon />
            Wyślij wszystkie do ATHOS{sendable.length > 0 && ` (${sendable.length})`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
