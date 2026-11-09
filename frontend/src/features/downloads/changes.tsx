import { DownloadIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  downloadFileUrl,
  useDownloadVersions,
  useMarkDownloadSeen,
  type Download,
  type DownloadChange,
  type DownloadChangeKind,
  type DownloadFileInfo,
} from '@/features/downloads/api'
import { CHANGE_KINDS, formatSize, versionName } from '@/features/downloads/format'
import { cn } from '@/lib/utils'

const date = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' })

const KIND_STYLES: Record<DownloadChangeKind, string> = {
  ADDED: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  CHANGED: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  FIXED: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
}

/** Lista zmian wersji pogrupowana: Dodano, Zmieniono, Naprawiono. */
export function ChangeList({ changes, className }: { changes: DownloadChange[]; className?: string }) {
  if (changes.length === 0) return null
  return (
    <div className={cn('flex flex-col gap-3', className)}>
      {CHANGE_KINDS.map(({ kind, label }) => {
        const items = changes.filter((change) => change.kind === kind)
        if (items.length === 0) return null
        return (
          <section key={kind} className="flex flex-col gap-1.5">
            <h4>
              <span className={cn('rounded-md px-2 py-0.5 text-xs font-semibold', KIND_STYLES[kind])}>{label}</span>
            </h4>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm leading-relaxed marker:text-muted-foreground">
              {items.map((change, index) => (
                <li key={index}>{change.text}</li>
              ))}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

/** Przycisk pobrania; kliknięcie od razu zdejmuje baner o tej wersji. */
export function DownloadButton({
  download,
  label,
  size = 'lg',
}: {
  download: Download
  label?: string
  size?: 'default' | 'lg'
}) {
  const seen = useMarkDownloadSeen()
  if (!download.file) return null
  return (
    <Button
      size={size}
      nativeButton={false}
      render={<a href={downloadFileUrl(download.id)} download={download.file.fileName} />}
      onClick={() => {
        if (download.newVersion) seen.mutate(download.id)
      }}
    >
      <DownloadIcon />
      {label ?? `Pobierz ${download.file.fileName}`}
    </Button>
  )
}

/** „Co nowego” w obowiązującej wersji: z banera i z karty pozycji. */
export function WhatsNewDialog({
  download,
  open,
  onOpenChange,
}: {
  download: Download
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const seen = useMarkDownloadSeen()
  const file = download.file
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="text-lg">Co nowego: {versionName(download)}</DialogTitle>
          {file && <DialogDescription>Wersja z {date.format(new Date(file.uploadedAt))}.</DialogDescription>}
        </DialogHeader>
        {file && file.changes.length > 0 ? (
          <ChangeList changes={file.changes} />
        ) : (
          <p className="text-sm text-muted-foreground">Do tej wersji nie dopisano listy zmian.</p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          {download.newVersion && (
            <Button
              variant="ghost"
              size="lg"
              onClick={() => {
                seen.mutate(download.id)
                onOpenChange(false)
              }}
            >
              Mam już tę wersję
            </Button>
          )}
          <DownloadButton download={download} label="Pobierz nową wersję" />
        </div>
      </DialogContent>
    </Dialog>
  )
}

/** Wszystkie wersje pozycji z listami zmian, od najnowszej. */
export function VersionHistoryDialog({
  download,
  open,
  onOpenChange,
}: {
  download: Download
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { data: versions, isPending, isError } = useDownloadVersions(download.id, open)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        <DialogHeader>
          <DialogTitle className="text-lg">Historia zmian: {download.title}</DialogTitle>
          <DialogDescription>Wszystkie wgrane wersje, od najnowszej.</DialogDescription>
        </DialogHeader>
        {isError ? (
          <p className="text-sm text-destructive">Nie udało się wczytać historii.</p>
        ) : isPending ? (
          <div className="h-32 animate-pulse rounded-lg bg-muted/50" />
        ) : (
          <ol className="flex flex-col gap-5">
            {versions.map((version, index) => (
              <VersionEntry key={version.id} version={version} current={index === 0} />
            ))}
          </ol>
        )}
      </DialogContent>
    </Dialog>
  )
}

function VersionEntry({ version, current }: { version: DownloadFileInfo; current: boolean }) {
  return (
    <li
      className="flex flex-col gap-3 border-l-2 pl-4 data-current:border-foreground"
      data-current={current || undefined}
    >
      <div>
        <div className="flex flex-wrap items-baseline gap-2">
          <span className="font-semibold">{version.version ?? version.fileName}</span>
          {current && <span className="text-xs font-medium text-muted-foreground">obecna wersja</span>}
        </div>
        <div className="text-xs text-muted-foreground">
          {[date.format(new Date(version.uploadedAt)), formatSize(version.sizeBytes), version.uploadedBy]
            .filter(Boolean)
            .join(' · ')}
        </div>
      </div>
      {version.changes.length > 0 ? (
        <ChangeList changes={version.changes} />
      ) : (
        <p className="text-sm text-muted-foreground">Bez listy zmian.</p>
      )}
    </li>
  )
}
