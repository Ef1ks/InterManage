import { useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { CheckCheckIcon, CornerDownLeftIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { PROTO_FOLDER } from '@/features/deli-order/api'
import { incomingTransferExportPath, useReceiveTransfer } from '@/features/documents/api'
import { formatQuantity } from '@/features/documents/format'
import { ApiError, downloadFile } from '@/lib/api'
import { ensureFolderAccess, saveApiFileToFolder } from '@/lib/saved-folders'
import type { IncomingTransfer } from '@/lib/types'
import { cn } from '@/lib/utils'

type Props = {
  storeId: string
  transfer: IncomingTransfer
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Ilość przyjęta w dokumencie: ostatnio wpisana albo (bez wpisu) ilość z dokumentu bez znaku. */
export function receivedQuantities(transfer: IncomingTransfer) {
  const received = new Map(transfer.received.map((item) => [item.lineId, item.quantity]))
  return new Map(transfer.lines.map((line) => [line.id, received.get(line.id) ?? Math.abs(line.quantity)]))
}

/** Przecinek albo kropka, najwyżej 3 miejsca po przecinku (tyle mieści plik PROTO6), bez minusa. */
function parseQuantity(raw: string): { value?: number; error?: string } {
  const text = raw.trim().replace(',', '.')
  if (text === '') return { error: 'Wpisz ilość' }
  if (!/^\d+(\.\d+)?$/.test(text)) return { error: 'Wpisz liczbę' }
  if ((text.split('.')[1]?.length ?? 0) > 3) return { error: 'Najwyżej 3 miejsca po przecinku' }
  if (Number(text) > 999_999.999) return { error: 'Za duża ilość' }
  return { value: Number(text) }
}

const toInput = (value: number) => String(value).replace('.', ',')

/**
 * Wpisywanie ilości, które naprawdę przyjechały: ilość, Enter, następne pole. Potwierdzenie zapisuje je, oznacza
 * dokument jako wprowadzony i zapisuje plik PROTO6 w folderze programu sklepu.
 */
export function ReceiveTransferDialog({ open, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        {open && <ReceiveForm {...props} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function ReceiveForm({ storeId, transfer, onDone }: Omit<Props, 'open' | 'onOpenChange'> & { onDone: () => void }) {
  const receive = useReceiveTransfer(storeId, transfer.id)
  const lines = [...transfer.lines].sort((a, b) => a.lineNumber - b.lineNumber)
  // Pierwsze wpisanie startuje od pustych pól (ilość zwykle się różni); poprawka od zapisanych ilości.
  const [draft, setDraft] = useState<Record<string, string>>(() => {
    if (transfer.received.length === 0) return {}
    const initial = receivedQuantities(transfer)
    return Object.fromEntries(lines.map((line) => [line.id, toInput(initial.get(line.id) ?? 0)]))
  })
  const [submitting, setSubmitting] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)

  const parsed = new Map(lines.map((line) => [line.id, parseQuantity(draft[line.id] ?? '')]))
  const isEmpty = (lineId: string) => (draft[lineId] ?? '').trim() === ''
  const missing = lines.filter((line) => isEmpty(line.id)).length
  const errors = lines.filter((line) => !isEmpty(line.id) && parsed.get(line.id)?.error).length
  const changed = lines.filter((line) => parsed.get(line.id)?.value !== Math.abs(line.quantity)).length

  // Enter przechodzi do następnego pola, Shift+Enter wraca; po ostatnim fokus trafia na „Potwierdź”.
  // Enter w pustym polu wpisuje ilość z dokumentu (przyjechało tyle, ile wysłano).
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Enter' || !(event.target instanceof HTMLInputElement)) return
    event.preventDefault()
    const lineId = event.target.dataset.lineId
    const line = lines.find((item) => item.id === lineId)
    if (line && !event.shiftKey && isEmpty(line.id)) {
      setDraft((current) => ({ ...current, [line.id]: toInput(Math.abs(line.quantity)) }))
    }
    const all = [...(listRef.current?.querySelectorAll('input') ?? [])]
    const next = all[all.indexOf(event.target) + (event.shiftKey ? -1 : 1)]
    if (next) next.focus()
    else if (!event.shiftKey) confirmRef.current?.focus()
  }

  const onFocus = (event: FocusEvent<HTMLDivElement>) => {
    if (event.target instanceof HTMLInputElement) event.target.select()
  }

  const confirm = async () => {
    setSubmitting(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(PROTO_FOLDER.key)
      await receive.mutateAsync(lines.map((line) => ({ lineId: line.id, quantity: parsed.get(line.id)!.value! })))
      onDone()
      if (!folderReady) {
        toast.success('Zapisano przyjęte ilości')
        return
      }
      const path = incomingTransferExportPath(storeId, transfer.id, 'proto')
      try {
        const fileName = await saveApiFileToFolder(PROTO_FOLDER.key, path, 'przyjecie.Traite0')
        toast.success('Zapisano przyjęte ilości', { description: `Zapisano ${fileName}` })
      } catch (error) {
        toast.error('Ilości zapisane, ale pliku PROTO6 nie zapisano', {
          description: error instanceof ApiError ? error.message : undefined,
          action: { label: 'Pobierz', onClick: () => downloadFile(path, 'przyjecie.Traite0') },
          duration: 15_000,
        })
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać ilości')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Przyjęte ilości</DialogTitle>
        <DialogDescription>
          Wpisz, ile naprawdę przyjechało, i naciśnij{' '}
          <kbd className="inline-flex items-center gap-1 rounded border bg-muted px-1.5 font-sans text-xs">
            Enter <CornerDownLeftIcon className="size-3" />
          </kbd>{' '}
          - przejdziesz do następnego produktu. Enter w pustym polu wpisuje ilość z dokumentu. Brak towaru to 0.
        </DialogDescription>
      </DialogHeader>

      <div ref={listRef} onKeyDown={onKeyDown} onFocus={onFocus} className="grid divide-y">
        {lines.map((line, index) => {
          const { value } = parsed.get(line.id) ?? {}
          // Puste pole to jeszcze nie błąd, tylko brak wpisu (licznik na przycisku).
          const error = isEmpty(line.id) ? undefined : parsed.get(line.id)?.error
          const differs = value != null && value !== Math.abs(line.quantity)
          return (
            <div key={line.id} className="flex items-center gap-3 py-2">
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{line.name ?? `EAN ${line.ean ?? line.itm8}`}</div>
                <div className="text-xs text-muted-foreground tabular-nums">
                  w dokumencie {formatQuantity(Math.abs(line.quantity))}
                  {line.ean ? ` · ${line.ean}` : ' · bez EAN, nie trafi do PROTO6'}
                  {error && <span className="text-destructive"> · {error}</span>}
                </div>
              </div>
              <label
                className={cn(
                  'flex h-10 w-28 shrink-0 items-center rounded-xl border border-input bg-background pr-3 transition-[border-color,box-shadow] duration-150',
                  'focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30',
                  differs && !error && 'border-amber-400 bg-amber-50/60 dark:border-amber-500/50 dark:bg-amber-500/5',
                  error && 'border-destructive focus-within:border-destructive focus-within:ring-destructive/20',
                )}
              >
                <input
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  aria-label={`Przyjęto: ${line.name ?? line.ean ?? line.itm8}`}
                  aria-invalid={Boolean(error) || undefined}
                  data-line-id={line.id}
                  placeholder={formatQuantity(Math.abs(line.quantity))}
                  value={draft[line.id] ?? ''}
                  autoFocus={index === 0}
                  onChange={(event) => setDraft((current) => ({ ...current, [line.id]: event.target.value }))}
                  className="h-full w-full min-w-0 bg-transparent pl-3 text-right text-sm font-medium tabular-nums outline-none placeholder:font-normal placeholder:text-muted-foreground/50"
                />
              </label>
            </div>
          )
        })}
      </div>

      <SavedFolder folder={PROTO_FOLDER} />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button ref={confirmRef} type="button" size="lg" loading={submitting} disabled={errors + missing > 0} onClick={confirm}>
          <CheckCheckIcon />
          {errors > 0
            ? `Popraw ilości (${errors})`
            : missing > 0
              ? `Uzupełnij ilości (${missing})`
              : changed > 0
              ? `Zapisz przyjęcie (${changed} różnic)`
              : 'Zapisz przyjęcie'}
        </Button>
      </FormActions>
    </>
  )
}
