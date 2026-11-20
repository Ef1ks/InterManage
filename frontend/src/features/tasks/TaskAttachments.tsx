import { useEffect, useRef, useState } from 'react'
import { ClipboardPasteIcon, ExternalLinkIcon, ImagePlusIcon, MonitorIcon, PencilLineIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { MAX_TASK_ATTACHMENTS, attachmentUrl, useTaskAttachmentActions } from '@/features/tasks/api'
import { ScreenshotEditor } from '@/features/tasks/ScreenshotEditor'
import { localShot, taskErrorMessage, type LocalShot } from '@/features/tasks/task-helpers'
import {
  canCaptureScreen,
  captureCurrentTab,
  isCaptureCancelled,
  pastedImage,
  readClipboardImage,
} from '@/lib/screen-capture'
import type { StoreTask } from '@/lib/types'

type Thumb = { key: string; src: string; label: string; canRemove: boolean }

/**
 * Zrzuty w oknie nowego zadania: trzymane w przeglądarce, a wysyłane po zleceniu. Ctrl+V w oknie wkleja obraz
 * ze schowka (np. po Win+Shift+S) i od razu otwiera popisanie.
 */
export function DraftAttachments({ shots, onChange }: { shots: LocalShot[]; onChange: (shots: LocalShot[]) => void }) {
  const [viewing, setViewing] = useState<LocalShot | null>(null)
  const latest = useRef(shots)
  useEffect(() => {
    latest.current = shots
  })

  // Adresów obrazków (blob:) celowo nie unieważniamy: sprzątanie w efekcie (podwójne w trybie deweloperskim)
  // psuło zrzut przekazany do okna przy otwarciu, a kilka zrzutów w sesji to najwyżej parę MB do odświeżenia strony.

  return (
    <div className="grid gap-2">
      <AttachmentAdder
        count={shots.length}
        listenPaste
        onAdd={(blob) => onChange([...latest.current, localShot(blob)])}
      />
      <Thumbs
        items={shots.map((shot, index) => ({
          key: shot.id,
          src: shot.url,
          label: `Zrzut ${index + 1}`,
          canRemove: true,
        }))}
        onOpen={(key) => setViewing(shots.find((shot) => shot.id === key) ?? null)}
        onRemove={(key) => {
          onChange(shots.filter((found) => found.id !== key))
        }}
      />
      <Viewer
        src={viewing?.url ?? null}
        title="Zrzut do zadania"
        onClose={() => setViewing(null)}
        onRedraw={
          viewing
            ? (blob) => {
                const current = viewing
                onChange(latest.current.map((shot) => (shot.id === current.id ? localShot(blob) : shot)))
                setViewing(null)
              }
            : undefined
        }
        redrawLabel="Zapisz zmiany"
      />
    </div>
  )
}

/** Zrzuty istniejącego zadania: dodaje je wykonawca albo zlecający, usuwa autor albo zlecający (z „Przywróć”). */
export function TaskAttachments({ storeId, task }: { storeId: string; task: StoreTask }) {
  const actions = useTaskAttachmentActions(storeId)
  const [viewing, setViewing] = useState<string | null>(null)
  const canAdd = (task.canComplete || task.canManage) && task.attachments.length < MAX_TASK_ATTACHMENTS
  const viewed = task.attachments.find((attachment) => attachment.id === viewing) ?? null
  const fail = (fallback: string) => (error: unknown) => toast.error(taskErrorMessage(error, fallback))

  const add = (blob: Blob) =>
    actions.add
      .mutateAsync({ taskId: task.id, file: blob })
      .then(() => toast.success('Dołączono zrzut'))
      .catch(fail('Nie udało się dołączyć zrzutu'))

  if (task.attachments.length === 0 && !canAdd) return null

  return (
    <div className="grid gap-2">
      {(task.canComplete || task.canManage) && (
        <AttachmentAdder count={task.attachments.length} onAdd={add} disabled={!canAdd} />
      )}
      <Thumbs
        items={task.attachments.map((attachment) => ({
          key: attachment.id,
          src: attachmentUrl(storeId, task.id, attachment.id),
          label: `${attachment.createdByName ?? 'Zrzut'} · ${new Date(attachment.createdAt).toLocaleString('pl-PL', {
            dateStyle: 'short',
            timeStyle: 'short',
          })}`,
          canRemove: attachment.canRemove,
        }))}
        onOpen={setViewing}
        onRemove={(attachmentId) =>
          actions.remove.mutate(
            { taskId: task.id, attachmentId },
            {
              onSuccess: () =>
                toast('Usunięto zrzut', {
                  action: {
                    label: 'Przywróć',
                    onClick: () =>
                      actions.restore.mutate(
                        { taskId: task.id, attachmentId },
                        { onError: fail('Nie udało się przywrócić zrzutu') },
                      ),
                  },
                }),
              onError: fail('Nie udało się usunąć zrzutu'),
            },
          )
        }
      />
      <Viewer
        src={viewed ? attachmentUrl(storeId, task.id, viewed.id) : null}
        title={viewed ? `Zrzut: ${viewed.createdByName ?? ''}`.trim() : ''}
        onClose={() => setViewing(null)}
        onRedraw={
          canAdd
            ? async (blob) => {
                await add(blob)
                setViewing(null)
              }
            : undefined
        }
        redrawLabel="Dołącz jako nowy"
      />
    </div>
  )
}

/**
 * Przyciski dodawania: zrzut bieżącej karty (okna znikają na czas zrzutu), wklejenie ze schowka i plik. Każdy obraz
 * przechodzi przez popisanie, a dopiero zapis z niego trafia do zadania.
 */
function AttachmentAdder({
  count,
  onAdd,
  disabled = false,
  listenPaste = false,
}: {
  count: number
  onAdd: (blob: Blob) => Promise<unknown> | void
  disabled?: boolean
  listenPaste?: boolean
}) {
  const [source, setSource] = useState<Blob | null>(null)
  const [capturing, setCapturing] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const full = count >= MAX_TASK_ATTACHMENTS

  useEffect(() => {
    if (!listenPaste || disabled || full) return
    const onPaste = (event: ClipboardEvent) => {
      const image = pastedImage(event)
      if (!image) return
      event.preventDefault()
      setSource(image)
    }
    document.addEventListener('paste', onPaste)
    return () => document.removeEventListener('paste', onPaste)
  }, [listenPaste, disabled, full])

  const capture = async () => {
    setCapturing(true)
    try {
      setSource(await captureCurrentTab())
    } catch (error) {
      if (!isCaptureCancelled(error)) {
        toast.error('Nie udało się zrobić zrzutu', {
          description: error instanceof Error ? error.message : undefined,
        })
      }
    } finally {
      setCapturing(false)
    }
  }

  const paste = async () => {
    try {
      const image = await readClipboardImage()
      if (image) setSource(image)
      else toast('W schowku nie ma obrazu', { description: 'Zrób zrzut (np. Win+Shift+S) i kliknij jeszcze raz.' })
    } catch {
      toast.error('Przeglądarka nie pozwoliła odczytać schowka', { description: 'Wklej obraz skrótem Ctrl+V.' })
    }
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <p className="mr-auto text-sm font-medium">
          Zrzuty ekranu{count > 0 ? ` (${count})` : ''}
          {full && <span className="ml-2 font-normal text-muted-foreground">najwyżej {MAX_TASK_ATTACHMENTS}</span>}
        </p>
        {canCaptureScreen() && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => void capture()}
            loading={capturing}
            disabled={disabled || full}
            title="Zrzut tej karty przeglądarki, bez okna zadania"
          >
            <MonitorIcon />
            Zrzut ekranu
          </Button>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => void paste()}
          disabled={disabled || full}
          title="Obraz ze schowka (Ctrl+V)"
        >
          <ClipboardPasteIcon />
          Wklej
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => fileRef.current?.click()}
          disabled={disabled || full}
        >
          <ImagePlusIcon />Z pliku
        </Button>
        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) setSource(file)
          }}
        />
      </div>
      <ScreenshotEditor
        source={source}
        onCancel={() => setSource(null)}
        onSave={async (image) => {
          await onAdd(image)
          setSource(null)
        }}
      />
    </>
  )
}

