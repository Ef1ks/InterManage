import { CheckIcon, DownloadIcon, RefreshCwIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { BaseDelivery } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useDownloadPortalDelivery, usePortalDeliveries } from './api'

/** Zakresy listy: ile dni wstecz pytamy portal (0 = dziś). */
const RANGES = [
  { days: 0, label: 'Dziś' },
  { days: 3, label: '3 dni' },
  { days: 7, label: '7 dni' },
  { days: 14, label: '14 dni' },
  { days: 30, label: '30 dni' },
]

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Pobrany dokument (z liniami): strona wczytuje go do tabeli. */
  onOpen: (delivery: BaseDelivery) => void
}

/**
 * Pobranie dokumentu dostawy z bazy prosto z portalu Grupy Muszkieterów: lista bonów sklepu z ostatnich dni, klik
 * pobiera bon, zapisuje go jak wgrany plik i wczytuje do tabeli. Logowanie kluczem API sklepu z modułu MEA.
 */
export function PortalDeliveryDialog({ storeId, open, onOpenChange, onOpen }: Props) {
  const [days, setDays] = useState(7)
  const list = usePortalDeliveries(storeId, days, open)
  const download = useDownloadPortalDelivery(storeId)
  const [pending, setPending] = useState<string | null>(null)

  const take = (number: string) => {
    setPending(number)
    download.mutate(number, {
      onSuccess: (delivery) => {
        toast.success(`Pobrano bon ${delivery.number}`, {
          description: `${delivery.lines.length} linii na ${delivery.carriers.length} nośnikach`,
        })
        onOpen(delivery)
      },
      onError: (error) => {
        const rowErrors =
          error instanceof ApiError && Array.isArray(error.problem.rowErrors)
            ? (error.problem.rowErrors as string[])
            : []
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać dokumentu', {
          description: rowErrors.length > 0 ? rowErrors.slice(0, 5).join('\n') : undefined,
          duration: 10_000,
        })
      },
      onSettled: () => setPending(null),
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Pobierz dokument z bazy</DialogTitle>
          <DialogDescription>
            Bony dostaw sklepu z portalu Grupy Muszkieterów. Kliknij bon, a pobierze się i trafi do tabeli.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Zakres dni">
          {RANGES.map((range) => (
            <Button
              key={range.days}
              size="sm"
              variant={days === range.days ? 'default' : 'outline'}
              aria-pressed={days === range.days}
              onClick={() => setDays(range.days)}
            >
              {range.label}
            </Button>
          ))}
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            loading={list.isFetching}
            onClick={() => void list.refetch()}
            title="Odśwież listę z portalu"
          >
            <RefreshCwIcon />
            Odśwież
          </Button>
        </div>

        {list.isPending ? (
          <p className="text-sm text-muted-foreground">Pobieranie listy z portalu…</p>
        ) : list.error ? (
          <p className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {list.error instanceof ApiError ? list.error.message : 'Nie udało się pobrać listy z portalu'}
          </p>
        ) : list.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">Portal nie ma bonów z tego okresu. Wybierz dłuższy zakres.</p>
        ) : (
          <div className="max-h-96 overflow-y-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-popover text-left text-xs text-muted-foreground">
                <tr className="border-b">
                  <th className="px-3 py-2 font-medium">Numer bonu</th>
                  <th className="px-3 py-2 font-medium">Data</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y">
                {list.data.map((document) => (
                  <tr
                    key={document.number}
                    className={cn(
                      'cursor-pointer hover:bg-accent',
                      pending === document.number && 'bg-accent',
                      pending !== null && 'pointer-events-none opacity-70',
                    )}
                    onClick={() => take(document.number)}
                  >
                    <td className="px-3 py-2 font-medium tabular-nums">{document.number}</td>
                    <td className="px-3 py-2 tabular-nums">
                      {document.date ? formatDocumentDate(document.date) : '–'}
                    </td>
                    <td className="px-3 py-1.5 text-right">
                      <span className="inline-flex items-center gap-2">
                        {document.loaded && (
                          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-200 px-2 py-0.5 text-xs font-medium text-emerald-950 dark:bg-emerald-900/60 dark:text-emerald-100">
                            <CheckIcon className="size-3" />
                            wgrany
                          </span>
                        )}
                        <Button
                          size="sm"
                          variant={document.loaded ? 'outline' : 'default'}
                          loading={pending === document.number}
                          disabled={pending !== null}
                          onClick={(event) => {
                            event.stopPropagation()
                            take(document.number)
                          }}
                        >
                          <DownloadIcon />
                          {document.loaded ? 'Pobierz ponownie' : 'Pobierz'}
                        </Button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
