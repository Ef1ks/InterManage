import { useState } from 'react'
import { CameraIcon } from 'lucide-react'
import { toast } from 'sonner'

import { useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { ScreenshotEditor } from '@/features/tasks/ScreenshotEditor'
import { TaskDialog, type TaskDraft } from '@/features/tasks/TaskDialog'
import { useTaskSource } from '@/features/tasks/task-helpers'
import { canCaptureScreen, captureCurrentTab, isCaptureCancelled } from '@/lib/screen-capture'
import { cn } from '@/lib/utils'

/**
 * Szybki zrzut w lewym dolnym rogu: zrzut bieżącej karty, popisanie i od razu okno zlecenia z tym zrzutem i linkiem
 * do strony, na której był. Tylko dla tego, kto może zlecać (właściciel i konta z włączonym zlecaniem).
 */
export function QuickTaskButton() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const page = useTaskSource('SCREENSHOT')
  const [capturing, setCapturing] = useState(false)
  const [shot, setShot] = useState<Blob | null>(null)
  const [draft, setDraft] = useState<TaskDraft | null>(null)

  if (!me.canAssignTasks || !store || !canCaptureScreen()) return null

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
      <button
        type="button"
        data-quick-task
        onClick={() => void capture()}
        disabled={capturing}
        aria-label="Zrzut ekranu i nowe zadanie"
        title="Zrzut ekranu i nowe zadanie"
        className={cn(
          'glass-float fixed bottom-3 left-2 z-50 grid size-10 place-items-center rounded-full text-foreground',
          'transition-transform duration-150 ease-out hover:scale-105 active:scale-95 motion-reduce:transition-none',
          'focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60',
        )}
      >
        <CameraIcon className="size-5" />
      </button>
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
