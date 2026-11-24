import { CheckCheckIcon, PencilIcon, TriangleAlertIcon } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useAgentComputers } from '@/features/agent-macros/api'
import { RemoteMacroButton, RemoteMacroStatus, useRemoteMacro } from '@/features/agent-macros/RemoteMacro'
import { useCan } from '@/features/auth/api'
import type { DataEntryRow } from '@/features/data-entry/rows'
import { ApiError } from '@/lib/api'
import type { BaseDelivery, BaseReceivingSupplier } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useBaseReceiving, useEnterBaseOrder } from './api'
import { findDifferences } from './differences'
import { DifferencesView } from './DifferencesView'

type Props = {
  storeId: string
  delivery: BaseDelivery | null
  /** Wszystkie wiersze tabeli (bez względu na filtry). */
  rows: DataEntryRow[]
  /** Nośniki dokumentu, których nie ma w tabeli: różnice byłyby fałszywe. */
  missingCarriers: number
  open: boolean
  onOpenChange: (open: boolean) => void
}

const time = (value: string) =>
  new Date(value).toLocaleString('pl-PL', { day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit' })

/**
 * „Wprowadź dostawę”: makro na wolnym komputerze sklepu integruje pliki bonu z Obsługi BL bazy z zamówieniami
 * w Mercalys i zapisuje bony dostawy, które agent od razu wysyła na serwer; okno porównuje je z tabelą („Znajdź
 * różnice”), a „Zatwierdź” wpisuje Nr BL w nagłówkach i kończy dostawy.
 */
export function ReceiveDialog({ storeId, delivery, rows, missingCarriers, open, onOpenChange }: Props) {
  const canRun = useCan('DATA_ENTRY', 'EDIT')
  const { data: computers = [] } = useAgentComputers(storeId, open && canRun)
  const receiving = useBaseReceiving(storeId, open ? (delivery?.id ?? null) : null)
  const remote = useRemoteMacro(storeId)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [closeOn, setCloseOn] = useState<string | undefined>()
  const suppliers = receiving.data?.suppliers ?? []
  const withOrder = suppliers.filter((supplier) => supplier.order)
  const toClose = withOrder.filter((supplier) => !supplier.order?.closedAt)
  const notes = withOrder.flatMap((supplier) => (supplier.note?.content ? [supplier.note.content] : []))
  const missingNotes = withOrder.filter((supplier) => !supplier.note?.content)
  const staleNotes = withOrder.filter(isStale)
  const result = notes.length > 0 ? findDifferences(rows, notes) : null
  const differences = result?.differences.length ?? 0
  const ready = canRun && computers.length > 0

  const receive = async (agentId?: string) => {
    if (!delivery) return
    const job = await remote.run({ macro: 'base-receive', deliveryId: delivery.id, agentId })
    await receiving.refetch()
    if (job?.status === 'DONE') toast.success('Dostawa wprowadzona do Mercalys', { description: 'Sprawdź różnice.' })
    else if (job && job.status !== 'CANCELLED') toast.error('Dostawa nie weszła w całości do Mercalys')
  }

  const close = async () => {
    setConfirmOpen(false)
    if (!delivery) return
    const job = await remote.run({ macro: 'base-close', deliveryId: delivery.id, agentId: closeOn })
    await receiving.refetch()
    if (job?.status === 'DONE') toast.success(`Dostawa ${delivery.number} zakończona w Mercalys`)
    else if (job && job.status !== 'CANCELLED') toast.error('Dostawa nie została zakończona w całości')
  }

  return (
    <Dialog open={open && delivery !== null} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Wprowadź dostawę · bon {delivery?.number}</DialogTitle>
          <DialogDescription>
            Makro na komputerze sklepu wprowadza bon do zamówień w Mercalys, a tu od razu widać różnice względem tabeli.
            Najpierw uzupełnij ITM8.
          </DialogDescription>
        </DialogHeader>

        {remote.job && <RemoteMacroStatus job={remote.job} onCancel={remote.cancel} />}

        {!ready && (
          <Warning>
            {canRun
              ? 'Sklep nie ma komputera z agentem, więc makra nie ma gdzie uruchomić.'
              : 'Makro może zlecić osoba z prawem zmian we wprowadzaniu danych.'}
          </Warning>
        )}

        <section className="grid gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h3 className="text-sm font-semibold">1. Wprowadzenie do Mercalys</h3>
            {ready && (
              <RemoteMacroButton
                computers={computers}
                label={withOrder.length > 0 ? 'Dokończ albo pobierz bony ponownie' : 'Wprowadź na wolnym komputerze'}
                busy={remote.busy && remote.job?.kind !== 'BASE_CLOSE'}
                disabled={remote.busy}
                onRun={receive}
              />
            )}
          </div>
          {receiving.isPending ? (
            <LoadingPanel className="h-28 rounded-lg" />
          ) : receiving.isError ? (
            <Warning>Nie udało się wczytać stanu dostawy.</Warning>
          ) : (
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-sm tabular-nums">
                <thead className="bg-muted text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">Dostawca</th>
                    <th className="px-3 py-2 font-medium">Nr zamówienia</th>
                    <th className="px-3 py-2 font-medium">Bon dostawy</th>
                    <th className="px-3 py-2 font-medium">Stan</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {suppliers.map((supplier) => (
                    <SupplierRow
                      key={supplier.code}
                      storeId={storeId}
                      deliveryId={delivery?.id ?? ''}
                      supplier={supplier}
                      canEdit={canRun && !remote.busy}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="grid gap-3">
          <h3 className="text-sm font-semibold">2. Różnice</h3>
          {missingCarriers > 0 && (
            <Warning>
              W tabeli nie ma wszystkich nośników dokumentu (brakuje {missingCarriers}), więc różnice będą fałszywe.
            </Warning>
          )}
          {missingNotes.length > 0 && (
            <Warning>
              Brak bonu dostawy od dostawcy {missingNotes.map((supplier) => supplier.code).join(', ')}: porównanie jest
              niepełne.
            </Warning>
          )}
          {staleNotes.length > 0 && (
            <Warning>
              Bon dostawcy {staleNotes.map((supplier) => supplier.code).join(', ')} jest z innego zamówienia. Pobierz
              bony ponownie.
            </Warning>
          )}
          {result ? (
            <DifferencesView result={result} />
          ) : (
            <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
              Po wprowadzeniu pojawią się tu różnice między tabelą a Mercalys.
            </p>
          )}
        </section>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          {ready && (
            <RemoteMacroButton
              size="xl"
              computers={computers}
              label={differences > 0 ? `Zatwierdź mimo różnic (${differences})` : 'Zatwierdź i zakończ'}
              busy={remote.busy && remote.job?.kind === 'BASE_CLOSE'}
              disabled={remote.busy || toClose.length === 0}
              onRun={(agentId) => {
                setCloseOn(agentId)
                setConfirmOpen(true)
              }}
            />
          )}
        </DialogFooter>

        <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Zakończyć dostawę w Mercalys?</AlertDialogTitle>
              <AlertDialogDescription>
                Makro wpisze Nr BL {delivery?.number} w zamówieniach{' '}
                {toClose.map((supplier) => `${supplier.order?.orderNumber} (${supplier.code})`).join(', ')} i je
                zakończy. Tego nie da się cofnąć.
                {differences > 0 && ` Różnic względem tabeli: ${differences}.`}
                {missingNotes.length > 0 && ' Nie wszystkie bony zostały porównane.'}
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Anuluj</AlertDialogCancel>
              <AlertDialogAction onClick={() => void close()}>
                <CheckCheckIcon />
                Zakończ dostawę
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </DialogContent>
    </Dialog>
  )
}

/** Bon z numerem innym niż zamówienie (np. zamówienie poprawiono ręcznie po pobraniu bonu). */
function isStale({ order, note }: BaseReceivingSupplier) {
  const number = note?.content?.number
  return Boolean(order && number && number.replace(/^0+/, '') !== order.orderNumber.replace(/^0+/, ''))
}

type SupplierRowProps = {
  storeId: string
  deliveryId: string
  supplier: BaseReceivingSupplier
  canEdit: boolean
}

function SupplierRow({ storeId, deliveryId, supplier, canEdit }: SupplierRowProps) {
  const { order, note } = supplier
  const enter = useEnterBaseOrder(storeId, deliveryId)
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')

  const submit = (event: FormEvent) => {
    event.preventDefault()
    enter.mutate(
      { supplierCode: supplier.code, orderNumber: value.trim() },
      {
        onSuccess: () => setEditing(false),
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać numeru zamówienia'),
      },
    )
  }

  return (
    <tr>
      <td className="px-3 py-2">
        <span className="font-medium">{supplier.code}</span>{' '}
        <span className="text-muted-foreground">{supplier.name}</span>
      </td>
      <td className="px-3 py-2">
        {editing ? (
          <form onSubmit={submit} className="flex items-center gap-2">
            <TextField
              label="Nr zamówienia"
              className="w-36"
              inputMode="numeric"
              value={value}
              onChange={(event) => setValue(event.target.value.replace(/\D/g, ''))}
              maxLength={10}
              autoFocus
            />
            <Button type="submit" size="sm" loading={enter.isPending} disabled={value.trim() === ''}>
              Zapisz
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(false)}>
              Anuluj
            </Button>
          </form>
        ) : (
          <span className="flex items-center gap-1">
            {order ? (
              <span className="font-medium">{order.orderNumber}</span>
            ) : (
              <span className="text-muted-foreground">jeszcze nie ma</span>
            )}
            {canEdit && !order?.closedAt && (
              <Button
                variant="ghost"
                size="icon-sm"
                title="Wpisz numer ręcznie, gdy dostawę wprowadził człowiek"
                aria-label={`Wpisz numer zamówienia dostawcy ${supplier.code}`}
                onClick={() => {
                  setValue(order?.orderNumber ?? '')
                  setEditing(true)
                }}
              >
                <PencilIcon />
              </Button>
            )}
          </span>
        )}
      </td>
      <td className="px-3 py-2">
        {!note ? (
          <span className="text-muted-foreground">–</span>
        ) : note.error ? (
          <span className="text-red-700 dark:text-red-300">{note.error}</span>
        ) : (
          <>
            {note.content?.lines.length} poz. <span className="text-muted-foreground">· {time(note.uploadedAt)}</span>
          </>
        )}
      </td>
      <td className="px-3 py-2">
        <span
          className={cn(
            'rounded-md px-2 py-0.5 text-xs font-medium whitespace-nowrap',
            order?.closedAt
              ? 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50'
              : order
                ? 'bg-sky-300 text-sky-950 dark:bg-sky-800 dark:text-sky-50'
                : 'bg-muted text-muted-foreground',
          )}
        >
          {order?.closedAt ? `zakończona ${time(order.closedAt)}` : order ? 'wprowadzona' : 'nie wprowadzona'}
        </span>
        {order?.enteredBy && <span className="ml-2 text-xs text-muted-foreground">nr wpisał(a) {order.enteredBy}</span>}
      </td>
    </tr>
  )
}

function Warning({ children }: { children: ReactNode }) {
  return (
    <p className="flex items-start gap-2 rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
      <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </p>
  )
}
