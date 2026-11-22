import { useState } from 'react'
import { useLocation } from 'react-router'
import { MessageSquareWarningIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { FeedbackDialog } from '@/features/feedback/FeedbackDialog'
import { useCurrentStore } from '@/features/stores/current-store'
import { ScreenshotEditor } from '@/features/tasks/ScreenshotEditor'
import { usePageLabel } from '@/features/tasks/task-helpers'
import { canCaptureScreen, captureCurrentTab, isCaptureCancelled } from '@/lib/screen-capture'

/**
 * Zgłoszenie poprawki albo błędu z prawego górnego rogu: najpierw zrzut bieżącej strony do popisania (jak szybkie
 * zlecanie zadań), potem okno z opisem. Bez zrzutu (odmowa zgody, przeglądarka bez Screen Capture) od razu okno, a zrzut
 * da się dołożyć w nim (wklejenie, plik). Zgłoszenie pamięta stronę i sklep, a czyta je właściciel w „Zgłoszeniach”.
 */
export function FeedbackButton() {
  const store = useCurrentStore()
  const location = useLocation()
  const pageLabel = usePageLabel()
  const [capturing, setCapturing] = useState(false)
  const [shot, setShot] = useState<Blob | null>(null)
  const [dialog, setDialog] = useState<{ shots: Blob[] } | null>(null)
  // Strona z chwili kliknięcia: okno i zrzut nie zmieniają adresu, ale zapis i tak idzie z tym, co było widać.
  const [page, setPage] = useState({ url: '', label: '' })

  const start = async () => {
    setPage({ url: `${location.pathname}${location.search}`, label: pageLabel })
    if (!canCaptureScreen()) {
      setDialog({ shots: [] })
      return
    }
    setCapturing(true)
    try {
      setShot(await captureCurrentTab())
    } catch (error) {
      if (!isCaptureCancelled(error)) {
        toast.error('Nie udało się zrobić zrzutu', {
          description: error instanceof Error ? error.message : undefined,
        })
      }
      // Bez zrzutu zgłoszenie i tak ma sens: opis i ewentualnie zrzut wklejony w oknie.
      setDialog({ shots: [] })
    } finally {
      setCapturing(false)
    }
  }

  return (
    <>
      <Button
        data-quick-task
        variant="ghost"
        size="icon"
        onClick={() => void start()}
        disabled={capturing}
        aria-label="Zgłoś poprawkę lub błąd"
        title="Zgłoś poprawkę lub błąd"
      >
        <MessageSquareWarningIcon />
      </Button>
      <ScreenshotEditor
        source={shot}
        saveLabel="Dalej: opisz"
        onCancel={() => {
          setShot(null)
          setDialog({ shots: [] })
        }}
        onSave={(image) => {
          setShot(null)
          setDialog({ shots: [image] })
        }}
      />
      {dialog && (
        <FeedbackDialog
          open
          onOpenChange={(open) => !open && setDialog(null)}
          shots={dialog.shots}
          pageUrl={page.url}
          pageLabel={page.label}
          storeId={store?.id ?? null}
        />
      )}
    </>
  )
}
