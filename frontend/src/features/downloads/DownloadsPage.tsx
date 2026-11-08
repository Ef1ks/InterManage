import { useState } from 'react'
import { DownloadIcon, PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import {
  downloadFileUrl,
  useDownloads,
  useRemoveDownload,
  useRestoreDownload,
  type Download,
} from '@/features/downloads/api'
import { DownloadFormDialog } from '@/features/downloads/DownloadFormDialog'
import { formatSize } from '@/features/downloads/format'
import { ApiError } from '@/lib/api'

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
  return (
    <Card className="gap-4 p-5">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-lg font-semibold">{download.title}</h2>
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

      <div className="mt-auto flex flex-wrap items-center gap-3">
        {file ? (
          <>
            <Button
              size="lg"
              nativeButton={false}
              render={<a href={downloadFileUrl(download.id)} download={file.fileName} />}
            >
              <DownloadIcon />
              Pobierz {file.fileName}
            </Button>
            <span className="truncate font-mono text-xs text-muted-foreground" title={`SHA-256: ${file.sha256}`}>
              SHA-256 {file.sha256.slice(0, 12)}…
            </span>
          </>
        ) : (
          <p className="text-sm text-amber-700 dark:text-amber-300">Bez pliku - pracownicy jeszcze tego nie widzą.</p>
        )}
      </div>
    </Card>
  )
}
