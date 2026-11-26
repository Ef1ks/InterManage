import { useEffect, useState } from 'react'
import { ClipboardCopyIcon, MonitorPlayIcon, Trash2Icon } from 'lucide-react'
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
import { formatMoney } from '@/features/documents/format'
import { formatDate } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import type { InvoiceClosing } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useInvoiceClosingChange, useInvoiceClosings } from './api'
import { buildDeliveryMacro } from './mercalys-macro'

const ROW_GRID = 'grid grid-cols-[auto_minmax(0,1.2fr)_minmax(0,1.5fr)_5rem_6rem_1.75rem] items-center gap-3'

const toMacroDocument = (item: InvoiceClosing) => ({ number: item.documentNumber, supplierCode: item.supplierCode })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/** Wybór „Drukuj bony” zostaje w przeglądarce, bo zależy od stanowiska (drukarka przy komputerze). */
const PRINT_KEY = 'intermanage.closeInvoicesPrint'

function readPrint() {
  try {
    return localStorage.getItem(PRINT_KEY) === '1'
  } catch {
    return false
  }
}

function savePrint(print: boolean) {
  try {
    localStorage.setItem(PRINT_KEY, print ? '1' : '0')
  } catch {
    // bez zapisu wybór działa do zamknięcia karty
  }
}

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Zamknięcie na komputerze sklepu czeka na „Tak” w okienku na tej stronie: odtwarzacz o nie już nie pyta. */
type RemoteClose = { items: InvoiceClosing[]; agentId?: string }

/**
 * Faktury z plików XML zapisanych na dysku. Skrypt idzie do schowka albo makro na wolny komputer sklepu (agent), i
 * dopiero wtedy faktury stają się wygenerowane. Przy komputerze sklepu pytanie „Zamknąć dostawy?” jest tutaj, przed
 * zleceniem, a podsumowanie (zamknięte i pominięte dostawy) wraca na stronę.
 * Schowek łatwo nadpisać, więc wygenerowane zostają na liście: można je skopiować ponownie, a usuwa je osoba, która
 * sprawdziła zamknięcie. Fakturę, której nie trzeba zamykać, usuwa się też z „Do zamknięcia” (znacznik, „Przywróć”).
 */
