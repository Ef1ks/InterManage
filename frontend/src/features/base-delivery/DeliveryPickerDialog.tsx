import { useState } from 'react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError, api } from '@/lib/api'
import type { BaseDelivery } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useBaseDeliveries, useUploadBaseDelivery } from './api'

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Wgrany albo wybrany z listy dokument (z liniami): strona pyta potem o nośniki. */
  onOpen: (delivery: BaseDelivery) => void
}

/**
 * Wczytanie dokumentu z bazy: wgranie pliku .xls (ten sam bon wgrany ponownie zastępuje poprzedni) albo wybór
 * dokumentu wgranego wcześniej.
 */
export function DeliveryPickerDialog({ storeId, open, onOpenChange, onOpen }: Props) {
  const list = useBaseDeliveries(storeId, open)
  const upload = useUploadBaseDelivery(storeId)
  const [file, setFile] = useState<File | undefined>()
  const [opening, setOpening] = useState<string | null>(null)

  const openExisting = async (id: string) => {
    setOpening(id)
    try {
      onOpen(await api<BaseDelivery>(`/stores/${storeId}/base-deliveries/${id}`))
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się otworzyć dokumentu')
    } finally {
      setOpening(null)
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) upload.reset()
        onOpenChange(next)
      }}
    >
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Dokumenty z bazy</DialogTitle>
          <DialogDescription>
            Otwórz dokument wgrany wcześniej albo przeciągnij nowy plik .xls. Potem wybierzesz nośniki do tabeli.
          </DialogDescription>
        </DialogHeader>

        <FileDropZone
          accept={['.xls', '.xlsx', '.htm', '.html']}
          pendingFile={upload.isPending ? file : undefined}
          title="Przeciągnij plik z bazy albo kliknij"
          hint=".xls (albo .xlsx, albo sheet001.htm ze strony zapisanej przez Excela), ten sam bon wgrany ponownie zastępuje poprzedni"
          className="py-6"
          onReject={(name) => toast.error(`${name}: wgraj plik .xls z bazy`)}
          onFile={(chosen) => {
            setFile(chosen)
            upload.mutate(chosen, {
              onSuccess: (delivery) => {
                toast.success(`Wgrano bon ${delivery.number}`, {
                  description: `${delivery.lines.length} linii na ${delivery.carriers.length} nośnikach`,
                })
                onOpen(delivery)
              },
            })
          }}
        />
        {upload.error && <ImportError error={upload.error} />}

        <section className="grid gap-2">
          <h3 className="text-sm font-medium">Wgrane wcześniej</h3>
          {list.isPending ? (
            <p className="text-sm text-muted-foreground">Wczytywanie…</p>
          ) : list.data && list.data.length > 0 ? (
            <ul className="max-h-72 divide-y overflow-y-auto rounded-lg border">
              {list.data.map((delivery) => (
                <li key={delivery.id}>
                  <button
                    type="button"
                    disabled={opening !== null}
                    onClick={() => void openExisting(delivery.id)}
                    className={cn(
                      'flex w-full items-center gap-3 px-3 py-2 text-left text-sm hover:bg-accent disabled:opacity-60',
                      opening === delivery.id && 'bg-accent',
                    )}
                  >
                    <span className="font-medium tabular-nums">{delivery.number}</span>
                    <span className="text-muted-foreground">{formatDocumentDate(delivery.deliveryDate)}</span>
                    <span className="ml-auto text-xs text-muted-foreground tabular-nums">
                      {delivery.lineCount} linii · {delivery.uploadedBy ?? 'wgrany'}{' '}
                      {new Date(delivery.uploadedAt).toLocaleString('pl-PL', {
                        day: 'numeric',
                        month: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">Nie wgrano jeszcze żadnego dokumentu z bazy.</p>
          )}
        </section>
      </DialogContent>
    </Dialog>
  )
}
