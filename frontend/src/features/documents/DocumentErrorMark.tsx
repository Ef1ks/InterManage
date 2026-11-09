import { useState } from 'react'
import { BanIcon, TriangleAlertIcon } from 'lucide-react'
import { toast } from 'sonner'

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { useDocumentErrorMark } from '@/features/documents/api'
import { bonNumber } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { Document } from '@/lib/types'

const markedAtFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Przycisk „Oznacz jako błąd” w nagłówku przyjęcia PZ (z potwierdzeniem). Oznaczone przyjęcie znika z listy dokumentów
 * i z raportów, a toast pozwala od razu je przywrócić.
 */
export function MarkAsErrorButton({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { mark, unmark } = useDocumentErrorMark(storeId, document.type, document.id)
  const [confirming, setConfirming] = useState(false)
  if (!canEdit || !document.errorMarkable || document.errorMarkedAt) return null

  const onConfirm = () =>
    mark.mutate(undefined, {
      onSuccess: () => {
        setConfirming(false)
        toast.success(`Bon ${bonNumber(document.number)} oznaczony jako błąd`, {
          description: 'Nie ma go już na liście przyjęć ani w raportach.',
          action: {
            label: 'Przywróć',
            onClick: () =>
              unmark.mutate(undefined, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić przyjęcia')),
              }),
          },
        })
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się oznaczyć przyjęcia')),
    })

  return (
    <>
      <Button variant="destructive" onClick={() => setConfirming(true)}>
        <BanIcon />
        Oznacz jako błąd
      </Button>
      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Oznaczyć bon {bonNumber(document.number)} jako błąd?</AlertDialogTitle>
            <AlertDialogDescription>
              Przyjęcie zniknie z listy dokumentów i ze wszystkich raportów (przyjęcia za okres, weryfikacja zakupów,
              dostawy artykułów). Zostaje w aplikacji: znajdziesz je w filtrach listy pod „Oznaczone jako błąd” i możesz
              je przywrócić.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction variant="destructive" loading={mark.isPending} onClick={onConfirm}>
              Oznacz jako błąd
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

/** Baner nad przyjęciem oznaczonym jako błąd: kto i kiedy, z „Przywróć” dla osoby z prawem edycji. */
export function ErrorMarkBanner({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { mark, unmark } = useDocumentErrorMark(storeId, document.type, document.id)
  if (!document.errorMarkedAt) return null

  const onRestore = () =>
    unmark.mutate(undefined, {
      onSuccess: () =>
        toast.success(`Przywrócono bon ${bonNumber(document.number)}`, {
          description: 'Przyjęcie jest znowu na liście i w raportach.',
          action: { label: 'Cofnij', onClick: () => mark.mutate() },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić przyjęcia')),
    })

  return (
    <div
      role="status"
      className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-xl bg-red-200 px-4 py-3 text-sm text-red-950 dark:bg-red-950/60 dark:text-red-100"
    >
      <div className="flex items-start gap-2.5">
        <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">Przyjęcie oznaczone jako błąd</p>
          <p>
            {markedAtFormat.format(new Date(document.errorMarkedAt))}
            {document.errorMarkedBy && ` · ${document.errorMarkedBy}`} · nie ma go na liście dokumentów ani w raportach.
          </p>
        </div>
      </div>
      {canEdit && (
        <Button variant="outline" loading={unmark.isPending} onClick={onRestore}>
          Przywróć
        </Button>
      )}
    </div>
  )
}
