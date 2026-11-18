import { CheckIcon, HardDriveDownloadIcon, Trash2Icon } from 'lucide-react'
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
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
import {
  folderSavingSupported,
  hasSavedFolder,
  requestFolderAccess,
  type SavedFolderSpec,
  writeToFolder,
} from '@/lib/saved-folders'
import type { FileQueueItem } from '@/lib/types'
import { cn } from '@/lib/utils'

import { fileQueueKey, useFileQueue, useFileQueueRemoval } from './api'

const shortTime = (iso: string) =>
  new Date(iso).toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

const PROTOCOLS: Record<string, string> = {
  '1': 'Likwidacje',
  '2': 'Potrzeby sklepu',
  '3': 'Reklama',
  '4': 'Darowizna',
  '5': 'Reprezentacja',
  '6': 'Przeniesienie MM',
}

/** Krótki opis pliku zamiast pełnej nazwy (rodzaj z początku nazwy albo końcówki). */
function fileKind(item: FileQueueItem): string {
  const name = item.fileName.toUpperCase()
  const proto = /^PROTO(\d)_/.exec(name)
  if (proto) return PROTOCOLS[proto[1]] ?? 'Protokół'
  if (name.startsWith('CTRLPRIX')) return 'Zmiana cen'
  if (name.startsWith('ETIQUETTE')) return 'Cenówki'
  if (name.startsWith('COMMANDE')) return 'Zamówienie'
  if (name.startsWith('CADBEZP')) return 'Nowe artykuły'
  if (name.startsWith('LIVRJPOL')) return 'Plik dostawy'
  if (name.startsWith('PLU_')) return 'Sprawdź PLU'
  if (name.endsWith('.XML')) return 'Faktura XML'
  if (name.endsWith('.PRO')) return 'Ceny promocji'
  if (name.endsWith('.LST')) return 'Lista EAN-ów'
  return QUEUE_FOLDER_LABELS[item.folderKey] ?? item.folderLabel
}

/** Wiersz kolejki: dokument (faktura, bon) ze wszystkimi plikami albo pojedynczy plik bez dokumentu. */
type Row = { key: string; label: string | null; items: FileQueueItem[] }

/** Pliki jednego zapisu faktury (XML, nowe artykuły, zmiana cen) idą jeden po drugim w ciągu kilku sekund. */
const SAME_SAVE_MS = 60_000
const INVOICE_FOLDERS = new Set(['data-entry-xml', 'data-entry-product-file', 'deli-order-proto'])

/** Opis faktury z nazwy pliku XML (`<sklep>_<kod>_<dostawca>_<numer>.xml`), bez samej nazwy pliku. */
function invoiceLabel(items: FileQueueItem[]): string | null {
  const xml = items.find((item) => item.folderKey === 'data-entry-xml')
  if (!xml) return null
  const name = xml.fileName
    .replace(/\.xml$/i, '')
    .replace(/^\d+_(\d+_)?/, '')
    .replace(/_/g, ' ')
  return `Faktura ${name}`.trim()
}

