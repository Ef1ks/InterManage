import { useState } from 'react'
import { CameraIcon, PlusIcon } from 'lucide-react'
import { toast } from 'sonner'

import { useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { ScreenshotEditor } from '@/features/tasks/ScreenshotEditor'
import { TaskDialog, type TaskDraft } from '@/features/tasks/TaskDialog'
import { useTaskSource } from '@/features/tasks/task-helpers'
import { canCaptureScreen, captureCurrentTab, isCaptureCancelled } from '@/lib/screen-capture'
import { cn } from '@/lib/utils'

const ROUND_BUTTON = cn(
  'glass-float grid size-10 place-items-center rounded-full text-foreground',
  'transition-transform duration-150 ease-out hover:scale-105 active:scale-95 motion-reduce:transition-none',
  'focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60',
)

/**
 * Szybkie zlecanie w lewym dolnym rogu: plus otwiera okno zlecenia od razu, a aparat najpierw robi zrzut bieżącej
 * karty do popisania. Zadanie ma link do strony, na której je zlecono. Tylko dla tego, kto może zlecać (właściciel
 * i konta z włączonym zlecaniem).
 */
export function QuickTaskButton() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const page = useTaskSource('SCREENSHOT')
  const [capturing, setCapturing] = useState(false)
  const [shot, setShot] = useState<Blob | null>(null)
  const [draft, setDraft] = useState<TaskDraft | null>(null)

  if (!me.canAssignTasks || !store) return null

  const capture = async () => {
    setCapturing(true)
    try {
      setShot(await captureCurrentTab())
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

  return (
    <>
      <div data-quick-task className="fixed bottom-3 left-2 z-50 flex flex-col gap-2">
        <button
          type="button"
          onClick={() => setDraft({ source: { ...page, kind: 'PAGE' } })}
          aria-label="Nowe zadanie"
          title="Nowe zadanie"
          className={ROUND_BUTTON}
        >
          <PlusIcon className="size-5" />
        </button>
        {canCaptureScreen() && (
          <button
            type="button"
            onClick={() => void capture()}
            disabled={capturing}
            aria-label="Zrzut ekranu i nowe zadanie"
            title="Zrzut ekranu i nowe zadanie"
            className={ROUND_BUTTON}
          >
            <CameraIcon className="size-5" />
          </button>
        )}
      </div>
      <ScreenshotEditor
        source={shot}
        saveLabel="Dalej: zleć zadanie"
        onCancel={() => setShot(null)}
        onSave={(image) => {
          setShot(null)
          setDraft({
            shots: [image],
            source: page,
          })
        }}
      />
      {draft && <TaskDialog storeId={store.id} open onOpenChange={(open) => !open && setDraft(null)} draft={draft} />}
    </>
  )
}
