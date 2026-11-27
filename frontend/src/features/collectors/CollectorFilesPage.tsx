import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import {
  ClipboardCheckIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  PrinterIcon,
  ShoppingCartIcon,
  Trash2Icon,
  UploadIcon,
} from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, downloadFile, printFile } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import type { CollectorFile } from '@/lib/types'
import { cn } from '@/lib/utils'

import {
  collectorFilePath,
  collectorFilesKey,
  markLoaded,
  useCollectorFiles,
  useRemoveCollectorFile,
  writeCollectorFile,
} from './files-api'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

type Filter = 'wszystkie' | 'czeka' | 'wgrane'

const PAGES: Record<CollectorFile['kind'], { title: string; empty: string; done: string }> = {
  INVENTORY: {
    title: 'Inwentaryzacje',
    empty:
      'Inwentaryzację robi się z listy w Danych z kolektorów albo z tabeli wprowadzania danych: od razu albo na przyszłość.',
    done: 'Inwentaryzacja wgrana do programu sklepu',
  },
  ORDER: {
    title: 'Zamówienia',
    empty: 'Zamówienie robi się z listy w Danych z kolektorów: od razu albo na później.',
    done: 'Zamówienie wgrane do programu sklepu',
  },
}

/**
 * Historia inwentaryzacji albo zamówień z kolektora (osobne strony): zrobione od razu są wgrane do Mercalys,
 * przygotowane na później czekają, a plik dla programu sklepu robi się z historii w dniu, w którym ma trafić do Mercalys.
 */
export function CollectorFilesPage({ kind }: { kind: CollectorFile['kind'] }) {
  const store = useCurrentStore()
  return (
    <div data-wide-page>
      <PageHeader title={PAGES[kind].title} description={store ? `Z kolektorów · ${storeLabel(store)}` : undefined} />
      {store ? (
        <Files key={`${store.id}-${kind}`} storeId={store.id} kind={kind} />
      ) : (
        <FullPageMessage title="Brak sklepu" description="Historia jest w konkretnym sklepie." inline />
      )}
    </div>
  )
}

