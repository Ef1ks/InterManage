import { memo, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react'

import { formatMoney, formatPrice } from '@/features/documents/format'
import { cn } from '@/lib/utils'
import type { NomenclatureItem, SaleUnit } from '@/lib/types'

import {
  applyText,
  cellText,
  clearable,
  COLUMNS,
  type ColumnKey,
  levelCode,
  type Names,
  parseTsv,
  toTsv,
  withSubfamily,
  withUnit,
} from './grid-values'
import { NomenclaturePicker, UnitPicker } from './GridPickers'
import {
  type DataEntryRow,
  emptyRow,
  foreignInvoiceUnit,
  marginPercent,
  measureMismatch,
  NAME_LIMIT,
  netValue,
  nomenclatureState,
} from './rows'

export type GridHandle = {
  /** Zaznacza komórkę i przewija do niej (np. z listy problemów przed eksportem). */
  focusCell: (rowId: string, key: ColumnKey) => void
}

type Cell = { r: number; c: number }
type Editing = Cell & { text: string; mode: 'enter' | 'edit'; error?: string }
type Picker = Cell & { query: string }

type Props = {
  rows: DataEntryRow[]
  setRows: (update: (rows: DataEntryRow[]) => DataEntryRow[]) => void
  /** Wiersze ze zmienionym EAN-em (wpisanym albo wklejonym) - strona szuka ich w kartotece. */
  onEansChanged: (rowIds: string[]) => void
  onSelectedRowsChange: (rowIds: string[]) => void
  onInvalidPaste: (count: number) => void
  nomenclature: NomenclatureItem[]
  names: Names
  ref?: Ref<GridHandle>
}

const LAST = COLUMNS.length - 1

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))

/**
 * Tabela jak arkusz Excela: zaznaczanie komórki i zakresu (mysz, Shift+strzałki), strzałki, Tab i Enter, edycja przez
 * wpisanie albo F2/podwójne kliknięcie, Esc, Delete, Ctrl+C/Ctrl+V zakresu jako TSV (zgodne z Excelem). Nomenklatura
 * i rodzaj sprzedaży wybiera się z listy. Celowo bez animacji: nawigacja klawiaturą ma być natychmiastowa.
 */
