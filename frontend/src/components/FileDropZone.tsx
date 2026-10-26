import { useState, type DragEvent, type ReactNode } from 'react'
import { FileSpreadsheetIcon, LoaderCircleIcon } from 'lucide-react'

import { cn } from '@/lib/utils'

type Props = {
  /** Dozwolone rozszerzenia, np. ['.xlsx', '.xlsm']. */
  accept: string[]
  /** Plik w trakcie wgrywania (pole jest wtedy zablokowane). */
  pendingFile?: File
  onFile: (file: File) => void
  /** Wybrany plik ma inne rozszerzenie. */
  onReject: (fileName: string) => void
  title: string
  hint: ReactNode
  className?: string
}

/** Pole "przeciągnij albo kliknij" do wgrywania jednego pliku Excela. */
export function FileDropZone({ accept, pendingFile, onFile, onReject, title, hint, className }: Props) {
  const [dragging, setDragging] = useState(false)

  const start = (file: File | undefined) => {
    if (!file || pendingFile) return
    if (!accept.some((extension) => file.name.toLowerCase().endsWith(extension))) {
      onReject(file.name)
      return
    }
    onFile(file)
  }

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    setDragging(false)
    start(event.dataTransfer.files[0])
  }

  return (
    <label
      onDragOver={(event) => {
        event.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      aria-busy={pendingFile ? true : undefined}
      className={cn(
        'group flex min-h-36 cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-foreground/15 bg-card px-6 py-8 text-center transition-[background-color,border-color] duration-150 ease-out',
        'hover:border-foreground/30 hover:bg-muted/40 has-focus-visible:border-ring has-focus-visible:ring-3 has-focus-visible:ring-ring/50',
        dragging && 'border-foreground/40 bg-muted/60',
        pendingFile && 'pointer-events-none',
        className,
      )}
    >
      <input
        type="file"
        accept={accept.join(',')}
        className="sr-only"
        disabled={Boolean(pendingFile)}
        onChange={(event) => {
          start(event.target.files?.[0])
          // Ten sam plik wybrany drugi raz też ma uruchomić import.
          event.target.value = ''
        }}
      />
      {pendingFile ? (
        <>
          <LoaderCircleIcon aria-hidden className="size-6 animate-spin text-muted-foreground motion-reduce:animate-none" />
          <div>
            <p className="font-medium">Importuję {pendingFile.name}</p>
            <p className="mt-0.5 text-sm text-muted-foreground">
              {formatFileSize(pendingFile.size)} · zwykle kilka sekund, nie zamykaj strony
            </p>
          </div>
        </>
      ) : (
        <>
          <FileSpreadsheetIcon aria-hidden className="size-6 text-muted-foreground transition-colors group-hover:text-foreground" />
          <div>
            <p className="font-medium">{dragging ? 'Upuść plik, aby zaimportować' : title}</p>
            <p className="mt-0.5 text-sm text-muted-foreground">{hint}</p>
          </div>
        </>
      )}
    </label>
  )
}

function formatFileSize(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB` : `${Math.ceil(bytes / 1024)} kB`
}
