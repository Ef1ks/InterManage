import { useMemo, useState } from 'react'
import { ListPlusIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

import { useFreeScaleCodes } from './api'

type Props = {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** EAN-y z tabeli bez zer wiodących: kody już wstawione nie są do wyboru. */
  inTable: Set<string>
  onInsert: (eans: string[]) => void
}

/**
 * Wolne kody 24 (240001-242999) do zakładania nowych artykułów ważonych. Serwer pomija kody zajęte w bazie sklepu,
 * okno jeszcze te, które są już w tabeli. Pole „Ile” zaznacza tyle pierwszych wolnych kodów, zaznaczenie można
 * poprawić klikaniem; „Wstaw” dopisuje je do tabeli jako nowe artykuły.
 */
export function FreeScaleCodesDialog({ storeId, open, onOpenChange, inTable, onInsert }: Props) {
  const query = useFreeScaleCodes(storeId, open)
  const available = useMemo(() => (query.data ?? []).filter((ean) => !inTable.has(ean)), [query.data, inTable])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [count, setCount] = useState('')

  // Każde otwarcie zaczyna od pustego zaznaczenia.
  const changeOpen = (next: boolean) => {
    if (!next) {
      setSelected(new Set())
      setCount('')
    }
    onOpenChange(next)
  }

  const changeCount = (value: string) => {
    setCount(value)
    const n = Math.max(0, Math.min(available.length, Math.floor(Number(value) || 0)))
    setSelected(new Set(available.slice(0, n)))
  }

  const toggle = (ean: string) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(ean)) next.delete(ean)
      else next.add(ean)
      return next
    })
    setCount('')
  }

  const insert = () => {
    onInsert(available.filter((ean) => selected.has(ean)))
    changeOpen(false)
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Wolne kody 24</DialogTitle>
          <DialogDescription>
            Kody 240001–242999 do zakładania nowych artykułów ważonych, bez kodów zajętych w kartotece, multikodach i
            tabeli.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-wrap items-end gap-3">
          <label className="grid gap-1 text-sm">
            <span className="text-muted-foreground">Ile kodów</span>
            <Input
              type="number"
              min={0}
              max={available.length}
              inputMode="numeric"
              className="w-28 tabular-nums"
              value={count}
              onChange={(event) => changeCount(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && selected.size > 0) insert()
              }}
              autoFocus
            />
          </label>
          <p className="pb-2 text-sm text-muted-foreground tabular-nums">
            {query.isPending ? 'Szukam wolnych kodów…' : `Wolnych: ${available.length} · zaznaczone: ${selected.size}`}
          </p>
          <Button className="ml-auto" onClick={insert} disabled={selected.size === 0}>
            <ListPlusIcon />
            Wstaw do tabeli{selected.size > 0 && ` (${selected.size})`}
          </Button>
        </div>

        {query.isError ? (
          <p className="text-sm text-destructive">Nie udało się pobrać wolnych kodów.</p>
        ) : (
          <div className="grid max-h-[55vh] grid-cols-3 gap-1 overflow-y-auto rounded-lg border p-2 sm:grid-cols-5 lg:grid-cols-6">
            {available.map((ean) => (
              <label
                key={ean}
                className={cn(
                  'flex cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-sm tabular-nums hover:bg-muted',
                  selected.has(ean) && 'bg-primary/10 font-medium',
                )}
                title={ean}
              >
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={selected.has(ean)}
                  onChange={() => toggle(ean)}
                />
                {ean.slice(0, 6)}
              </label>
            ))}
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
