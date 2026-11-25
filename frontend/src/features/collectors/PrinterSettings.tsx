import { useState } from 'react'
import { PencilIcon, PlusIcon, PrinterIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ApiError } from '@/lib/api'
import type { StorePrinter } from '@/lib/types'

import {
  formatMac,
  normalizeMac,
  type PrinterInput,
  useAddPrinter,
  useChangePrinter,
  usePrinters,
  useRemovePrinter,
  useRestorePrinter,
} from './printers-api'

const EMPTY: PrinterInput = { name: '', mac: '', model: '', pin: '' }

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Drukarki Bluetooth sklepu (Kolektory, Urządzenia): adres wpisuje się raz tutaj, kolektory dostają listę na
 * podstawce, a pracownik wybiera na kolektorze drukarkę, z którą idzie pracować.
 */
export function PrinterSettings({ storeId }: { storeId: string }) {
  const printers = usePrinters(storeId)
  const add = useAddPrinter(storeId)
  const change = useChangePrinter(storeId)
  const remove = useRemovePrinter(storeId)
  const restore = useRestorePrinter(storeId)
  const [input, setInput] = useState<PrinterInput>(EMPTY)
  const [editing, setEditing] = useState<string | null>(null)
  const [macTouched, setMacTouched] = useState(false)

  if (printers.isPending) return <LoadingPanel className="h-56 rounded-xl" />
  if (printers.isError) {
    return (
      <p className="text-sm text-red-700 dark:text-red-300">
        {errorMessage(printers.error, 'Nie udało się wczytać drukarek.')}
      </p>
    )
  }
  const active = printers.data.filter((printer) => !printer.deletedAt)
  const removed = printers.data.filter((printer) => printer.deletedAt)
  const mac = normalizeMac(input.mac)
  const macError =
    macTouched && input.mac.trim() && !mac ? 'Adres ma 12 znaków 0-9 i A-F, np. AC:3F:A4:12:34:56' : undefined
  const ready = input.name.trim().length > 0 && mac !== null
  const saving = add.isPending || change.isPending

  const set = (key: keyof PrinterInput) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setInput((current) => ({ ...current, [key]: event.target.value }))

  const reset = () => {
    setInput(EMPTY)
    setEditing(null)
    setMacTouched(false)
  }

  const submit = () => {
    if (!ready) {
      setMacTouched(true)
      return
    }
    const value = { ...input, mac: mac ?? input.mac }
    const options = {
      onSuccess: () => {
        toast.success(
          editing
            ? `Zapisano ${value.name.trim()}.`
            : `Dodano ${value.name.trim()}. Kolektory dostaną ją na podstawce.`,
        )
        reset()
      },
      onError: (error: unknown) => toast.error(errorMessage(error, 'Nie udało się zapisać drukarki.')),
    }
    if (editing) change.mutate({ ...value, id: editing }, options)
    else add.mutate(value, options)
  }

  const edit = (printer: StorePrinter) => {
    setEditing(printer.id)
    setMacTouched(false)
    setInput({ name: printer.name, mac: formatMac(printer.mac), model: printer.model ?? '', pin: printer.pin ?? '' })
  }

  const removePrinter = (printer: StorePrinter) =>
    remove.mutate(printer.id, {
      onSuccess: () => {
        if (editing === printer.id) reset()
        toast.success(`Usunięto ${printer.name}. Zniknie z kolektorów na podstawce.`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(printer.id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić drukarki.')),
              }),
          },
        })
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć drukarki.')),
    })

  return (
    <Card className="grid gap-3 p-4">
      <div className="grid max-w-prose gap-1">
        <h3 className="font-medium">Drukarki Bluetooth</h3>
        <p className="text-sm text-muted-foreground">
          Kolektory dostają tę listę na podstawce, a pracownik wybiera na kolektorze swoją drukarkę (Menu, Drukarka).
          Adres Bluetooth jest na wydruku konfiguracji drukarki i na naklejce pod baterią.
        </p>
      </div>
      {active.length === 0 ? (
        <p className="text-sm text-muted-foreground">Brak drukarek. Dodaj pierwszą poniżej.</p>
      ) : (
        <ul className="grid gap-2">
          {active.map((printer) => (
            <li key={printer.id} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
              <PrinterIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
              <div className="grid min-w-0 flex-1 gap-1">
                <p className="flex flex-wrap items-center gap-2 font-medium">
                  <span className="break-words">{printer.name}</span>
                  {printer.model && <Badge className="bg-muted text-foreground">{printer.model}</Badge>}
                  {printer.pin && (
                    <Badge className="bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100">z PIN-em</Badge>
                  )}
                </p>
                <p className="font-mono text-xs text-muted-foreground tabular-nums">{formatMac(printer.mac)}</p>
              </div>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Zmień ${printer.name}`}
                title="Zmień"
                onClick={() => edit(printer)}
              >
                <PencilIcon aria-hidden />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń ${printer.name}`}
                title="Usuń drukarkę"
                onClick={() => removePrinter(printer)}
                disabled={remove.isPending}
              >
                <Trash2Icon aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <Form
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <TextField label="Nazwa, np. Garmaż 1" value={input.name} onChange={set('name')} maxLength={60} />
          <TextField
            label="Adres Bluetooth, np. AC:3F:A4:12:34:56"
            value={input.mac}
            onChange={set('mac')}
            onBlur={() => setMacTouched(true)}
            maxLength={20}
            error={macError}
            noSave
          />
          <TextField
            label="Model (opcjonalnie), np. QLn220"
            value={input.model}
            onChange={set('model')}
            maxLength={60}
          />
          <TextField
            label="PIN (opcjonalnie)"
            hint="Tylko gdy drukarka prosi o PIN przy łączeniu."
            value={input.pin}
            onChange={set('pin')}
            maxLength={16}
            noSave
          />
        </div>
        <FormActions>
          {editing && (
            <Button type="button" variant="outline" onClick={reset}>
              Anuluj
            </Button>
          )}
          <Button type="submit" loading={saving} disabled={!input.name.trim() || !input.mac.trim()}>
            {editing ? null : <PlusIcon aria-hidden />}
            {editing ? 'Zapisz drukarkę' : 'Dodaj drukarkę'}
          </Button>
        </FormActions>
      </Form>
      {removed.length > 0 && (
        <div className="grid gap-1 border-t pt-3">
          <p className="text-xs font-medium text-muted-foreground">Usunięte</p>
          {removed.map((printer) => (
            <div key={printer.id} className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
              <span className="min-w-0 break-words">
                {printer.name} <span className="font-mono text-xs">{formatMac(printer.mac)}</span>
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() =>
                  restore.mutate(printer.id, {
                    onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić drukarki.')),
                  })
                }
                disabled={restore.isPending}
              >
                Przywróć
              </Button>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}
