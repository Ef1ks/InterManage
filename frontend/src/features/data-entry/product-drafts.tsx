import { useState } from 'react'
import { toast } from 'sonner'

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { ApiError, api } from '@/lib/api'

import type { ProductFilePayload } from './api'

/** Nowe artykuły z pliku CADBEZP (bez zmian artykułów z kartoteki) i numer dostawcy do pytania. */
export type NewArticles = { payload: ProductFilePayload; supplierCode: string | null }

/** Zapisany plik CADBEZP z nowymi artykułami. */
export type SavedProductFile = NewArticles & { fileName: string }

type DraftResult = { createdIds: string[]; knownCount: number; withoutEanCount: number }

/**
 * Pytanie po zapisie plików CADBEZP z nowymi artykułami (sama zmiana artykułów z kartoteki o nic nie pyta), czy
 * wprowadzono je w programie sklepu. „Tak” zapisuje je jako szkice w bazie: kolejny raport kartoteki dopisze resztę
 * danych (ITM8, stan…), a artykuł, którego w nim nie będzie, usunie. Zwraca `ask` do wywołania po zapisie i okno do
 * wyrenderowania.
 */
export function useProductDraftConfirm(storeId: string) {
  const [files, setFiles] = useState<SavedProductFile[] | null>(null)
  const [confirming, setConfirming] = useState(false)
  const path = `/stores/${storeId}/data-entry/product-drafts`

  const ask = (saved: SavedProductFile[]) => {
    if (saved.length > 0) setFiles(saved)
  }

  const confirm = async () => {
    if (!files) return
    setConfirming(true)
    try {
      const results: DraftResult[] = []
      for (const file of files) {
        results.push(
          await api<DraftResult>(path, { method: 'POST', json: { fileName: file.fileName, file: file.payload } }),
        )
      }
      const ids = results.flatMap((result) => result.createdIds)
      const known = results.reduce((sum, result) => sum + result.knownCount, 0)
      setFiles(null)
      toast.success(
        ids.length > 0 ? `Dodano do bazy nowe artykuły: ${ids.length}` : 'W plikach nie było nowych artykułów',
        {
          description: [
            ids.length > 0
              ? 'Resztę danych dopisze kolejny raport kartoteki, a artykuły, których w nim nie będzie, usunie.'
              : null,
            known > 0 ? `Już w kartotece (zmienione): ${known}.` : null,
          ]
            .filter(Boolean)
            .join(' '),
          duration: 10_000,
          action:
            ids.length > 0
              ? {
                  label: 'Cofnij',
                  onClick: () =>
                    void api(`${path}/undo`, { method: 'POST', json: { ids } })
                      .then(() => toast('Cofnięto dodanie artykułów'))
                      .catch((error) =>
                        toast.error(error instanceof ApiError ? error.message : 'Nie udało się cofnąć'),
                      ),
                }
              : undefined,
        },
      )
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się dodać artykułów do bazy')
    } finally {
      setConfirming(false)
    }
  }

  const supplierCodes = [...new Set(files?.flatMap((file) => file.supplierCode ?? []) ?? [])]

  const dialog = (
    <AlertDialog open={files !== null} onOpenChange={(open) => !open && !confirming && setFiles(null)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Czy wprowadziłeś nowe artykuły?</AlertDialogTitle>
          {supplierCodes.length > 0 && (
            <AlertDialogDescription className="text-3xl font-bold text-foreground tabular-nums">
              dla dostawcy NR {supplierCodes.join(', ')}
            </AlertDialogDescription>
          )}
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={confirming}>Nie</AlertDialogCancel>
          <Button loading={confirming} onClick={() => void confirm()}>
            Tak
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )

  return { ask, dialog }
}
