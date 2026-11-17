import { useRef, useState } from 'react'

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import type { Column, ColumnKey } from './grid-values'

/** Ukryte pytanie „tylko nowe czy wszystkie” (useHiddenHint): wyłączone = zawsze tylko nowe artykuły. */
export const COLUMN_FILL_PROMPT = 'column-fill-prompt'

/** Kolumny z polem nad sobą: wartość wpisana w pole trafia do całej kolumny. */
const PLACEHOLDERS: Partial<Record<ColumnKey, string>> = {
  brand: 'marka',
  region: 'rejon',
  family: 'rodzina',
  subfamily: 'podrodz.',
  unit: 'kg/L/szt.',
  netContent: 'waga',
}

export const COLUMN_FILL_LABELS: Partial<Record<ColumnKey, string>> = {
  brand: 'Marka',
  region: 'Rejon',
  family: 'Rodzina',
  subfamily: 'Podrodzina',
  unit: 'Rodzaj sprzedaży',
  netContent: 'Waga',
}

/** Pole nad kolumną: Enter ustawia wpisaną wartość w całej kolumnie, Esc czyści pole. */
export function ColumnFillInput({ column, onFill }: { column: Column; onFill: (text: string) => void }) {
  const [text, setText] = useState('')
  const placeholder = PLACEHOLDERS[column.key]
  if (!placeholder) return null
  const label = COLUMN_FILL_LABELS[column.key] ?? column.header
  return (
    <input
      value={text}
      placeholder={placeholder}
      aria-label={`${label} dla całej kolumny`}
      title={`${label}: wpisz i naciśnij Enter, żeby ustawić w całej kolumnie`}
      autoComplete="off"
      onChange={(event) => setText(event.target.value)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          setText('')
          return
        }
        if (event.key !== 'Enter' || text.trim() === '') return
        event.preventDefault()
        onFill(text.trim())
        setText('')
      }}
      className={cn(
        'h-7 w-full min-w-0 rounded-md border border-input bg-background px-1.5 text-[0.86em] outline-none placeholder:text-muted-foreground/70 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 dark:bg-input/30',
        column.align === 'right' && 'text-right',
      )}
    />
  )
}

export type ColumnFillRequest = { key: ColumnKey; text: string; newCount: number; allCount: number }

/** Pytanie przed ustawieniem kolumny: domyślnie tylko nowe artykuły (przycisk z fokusem). */
export function ColumnFillDialog({
  request,
  onClose,
  onApply,
}: {
  request: ColumnFillRequest | null
  onClose: () => void
  onApply: (onlyNew: boolean) => void
}) {
  const onlyNewRef = useRef<HTMLButtonElement>(null)
  const label = request ? (COLUMN_FILL_LABELS[request.key] ?? '') : ''
  return (
    <AlertDialog open={request !== null} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent initialFocus={onlyNewRef}>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {label} „{request?.text}” w całej kolumnie
          </AlertDialogTitle>
          <AlertDialogDescription>
            Ustawić tylko w nowych artykułach, czy też w artykułach z kartoteki? Pytanie można wyłączyć w ustawieniach
            zapisu (koło zębate); wtedy zmiana dotyczy tylko nowych.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Anuluj</AlertDialogCancel>
          <Button variant="outline" disabled={!request?.allCount} onClick={() => onApply(false)}>
            Wszystkie artykuły ({request?.allCount ?? 0})
          </Button>
          <Button ref={onlyNewRef} disabled={!request?.newCount} onClick={() => onApply(true)}>
            Tylko nowe ({request?.newCount ?? 0})
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