export function DataEntryGrid({
  rows,
  setRows,
  onEansChanged,
  onSelectedRowsChange,
  onInvalidPaste,
  nomenclature,
  names,
  ref,
}: Props) {
  const gridRef = useRef<HTMLDivElement>(null)
  const [activeState, setActive] = useState<Cell>({ r: 0, c: 0 })
  const [anchorState, setAnchor] = useState<Cell>({ r: 0, c: 0 })
  const [editing, setEditing] = useState<Editing | null>(null)
  const [picker, setPicker] = useState<Picker | null>(null)
  const dragging = useRef(false)
  const lastRow = Math.max(rows.length - 1, 0)
  // Po usunięciu wierszy zaznaczenie nie może wskazywać poza tabelę.
  const active = activeState.r > lastRow ? { ...activeState, r: lastRow } : activeState
  const anchor = anchorState.r > lastRow ? { ...anchorState, r: lastRow } : anchorState

  const range = useMemo(
    () => ({
      top: Math.min(anchor.r, active.r),
      bottom: Math.max(anchor.r, active.r),
      left: Math.min(anchor.c, active.c),
      right: Math.max(anchor.c, active.c),
    }),
    [anchor.r, anchor.c, active.r, active.c],
  )

  const selectedIds = useMemo(
    () => rows.slice(range.top, range.bottom + 1).map((row) => row.id).join(','),
    [rows, range.top, range.bottom],
  )
  useEffect(() => {
    onSelectedRowsChange(selectedIds ? selectedIds.split(',') : [])
  }, [selectedIds, onSelectedRowsChange])

  const cellElement = useCallback(
    (cell: Cell) => gridRef.current?.querySelector<HTMLElement>(`[data-r="${cell.r}"][data-c="${cell.c}"]`) ?? null,
    [],
  )

  useEffect(() => {
    cellElement({ r: active.r, c: active.c })?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [active.r, active.c, cellElement])

  const focusGrid = () => gridRef.current?.focus({ preventScroll: true })

  const select = (cell: Cell, extend = false) => {
    const next = { r: clamp(cell.r, 0, lastRow), c: clamp(cell.c, 0, LAST) }
    setActive(next)
    if (!extend) setAnchor(next)
  }

  useImperativeHandle(ref, () => ({
    focusCell: (rowId, key) => {
      const r = rows.findIndex((row) => row.id === rowId)
      const c = COLUMNS.findIndex((column) => column.key === key)
      if (r < 0 || c < 0) return
      setEditing(null)
      setPicker(null)
      setActive({ r, c })
      setAnchor({ r, c })
      focusGrid()
    },
  }))

  // ---------- zmiany wierszy

  const updateRow = (index: number, update: (row: DataEntryRow) => DataEntryRow) =>
    setRows((current) => current.map((row, i) => (i === index ? update(row) : row)))

  const commitEdit = (state: Editing): boolean => {
    const row = rows[state.r]
    if (!row) return true
    const result = applyText(row, COLUMNS[state.c].key, state.text, names)
    if ('error' in result) {
      setEditing({ ...state, error: result.error })
      return false
    }
    if (result.row !== row) updateRow(state.r, () => result.row)
    if (result.eanChanged) onEansChanged([row.id])
    setEditing(null)
    return true
  }

  const startEdit = (cell: Cell, mode: Editing['mode'], text?: string) => {
    const column = COLUMNS[cell.c]
    const row = rows[cell.r]
    if (!row) return
    if (column.kind === 'nomenclature') {
      setPicker({ ...cell, c: cell.c, query: text ?? '' })
    } else if (column.kind === 'unit') {
      if (text) {
        const unit = ({ k: 'kg', l: 'L', s: 'szt.' } as Record<string, SaleUnit>)[text.toLowerCase()]
        if (unit) updateRow(cell.r, (current) => withUnit(current, unit))
        return
      }
      setPicker({ ...cell, query: '' })
    } else if (column.kind !== 'computed') {
      if (column.key === 'netContent' && row.unit === 'szt.') return
      setEditing({ ...cell, mode, text: text ?? cellText(row, column.key, names) })
    }
  }

  const clearRange = () => {
    setRows((current) =>
      current.map((row, index) => {
        if (index < range.top || index > range.bottom) return row
        let next = row
        for (let c = range.left; c <= range.right; c++) {
          const key = COLUMNS[c].key
          if (!clearable(key)) continue
          if (key === 'region' || key === 'family' || key === 'subfamily') {
            if (next.subfamilyCode !== null) next = withSubfamily(next, null)
            continue
          }
          const result = applyText(next, key, '', names)
          if (!('error' in result)) next = result.row
        }
        return next
      }),
    )
    if (range.left === 0) {
      onEansChanged(rows.slice(range.top, range.bottom + 1).filter((row) => row.ean !== '').map((row) => row.id))
    }
  }

  const paste = (text: string) => {
    const cells = parseTsv(text)
    if (cells.length === 0) return
    // Jedna wartość w zaznaczonym zakresie wypełnia cały zakres, jak w Excelu.
    const single = cells.length === 1 && cells[0].length === 1
    const height = single ? range.bottom - range.top + 1 : cells.length
    const width = single ? range.right - range.left + 1 : Math.max(...cells.map((line) => line.length))
    const top = single ? range.top : active.r
    const left = single ? range.left : active.c
    const next = [...rows]
    while (next.length < top + height) next.push(emptyRow())
    const changedEans: string[] = []
    let invalid = 0
    for (let i = 0; i < height; i++) {
      let row = next[top + i]
      for (let j = 0; j < width && left + j <= LAST; j++) {
        const value = single ? cells[0][0] : cells[i]?.[j]
        if (value === undefined) continue
        const result = applyText(row, COLUMNS[left + j].key, value, names)
        if ('error' in result) {
          invalid++
          continue
        }
        if (result.eanChanged) changedEans.push(row.id)
        row = result.row
      }
      next[top + i] = row
    }
    setRows(() => next)
    if (changedEans.length > 0) onEansChanged(changedEans)
    if (invalid > 0) onInvalidPaste(invalid)
    setAnchor({ r: top, c: left })
    setActive({ r: top + height - 1, c: Math.min(left + width - 1, LAST) })
  }

  // ---------- klawiatura i schowek

  const move = (dr: number, dc: number, extend: boolean, toEdge = false) => {
    const from = active
    if (toEdge) {
      select({ r: dr === 0 ? from.r : dr < 0 ? 0 : lastRow, c: dc === 0 ? from.c : dc < 0 ? 0 : LAST }, extend)
    } else {
      select({ r: from.r + dr, c: from.c + dc }, extend)
    }
  }

  const tab = (back: boolean) => {
    if (back) {
      select(active.c > 0 ? { r: active.r, c: active.c - 1 } : { r: Math.max(active.r - 1, 0), c: LAST })
    } else {
      select(active.c < LAST ? { r: active.r, c: active.c + 1 } : { r: Math.min(active.r + 1, lastRow), c: 0 })
    }
  }

  const onGridKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (editing || picker || event.target !== gridRef.current || rows.length === 0) return
    const ctrl = event.ctrlKey || event.metaKey
    const handled = () => event.preventDefault()
    switch (event.key) {
      case 'ArrowUp':
        handled()
        return move(-1, 0, event.shiftKey, ctrl)
      case 'ArrowDown':
        handled()
        if (event.altKey) return startEdit(active, 'edit')
        return move(1, 0, event.shiftKey, ctrl)
      case 'ArrowLeft':
        handled()
        return move(0, -1, event.shiftKey, ctrl)
      case 'ArrowRight':
        handled()
        return move(0, 1, event.shiftKey, ctrl)
      case 'Tab':
        handled()
        return tab(event.shiftKey)
      case 'Enter':
        handled()
        return move(event.shiftKey ? -1 : 1, 0, false)
      case 'Home':
        handled()
        return select({ r: ctrl ? 0 : active.r, c: 0 }, event.shiftKey)
      case 'End':
        handled()
        return select({ r: ctrl ? lastRow : active.r, c: LAST }, event.shiftKey)
      case 'F2':
        handled()
        return startEdit(active, 'edit')
      case 'Escape':
        return setAnchor(active)
      case 'Delete':
      case 'Backspace':
        handled()
        return clearRange()
    }
    if (ctrl && event.key.toLowerCase() === 'a') {
      handled()
      setAnchor({ r: 0, c: 0 })
      setActive({ r: lastRow, c: LAST })
      return
    }
    if (!ctrl && !event.altKey && event.key.length === 1) {
      handled()
      setAnchor(active)
      startEdit(active, 'enter', event.key)
    }
  }

  const onCopy = (event: React.ClipboardEvent) => {
    if (editing || picker) return
    event.preventDefault()
    const cells = rows
      .slice(range.top, range.bottom + 1)
      .map((row) => COLUMNS.slice(range.left, range.right + 1).map((column) => cellText(row, column.key, names)))
    event.clipboardData.setData('text/plain', toTsv(cells))
  }

  const onPaste = (event: React.ClipboardEvent) => {
    if (editing || picker) return
    event.preventDefault()
    paste(event.clipboardData.getData('text/plain'))
  }

  // ---------- mysz

  const cellFromEvent = (event: React.MouseEvent): Cell | null => {
    const cell = (event.target as HTMLElement).closest<HTMLElement>('[data-r]')
    return cell ? { r: Number(cell.dataset.r), c: Number(cell.dataset.c) } : null
  }

  useEffect(() => {
    const stop = () => (dragging.current = false)
    window.addEventListener('mouseup', stop)
    return () => window.removeEventListener('mouseup', stop)
  }, [])

  const onMouseDown = (event: React.MouseEvent) => {
    if (event.button !== 0) return
    const rowHeader = (event.target as HTMLElement).closest<HTMLElement>('[data-row-header]')
    if (rowHeader) {
      event.preventDefault()
      const r = Number(rowHeader.dataset.rowHeader)
      if (event.shiftKey) {
        setAnchor({ r: anchor.r, c: 0 })
        setActive({ r, c: LAST })
      } else {
        setAnchor({ r, c: 0 })
        setActive({ r, c: LAST })
      }
      focusGrid()
      return
    }
    const cell = cellFromEvent(event)
    if (!cell || (editing && editing.r === cell.r && editing.c === cell.c)) return
    event.preventDefault()
    if (editing && !commitEdit(editing)) setEditing(null)
    select(cell, event.shiftKey)
    dragging.current = true
    focusGrid()
  }

  const onMouseOver = (event: React.MouseEvent) => {
    if (!dragging.current) return
    const cell = cellFromEvent(event)
    if (cell) setActive(cell)
  }

  const onDoubleClick = (event: React.MouseEvent) => {
    const cell = cellFromEvent(event)
    if (cell) startEdit(cell, 'edit')
  }

  // ---------- edytor w komórce

  const onEditorKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (!editing) return
    const after = (next: () => void) => {
      event.preventDefault()
      if (commitEdit(editing)) {
        next()
        focusGrid()
      }
    }
    switch (event.key) {
      case 'Enter':
        return after(() => move(event.shiftKey ? -1 : 1, 0, false))
      case 'Tab':
        return after(() => tab(event.shiftKey))
      case 'Escape':
        event.preventDefault()
        setEditing(null)
        focusGrid()
        return
      case 'ArrowUp':
      case 'ArrowDown':
      case 'ArrowLeft':
      case 'ArrowRight':
        // Edycja rozpoczęta pisaniem: strzałki kończą ją i przechodzą dalej (jak w Excelu); po F2 przesuwają kursor.
        if (editing.mode === 'enter') {
          const [dr, dc] = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }[event.key]
          return after(() => move(dr, dc, false))
        }
    }
  }

  const editor = editing ? (
    <input
      autoFocus
      value={editing.text}
      aria-invalid={editing.error ? true : undefined}
      aria-label={COLUMNS[editing.c].header}
      title={editing.error}
      onChange={(event) => setEditing({ ...editing, text: event.target.value, error: undefined })}
      onKeyDown={onEditorKeyDown}
      onBlur={() => {
        if (!commitEdit(editing)) setEditing(null)
      }}
      className={cn(
        'absolute inset-0 z-10 w-full bg-background px-2 text-sm outline-2 -outline-offset-2 outline-primary',
        COLUMNS[editing.c].align === 'right' && 'text-right',
        editing.error && 'outline-destructive',
      )}
    />
  ) : null

  const pickerRow = picker ? rows[picker.r] : undefined
  const closePicker = () => {
    setPicker(null)
    focusGrid()
  }

  return (
    <>
      <div
        ref={gridRef}
        role="grid"
        aria-label="Pozycje do wprowadzenia"
        aria-rowcount={rows.length + 1}
        aria-colcount={COLUMNS.length}
        tabIndex={0}
        onKeyDown={onGridKeyDown}
        onCopy={onCopy}
        onPaste={onPaste}
        onMouseDown={onMouseDown}
        onMouseOver={onMouseOver}
        onDoubleClick={onDoubleClick}
        className="relative max-h-[calc(100svh-19rem)] min-h-48 overflow-auto rounded-xl border bg-card outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/30"
      >
        {/* Stały układ i szerokość 0: tabela ma dokładnie sumę szerokości kolumn, długa treść nie rozpycha kolumn. */}
        <table className="w-0 table-fixed border-separate border-spacing-0 text-sm tabular-nums">
          <colgroup>
            <col className="w-10" />
            {COLUMNS.map((column) => (
              <col key={column.key} className={column.width} />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th className="sticky top-0 left-0 z-30 h-9 border-r border-b bg-muted" aria-hidden />
              {COLUMNS.map((column, index) => (
                <th
                  key={column.key}
                  scope="col"
                  className={cn(
                    'sticky top-0 z-20 h-9 border-r border-b bg-muted px-2 text-left text-xs font-medium whitespace-nowrap text-muted-foreground',
                    column.align === 'right' && 'text-right',
                    index === 0 && 'left-10 z-30',
                    index >= range.left && index <= range.right && 'bg-accent text-foreground',
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => {
              const inRows = index >= range.top && index <= range.bottom
              return (
                <GridRow
                  key={row.id}
                  row={row}
                  index={index}
                  names={names}
                  rangeLeft={inRows ? range.left : -1}
                  rangeRight={inRows ? range.right : -1}
                  activeCol={active.r === index ? active.c : -1}
                  editorCol={editing?.r === index ? editing.c : -1}
                  editor={editing?.r === index ? editor : null}
                />
              )
            })}
          </tbody>
        </table>
      </div>

      {picker && pickerRow && COLUMNS[picker.c].kind === 'nomenclature' && (
        <NomenclaturePicker
          anchor={() => cellElement(picker)}
          row={pickerRow}
          initialQuery={picker.query}
          nomenclature={nomenclature}
          names={names}
          onPick={(code) => {
            updateRow(picker.r, (row) => withSubfamily(row, code))
            closePicker()
          }}
          onClose={closePicker}
        />
      )}
      {picker && pickerRow && COLUMNS[picker.c].kind === 'unit' && (
        <UnitPicker
          anchor={() => cellElement(picker)}
          value={pickerRow.unit}
          onPick={(unit) => {
            updateRow(picker.r, (row) => withUnit(row, unit))
            closePicker()
          }}
          onClose={closePicker}
        />
      )}
    </>
  )
}

type RowProps = {
  row: DataEntryRow
  index: number
  names: Names
  rangeLeft: number
  rangeRight: number
  activeCol: number
  editorCol: number
  editor: React.ReactNode
}

const NOMENCLATURE_TINT: Record<string, string> = {
  CERTAIN: 'bg-emerald-50 dark:bg-emerald-950/30',
  AGREED: 'bg-sky-50 dark:bg-sky-950/30',
  TO_CHECK: 'bg-amber-100/80 text-amber-950 dark:bg-amber-950/40 dark:text-amber-100',
}

/** Wiersz tabeli; odświeża się tylko, gdy zmienia się on sam albo jego część zaznaczenia. */
const GridRow = memo(function GridRow({ row, index, names, rangeLeft, rangeRight, activeCol, editorCol, editor }: RowProps) {
  const state = nomenclatureState(row)
  return (
    <tr aria-rowindex={index + 2}>
      <th
        scope="row"
        data-row-header={index}
        className={cn(
          'sticky left-0 z-10 h-8 cursor-default border-r border-b bg-muted px-1 text-center text-xs font-normal text-muted-foreground',
          rangeLeft >= 0 && 'bg-accent text-foreground',
        )}
      >
        {index + 1}
      </th>
      {COLUMNS.map((column, c) => {
        const selected = c >= rangeLeft && c <= rangeRight
        const isActive = c === activeCol
        const nomenclature = column.kind === 'nomenclature'
        return (
          <td
            key={column.key}
            data-r={index}
            data-c={c}
            role="gridcell"
            aria-selected={selected}
            className={cn(
              'relative h-8 scroll-mt-9 scroll-ml-54 truncate border-r border-b px-2 whitespace-nowrap',
              column.align === 'right' && 'text-right',
              c === 0 && 'sticky left-10 z-[5] bg-card',
              column.kind === 'computed' && 'text-muted-foreground',
              nomenclature && NOMENCLATURE_TINT[state],
              // Przyklejona kolumna EAN musi mieć pełne tło, inaczej przy przewijaniu prześwitują komórki spod spodu.
              selected && (c === 0 ? 'bg-accent' : 'bg-primary/8'),
              isActive && 'outline-2 -outline-offset-2 outline-primary',
            )}
          >
            <CellContent row={row} column={column.key} names={names} state={state} />
            {c === editorCol && editor}
          </td>
        )
      })}
    </tr>
  )
})

const quantityFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })
const percentFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1 })
const weightFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 3, maximumFractionDigits: 3 })