function toRows(items: FileQueueItem[]): Row[] {
  const rows = new Map<string, Row>()
  // Pliki bez dokumentu (dodane, zanim kolejka zapisywała fakturę): XML i pliki zapisane razem z nim tą samą osobą
  // w tej samej chwili należą do jednej faktury.
  let open: Row | null = null
  for (const item of items) {
    if (item.groupKey) {
      const key = `group:${item.groupKey}`
      const row = rows.get(key)
      if (row) row.items.push(item)
      else rows.set(key, { key, label: item.groupLabel ?? item.groupKey, items: [item] })
      continue
    }
    const last = open?.items.at(-1)
    const together =
      open &&
      last &&
      INVOICE_FOLDERS.has(item.folderKey) &&
      INVOICE_FOLDERS.has(last.folderKey) &&
      last.createdBy === item.createdBy &&
      new Date(item.createdAt).getTime() - new Date(last.createdAt).getTime() <= SAME_SAVE_MS &&
      !(item.folderKey === 'data-entry-xml' && open.items.some((other) => other.folderKey === 'data-entry-xml'))
    if (open && together) {
      open.items.push(item)
      open.label = invoiceLabel(open.items)
    } else {
      open = { key: `file:${item.id}`, label: null, items: [item] }
      rows.set(open.key, open)
    }
  }
  // Wiersz z kilkoma plikami bez XML (np. nowe artykuły i zmiana cen) też dostaje nagłówek.
  for (const row of rows.values()) {
    if (!row.label && row.items.length > 1) row.label = 'Pliki zapisane razem'
  }
  return [...rows.values()]
}

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
  const rows = toRows(items)
  const folders = [
    ...new Map(items.map((item) => [item.folderKey, folderFor(item.folderKey, item.folderLabel)])).values(),
  ]
  // Ścieżki są wspólne dla sklepu, ale uchwyt folderu przeglądarka daje tylko po wybraniu go w oknie wyboru, raz
  // w każdej przeglądarce. Pokazujemy więc tylko foldery, których ta przeglądarka jeszcze nie ma.
  const folderKeys = folders.map((folder) => folder.key).sort()
  const picked = useQuery({
    queryKey: ['saved-folders', ...folderKeys],
    queryFn: async () =>
      Object.fromEntries(await Promise.all(folderKeys.map(async (key) => [key, await hasSavedFolder(key)] as const))),
    enabled: open && folderSavingSupported && folderKeys.length > 0,
  })
  const missingFolders = folders.filter((folder) => picked.data && !picked.data[folder.key])
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
      void picked.refetch()
    } finally {
      setSaving(false)
    }
  }

  const removeRow = async (row: Row) => {
    const ids = row.items.filter((item) => !results[item.id]?.ok).map((item) => item.id)
    const removed: string[] = []
    try {
      for (const id of ids) {
        await remove.mutateAsync(id)
        removed.push(id)
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć z kolejki')
    }
    if (removed.length === 0) return
    toast(`Usunięto z kolejki: ${row.label ?? fileKind(row.items[0])}`, {
      action: {
        label: 'Przywróć',
        onClick: async () => {
          try {
            for (const id of removed) await restore.mutateAsync(id)
          } catch (error) {
            toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić')
          }
        },
      },
    })
  }

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
            {rows.map((row, index) => {
              const rowResults = row.items.map((item) => results[item.id])
              const allSaved = rowResults.every((result) => result?.ok)
              const errors = row.items.flatMap((item) => {
                const result = results[item.id]
                return result && !result.ok ? [`${fileKind(item)}: ${result.message}`] : []
              })
              const first = row.items[0]
              return (
                <li key={row.key} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
                  <span className="w-6 shrink-0 pt-0.5 text-right text-xs text-muted-foreground tabular-nums">
                    {index + 1}.
                  </span>
                  <div className="grid min-w-0 flex-1 gap-1.5">
                    <p className="font-medium">{row.label ?? fileKind(first)}</p>
                    {row.label && (
                      <ul className="flex flex-wrap gap-1.5">
                        {row.items.map((item) => {
                          const result = results[item.id]
                          return (
                            <li
                              key={item.id}
                              className={cn(
                                'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium',
                                result?.ok
                                  ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100'
                                  : result
                                    ? 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100'
                                    : 'bg-muted text-foreground',
                              )}
                            >
                              {result?.ok && <CheckIcon aria-hidden className="size-3" />}
                              {fileKind(item)}
                            </li>
                          )
                        })}
                      </ul>
                    )}
                    <p className="text-xs text-muted-foreground">
                      {shortTime(first.createdAt)}
                      {first.createdBy && ` · ${first.createdBy}`}
                    </p>
                    {errors.map((error) => (
                      <p key={error} className="text-xs font-medium text-red-700 dark:text-red-300">
                        {error}
                      </p>
                    ))}
                  </div>
                  {allSaved ? (
                    <CheckIcon aria-label="Zapisano" className="size-5 shrink-0 text-emerald-600" />
                  ) : (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Usuń z kolejki: ${row.label ?? fileKind(first)}`}
                      title="Usuń z kolejki"
                      onClick={() => void removeRow(row)}
                      disabled={saving || remove.isPending}
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
          (!folderSavingSupported ? (
            <p className="text-sm text-muted-foreground">Zapis do folderów działa w Chrome i Edge.</p>
          ) : (
            missingFolders.length > 0 && (
              <div className="grid gap-2">
                <p className="text-sm font-medium">Wybierz foldery w tej przeglądarce</p>
                <p className="text-xs text-muted-foreground">
                  Ścieżki są zapisane dla sklepu, ale przeglądarka zapisuje tylko w folderze wskazanym w oknie wyboru,
                  raz na każdym komputerze. Ścieżkę skopiujemy do schowka.
                </p>
                {missingFolders.map((folder) => (
                  <SavedFolder
                    key={folder.key}
                    folder={folder}
                    pathFieldWhenMissing
                    onPicked={() => void picked.refetch()}
                  />
                ))}
              </div>
            )
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
            Zapisz wszystkie w folderach{rows.length > 0 && ` (${rows.length})`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