function Files({ storeId, kind }: { storeId: string; kind: CollectorFile['kind'] }) {
  const files = useCollectorFiles(storeId)
  const [params, setParams] = useSearchParams()
  const filter = (params.get('pokaz') as Filter | null) ?? 'wszystkie'

  if (files.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (files.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać historii"
        action={{ label: 'Spróbuj ponownie', onClick: () => files.refetch() }}
        inline
      />
    )
  }
  const ofKind = files.data.filter((file) => file.kind === kind)
  const matches = (file: CollectorFile, key: Filter) =>
    key === 'czeka' ? file.status === 'WAITING' : key === 'wgrane' ? file.status === 'LOADED' : true
  const visible = ofKind.filter((file) => matches(file, filter))
  const labels: Record<Filter, string> = { wszystkie: 'Wszystkie', czeka: 'Czekają na wgranie', wgrane: 'Wgrane' }

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap gap-1.5">
        {(Object.keys(labels) as Filter[]).map((key) => (
          <Button
            key={key}
            size="sm"
            variant={filter === key ? 'default' : 'outline'}
            onClick={() =>
              setParams(
                (current) => {
                  const next = new URLSearchParams(current)
                  if (key === 'wszystkie') next.delete('pokaz')
                  else next.set('pokaz', key)
                  return next
                },
                { replace: true },
              )
            }
          >
            {labels[key]}
            <span className="tabular-nums opacity-70">{ofKind.filter((file) => matches(file, key)).length}</span>
          </Button>
        ))}
      </div>
      {visible.length === 0 ? (
        <FullPageMessage title="Pusto" description={PAGES[kind].empty} inline />
      ) : (
        <Card className="gap-0 overflow-hidden p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Z kolektora</TableHead>
                <TableHead className="text-right">Artykułów</TableHead>
                <TableHead>Stan</TableHead>
                <TableHead>Notatka</TableHead>
                <TableHead className="text-right">Działania</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((file) => (
                <FileRow key={file.id} storeId={storeId} file={file} />
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </div>
  )
}
function FileRow({ storeId, file }: { storeId: string; file: CollectorFile }) {
  const folder = useProtoFolder(storeId)
  const queryClient = useQueryClient()
  const { remove, restore } = useRemoveCollectorFile(storeId)
  const [writing, setWriting] = useState(false)
  const Icon = file.kind === 'INVENTORY' ? ClipboardCheckIcon : ShoppingCartIcon
  const refresh = () => void queryClient.invalidateQueries({ queryKey: collectorFilesKey(storeId) })

  const write = async () => {
    setWriting(true)
    try {
      const { fileName, downloaded } = await writeCollectorFile(storeId, folder.key, file.id)
      if (downloaded) {
        toast.success('Pobrano plik', {
          description: 'Folder protokołów nie jest wybrany w tej przeglądarce. Gdy skopiujesz plik, oznacz go.',
          duration: 15_000,
          action: {
            label: 'Oznacz jako wgrany',
            onClick: () =>
              void markLoaded(storeId, file.id, fileName)
                .then(refresh)
                .catch((error: unknown) => toast.error(errorMessage(error, 'Nie udało się oznaczyć'))),
          },
        })
      } else {
        toast.success(PAGES[file.kind].done, { description: fileName })
      }
      refresh()
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się zapisać pliku'))
    } finally {
      setWriting(false)
    }
  }

  const removeFile = () =>
    remove.mutate(file.id, {
      onSuccess: () =>
        toast.success('Usunięto z historii', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(file.id) },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć')),
    })

  const fail = (fallback: string) => (error: unknown) => toast.error(errorMessage(error, fallback))

  return (
    <TableRow className={cn(file.status === 'WAITING' && 'bg-amber-100/60 dark:bg-amber-950/30')}>
      <TableCell className="tabular-nums">
        <span className="flex items-center gap-2 font-medium">
          <Icon aria-hidden className="size-4 text-muted-foreground" />
          {dateTime.format(new Date(file.createdAt))}
        </span>
        {file.createdBy && <span className="block text-xs text-muted-foreground">{file.createdBy}</span>}
      </TableCell>
      <TableCell className="text-right tabular-nums">{file.lineCount}</TableCell>
      <TableCell>
        {file.status === 'LOADED' ? (
          <span className="grid gap-0.5">
            <Badge className="w-fit bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100">
              wgrana do Mercalys
            </Badge>
            <span className="text-xs text-muted-foreground tabular-nums">
              {file.loadedAt && dateTime.format(new Date(file.loadedAt))}
              {file.loadedBy && `, ${file.loadedBy}`}
            </span>
          </span>
        ) : (
          <Badge className="bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">czeka</Badge>
        )}
      </TableCell>
      <TableCell className="max-w-64 whitespace-normal text-sm">{file.note}</TableCell>
      <TableCell>
        <div className="flex flex-wrap justify-end gap-1">
          <Button
            size="sm"
            variant={file.status === 'WAITING' ? 'default' : 'outline'}
            loading={writing}
            title={
              file.status === 'WAITING'
                ? 'Zapisze plik w folderze programu sklepu'
                : 'Zapisze plik jeszcze raz (program sklepu wczyta go drugi raz)'
            }
            onClick={() => void write()}
          >
            <UploadIcon aria-hidden />
            {file.status === 'WAITING' ? 'Wgraj do Mercalys' : 'Wgraj ponownie'}
          </Button>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="Excel"
            title="Excel"
            onClick={() =>
              void downloadFile(collectorFilePath(storeId, file.id, 'xlsx')).catch(fail('Nie udało się pobrać Excela'))
            }
          >
            <FileSpreadsheetIcon />
          </Button>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="PDF"
            title="PDF"
            onClick={() =>
              void downloadFile(collectorFilePath(storeId, file.id, 'pdf')).catch(fail('Nie udało się pobrać PDF-a'))
            }
          >
            <FileTextIcon />
          </Button>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="Drukuj"
            title="Drukuj"
            onClick={() =>
              void printFile(collectorFilePath(storeId, file.id, 'pdf')).catch(fail('Nie udało się wydrukować'))
            }
          >
            <PrinterIcon />
          </Button>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="Usuń z historii"
            title="Usuń z historii"
            onClick={removeFile}
          >
            <Trash2Icon />
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}