export function CloseInvoicesDialog({ storeId, open, onOpenChange }: Props) {
  const closings = useInvoiceClosings(storeId, open)
  const change = useInvoiceClosingChange(storeId)
  // Zaznaczone jest wszystko, więc pamiętamy tylko odznaczone.
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())
  const [print, setPrint] = useState(readPrint)
  const canRunRemotely = useCan('DATA_ENTRY', 'EDIT')
  const { data: computers = [] } = useAgentComputers(storeId, open && canRunRemotely)
  const remote = useRemoteMacro(storeId, { matches: (job) => job.kind === 'INVOICE_CLOSE' })
  const [confirm, setConfirm] = useState<RemoteClose | null>(null)
  const remoteJobId = remote.job?.id
  const refetchClosings = closings.refetch
  // Zlecenie oznacza faktury jako wygenerowane na serwerze; lista w oknie musi to pokazać od razu.
  useEffect(() => {
    if (remoteJobId) void refetchClosings()
  }, [remoteJobId, refetchClosings])
  const remoteReady = canRunRemotely && computers.length > 0

  const items = closings.data ?? []
  const pending = items.filter((item) => item.generatedAt === null)
  const generated = items.filter((item) => item.generatedAt !== null)
  const pickedPending = pending.filter((item) => !unchecked.has(item.id))
  const pickedGenerated = generated.filter((item) => !unchecked.has(item.id))

  const toggle = (id: string) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  const copyScript = async (picked: InvoiceClosing[]) => {
    try {
      await navigator.clipboard.writeText(buildDeliveryMacro(picked.map(toMacroDocument), print))
    } catch {
      toast.error('Nie udało się skopiować skryptu do schowka')
      return false
    }
    return true
  }

  const generate = async () => {
    if (!(await copyScript(pickedPending))) return
    change.mutate(
      { action: 'generated', ids: pickedPending.map((item) => item.id) },
      {
        onSuccess: () => {
          toast.success(`Skrypt w schowku, faktur: ${pickedPending.length}`, {
            description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
          })
          onOpenChange(false)
        },
        onError: (error) =>
          toast.error(errorMessage(error, 'Skrypt jest w schowku, ale nie udało się oznaczyć faktur')),
      },
    )
  }

  const copyAgain = async () => {
    if (!(await copyScript(pickedGenerated))) return
    toast.success(`Skrypt ponownie w schowku, faktur: ${pickedGenerated.length}`, {
      description: 'Naciśnij Alt+E, a odtwarzacz sam otworzy Mercalys.',
    })
    onOpenChange(false)
  }

  const closeRemotely = async ({ items: picked, agentId }: RemoteClose) => {
    setConfirm(null)
    await remote.run({
      macro: 'invoice-close',
      ids: picked.map((item) => item.id),
      print,
      confirmed: true,
      agentId,
    })
  }

  const remove = (picked: InvoiceClosing[]) => {
    const ids = picked.map((item) => item.id)
    if (ids.length === 0) return
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(ids.length === 1 ? `Usunięto fakturę ${picked[0].documentNumber}` : `Usunięto faktury: ${ids.length}`, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate(
                  { action: 'restore', ids },
                  { onError: () => toast.error('Nie udało się przywrócić faktur') },
                ),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć faktur')),
      },
    )
  }

  const row = (item: InvoiceClosing) => (
    <li key={item.id} className="border-b last:border-b-0">
      <label
        className={cn(
          ROW_GRID,
          'cursor-pointer px-3 py-1.5 text-sm hover:bg-accent',
          'has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
        )}
      >
        <input
          type="checkbox"
          checked={!unchecked.has(item.id)}
          onChange={() => toggle(item.id)}
          className="size-4 accent-blue-600"
        />
        <span className="min-w-0 truncate font-medium" title={item.documentNumber}>
          {item.documentNumber}
        </span>
        <span className="min-w-0 truncate" title={item.supplierName ?? undefined}>
          {item.supplierCode && (
            <span className="mr-2 text-xs text-muted-foreground tabular-nums">{item.supplierCode}</span>
          )}
          {item.supplierName}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">{formatDate(item.documentDate)}</span>
        <span className="text-right tabular-nums">{formatMoney(item.netValue)}</span>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń fakturę ${item.documentNumber}`}
          title="Usuń z listy"
          disabled={change.isPending}
          onClick={(event) => {
            event.preventDefault()
            remove([item])
          }}
        >
          <Trash2Icon />
        </Button>
      </label>
    </li>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Zamknij faktury</DialogTitle>
          <DialogDescription>
            Skrypt zintegruje pliki PZ, utworzy kody, wpisze numery faktur w nagłówkach i zamknie dostawy. Na tym
            komputerze: skrypt do schowka i Alt+E. Na komputerze sklepu: zamknięcie zatwierdzasz tutaj, a wynik wraca na
            tę stronę.
          </DialogDescription>
        </DialogHeader>

        <label className="flex cursor-pointer items-start gap-3 rounded-lg border px-3 py-2.5 text-sm hover:bg-accent">
          <input
            type="checkbox"
            checked={print}
            onChange={(event) => {
              setPrint(event.target.checked)
              savePrint(event.target.checked)
            }}
            className="mt-0.5 size-4 accent-blue-600"
          />
          <span className="flex flex-col gap-0.5">
            <span className="font-medium">Drukuj bony po zamknięciu</span>
            <span className="text-xs text-muted-foreground">
              {print
                ? 'Każdy bon otwarty w Adobe Acrobat pójdzie od razu do drukarki domyślnej komputera, który wykonuje makro, potem się zamknie (odtwarzacz od wersji 0.51).'
                : 'Bony otwarte w Adobe Acrobat zostaną zamknięte bez drukowania.'}
            </span>
          </span>
        </label>

        {remote.job && <RemoteMacroStatus job={remote.job} onCancel={remote.cancel} onClose={remote.close} />}

        <section aria-label="Do zamknięcia" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Do zamknięcia ({pending.length})</h3>
          <div className="max-h-[32vh] overflow-y-auto rounded-lg border">
            <ul aria-label="Faktury do zamknięcia">{pending.map(row)}</ul>
            {pending.length === 0 && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {closings.isLoading ? 'Wczytywanie…' : 'Nowe faktury pojawią się po zapisaniu pliku XML.'}
              </p>
            )}
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="outline"
              disabled={pickedPending.length === 0 || change.isPending}
              onClick={() => remove(pickedPending)}
            >
              <Trash2Icon />
              Usuń zaznaczone ({pickedPending.length})
            </Button>
            <Button
              variant={remoteReady ? 'outline' : 'default'}
              disabled={pickedPending.length === 0 || change.isPending}
              onClick={() => void generate()}
            >
              <ClipboardCopyIcon />
              Generuj skrypt ({pickedPending.length})
            </Button>
            {remoteReady && (
              <RemoteMacroButton
                computers={computers}
                label={`Zamknij na komputerze sklepu (${pickedPending.length})`}
                busy={remote.busy}
                disabled={pickedPending.length === 0 || remote.busy || change.isPending}
                onRun={(agentId) => setConfirm({ items: pickedPending, agentId })}
              />
            )}
          </div>
        </section>

        {generated.length > 0 && (
          <section aria-label="Wygenerowane" className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">Wygenerowane ({generated.length})</h3>
            <div className="max-h-[24vh] overflow-y-auto rounded-lg border">
              <ul aria-label="Faktury wygenerowane">{generated.map(row)}</ul>
            </div>
            <p className="text-xs text-muted-foreground">
              Skrypt zniknął ze schowka albo przerwał się? Zaznacz niezamknięte faktury i skopiuj go ponownie albo
              zamknij je na komputerze sklepu.
            </p>
          </section>
        )}

        <DialogFooter className="sm:justify-between">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          {generated.length > 0 && (
            <div className="flex flex-wrap justify-end gap-2">
              <Button variant="outline" disabled={pickedGenerated.length === 0} onClick={() => void copyAgain()}>
                <ClipboardCopyIcon />
                Skopiuj ponownie ({pickedGenerated.length})
              </Button>
              {remoteReady && (
                <Button
                  variant="outline"
                  disabled={pickedGenerated.length === 0 || remote.busy}
                  onClick={() => setConfirm({ items: pickedGenerated })}
                >
                  <MonitorPlayIcon />
                  Na komputerze sklepu ({pickedGenerated.length})
                </Button>
              )}
              <Button
                variant="outline"
                disabled={pickedGenerated.length === 0 || change.isPending}
                onClick={() => remove(pickedGenerated)}
              >
                <Trash2Icon />
                Usuń wygenerowane ({pickedGenerated.length})
              </Button>
            </div>
          )}
        </DialogFooter>

        <AlertDialog open={confirm !== null} onOpenChange={(next) => !next && setConfirm(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Zamknąć dostawy: {confirm?.items.length}?</AlertDialogTitle>
              <AlertDialogDescription>
                Komputer sklepu zintegruje pliki PZ, utworzy kody, wpisze numery faktur w nagłówkach i zamknie dostawy
                {print ? ', a bony wydrukuje na swojej drukarce domyślnej' : ''}. Zamknięcia nie da się cofnąć, a przy
                komputerze makro już o nie nie zapyta.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <ul className="max-h-[40vh] overflow-y-auto rounded-lg border text-sm">
              {confirm?.items.map((item) => (
                <li key={item.id} className="flex gap-3 border-b px-3 py-1.5 last:border-b-0">
                  <span className="font-medium">{item.documentNumber}</span>
                  <span className="min-w-0 flex-1 truncate text-muted-foreground">
                    {item.supplierCode && <span className="mr-2 tabular-nums">{item.supplierCode}</span>}
                    {item.supplierName}
                  </span>
                  <span className="tabular-nums">{formatMoney(item.netValue)}</span>
                </li>
              ))}
            </ul>
            <AlertDialogFooter>
              <AlertDialogCancel>Nie</AlertDialogCancel>
              <AlertDialogAction onClick={() => confirm && void closeRemotely(confirm)}>
                <MonitorPlayIcon />
                Tak, zamknij
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </DialogContent>
    </Dialog>
  )
}
