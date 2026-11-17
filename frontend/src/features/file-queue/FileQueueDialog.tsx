import { CheckIcon, HardDriveDownloadIcon, Trash2Icon } from 'lucide-react'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { SwitchField } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { PLU_FOLDER, NOTE_FILE_FOLDER } from '@/features/base-delivery/base-rows'
import { LST_FOLDER } from '@/features/data-entry/lst-file'
import { XML_FOLDER } from '@/features/data-entry/xml-save'
import { PRO_FOLDER } from '@/features/leaflet/leaflet-rows'
import { useCurrentStore } from '@/features/stores/current-store'
import { api, ApiError, fetchNamedFile } from '@/lib/api'
import { QUEUE_FOLDER_LABELS, setFileQueueEnabled, useFileQueueEnabled } from '@/lib/file-queue'
import { useProductFileFolder, useProtoFolder } from '@/lib/proto-files'
import { folderSavingSupported, requestFolderAccess, type SavedFolderSpec, writeToFolder } from '@/lib/saved-folders'
import type { FileQueueItem } from '@/lib/types'

import { fileQueueKey, useFileQueue, useFileQueueRemoval } from './api'

const shortTime = (iso: string) =>
  new Date(iso).toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

const sizeLabel = (bytes: number) =>
  bytes < 1024
    ? `${bytes} B`
    : bytes < 1024 * 1024
      ? `${Math.round(bytes / 1024)} kB`
      : `${(bytes / 1048576).toFixed(1)} MB`

/** Wynik zapisu jednego pliku w tym oknie. */
type Result = { ok: true } | { ok: false; message: string }

/** Folder zapisu dla klucza z kolejki, z podpowiedzią ścieżki jak w narzędziu, które plik utworzyło. */
function useQueueFolders(storeId: string): (key: string, fallbackLabel: string) => SavedFolderSpec {
  const proto = useProtoFolder(storeId)
  const product = useProductFileFolder(storeId)
  const known: Record<string, SavedFolderSpec> = {
    [proto.key]: proto,
    [product.key]: product,
    [XML_FOLDER.key]: XML_FOLDER,
    [LST_FOLDER.key]: LST_FOLDER,
    [PRO_FOLDER.key]: PRO_FOLDER,
    [NOTE_FILE_FOLDER.key]: NOTE_FILE_FOLDER,
    [PLU_FOLDER.key]: PLU_FOLDER,
  }
  return (key, fallbackLabel) => {
    const spec = known[key] ?? { key, label: fallbackLabel }
    return { ...spec, label: QUEUE_FOLDER_LABELS[key] ?? spec.label }
  }
}

/**
 * Kolejka plików sklepu. Przy pracy dla innego sklepu bez połączenia z jego komputerem pliki dla programu sklepu
 * czekają tutaj (przełącznik „Zapisuj pliki do kolejki” w tej przeglądarce). Pod koniec, na komputerze sklepu (albo
 * po połączeniu z nim), „Zapisz wszystkie” zapisuje je w folderach w kolejności dodania; każdy plik znika z kolejki
 * dopiero po zapisie, a nieudany zostaje z opisem błędu.
 */
