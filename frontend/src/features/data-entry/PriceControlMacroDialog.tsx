import { useState } from 'react'
import { ClipboardCopyIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { PriceControlFileItem } from '@/lib/types'

import { buildPriceControlMacro, priceControlFileTime } from './mercalys-macro'

const fileLabel = (file: PriceControlFileItem) =>
  `${file.supplierName ?? file.supplierCode ?? 'Bez dostawcy'}${file.invoiceNumber ? ` · ${file.invoiceNumber}` : ''}`

/** Data i godzina z nazwy pliku: po nich (i liczbie cen) makro szuka pliku w historii Mercalys. */
const fileTime = (file: PriceControlFileItem) => {
  const time = priceControlFileTime(file.fileName)
  return time ? `${time.day}.${time.month}.${time.year} ${time.hour}:${time.minute}` : null
}

/**
 * Wybór zmian cen do wprowadzenia w Mercalys: na start wszystkie niewgrane. Makro opracowuje tylko wiersze historii z tą
 * datą, godziną i liczbą cen, które nie były jeszcze przetworzone, więc zdublowany plik można tu odznaczyć.
 */
export function PriceControlMacroDialog({
  open,
  onOpenChange,
  files,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  files: PriceControlFileItem[]
}) {
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const [wasOpen, setWasOpen] = useState(false)

  // Każde otwarcie: wszystkie zaznaczone.
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) setExcluded(new Set())
  }

  // Od najstarszego, w kolejności, w jakiej program sklepu je dostał.
  const ordered = [...files].sort((a, b) => a.fileName.localeCompare(b.fileName))
  const chosen = ordered.filter((file) => fileTime(file) !== null && !excluded.has(file.id))

  const toggle = (id: string) =>
    setExcluded((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(
        buildPriceControlMacro(
          chosen.map((file) => ({
            fileName: file.fileName,
            lineCount: file.lineCount,
            label: `${fileTime(file)}, cen: ${file.lineCount}, ${fileLabel(file)}`,
          })),
        ),
      )
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: ${chosen.length} ${chosen.length === 1 ? 'zmiana' : 'zmian'} cen`, {
      description: 'Naciśnij Alt+E. Potrzebny odtwarzacz od wersji 0.57.',
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="grid-cols-[minmax(0,1fr)] gap-4 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Wprowadź zmiany cen</DialogTitle>
          <DialogDescription>
            Makro otwiera w Mercalys Kontrolę ceny i dla każdej zaznaczonej zmiany opracowuje jej plik z historii (data,
            godzina, liczba cen, jeszcze nieprzetworzony), a potem w Różnych cenach zaznacza wszystko i porównuje z MSI.
            Odznacz zmiany, które są zdublowane albo już wprowadzone.
          </DialogDescription>
        </DialogHeader>

        <ul aria-label="Zmiany cen" className="max-h-[50vh] divide-y overflow-y-auto rounded-lg border">
          {ordered.map((file) => {
            const time = fileTime(file)
            return (
              <li key={file.id}>
                <label
                  className={
                    time
                      ? 'flex cursor-pointer items-center gap-3 px-3 py-2 text-sm hover:bg-accent has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60'
                      : 'flex cursor-not-allowed items-center gap-3 px-3 py-2 text-sm opacity-70'
                  }
                >
                  <input
                    type="checkbox"
                    checked={time !== null && !excluded.has(file.id)}
                    disabled={time === null}
                    onChange={() => toggle(file.id)}
                    className="size-4 accent-blue-600"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{fileLabel(file)}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {time ? `${time} · cen: ${file.lineCount}` : 'nazwa pliku bez daty, makro go nie znajdzie'}
                    </span>
                  </span>
                </label>
              </li>
            )
          })}
        </ul>

        <div className="flex justify-end">
          <Button size="lg" disabled={chosen.length === 0} onClick={() => void copy()}>
            <ClipboardCopyIcon />
            Skopiuj makro ({chosen.length})
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
