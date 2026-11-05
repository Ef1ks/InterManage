import { useQueryClient } from '@tanstack/react-query'
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
import { useHiddenHint } from '@/lib/use-hidden-hint'

import type { ProductFilePayload } from './api'

/** Nowe artykuły z pliku CADBEZP (bez zmian artykułów z kartoteki) i numer dostawcy do pytania. */
export type NewArticles = { payload: ProductFilePayload; supplierCode: string | null }

/** Zapisany plik CADBEZP z nowymi artykułami. */
export type SavedProductFile = NewArticles & { fileName: string }

type DraftResult = { createdIds: string[]; knownCount: number; withoutEanCount: number }

/** Pytanie wyłączone w ustawieniach zapisu (w tej przeglądarce): działa jak „Tak”, a toast z „Cofnij” zostaje. */
export const PRODUCT_DRAFT_PROMPT = 'product-draft-prompt'

/**
 * Pytanie po zapisie plików CADBEZP z nowymi artykułami (sama zmiana artykułów z kartoteki o nic nie pyta), czy
 * wprowadzono je w programie sklepu. „Tak” zapisuje je jako szkice w bazie: kolejny raport kartoteki dopisze resztę
 * danych (ITM8, stan…), a artykuł, którego w nim nie będzie, usunie. Zwraca `ask` do wywołania po zapisie i okno do
 * wyrenderowania. Po dodaniu i po „Cofnij” odświeża zapytania, a `onChanged` dostaje EAN-y z plików, żeby tabela bez
 * zapytania o dokument (wprowadzanie danych) mogła zmienić swoje wiersze.
 */
export function useProductDraftConfirm(storeId: string, onChanged?: (eans: string[]) => void) {
  const queryClient = useQueryClient()
  const [files, setFiles] = useState<SavedProductFile[] | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [promptOff] = useHiddenHint(PRODUCT_DRAFT_PROMPT)
  const path = `/stores/${storeId}/data-entry/product-drafts`

  const confirm = async (saved: SavedProductFile[]) => {
    setConfirming(true)
    try {
      const results: DraftResult[] = []
      for (const file of saved) {
        results.push(
          await api<DraftResult>(path, { method: 'POST', json: { fileName: file.fileName, file: file.payload } }),
        )
      }
      const ids = results.flatMap((result) => result.createdIds)
      const eans = saved.flatMap((file) => file.payload.rows.flatMap((row) => row.ean ?? []))
      const changed = () => {
        void queryClient.invalidateQueries()
        onChanged?.(eans)
      }
      changed()
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
                      .then(() => {
                        changed()
                        toast('Cofnięto dodanie artykułów')
                      })
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

  const ask = (saved: SavedProductFile[]) => {
    if (saved.length === 0) return
    if (promptOff) void confirm(saved)
    else setFiles(saved)
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
          <Button loading={confirming} onClick={() => files && void confirm(files)}>
            Tak
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )

  return { ask, dialog }
}
