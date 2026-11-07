import { useMemo, useState } from 'react'
import { ClipboardCopyIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { formatDate } from '@/features/ksef/format'
import { cn } from '@/lib/utils'

import { useXmlHistory } from './api'
import { buildCreateCodesMacro, macroDocuments, savedWithin } from './mercalys-macro'

const PERIODS: { label: string; days: number | null }[] = [
  { label: 'Dzisiaj', days: 1 },
  { label: '3 dni', days: 3 },
  { label: '7 dni', days: 7 },
  { label: 'Wszystkie', days: null },
]

const ROW_GRID = 'grid grid-cols-[auto_minmax(0,1.2fr)_minmax(0,1.5fr)_5rem_6rem] items-center gap-3'

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Makro „Tworzenie kodu” w Mercalys: bony z plików XML zapisanych na dysku w wybranym okresie. Kod dostaje tylko bon
 * z tej listy, który nie ma jeszcze Nr Zamów, więc obce pliki z listy w Mercalys zostają nietknięte.
 */
export function MercalysCodesDialog({ storeId, open, onOpenChange }: Props) {
  const history = useXmlHistory(storeId, open)
  const [days, setDays] = useState<number | null>(1)
  // Zaznaczone jest wszystko z okresu, więc pamiętamy tylko odznaczone.
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())

  const documents = useMemo(
    () => macroDocuments(history.data ?? []).filter((document) => savedWithin(document, days)),
    [history.data, days],
  )
  const picked = documents.filter((document) => !unchecked.has(document.key))

  const toggle = (key: string) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(key)) next.add(key)
      return next
    })

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(buildCreateCodesMacro(picked))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku, bonów: ${picked.length}`, {
      description: 'W Mercalys otwórz Obsługę BL Bezpośrednich i wciśnij F9.',
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Tworzenie kodu w Mercalys</DialogTitle>
          <DialogDescription>
            Kod dostanie tylko bon z tej listy, który nie ma jeszcze numeru zamówienia. Na końcu odtwarzacz pokaże,
            czego nie znalazł.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-wrap gap-2">
          {PERIODS.map((period) => (
            <Button
              key={period.label}
              size="sm"
              variant={days === period.days ? 'default' : 'outline'}
              onClick={() => setDays(period.days)}
            >
              {period.label}
            </Button>
          ))}
        </div>

        <div className="max-h-[50vh] overflow-y-auto rounded-lg border">
          <ul aria-label="Bony">
            {documents.map((document) => (
              <li key={document.key} className="border-b last:border-b-0">
                <label
                  className={cn(
                    ROW_GRID,
                    'cursor-pointer px-3 py-1.5 text-sm hover:bg-accent',
                    'has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
                  )}
                >
                  <input
                    type="checkbox"
                    checked={!unchecked.has(document.key)}
                    onChange={() => toggle(document.key)}
                    className="size-4 accent-blue-600"
                  />
                  <span className="min-w-0 truncate font-medium" title={document.number}>
                    {document.number}
                  </span>
                  <span className="min-w-0 truncate" title={document.supplierName ?? undefined}>
                    {document.supplierCode && (
                      <span className="mr-2 text-xs text-muted-foreground tabular-nums">{document.supplierCode}</span>
                    )}
                    {document.supplierName}
                  </span>
                  <span className="text-xs text-muted-foreground tabular-nums">
                    {formatDate(document.documentDate)}
                  </span>
                  <span className="text-right tabular-nums">{formatMoney(document.netValue)}</span>
                </label>
              </li>
            ))}
          </ul>
          {documents.length === 0 && (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground">
              {history.isLoading ? 'Wczytywanie…' : 'W tym okresie nie zapisano plików XML.'}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button disabled={picked.length === 0} onClick={() => void copy()}>
            <ClipboardCopyIcon />
            Kopiuj makro ({picked.length})
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
