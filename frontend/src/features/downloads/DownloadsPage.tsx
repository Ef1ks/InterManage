import { useState } from 'react'
import { CheckIcon, HistoryIcon, PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import {
  useDownloads,
  useMarkDownloadSeen,
  useRemoveDownload,
  useRestoreDownload,
  type Download,
} from '@/features/downloads/api'
import { ChangeList, DownloadButton, VersionHistoryDialog } from '@/features/downloads/changes'
import { DownloadFormDialog } from '@/features/downloads/DownloadFormDialog'
import { formatSize } from '@/features/downloads/format'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'

const date = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' })

/** Programy i pliki dla każdego pracownika (np. AutoJob); pozycje dodaje i zmienia właściciel. */
export function DownloadsPage() {
  const me = useCurrentUser()
  const { data: downloads, isPending, isError, refetch } = useDownloads()
  const [editor, setEditor] = useState<{ open: boolean; download?: Download }>({ open: false })
  const remove = useRemoveDownload()
  const restore = useRestoreDownload()

  const onRemove = (download: Download) =>
    remove.mutate(download.id, {
      onSuccess: () =>
        toast.success(`Usunięto „${download.title}”`, {
          action: { label: 'Przywróć', onClick: () => restore.mutate(download.id) },
        }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć'),
    })

  return (
    <>
      <PageHeader
        title="Do pobrania"
        description="Programy i pliki do zainstalowania na komputerze w sklepie."
        actions={
          me.owner && (
            <Button size="lg" onClick={() => setEditor({ open: true })}>
              <PlusIcon />
              Dodaj plik
            </Button>
          )
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać listy"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : isPending ? (
        <div className="grid gap-4 md:grid-cols-2">
          {[0, 1].map((index) => (
            <Card key={index} className="h-40 animate-pulse bg-muted/50" />
          ))}
        </div>
      ) : downloads.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie ma jeszcze nic do pobrania</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {me.owner
              ? 'Dodaj program, np. AutoJob, a każdy pracownik pobierze go stąd na swój komputer.'
              : 'Gdy pojawią się tu programy do zainstalowania, pobierzesz je z tej strony.'}
          </p>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {downloads.map((download) => (
            <DownloadCard
              key={download.id}
              download={download}
              editable={me.owner}
              onEdit={() => setEditor({ open: true, download })}
              onRemove={() => onRemove(download)}
            />
          ))}
        </div>
      )}

      {me.owner && (
        <DownloadFormDialog
          open={editor.open}
          download={editor.download}
          onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
        />
      )}
    </>
  )
}

function DownloadCard({
  download,
  editable,
  onEdit,
  onRemove,
}: {
  download: Download
  editable: boolean
  onEdit: () => void
  onRemove: () => void
}) {
  const { file } = download
  const [history, setHistory] = useState(false)
  return (
    <Card className={cn('gap-4 p-5', download.newVersion && 'ring-2 ring-sky-400 dark:ring-sky-500')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-semibold">{download.title}</h2>
            {download.newVersion && (
              <span className="rounded-md bg-sky-200 px-2 py-0.5 text-xs font-semibold text-sky-950 dark:bg-sky-900/70 dark:text-sky-100">
                Nowa wersja
              </span>
            )}
          </div>
          {file && (
            <p className="text-sm text-muted-foreground">
              {[file.version, formatSize(file.sizeBytes), `wgrano ${date.format(new Date(file.uploadedAt))}`]
                .filter(Boolean)
                .join(' · ')}
            </p>
          )}
        </div>
        {editable && (
          <div className="-mt-1 -mr-2 flex shrink-0">
            <Button variant="ghost" size="icon-sm" title="Edytuj albo wgraj nową wersję" onClick={onEdit}>
              <PencilIcon />
            </Button>
            <Button variant="ghost" size="icon-sm" title="Usuń" onClick={onRemove}>
              <Trash2Icon />
            </Button>
          </div>
        )}
      </div>

      {download.description && (
        <div className="flex flex-col gap-2 text-sm leading-relaxed">
          {download.description
            .split('\n')
            .filter((line) => line.trim())
            .map((line, index) => (
              <p key={index}>{line}</p>
            ))}
        </div>
      )}

      {file && file.changes.length > 0 && (
        <div className="flex flex-col gap-2 rounded-lg bg-muted/60 p-3">
          <h3 className="text-sm font-semibold">Co nowego{file.version ? ` w ${file.version}` : ''}</h3>
          <ChangeList changes={file.changes} />
        </div>
      )}

      <div className="mt-auto flex flex-wrap items-center gap-3">
        {file ? (
          <>
            <DownloadButton download={download} />
            <Button variant="ghost" onClick={() => setHistory(true)}>
              <HistoryIcon />
              Historia zmian
            </Button>
            {download.newVersion && <MarkSeenButton id={download.id} />}
            <span
              className="ml-auto truncate font-mono text-xs text-muted-foreground"
              title={`SHA-256: ${file.sha256}`}
            >
              SHA-256 {file.sha256.slice(0, 12)}…
            </span>
          </>
        ) : (
          <p className="text-sm text-amber-700 dark:text-amber-300">Bez pliku - pracownicy jeszcze tego nie widzą.</p>
        )}
      </div>
      {file && <VersionHistoryDialog download={download} open={history} onOpenChange={setHistory} />}
    </Card>
  )
}

function MarkSeenButton({ id }: { id: string }) {
  const seen = useMarkDownloadSeen()
  return (
    <Button variant="ghost" onClick={() => seen.mutate(id)}>
      <CheckIcon />
      Mam już tę wersję
    </Button>
  )
}
