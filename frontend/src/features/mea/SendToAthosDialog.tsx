import { SendIcon } from 'lucide-react'
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { api, ApiError, fetchPostedFile } from '@/lib/api'
import { ensureFolderAccess, folderSavingSupported, saveToFolder } from '@/lib/saved-folders'
import type { MeaDocument } from '@/lib/types'
import type { MeaOrderDraft } from '@/store/meaOrderDraftSlice'

import { comrnisFolderSpec } from './athos-history'
import { FolderRow } from './FolderRow'

const money = (value: number) => value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * „Wyślij do ATHOS” jak w eMEA: serwer składa plik V… ze wszystkimi pozycjami MEA (kartony R1-R4, bez kartonów
 * zera) i oddaje go jako ZIP R…, który zapisujemy w folderze appres\comrnis serwera sklepu; ATHOS sam go stamtąd
 * bierze. Zgoda na zapis w folderze tylko zaraz po kliknięciu, więc pytamy o nią, zanim cokolwiek pójdzie do serwera.
 */
export function SendToAthosDialog({
  storeId,
  document,
  draft,
  totals,
  open,
  onOpenChange,
}: {
  storeId: string
  document: MeaDocument
  draft: MeaOrderDraft
  totals: { coli: number; value: number; ordered: number }
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const [sending, setSending] = useState(false)
  const spec = comrnisFolderSpec(storeId)
  const [previous] = document.orders

  const send = async () => {
    setSending(true)
    try {
      if (!(await ensureFolderAccess(spec.key, 'readwrite'))) {
        toast.error('Wybierz folder comrnis i zezwól na zapis, żeby wysłać zamówienie do ATHOS')
        return
      }
      const lines = document.lines.map((line) => ({
        itm8: line.meaItm8,
        colis: (draft[line.meaItm8]?.quantities ?? []).slice(0, 4),
      }))
      const { fileName, blob, headers } = await fetchPostedFile(
        `/stores/${storeId}/mea/documents/${document.number}/athos-order`,
        { lines },
        'zamowienie.zip',
      )
      // Najpierw plik na dysku: dopiero zapisany liczy się jako wysłany.
      await saveToFolder(spec.key, fileName, blob)
      const orderId = headers.get('X-Mea-Order-Id')
      if (orderId) {
        try {
          await api(`/stores/${storeId}/mea/athos-orders/${orderId}/saved`, { method: 'POST' })
        } catch {
          toast.warning(`Plik ${fileName} jest w folderze, ale aplikacja nie zapisała, że MEA wysłano`)
        }
      }
      void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'mea'] })
      toast.success(`Zapisano zamówienie ${fileName} w folderze ATHOS`)
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wysłać zamówienia do ATHOS')
    } finally {
      setSending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Wyślij MEA {document.number} do ATHOS</DialogTitle>
          <DialogDescription>
            Zamówienie trafi do folderu comrnis na serwerze sklepu, skąd ATHOS sam je bierze. Plik ma wszystkie pozycje
            MEA, pozycje bez kartonów idą z zerami, jak w eMEA.
          </DialogDescription>
        </DialogHeader>

        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Pozycje z kartonami</dt>
          <dd className="font-semibold tabular-nums">
            {totals.ordered} z {document.lines.length}
          </dd>
          <dt className="text-muted-foreground">Kartony (COLI)</dt>
          <dd className="font-semibold tabular-nums">{totals.coli}</dd>
          <dt className="text-muted-foreground">Wartość zakupu</dt>
          <dd className="tabular-nums">{money(totals.value)} zł</dd>
        </dl>

        {totals.coli === 0 && (
          <p className="rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900/70 dark:text-amber-50">
            Nie wpisano żadnych kartonów. ATHOS dostanie zamówienie z samymi zerami.
          </p>
        )}
        {previous && (
          <p className="rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900/70 dark:text-amber-50">
            To MEA zapisano już do ATHOS{' '}
            {new Date(previous.savedAt).toLocaleString('pl-PL', {
              day: '2-digit',
              month: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
            })}{' '}
            ({previous.colis} COLI). Nowy plik zastąpi {previous.fileName} w folderze, o ile ATHOS go jeszcze nie
            pobrał.
          </p>
        )}

        {folderSavingSupported ? (
          <FolderRow
            storeId={storeId}
            spec={spec}
            mode="readwrite"
            pathLabel="Ścieżka folderu comrnis w sklepie"
            pathHint="Np. \\SR10023\appres\comrnis"
            doneText="Zamówienie zapisze się w folderze"
          />
        ) : (
          <p className="text-sm text-muted-foreground">Zapis do folderu ATHOS działa w Chrome i Edge.</p>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button size="xl" onClick={send} loading={sending} disabled={!folderSavingSupported}>
            <SendIcon />
            Zapisz do ATHOS
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