function Thumbs({
  items,
  onOpen,
  onRemove,
}: {
  items: Thumb[]
  onOpen: (key: string) => void
  onRemove: (key: string) => void
}) {
  if (items.length === 0) return null
  return (
    <ul className="flex flex-wrap gap-2">
      {items.map((item) => (
        <li key={item.key} className="group relative">
          <button
            type="button"
            onClick={() => onOpen(item.key)}
            title={item.label}
            className="block overflow-hidden rounded-md border bg-muted focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <img src={item.src} alt={item.label} loading="lazy" className="h-20 w-32 object-cover object-top" />
          </button>
          {item.canRemove && (
            <Button
              type="button"
              variant="secondary"
              size="icon-xs"
              aria-label={`Usuń: ${item.label}`}
              onClick={() => onRemove(item.key)}
              className="absolute -top-1.5 -right-1.5 rounded-full shadow-sm"
            >
              <XIcon />
            </Button>
          )}
        </li>
      ))}
    </ul>
  )
}

/** Podgląd zrzutu w dużym oknie; „Dorysuj” otwiera popisanie tego obrazu. */
function Viewer({
  src,
  title,
  onClose,
  onRedraw,
  redrawLabel,
}: {
  src: string | null
  title: string
  onClose: () => void
  onRedraw?: (blob: Blob) => Promise<void> | void
  redrawLabel: string
}) {
  const [editing, setEditing] = useState<Blob | null>(null)
  const [loading, setLoading] = useState(false)

  const redraw = async () => {
    if (!src) return
    setLoading(true)
    try {
      const response = await fetch(src, { credentials: 'same-origin' })
      if (!response.ok) throw new Error()
      setEditing(await response.blob())
    } catch {
      toast.error('Nie udało się wczytać zrzutu')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Dialog open={src !== null} onOpenChange={(open) => !open && onClose()}>
        <DialogContent className="gap-3 p-4 sm:max-w-[min(96vw,110rem)]">
          <DialogHeader>
            <DialogTitle>{title || 'Zrzut'}</DialogTitle>
            <DialogDescription className="sr-only">Podgląd zrzutu ekranu</DialogDescription>
          </DialogHeader>
          {src && (
            <div className="grid place-items-center overflow-auto rounded-lg bg-muted/60 p-2">
              <img src={src} alt={title} className="max-h-[calc(100svh-12rem)] max-w-full rounded shadow-sm" />
            </div>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            {src && (
              <Button
                variant="ghost"
                size="sm"
                render={<a href={src} target="_blank" rel="noreferrer" />}
                nativeButton={false}
              >
                <ExternalLinkIcon />
                Otwórz w nowej karcie
              </Button>
            )}
            {onRedraw && (
              <Button variant="outline" size="sm" onClick={() => void redraw()} loading={loading}>
                <PencilLineIcon />
                Dorysuj
              </Button>
            )}
          </div>
        </DialogContent>
      </Dialog>
      {onRedraw && (
        <ScreenshotEditor
          source={editing}
          saveLabel={redrawLabel}
          onCancel={() => setEditing(null)}
          onSave={async (image) => {
            await onRedraw(image)
            setEditing(null)
          }}
        />
      )}
    </>
  )
}
