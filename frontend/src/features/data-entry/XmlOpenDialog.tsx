import { type ReactNode, useState } from 'react'
import { DownloadIcon, LoaderCircleIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { formatDate, formatDateTime } from '@/features/ksef/format'
import { ApiError, downloadFile } from '@/lib/api'
import type { DataEntryXmlDocument, DataEntryXmlExport } from '@/lib/types'

import {
  fetchXmlHistoryDocument,
  readXmlFile,
  useTemporaryXml,
  useTemporaryXmlChange,
  useXmlHistory,
  xmlHistoryFilePath,
} from './api'

/** Najwięcej zapisów tymczasowych w sklepie (starszy znika przy kolejnym zapisie). */
const TEMPORARY_LIMIT = 10

/** Skąd wczytać: plik z dysku, zapis tymczasowy albo kopia pliku zapisanego wcześniej. */
export type XmlSource = 'file' | 'temporary' | 'history'

const HEADINGS: Record<XmlSource, { title: string; description: string }> = {
  file: {
    title: 'Wczytaj z pliku XML',
    description:
      'Plik XML faktury dla programu sklepu, własny albo od dostawcy. Artykuły z kartoteki dostaną dane z kartoteki, jak przy fakturze z KSeF.',
  },
  temporary: { title: 'Wczytaj tymczasowe', description: 'Wraca cała tabela, taka jak przy zapisie.' },
  history: {
    title: 'Wczytaj kopię',
    description: 'Kopie 100 ostatnich plików XML zapisanych na dysku. Plik da się też pobrać ponownie.',
  },
}

type Props = {
  storeId: string
  source: XmlSource
  open: boolean
  onOpenChange: (open: boolean) => void
  onLoaded: (document: DataEntryXmlDocument) => void
}

/**
 * Wczytanie do tabeli, osobne okno dla każdego źródła: plik XML z dysku (własny albo od dostawcy), jeden z 10 zapisów
 * tymczasowych (wraca cała tabela, taka jak przy zapisie) albo kopia jednego ze 100 ostatnich plików zapisanych na
 * dysku. Kopię da się też pobrać ponownie, a zapis tymczasowy usunąć (z „Przywróć”).
 */
export function XmlOpenDialog({ storeId, source, open, onOpenChange, onLoaded }: Props) {
  const history = useXmlHistory(storeId, open && source === 'history')
  const temporary = useTemporaryXml(storeId, open && source === 'temporary')
  const changeTemporary = useTemporaryXmlChange(storeId)
  const [pendingFile, setPendingFile] = useState<File>()
  const [loadingId, setLoadingId] = useState<string | null>(null)
  const busy = pendingFile !== undefined || loadingId !== null

  const read = async (load: () => Promise<DataEntryXmlDocument>) => {
    try {
      onLoaded(await load())
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wczytać pliku XML')
    }
  }

  const fromFile = async (file: File) => {
    setPendingFile(file)
    await read(() => readXmlFile(storeId, file))
    setPendingFile(undefined)
  }

  const fromStored = async (item: DataEntryXmlExport) => {
    setLoadingId(item.id)
    await read(() => fetchXmlHistoryDocument(storeId, item.id))
    setLoadingId(null)
  }

  const download = (item: DataEntryXmlExport) =>
    downloadFile(xmlHistoryFilePath(storeId, item.id), item.fileName).catch((error) =>
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku'),
    )

  const removeTemporary = (item: DataEntryXmlExport) =>
    changeTemporary.mutate(
      { id: item.id, restore: false },
      {
        onSuccess: () =>
          toast('Usunięto zapis tymczasowy', {
            description: title(item),
            action: {
              label: 'Przywróć',
              onClick: () =>
                changeTemporary.mutate(
                  { id: item.id, restore: true },
                  { onError: () => toast.error('Nie udało się przywrócić zapisu tymczasowego') },
                ),
            },
          }),
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć zapisu tymczasowego'),
      },
    )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{HEADINGS[source].title}</DialogTitle>
          <DialogDescription>{HEADINGS[source].description}</DialogDescription>
        </DialogHeader>

        {source === 'file' && (
          <FileDropZone
            accept={['.xml']}
            pendingFile={pendingFile}
            onFile={fromFile}
            onReject={(name) => toast.error(`${name} nie jest plikiem XML`)}
            title="Wybierz plik XML z dysku"
            hint="Przeciągnij plik tutaj albo kliknij"
            className="min-h-28 py-6"
          />
        )}

        {source === 'temporary' && (
          <StoredList
            id="xml-temporary"
            title={`Zapisane tymczasowo (${temporary.data?.length ?? 0}/${TEMPORARY_LIMIT})`}
            empty="Brak zapisów tymczasowych. Zapisz tabelę: strzałka przy „Zapisz” → „Zapisz tymczasowo”."
            query={temporary}
            loadingId={loadingId}
            busy={busy}
            onLoad={fromStored}
            action={(item) => (
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => removeTemporary(item)}
                disabled={changeTemporary.isPending}
                aria-label={`Usuń zapis tymczasowy ${title(item)}`}
                title="Usuń zapis tymczasowy"
              >
                <Trash2Icon />
              </Button>
            )}
          />
        )}

        {source === 'history' && (
          <StoredList
            id="xml-history"
            title="Zapisane na dysku"
            empty="Nie zapisano tu jeszcze żadnego pliku XML."
            query={history}
            loadingId={loadingId}
            busy={busy}
            onLoad={fromStored}
            action={(item) => (
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => download(item)}
                aria-label={`Pobierz ${item.fileName}`}
                title={`Pobierz ${item.fileName}`}
              >
                <DownloadIcon />
              </Button>
            )}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function title(item: DataEntryXmlExport) {
  return `${item.documentNumber ?? 'Bez numeru'} · ${item.supplierName ?? item.supplierNip ?? 'bez dostawcy'}`
}

function StoredList({
  id,
  title: heading,
  empty,
  query,
  loadingId,
  busy,
  onLoad,
  action,
}: {
  id: string
  title: string
  empty: string
  query: { data?: DataEntryXmlExport[]; isPending: boolean; isError: boolean }
  loadingId: string | null
  busy: boolean
  onLoad: (item: DataEntryXmlExport) => void
  action: (item: DataEntryXmlExport) => ReactNode
}) {
  const items = query.data ?? []
  return (
    <section aria-labelledby={`${id}-title`} className="min-w-0">
      <h3 id={`${id}-title`} className="mb-2 text-sm font-medium">
        {heading}
      </h3>
      {query.isPending ? (
        <p className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
          <LoaderCircleIcon className="size-4 animate-spin motion-reduce:animate-none" />
          Wczytuję listę…
        </p>
      ) : query.isError ? (
        <p className="py-4 text-sm text-destructive">Nie udało się pobrać listy.</p>
      ) : items.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-4 text-center text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="max-h-[28rem] divide-y overflow-y-auto rounded-xl border">
          {items.map((item) => (
            <li key={item.id} className="flex items-center gap-3 px-3 py-2">
              <button
                type="button"
                disabled={busy}
                onClick={() => onLoad(item)}
                className="min-w-0 flex-1 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-wait"
              >
                <span className="flex items-baseline gap-2">
                  <span className="truncate font-medium">{title(item)}</span>
                  {loadingId === item.id && (
                    <LoaderCircleIcon className="size-3.5 shrink-0 animate-spin self-center motion-reduce:animate-none" />
                  )}
                </span>
                <span className="block truncate text-xs text-muted-foreground tabular-nums">
                  Dokument z {formatDate(item.documentDate)} · {item.lineCount}{' '}
                  {item.lineCount === 1 ? 'pozycja' : 'pozycji'} · {formatMoney(item.netValue)} zł netto · zapisano{' '}
                  {formatDateTime(item.createdAt)}
                  {item.createdBy && ` · ${item.createdBy}`}
                </span>
              </button>
              {action(item)}
              <Button variant="outline" size="sm" disabled={busy} onClick={() => onLoad(item)}>
                Wczytaj
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
