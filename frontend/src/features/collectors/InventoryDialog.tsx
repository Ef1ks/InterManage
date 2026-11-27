import { useState } from 'react'
import { useNavigate } from 'react-router'
import { CalendarClockIcon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess } from '@/lib/saved-folders'

import { markLoaded, useCreateCollectorFile, writeCollectorFile, type InventoryItem } from './files-api'
import { collectorPaths } from './paths'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Inwentaryzacja z listy kolektora: „Zrób teraz” zapisuje plik inwentaryzacji dla programu sklepu od razu (w historii
 * jako wgrana), „Na przyszłość” zostawia ją w historii jako czekającą, a plik robi się stamtąd w wybranym dniu.
 */
export function InventoryDialog({
  storeId,
  listIds,
  items,
  open,
  onOpenChange,
}: {
  storeId: string
  /** Listy z kolektora albo (bez nich) pozycje z tabeli. */
  listIds?: string[]
  items?: InventoryItem[]
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const folder = useProtoFolder(storeId)
  const create = useCreateCollectorFile(storeId)
  const navigate = useNavigate()
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState<'now' | 'later' | null>(null)
  const openHistory = { label: 'Historia', onClick: () => navigate(collectorPaths.inventories) }

  const skippedNote = (skipped: number) =>
    skipped > 0 ? ` Pominięto bez EAN-u albo z ilością na minusie: ${skipped}.` : ''

  const now = async () => {
    setBusy('now')
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const { file, skipped } = await create.mutateAsync({ kind: 'INVENTORY', listIds, items, note })
      const { fileName, downloaded } = await writeCollectorFile(storeId, folder.key, file.id, folderReady)
      if (downloaded) {
        toast.success('Pobrano plik inwentaryzacji', {
          description:
            'Folder protokołów nie jest wybrany w tej przeglądarce. Gdy skopiujesz plik, oznacz go.' +
            skippedNote(skipped),
          duration: 15_000,
          action: {
            label: 'Oznacz jako wgrany',
            onClick: () =>
              void markLoaded(storeId, file.id, fileName).catch(() => toast.error('Nie udało się oznaczyć')),
          },
        })
      } else {
        toast.success('Inwentaryzacja wgrana do programu sklepu', {
          description: fileName + skippedNote(skipped),
          action: openHistory,
        })
      }
      onOpenChange(false)
      setNote('')
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się zrobić inwentaryzacji'), {
        description: 'Jeśli lista trafiła już do historii, wgrasz ją stamtąd.',
        action: openHistory,
      })
    } finally {
      setBusy(null)
    }
  }

  const later = async () => {
    setBusy('later')
    try {
      const { skipped } = await create.mutateAsync({ kind: 'INVENTORY', listIds, items, note })
      toast.success('Inwentaryzacja czeka w historii', {
        description: 'Plik zrobisz w Kolektory → Inwentaryzacje.' + skippedNote(skipped),
        action: openHistory,
      })
      onOpenChange(false)
      setNote('')
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się przygotować inwentaryzacji'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {items ? `Inwentaryzacja z tabeli (${items.length})` : 'Inwentaryzacja z kolektora'}
          </DialogTitle>
          <DialogDescription>
            Policzone ilości {items ? 'z tabeli' : 'z listy'} trafią do programu sklepu jako stan. Każda inwentaryzacja
            zostaje w historii.
          </DialogDescription>
        </DialogHeader>
        <TextField
          label="Notatka (np. dział albo dzień wgrania)"
          value={note}
          maxLength={200}
          autoComplete="off"
          onChange={(event) => setNote(event.target.value)}
        />
        <div className="grid gap-2 sm:grid-cols-2">
          <Button size="lg" loading={busy === 'now'} disabled={busy !== null} onClick={() => void now()}>
            <UploadIcon />
            Zrób teraz
          </Button>
          <Button
            size="lg"
            variant="outline"
            loading={busy === 'later'}
            disabled={busy !== null}
            onClick={() => void later()}
          >
            <CalendarClockIcon />
            Na przyszłość
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