export function FileQueueDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const store = useCurrentStore()
  const queue = useFileQueue(storeId, open)
  const { remove, restore } = useFileQueueRemoval(storeId)
  const enabled = useFileQueueEnabled(storeId)
  const folderFor = useQueueFolders(storeId)
  const [saving, setSaving] = useState(false)
  const [results, setResults] = useState<Record<string, Result>>({})
  const items = queue.data ?? []
  const folders = [
    ...new Map(items.map((item) => [item.folderKey, folderFor(item.folderKey, item.folderLabel)])).values(),
  ]
  const storeName = store?.id === storeId ? store.number : 'tego sklepu'

  const saveAll = async () => {
    setSaving(true)
    try {
      // Zgoda na zapis tylko zaraz po kliknięciu, więc najpierw wszystkie foldery, potem serwer.
      const ready = new Map<string, boolean>()
      for (const folder of folders) ready.set(folder.key, await requestFolderAccess(folder.key, 'readwrite'))
      const next: Record<string, Result> = {}
      let saved = 0
      for (const item of items) {
        const folder = folderFor(item.folderKey, item.folderLabel)
        if (!ready.get(item.folderKey)) {
          next[item.id] = { ok: false, message: `Wybierz folder „${folder.label}” i zezwól na zapis.` }
        } else {
          try {
            const { blob } = await fetchNamedFile(`/stores/${storeId}/file-queue/${item.id}/file`, item.fileName)
            await writeToFolder(item.folderKey, item.fileName, blob)
            saved++
            try {
              await api(`/stores/${storeId}/file-queue/${item.id}/saved`, { method: 'POST' })
              next[item.id] = { ok: true }
            } catch {
              next[item.id] = {
                ok: false,
                message: 'Plik jest w folderze, ale nie zszedł z kolejki. Usuń go z kolejki, nie zapisuj drugi raz.',
              }
            }
          } catch (error) {
            next[item.id] = {
              ok: false,
              message: error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku w folderze',
            }
          }
        }
        setResults((current) => ({ ...current, [item.id]: next[item.id] }))
      }
      const failed = items.length - Object.values(next).filter((result) => result.ok).length
      if (failed === 0) toast.success(`Zapisano w folderach sklepu: ${saved} ${saved === 1 ? 'plik' : 'pliki'}`)
      else toast.warning(`Zapisano ${saved} z ${items.length}. Nieudane zostały w kolejce, opis przy pliku.`)
      void queryClient.invalidateQueries({ queryKey: fileQueueKey(storeId) })
    } finally {
      setSaving(false)
    }
  }

  const removeItem = (item: FileQueueItem) =>
    remove.mutate(item.id, {
      onSuccess: () =>
        toast(`Usunięto z kolejki: ${item.fileName}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(item.id, {
                onError: (error) =>
                  toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić pliku'),
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
      <DialogContent className="gap-5 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Kolejka plików sklepu {storeName}</DialogTitle>
          <DialogDescription>
            Pliki dla programu sklepu (faktury XML, nowe artykuły, protokoły, zmiany cen, etykiety, zamówienia) zrobione
            bez dostępu do folderów sklepu. Na komputerze sklepu „Zapisz wszystkie” zapisze je w folderach.
          </DialogDescription>
        </DialogHeader>

        <SwitchField
          label="Zapisuj pliki do kolejki w tej przeglądarce"
          description="Włącz, gdy pracujesz dla tego sklepu bez połączenia z jego komputerem. Na komputerze sklepu zostaw wyłączone: pliki zapiszą się od razu w folderach."
          checked={enabled}
          onCheckedChange={(checked) => setFileQueueEnabled(storeId, checked)}
        />

        {queue.isPending ? (
          <p className="py-4 text-sm text-muted-foreground">Wczytywanie kolejki…</p>
        ) : items.length === 0 ? (
          <p className="rounded-xl border bg-muted/40 px-4 py-6 text-center text-sm text-muted-foreground">
            {Object.values(results).some((result) => result.ok)
              ? 'Wszystkie pliki zapisane. Kolejka jest pusta.'
              : 'Kolejka jest pusta.'}
          </p>
        ) : (
          <ol className="grid max-h-[40dvh] gap-2 overflow-auto">
            {items.map((item, index) => {
              const result = results[item.id]
              return (
                <li key={item.id} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
                  <span className="w-6 shrink-0 pt-0.5 text-right text-xs text-muted-foreground tabular-nums">
                    {index + 1}.
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="font-medium break-all">{item.fileName}</p>
                    <p className="text-xs text-muted-foreground">
                      {folderFor(item.folderKey, item.folderLabel).label} · {sizeLabel(item.sizeBytes)} ·{' '}
                      {shortTime(item.createdAt)}
                      {item.createdBy && ` · ${item.createdBy}`}
                    </p>
                    {result && !result.ok && (
                      <p className="mt-1 text-xs font-medium text-red-700 dark:text-red-300">{result.message}</p>
                    )}
                  </div>
                  {result?.ok ? (
                    <CheckIcon aria-label="Zapisano" className="size-5 shrink-0 text-emerald-600" />
                  ) : (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Usuń ${item.fileName} z kolejki`}
                      title="Usuń z kolejki"
                      onClick={() => removeItem(item)}
                      disabled={saving}
                    >
                      <Trash2Icon />
                    </Button>
                  )}
                </li>
              )
            })}
          </ol>
        )}

        {items.length > 0 &&
          (folderSavingSupported ? (
            <div className="grid gap-2">
              <p className="text-sm font-medium">Foldery na komputerze sklepu</p>
              {folders.map((folder) => (
                <SavedFolder key={folder.key} folder={folder} />
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">Zapis do folderów działa w Chrome i Edge.</p>
          ))}

        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          <Button
            size="xl"
            onClick={saveAll}
            loading={saving}
            disabled={!folderSavingSupported || items.length === 0}
            title={enabled ? 'Zapisz na komputerze z dostępem do folderów sklepu' : undefined}
          >
            <HardDriveDownloadIcon />
            Zapisz wszystkie w folderach{items.length > 0 && ` (${items.length})`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
