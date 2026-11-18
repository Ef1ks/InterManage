import { useState } from 'react'
import { PlusIcon } from 'lucide-react'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'

import type { Column, ColumnKey } from './grid-values'

/** Ukryte pytanie „tylko nowe czy wszystkie” (useHiddenHint): wyłączone = zawsze tylko nowe artykuły. */
export const COLUMN_FILL_PROMPT = 'column-fill-prompt'

/** Kolumny, które da się ustawić naraz w całej kolumnie, z podpowiedzią do pola. */
const HINTS: Partial<Record<ColumnKey, string>> = {
  brand: 'Np. MILKA',
  region: 'Dwie cyfry rejonu, np. 66',
  family: 'Dwie cyfry rodziny albo pełny kod',
  subfamily: 'Dwie cyfry podrodziny albo pełny kod',
  unit: 'kg, L albo szt.',
  netContent: 'Np. 0,5',
}

export const COLUMN_FILL_LABELS: Partial<Record<ColumnKey, string>> = {
  brand: 'Marka',
  region: 'Rejon',
  family: 'Rodzina',
  subfamily: 'Podrodzina',
  unit: 'Rodzaj sprzedaży',
  netContent: 'Waga',
}

/**
 * Mały plus w nagłówku kolumny (widoczny mocniej po najechaniu): otwiera okno z wartością dla całej kolumny.
 * Okno stoi na stronie, poza tabelą, żeby pisanie w nim nie trafiało do tabeli.
 */
export function ColumnFillButton({ column, onOpen }: { column: Column; onOpen: (key: ColumnKey) => void }) {
  if (!HINTS[column.key]) return null
  const label = COLUMN_FILL_LABELS[column.key] ?? column.header
  return (
    <button
      type="button"
      aria-label={`${label} dla całej kolumny`}
      title={`${label}: ustaw jedną wartość w całej kolumnie`}
      onClick={() => onOpen(column.key)}
      className="grid size-5 shrink-0 place-items-center rounded bg-muted text-muted-foreground opacity-50 ring-1 ring-border transition-opacity duration-150 group-hover/th:opacity-100 hover:bg-background hover:text-foreground focus-visible:opacity-100"
    >
      <PlusIcon className="size-[1.05em]" />
    </button>
  )
}

export type ColumnFillTarget = { key: ColumnKey; newCount: number; allCount: number }

/** Okno wartości dla całej kolumny: od razu z wyborem „tylko nowe” albo „wszystkie” (bez pytania: tylko nowe). */
export function ColumnFillDialog({
  target,
  askScope,
  onClose,
  onApply,
}: {
  target: ColumnFillTarget | null
  askScope: boolean
  onClose: () => void
  onApply: (text: string, onlyNew: boolean) => void
}) {
  return (
    <Dialog open={target !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {target && <FillForm target={target} askScope={askScope} onCancel={onClose} onApply={onApply} />}
      </DialogContent>
    </Dialog>
  )
}

function FillForm({
  target,
  askScope,
  onCancel,
  onApply,
}: {
  target: ColumnFillTarget
  askScope: boolean
  onCancel: () => void
  onApply: (text: string, onlyNew: boolean) => void
}) {
  const [text, setText] = useState('')
  const label = COLUMN_FILL_LABELS[target.key] ?? ''
  const value = text.trim()
  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        if (value !== '' && target.newCount > 0) onApply(value, true)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">{label} w całej kolumnie</DialogTitle>
        <DialogDescription>
          {askScope
            ? 'Enter ustawia wartość w nowych artykułach. Artykuły z kartoteki zmienia drugi przycisk.'
            : 'Wartość trafi do nowych artykułów (pytanie o artykuły z kartoteki jest wyłączone w ustawieniach zapisu).'}
        </DialogDescription>
      </DialogHeader>
      <TextField
        label={label}
        autoComplete="off"
        autoFocus
        hint={HINTS[target.key]}
        value={text}
        onChange={(event) => setText(event.currentTarget.value)}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        {askScope && (
          <Button
            type="button"
            variant="outline"
            size="lg"
            disabled={value === '' || target.allCount === 0}
            onClick={() => onApply(value, false)}
          >
            Wszystkie ({target.allCount})
          </Button>
        )}
        <Button type="submit" size="lg" disabled={value === '' || target.newCount === 0}>
          Tylko nowe ({target.newCount})
        </Button>
      </FormActions>
    </Form>
  )
}