function Tag({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('ml-1.5 rounded px-1 py-px align-[0.08em] text-[0.68rem] font-medium', className)}>{children}</span>
  )
}

function CellContent({ row, column, names, state }: { row: DataEntryRow; column: ColumnKey; names: Names; state: string }) {
  switch (column) {
    case 'ean':
      return (
        <span className="flex items-center">
          {row.ean || <span className="text-muted-foreground">brak EAN</span>}
          {!row.known && (row.ean !== '' || row.name !== '') && (
            <Tag className="bg-violet-100 text-violet-800 dark:bg-violet-950/50 dark:text-violet-200">nowy</Tag>
          )}
        </span>
      )
    case 'quantity':
      return row.quantity === null ? null : quantityFormat.format(row.quantity)
    case 'unitNet':
      return formatPrice(row.unitNet)
    case 'name': {
      const tooLong = row.name.length > NAME_LIMIT
      // Plakietki zostają widoczne na końcu komórki, a długa nazwa skraca się wielokropkiem (pełna w podpowiedzi).
      return (
        <span className="flex min-w-0 items-center" title={row.nameFromInvoice ? `Z faktury: ${row.invoiceName}` : row.name}>
          <span className={cn('truncate', row.nameFromInvoice && 'text-muted-foreground italic')}>{row.name}</span>
          {row.nameFromInvoice && (
            <Tag className="shrink-0 bg-amber-100 text-amber-900 dark:bg-amber-950/50 dark:text-amber-200">do poprawy</Tag>
          )}
          {tooLong && (
            <Tag className="shrink-0 bg-destructive/10 text-destructive">
              {row.name.length}/{NAME_LIMIT}
            </Tag>
          )}
        </span>
      )
    }
    case 'brand':
      return row.brand
    case 'value':
      return formatMoney(netValue(row))
    case 'vat':
      return row.vatPercent === null ? null : `${percentFormat.format(row.vatPercent)}%`
    case 'salePrice':
      return formatMoney(row.salePrice)
    case 'margin': {
      const foreign = foreignInvoiceUnit(row)
      if (foreign) {
        return (
          <span className="text-amber-700 dark:text-amber-300" title={`Na fakturze jednostka „${foreign}” - marża liczy się od ceny sztuki. Popraw cenę netto i ilość na sztuki.`}>
            {foreign}?
          </span>
        )
      }
      const margin = marginPercent(row)
      return margin === null ? null : (
        <span className={cn(margin < 0 && 'font-medium text-destructive')}>{percentFormat.format(margin)}%</span>
      )
    }
    case 'region':
    case 'family':
    case 'subfamily': {
      const code = levelCode(row, column)
      if (code === null) {
        if (column !== 'subfamily') return null
        if (state === 'TO_CHECK') {
          return <span className="font-medium">Wybierz z {row.suggestion?.proposals.length ?? 0} propozycji</span>
        }
        return row.known ? null : <span className="text-muted-foreground">—</span>
      }
      return (
        <span title={`${code} ${names.get(code) ?? ''}`}>
          <span className="text-muted-foreground">{code}</span> {names.get(code)}
        </span>
      )
    }
    case 'unit':
      return row.unit
    case 'netContent': {
      const mismatch = measureMismatch(row) || (row.suggestion?.unitMismatch && !row.edited.netContent)
      return (
        <span
          className={cn(mismatch && 'rounded bg-amber-100 px-1 text-amber-900 dark:bg-amber-950/50 dark:text-amber-200')}
          title={mismatch ? 'Waga nie zgadza się z miarą w nazwie - sprawdź' : undefined}
        >
          {weightFormat.format(row.netContent)}
        </span>
      )
    }
  }
}
