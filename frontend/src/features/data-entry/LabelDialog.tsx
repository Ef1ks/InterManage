import { useState } from 'react'
import { TagsIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError, downloadPostedFile, fetchPostedFile } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'

/** Artykuł do etykiet: EAN i ilość z tabeli (może być pusta, ułamkowa albo ujemna). */
export type LabelLine = { ean: string; quantity: number | null }

type Props = {
  storeId: string
  lines: LabelLine[]
  /** Etykiety tylko dla zaznaczonych wierszy (inaczej dla wszystkich artykułów tabeli). */
  selection: boolean
  /** Artykuły pominięte, bo nie mają EAN-u. */
  skipped: number
  open: boolean
  onOpenChange: (open: boolean) => void
}

const MAX_LABELS = 9999

/**
 * Liczba etykiet według ilości z tabeli: cała ilość bez znaku, a ilość ułamkowa (towar na wagę), pusta albo 0
 * daje jedną etykietę.
 */
function countFromQuantity(quantity: number | null) {
  const value = Math.abs(quantity ?? 0)
  if (value < 1 || !Number.isInteger(value)) return 1
  return Math.min(value, MAX_LABELS)
}

/**
 * Plik etykiet cenowych (ETIQUETTE) z artykułów tabeli. Gdy któryś ma ilość większą niż 1, pyta, czy drukować po
 * jednej etykiecie, czy tyle, ile w ilości. Plik zapisuje się w folderze plików PROTO wybranym raz w przeglądarce,
 * skąd program sklepu sam go wczytuje; bez folderu się pobiera.
 */
export function LabelDialog({ storeId, lines, selection, skipped, open, onOpenChange }: Props) {
  const folder = useProtoFolder(storeId)
  const [pending, setPending] = useState<'one' | 'quantity' | null>(null)
  const byQuantity = lines.reduce((sum, line) => sum + countFromQuantity(line.quantity), 0)
  const asksCount = lines.some((line) => countFromQuantity(line.quantity) > 1)

  const generate = async (mode: 'one' | 'quantity') => {
    setPending(mode)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const path = `/stores/${storeId}/data-entry/export/labels`
      const body = {
        lines: lines.map((line) => ({ ean: line.ean, count: mode === 'one' ? 1 : countFromQuantity(line.quantity) })),
      }
      const fallbackName = 'ETIQUETTE.traite0'
      if (!folderReady) {
        await downloadPostedFile(path, body, fallbackName)
        toast.success('Pobrano plik etykiet', { description: 'Folder protokołów nie jest wybrany w tej przeglądarce.' })
      } else {
        const { fileName, blob } = await fetchPostedFile(path, body, fallbackName)
        await saveToFolder(folder.key, fileName, blob)
        toast.success('Zapisano plik etykiet', { description: fileName })
      }
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku etykiet')
    } finally {
      setPending(null)
    }
  }

  const articles = `${lines.length} ${lines.length === 1 ? 'artykuł' : 'artykułów'}`
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Etykiety cenowe</DialogTitle>
          <DialogDescription>
            {selection ? `Zaznaczone: ${articles}.` : `Wszystkie artykuły tabeli: ${articles}.`}
            {skipped > 0 && ` Bez EAN-u: ${skipped}, nie trafią do pliku.`}
          </DialogDescription>
        </DialogHeader>

        {asksCount ? (
          <>
            <p className="text-sm">Część artykułów ma ilość większą niż 1. Ile etykiet wydrukować?</p>
            <div className="grid gap-2 sm:grid-cols-2">
              <Button
                variant="outline"
                size="xl"
                loading={pending === 'one'}
                disabled={pending !== null}
                onClick={() => generate('one')}
              >
                Po 1 etykiecie ({lines.length})
              </Button>
              <Button
                variant="outline"
                size="xl"
                loading={pending === 'quantity'}
                disabled={pending !== null}
                onClick={() => generate('quantity')}
              >
                Tyle, ile w ilości ({byQuantity})
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">Towar na wagę (ilość z ułamkiem) zawsze dostaje 1 etykietę.</p>
          </>
        ) : (
          <Button
            size="xl"
            loading={pending === 'one'}
            disabled={pending !== null || lines.length === 0}
            onClick={() => generate('one')}
          >
            <TagsIcon />
            Zapisz etykiety ({lines.length})
          </Button>
        )}

        <SavedFolder folder={folder} />
      </DialogContent>
    </Dialog>
  )
}
