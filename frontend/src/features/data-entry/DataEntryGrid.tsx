import {
  ArrowDownIcon,
  ArrowUpIcon,
  ChartColumnIcon,
  ChevronDownIcon,
  GripVerticalIcon,
  Link2Icon,
  PlusIcon,
  SplitIcon,
  TriangleAlertIcon,
} from 'lucide-react'
import { Fragment, memo, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react'

import { formatDocumentDate, formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { cn } from '@/lib/utils'
import type { NomenclatureItem, SaleUnit } from '@/lib/types'

import {
  applyText,
  cellText,
  clearable,
  type Column,
  COLUMNS,
  type ColumnKey,
  levelCode,
  type Names,
  type NomenclatureKey,
  parseTsv,
  toTsv,
  withNomenclature,
  withSubfamily,
  withUnit,
} from './grid-values'
import { NomenclaturePicker, UnitPicker } from './GridPickers'
import { NOMENCLATURE_TINTS, ROW_TINTS } from './row-tints'
import { Button } from '@/components/ui/button'

import {
  activePromoPrice,
  belowRequiredMargin,
  vatMismatch,
  cadencierPrice,
  canSellSmaller,
  canSplitQuantity,
  type CatalogField,
  catalogChanges,
  type DataEntryRow,
  depositError,
  depositLabel,
  depositNotice,
  missingDeposit,
  eanError,
  emptyRow,
  type ServiceKind,
  type Pricing,
  marginPercent,
  measureMismatch,
  NAME_LIMIT,
  netValue,
  nomenclatureState,
  requiredMargin,
  SERVICE_LABELS,
  serviceKind,
  SMALLER_QUANTITIES_MARGIN,
  suggestedPrice,
  supplierPriceOf,
  withAutoSalePrice,
} from './rows'

export type GridHandle = {
  /** Zaznacza komórkę i przewija do niej (np. z listy problemów przed eksportem). */
  focusCell: (rowId: string, key: ColumnKey) => void
  /** Wraca fokusem do tabeli bez zmiany zaznaczenia (np. po przycisku nad tabelą). */
  focus: () => void
  /** Zaznacza całą tabelę (jak Ctrl+A). */
  selectAll: () => void
}

type Cell = { r: number; c: number }
/** Przeciągane wiersze: blok od {@code start} o długości {@code count}, wstawiany przed wiersz {@code to}. */
type RowDrag = { start: number; count: number; to: number }
type Editing = Cell & { text: string; mode: 'enter' | 'edit'; error?: string }
type Picker = Cell & { query: string }

type Props = {
  rows: DataEntryRow[]
  setRows: (update: (rows: DataEntryRow[]) => DataEntryRow[]) => void
  /** Wiersze ze zmienionym EAN-em (wpisanym albo wklejonym) - strona szuka ich w kartotece. */
  onEansChanged: (rowIds: string[]) => void
  onSelectedRowsChange: (rowIds: string[]) => void
  /** Ctrl+− (jak w Excelu): usuwa wiersze zaznaczenia. */
  onDeleteRows?: (rowIds: string[]) => void
  onInvalidPaste: (count: number) => void
  nomenclature: NomenclatureItem[]
  names: Names
  pricing: Pricing
  /** Okno ze sprzedażą i dostawami artykułu z kartoteki; bez niego (brak uprawnienia) wiersze nie mają przycisku. */
  onShowArticle?: (row: DataEntryRow) => void
  /** Okno przypięcia pozycji bez EAN-u (albo spoza kartoteki) do artykułu z kartoteki. */
  onLinkArticle?: (row: DataEntryRow) => void
  /** Okno podziału ilości (opakowanie z faktury na sztuki sprzedaży, cena przeliczona). */
  onSplitQuantity?: (row: DataEntryRow) => void
  /** Gdzie przykleja się nagłówek kolumn przy przewijaniu strony (pod nagłówkiem aplikacji i paskiem nad tabelą). */
  stickyTop?: string
  /** Kolumny tabeli (domyślnie jak we wprowadzaniu danych); pierwsza to zawsze EAN. */
  columns?: Column[]
  /** Dodatek w nagłówku kolumny, np. filtr jak w Excelu. */
  headerExtra?: (column: Column) => React.ReactNode
  /** Klik w nagłówek kolumny sortuje tabelę (sortuje strona); {@code sort} to strzałka przy sortowanej kolumnie. */
  onSort?: (key: ColumnKey) => void
  sort?: { key: ColumnKey; direction: 'asc' | 'desc' } | null
  ref?: Ref<GridHandle>
}

const NO_RANGE = { top: -1, bottom: -2, left: -1, right: -2 }

/** Szerokość kolumny z numerem wiersza w em czcionki tabeli. */
const ROW_HEADER_EM = 2.5

/**
 * Czcionka tabeli dopasowana do szerokości miejsca (cqw obszaru, minus ramka i pasek przewijania), żeby na laptopie
 * było widać wszystkie kolumny bez przewijania w bok. Nie większa niż zwykłe text-sm; poniżej dolnej granicy tabela
 * przewija się w bok.
 */
const tableFontSize = (tableEm: number) => `clamp(0.625rem, calc((100cqw - 1.25rem) / ${tableEm}), 0.875rem)`

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))

/**
 * Zaznaczenie rysuje warstwa nad komórką (::after), żeby kolor wiersza, nomenklatury i błędów został widoczny pod
 * spodem: niebieskie przyciemnienie i ramka wokół całego zakresu. Niebieski różni się od kolorów wierszy, a ramka
 * odróżnia zaznaczenie także od niebieskich komórek nomenklatury. Warstwa nie łapie kliknięć (przyciski w komórkach).
 */
const SELECTION_LAYER =
  'after:pointer-events-none after:absolute after:inset-0 after:border-blue-600 dark:after:border-blue-400'
const SELECTION_FILL = 'after:bg-blue-600/25 dark:after:bg-blue-400/30'
/** Nagłówek wiersza albo kolumny w zaznaczeniu. */
const SELECTED_HEADER = 'bg-blue-200 text-blue-950 dark:bg-blue-900 dark:text-blue-50'

/** Blok wierszy przeniesiony przed wiersz {@code to} (indeks sprzed przeniesienia). */
function moveRows<T>(rows: T[], start: number, count: number, to: number) {
  const block = rows.slice(start, start + count)
  const rest = [...rows.slice(0, start), ...rows.slice(start + count)]
  const at = to > start ? to - count : to
  return [...rest.slice(0, at), ...block, ...rest.slice(at)]
}

/** Odległość od krawędzi okna (px), przy której przeciąganie wiersza przewija stronę. */
const AUTO_SCROLL_EDGE = 72

/**
 * Tabela jak arkusz Excela: zaznaczanie komórki i zakresu (mysz, Shift+strzałki), strzałki, Tab i Enter, edycja przez
 * wpisanie albo F2/podwójne kliknięcie, Esc, Delete (czyści komórki), Ctrl+− (usuwa wiersze), Ctrl+C/Ctrl+V zakresu jako TSV (zgodne z Excelem). Nomenklatura
 * i rodzaj sprzedaży wybiera się z listy. Celowo bez animacji: nawigacja klawiaturą ma być natychmiastowa.
 */
export function DataEntryGrid({
  rows,
  setRows,
  onEansChanged,
  onSelectedRowsChange,
  onDeleteRows,
  onInvalidPaste,
  nomenclature,
  names,
  pricing,
  onShowArticle,
  onLinkArticle,
  onSplitQuantity,
  stickyTop = '3.5rem',
  columns = COLUMNS,
  headerExtra,
  onSort,
  sort,
  ref,
}: Props) {
  const last = columns.length - 1
  // Szerokości w em czcionki tabeli: pierwsza kolumna (EAN, przyklejona) i cała tabela.
  const stickyEm = ROW_HEADER_EM + columns[0].width
  const tableEm = columns.reduce((sum, column) => sum + column.width, ROW_HEADER_EM)
  const gridRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [activeState, setActiveState] = useState<Cell>({ r: 0, c: 0 })
  const [anchorState, setAnchor] = useState<Cell>({ r: 0, c: 0 })
  // Klik poza tabelą chowa zaznaczenie; każda zmiana aktywnej komórki (klik, klawisz) pokazuje je z powrotem.
  const [selectionHidden, setSelectionHidden] = useState(false)
  const setActive = useCallback((cell: Cell) => {
    setSelectionHidden(false)
    setActiveState(cell)
  }, [])
  const [editing, setEditing] = useState<Editing | null>(null)
  const [picker, setPicker] = useState<Picker | null>(null)
  const dragging = useRef(false)
  // Komórka z listą była już aktywna przed kliknięciem: dopiero wtedy klik otwiera listę (pierwszy tylko zaznacza).
  const clickedActive = useRef(false)
  const lastRow = Math.max(rows.length - 1, 0)
  // Po usunięciu wierszy zaznaczenie nie może wskazywać poza tabelę.
  const active = activeState.r > lastRow ? { ...activeState, r: lastRow } : activeState
  const anchor = anchorState.r > lastRow ? { ...anchorState, r: lastRow } : anchorState

  const range = useMemo(
    () =>
      selectionHidden
        ? NO_RANGE
        : {
            top: Math.min(anchor.r, active.r),
            bottom: Math.max(anchor.r, active.r),
            left: Math.min(anchor.c, active.c),
            right: Math.max(anchor.c, active.c),
          },
    [selectionHidden, anchor.r, anchor.c, active.r, active.c],
  )

  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Element | null
      if (!target || containerRef.current?.contains(target)) return
      // Przyciski działające na zaznaczeniu (data-keep-grid-selection), okna, listy i toasty go nie chowają.
      if (
        target.closest(
          '[data-keep-grid-selection], [role="dialog"], [role="alertdialog"], [role="listbox"], [role="menu"], [data-sonner-toaster]',
        )
      )
        return
      setSelectionHidden(true)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [])

  // Przewija się cała strona (jeden pasek), a nagłówek kolumn przykleja się pod nagłówkiem aplikacji. Tabela przewija się
  // w poziomie tylko wtedy, gdy nie mieści się nawet przy najmniejszej czcionce (wtedy nagłówek klei się tylko w niej,
  // bo przewijanie w poziomie robi z niej obszar przewijania także w pionie).
  const [scrollsX, setScrollsX] = useState(false)
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const observer = new ResizeObserver(() => {
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize)
      setScrollsX(container.clientWidth < (tableEm * 0.625 + 1.25) * rem)
    })
    observer.observe(container)
    return () => observer.disconnect()
  }, [tableEm])

  const selectedIds = useMemo(
    () =>
      rows
        .slice(range.top, range.bottom + 1)
        .map((row) => row.id)
        .join(','),
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
    cellElement({ r: active.r, c: active.c })?.scrollIntoView({
      block: 'nearest',
      inline: 'nearest',
    })
  }, [active.r, active.c, cellElement])

  const focusGrid = () => gridRef.current?.focus({ preventScroll: true })

  const select = (cell: Cell, extend = false) => {
    const next = { r: clamp(cell.r, 0, lastRow), c: clamp(cell.c, 0, last) }
    setActive(next)
    if (!extend) setAnchor(next)
  }

  // Aktywna komórka zostaje na początku (jak w Excelu), bo tabela przewija się do niej; zakres i tak obejmuje całość.
  const selectAll = () => {
    setAnchor({ r: lastRow, c: last })
    setActive({ r: 0, c: 0 })
  }

  useImperativeHandle(ref, () => ({
    focus: focusGrid,
    selectAll: () => {
      setEditing(null)
      setPicker(null)
      selectAll()
      focusGrid()
    },
    focusCell: (rowId, key) => {
      const r = rows.findIndex((row) => row.id === rowId)
      const c = columns.findIndex((column) => column.key === key)
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
    const result = applyText(row, columns[state.c].key, state.text, names)
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
    const column = columns[cell.c]
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
      setEditing({
        ...cell,
        mode,
        text: text ?? cellText(row, column.key, pricing),
      })
    }
  }

  const clearRange = () => {
    setRows((current) =>
      current.map((row, index) => {
        if (index < range.top || index > range.bottom) return row
        let next = row
        for (let c = range.left; c <= range.right; c++) {
          const key = columns[c].key
          if (!clearable(key)) continue
          if (key === 'region' || key === 'family' || key === 'subfamily') {
            if (next.subfamilyCode !== null || next.nomenclaturePrefix) next = withSubfamily(next, null)
            continue
          }
          const result = applyText(next, key, '', names)
          if (!('error' in result)) next = result.row
        }
        return next
      }),
    )
    if (range.left === 0) {
      onEansChanged(
        rows
          .slice(range.top, range.bottom + 1)
          .filter((row) => row.ean !== '')
          .map((row) => row.id),
      )
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
      for (let j = 0; j < width && left + j <= last; j++) {
        const value = single ? cells[0][0] : cells[i]?.[j]
        if (value === undefined) continue
        const result = applyText(row, columns[left + j].key, value, names)
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
    setActive({ r: top + height - 1, c: Math.min(left + width - 1, last) })
  }

  // ---------- klawiatura i schowek

  const move = (dr: number, dc: number, extend: boolean, toEdge = false) => {
    const from = active
    if (toEdge) {
      select(
        {
          r: dr === 0 ? from.r : dr < 0 ? 0 : lastRow,
          c: dc === 0 ? from.c : dc < 0 ? 0 : last,
        },
        extend,
      )
    } else {
      select({ r: from.r + dr, c: from.c + dc }, extend)
    }
  }

  const tab = (back: boolean) => {
    if (back) {
      select(active.c > 0 ? { r: active.r, c: active.c - 1 } : { r: Math.max(active.r - 1, 0), c: last })
    } else {
      select(active.c < last ? { r: active.r, c: active.c + 1 } : { r: Math.min(active.r + 1, lastRow), c: 0 })
    }
  }

  const onGridKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (editing || picker || event.target !== gridRef.current || rows.length === 0) return
    setSelectionHidden(false)
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
        return select({ r: ctrl ? lastRow : active.r, c: last }, event.shiftKey)
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
      return selectAll()
    }
    if (ctrl && (event.key === '-' || event.key === 'Subtract') && onDeleteRows && selectedIds) {
      handled()
      return onDeleteRows(selectedIds.split(','))
    }
    // Jak w Excelu: Ctrl + plus wstawia wiersz nad bieżącym.
    if (ctrl && (event.key === '+' || event.key === '=' || event.key === 'Add')) {
      handled()
      return insertRow(active.r)
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
      .map((row) => columns.slice(range.left, range.right + 1).map((column) => cellText(row, column.key, pricing)))
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
    const insert = (event.target as HTMLElement).closest<HTMLElement>('[data-row-insert]')
    if (insert) {
      event.preventDefault()
      insertRow(Number(insert.dataset.rowInsert) + 1)
      return
    }
    const handle = (event.target as HTMLElement).closest<HTMLElement>('[data-row-handle]')
    if (handle) {
      event.preventDefault()
      startRowDrag(Number(handle.dataset.rowHandle))
      return
    }
    const rowHeader = (event.target as HTMLElement).closest<HTMLElement>('[data-row-header]')
    if (rowHeader) {
      event.preventDefault()
      const r = Number(rowHeader.dataset.rowHeader)
      if (event.shiftKey) {
        setAnchor({ r: anchor.r, c: 0 })
        setActive({ r, c: last })
      } else {
        setAnchor({ r, c: 0 })
        setActive({ r, c: last })
      }
      focusGrid()
      return
    }
    const cell = cellFromEvent(event)
    if (!cell || (editing && editing.r === cell.r && editing.c === cell.c)) return
    event.preventDefault()
    if (editing && !commitEdit(editing)) setEditing(null)
    clickedActive.current =
      !event.shiftKey && !selectionHidden && cell.r === active.r && cell.c === active.c && range.top === range.bottom
    select(cell, event.shiftKey)
    dragging.current = true
    focusGrid()
  }

  const onMouseOver = (event: React.MouseEvent) => {
    if (!dragging.current) return
    const cell = cellFromEvent(event)
    if (cell) setActive(cell)
  }

  // Listy (nomenklatura, rodzaj): pierwsze kliknięcie tylko zaznacza komórkę (pojawia się w niej pole ze strzałką na
  // całą szerokość), kliknięcie w zaznaczoną otwiera listę. Przeciągnięcie zaznacza zakres jak zwykle.
  const onClick = (event: React.MouseEvent) => {
    const cell = cellFromEvent(event)
    if (!cell || event.shiftKey || editing || picker || !clickedActive.current) return
    const kind = columns[cell.c].kind
    if (kind !== 'nomenclature' && kind !== 'unit') return
    if (anchor.r !== cell.r || anchor.c !== cell.c || active.r !== cell.r || active.c !== cell.c) return
    startEdit(cell, 'edit')
  }

  const onDoubleClick = (event: React.MouseEvent) => {
    // W trakcie pisania w komórce podwójne kliknięcie działa jak w zwykłym polu (zaznacza słowo), a nie zaczyna
    // edycji od nowa (to cofałoby wpisane zmiany).
    if (event.target instanceof HTMLInputElement) return
    const cell = cellFromEvent(event)
    if (cell) startEdit(cell, 'edit')
  }

  // ---------- przenoszenie wierszy (uchwyt przy numerze wiersza)

  const [rowDrag, setRowDrag] = useState<RowDrag | null>(null)
  const isMovable = (row: DataEntryRow) => (row.ean !== '' || row.name !== '') && serviceKind(row) === null
  // Artykuły zostają nad pustym wierszem na kolejny EAN i nad kaucjami z dostawą (te układa strona).
  const movableEnd = (() => {
    const services = rows.findIndex((row) => serviceKind(row) !== null)
    let end = services < 0 ? rows.length : services
    while (end > 0 && !isMovable(rows[end - 1])) end--
    return end
  })()

  /**
   * Przeciągnięcie uchwytu przenosi wiersz, a gdy jest w zaznaczeniu - wszystkie zaznaczone wiersze naraz. Linia
   * pokazuje miejsce wstawienia; przy górnej i dolnej krawędzi okna strona przewija się sama. Esc przerywa.
   */
  const startRowDrag = (index: number) => {
    const inSelection = index >= range.top && index <= range.bottom && range.bottom < movableEnd
    const start = inSelection ? range.top : index
    const count = inSelection ? range.bottom - range.top + 1 : 1
    const span = inSelection ? { left: range.left, right: range.right } : { left: 0, right: last }
    let state: RowDrag = { start, count, to: start }
    let pointerY = 0
    let frame = 0
    setEditing(null)
    setPicker(null)
    setRowDrag(state)

    const target = () => {
      const headers = gridRef.current?.querySelectorAll<HTMLElement>('[data-row-header]') ?? []
      let to = rows.length
      for (const header of headers) {
        const rect = header.getBoundingClientRect()
        if (pointerY < rect.top + rect.height / 2) {
          to = Number(header.dataset.rowHeader)
          break
        }
      }
      return clamp(to, 0, movableEnd)
    }
    const update = () => {
      const to = target()
      if (to !== state.to) {
        state = { ...state, to }
        setRowDrag(state)
      }
    }
    const tick = () => {
      // Górna krawędź liczona od dołu przyklejonego nagłówka kolumn.
      const header = gridRef.current?.querySelector('thead')?.getBoundingClientRect().bottom ?? 56
      const top = Math.max(header, 56) + AUTO_SCROLL_EDGE / 2
      const bottom = window.innerHeight - AUTO_SCROLL_EDGE
      const step =
        pointerY < top ? -Math.ceil((top - pointerY) / 4) : pointerY > bottom ? Math.ceil((pointerY - bottom) / 4) : 0
      if (pointerY > 0 && step !== 0) {
        window.scrollBy(0, step)
        update()
      }
      frame = requestAnimationFrame(tick)
    }
    const finish = (apply: boolean) => {
      cancelAnimationFrame(frame)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('keydown', onKey, true)
      document.body.style.removeProperty('cursor')
      setRowDrag(null)
      focusGrid()
      const { to } = state
      if (!apply || (to >= start && to <= start + count)) return
      setRows((current) => moveRows(current, start, count, to))
      const first = to > start ? to - count : to
      setAnchor({ r: first, c: span.left })
      setActive({ r: first + count - 1, c: span.right })
    }
    const onMove = (event: PointerEvent) => {
      pointerY = event.clientY
      update()
    }
    const onUp = () => finish(true)
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      finish(false)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('keydown', onKey, true)
    document.body.style.cursor = 'grabbing'
    frame = requestAnimationFrame(tick)
  }

  // ---------- skanowanie

  /**
   * Po zatwierdzeniu EAN-u (czytnik kodów kończy Enterem) kolejny wiersz: pusty następny albo nowy wstawiony pod
   * bieżącym, od razu w edycji EAN-u, więc kolejne skany idą jeden po drugim bez dotykania myszy.
   */
  const nextScan = (r: number) => {
    // Stan bieżący, nie z renderu: zatwierdzenie EAN-u mogło już dodać pusty wiersz na końcu.
    setRows((current) => {
      const next = current[r + 1]
      return next && next.ean === '' && next.name === ''
        ? current
        : [...current.slice(0, r + 1), emptyRow(), ...current.slice(r + 1)]
    })
    const cell = { r: r + 1, c: 0 }
    setActive(cell)
    setAnchor(cell)
    setEditing({ ...cell, mode: 'enter', text: '' })
  }

  /** Pusty wiersz w miejscu `r`, od razu w edycji EAN-u; między artykułami, nie wśród kaucji i dostawy. */
  const insertRow = (r: number) => {
    const at = Math.min(r, movableEnd)
    setRows((current) => [...current.slice(0, at), emptyRow(), ...current.slice(at)])
    const cell = { r: at, c: 0 }
    setActive(cell)
    setAnchor(cell)
    setEditing({ ...cell, mode: 'enter', text: '' })
  }

  // ---------- przewijanie

  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const onScroll = () => setScrolled((gridRef.current?.getBoundingClientRect().top ?? 0) < -240)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])
  const scrollToTop = () => {
    const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    window.scrollTo({ top: 0, behavior: smooth ? 'smooth' : 'auto' })
    focusGrid()
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
        // Skan w pustym wierszu idzie dalej seriami; poprawiony kod pozycji (np. błędny EAN z faktury) tylko
        // przechodzi niżej, bez wstawiania pustego wiersza.
        if (
          columns[editing.c].key === 'ean' &&
          !event.shiftKey &&
          editing.text.trim() !== '' &&
          rows[editing.r]?.name === ''
        ) {
          event.preventDefault()
          if (commitEdit(editing)) nextScan(editing.r)
          return
        }
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
          const [dr, dc] = {
            ArrowUp: [-1, 0],
            ArrowDown: [1, 0],
            ArrowLeft: [0, -1],
            ArrowRight: [0, 1],
          }[event.key]
          return after(() => move(dr, dc, false))
        }
    }
  }

  const editor = editing ? (
    <input
      autoFocus
      value={editing.text}
      aria-invalid={editing.error ? true : undefined}
      aria-label={columns[editing.c].title ?? columns[editing.c].header}
      title={editing.error}
      onChange={(event) => setEditing({ ...editing, text: event.target.value, error: undefined })}
      onKeyDown={onEditorKeyDown}
      onBlur={() => {
        if (!commitEdit(editing)) setEditing(null)
      }}
      className={cn(
        'absolute inset-0 z-10 w-full bg-background px-2 font-normal text-foreground outline-2 -outline-offset-2 outline-primary',
        columns[editing.c].align === 'right' && 'text-right',
        editing.error && 'outline-destructive',
      )}
    />
  ) : null

  const tooLongNames = rows.filter((row) => row.name.length > NAME_LIMIT && !serviceKind(row)).length
  const invalidEans = rows.filter((row) => eanError(row) !== null).length
  const depositErrors = rows.filter((row) => depositError(row) !== null).length
  const vatMismatches = rows.filter(vatMismatch).length

  // Kaucje i dostawa są zawsze na końcu (układa je strona), nad nimi nagłówek sekcji.
  const firstService = rows.findIndex((row) => serviceKind(row) !== null)

  const pickerRow = picker ? rows[picker.r] : undefined
  const closePicker = () => {
    setPicker(null)
    focusGrid()
  }

  return (
    <div ref={containerRef} className="@container relative flex flex-col">
      <div
        ref={gridRef}
        role="grid"
        aria-label="Pozycje do wprowadzenia"
        aria-rowcount={rows.length + 1}
        aria-colcount={columns.length}
        tabIndex={0}
        onKeyDown={onGridKeyDown}
        onCopy={onCopy}
        onPaste={onPaste}
        onMouseDown={onMouseDown}
        onMouseOver={onMouseOver}
        onClick={onClick}
        onDoubleClick={onDoubleClick}
        className={cn(
          'relative rounded-xl border bg-card outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/30',
          scrollsX ? 'overflow-x-auto' : 'overflow-x-clip',
        )}
      >
        {/* Stały układ: długa treść nie rozpycha kolumn. Gdy po dojściu do pełnej czcionki zostaje miejsce, tabela
            wypełnia całą szerokość, a nadmiar dzieli się między kolumny proporcjonalnie do ich szerokości. */}
        <table
          className="table-fixed border-separate border-spacing-0 tabular-nums"
          style={
            {
              fontSize: tableFontSize(tableEm),
              width: `max(${tableEm}em, 100%)`,
              // Przesunięcia przyklejonej kolumny EAN w em, liczone w komórce (ma czcionkę tabeli).
              '--sticky-left': `${ROW_HEADER_EM}em`,
              '--sticky-width': `${stickyEm}em`,
              // Nagłówek kolumn klei się pod nagłówkiem aplikacji i paskiem nad tabelą, a przy przewijaniu w poziomie
              // u góry tabeli. Komórka przewinięta do widoku zatrzymuje się pod nagłówkiem kolumn (h-9).
              '--header-top': scrollsX ? '0' : stickyTop,
              '--scroll-top': `calc(${stickyTop} + 2.25rem)`,
            } as React.CSSProperties
          }
        >
          <colgroup>
            <col style={{ width: `${ROW_HEADER_EM}em` }} />
            {columns.map((column) => (
              <col key={column.key} style={{ width: `${column.width}em` }} />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th className="sticky top-(--header-top) left-0 z-30 h-9 border-r border-b bg-muted" aria-hidden />
              {columns.map((column, index) => (
                <th
                  key={column.key}
                  scope="col"
                  title={
                    column.key === 'vat' && vatMismatches > 0
                      ? `Błędny VAT w kartotece: ${vatMismatches}. Obowiązuje stawka z dokumentu.`
                      : onSort
                        ? `${column.title ?? column.header} · kliknij, żeby posortować`
                        : column.title
                  }
                  aria-sort={
                    sort?.key === column.key ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined
                  }
                  onClick={onSort ? () => onSort(column.key) : undefined}
                  className={cn(
                    'group/th sticky top-(--header-top) z-20 h-9 truncate border-r border-b bg-muted px-2 text-left font-medium whitespace-nowrap text-muted-foreground',
                    onSort && 'cursor-pointer hover:text-foreground',
                    column.align === 'right' && 'text-right',
                    index === 0 && 'left-(--sticky-left) z-30',
                    index >= range.left && index <= range.right && SELECTED_HEADER,
                    column.key === 'name' &&
                      tooLongNames > 0 &&
                      'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
                    column.key === 'ean' &&
                      invalidEans > 0 &&
                      'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
                    column.key === 'deposit' &&
                      depositErrors > 0 &&
                      'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
                    column.key === 'vat' &&
                      vatMismatches > 0 &&
                      'bg-red-300 text-red-950 dark:bg-red-800 dark:text-red-50',
                  )}
                >
                  {/* Dodatek (np. filtr) leży nad prawym brzegiem nagłówka, żeby nie zabierał miejsca na nazwę. */}
                  {headerExtra && (
                    // Klik w dodatek (filtr) nie sortuje kolumny.
                    <span
                      className="absolute inset-y-0 right-0.5 flex items-center"
                      onClick={(event) => event.stopPropagation()}
                    >
                      {headerExtra(column)}
                    </span>
                  )}
                  <span className="flex min-w-0 items-center gap-1">
                    <span className={cn('min-w-0 truncate text-[0.86em]', column.align === 'right' && 'ml-auto')}>
                      {sort?.key === column.key &&
                        (sort.direction === 'asc' ? (
                          <ArrowUpIcon
                            aria-hidden
                            className="mr-0.5 inline size-[1.05em] align-[-0.15em] text-blue-700 dark:text-blue-300"
                          />
                        ) : (
                          <ArrowDownIcon
                            aria-hidden
                            className="mr-0.5 inline size-[1.05em] align-[-0.15em] text-blue-700 dark:text-blue-300"
                          />
                        ))}
                      {column.header}
                      {column.key === 'ean' && invalidEans > 0 && (
                        <span className="ml-1.5 rounded bg-destructive px-1 py-px text-[0.9em] text-white">
                          błędne: {invalidEans}
                        </span>
                      )}
                      {column.key === 'name' && tooLongNames > 0 && (
                        <span className="ml-1.5 rounded bg-destructive px-1 py-px text-[0.9em] text-white">
                          za długie: {tooLongNames}
                        </span>
                      )}
                      {column.key === 'vat' && vatMismatches > 0 && (
                        <span className="ml-1.5 rounded bg-destructive px-1 py-px text-[0.9em] text-white">
                          {vatMismatches}
                        </span>
                      )}
                      {column.key === 'deposit' && depositErrors > 0 && (
                        <span className="ml-1.5 rounded bg-destructive px-1 py-px text-[0.9em] text-white">
                          {depositErrors}
                        </span>
                      )}
                    </span>
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => {
              const inRows = index >= range.top && index <= range.bottom
              const dragged = rowDrag !== null && index >= rowDrag.start && index < rowDrag.start + rowDrag.count
              // Linia wstawienia: nad wierszem docelowym, a na końcu artykułów pod ostatnim z nich.
              const drop =
                rowDrag === null || (rowDrag.to >= rowDrag.start && rowDrag.to <= rowDrag.start + rowDrag.count)
                  ? null
                  : rowDrag.to === index
                    ? 'before'
                    : rowDrag.to === index + 1 && rowDrag.to === movableEnd
                      ? 'after'
                      : null
              return (
                <Fragment key={row.id}>
                  {index === firstService && (
                    <tr aria-hidden>
                      <td
                        colSpan={columns.length + 1}
                        className="h-8 border-b bg-muted px-3 text-xs font-medium text-muted-foreground"
                      >
                        <span className="sticky left-3">Kaucje i dostawa · nie trafiają do Excela</span>
                      </td>
                    </tr>
                  )}
                  <GridRow
                    columns={columns}
                    row={row}
                    index={index}
                    names={names}
                    pricing={pricing}
                    rangeLeft={inRows ? range.left : -1}
                    rangeRight={inRows ? range.right : -1}
                    rangeTop={inRows && index === range.top}
                    rangeBottom={inRows && index === range.bottom}
                    rangeSingle={range.top === range.bottom && range.left === range.right}
                    activeCol={!selectionHidden && active.r === index ? active.c : -1}
                    editorCol={editing?.r === index ? editing.c : -1}
                    editor={editing?.r === index ? editor : null}
                    onShowArticle={onShowArticle}
                    onLinkArticle={onLinkArticle}
                    onSplitQuantity={onSplitQuantity}
                    movable={index < movableEnd && isMovable(row)}
                    dragged={dragged}
                    drop={drop}
                  />
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>

      {scrolled && (
        <Button
          variant="outline"
          size="sm"
          onClick={scrollToTop}
          className="fixed right-6 bottom-6 z-40 shadow-md"
          title="Przewiń na górę strony (Ctrl+Home przenosi też zaznaczenie)"
        >
          <ArrowUpIcon />
          Na górę
        </Button>
      )}

      {picker && pickerRow && columns[picker.c].kind === 'nomenclature' && (
        <NomenclaturePicker
          key={`${picker.r}:${picker.c}`}
          anchor={() => cellElement(picker)}
          row={pickerRow}
          level={columns[picker.c].key as NomenclatureKey}
          initialQuery={picker.query}
          nomenclature={nomenclature}
          names={names}
          onPick={(code) => {
            updateRow(picker.r, (row) => withAutoSalePrice(row, withNomenclature(row, code), pricing))
            if (code.length < 6) {
              // Od razu lista kolejnego poziomu, z dziećmi wybranego kodu.
              const next = { r: picker.r, c: picker.c + 1 }
              select(next)
              setPicker({ ...next, query: '' })
            } else {
              closePicker()
            }
          }}
          onClose={closePicker}
        />
      )}
      {picker && pickerRow && columns[picker.c].kind === 'unit' && (
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
    </div>
  )
}

type RowProps = {
  columns: Column[]
  row: DataEntryRow
  index: number
  names: Names
  pricing: Pricing
  rangeLeft: number
  rangeRight: number
  /** Wiersz jest górną / dolną krawędzią zaznaczenia (ramka zakresu). */
  rangeTop: boolean
  rangeBottom: boolean
  /** Zaznaczona jedna komórka: wystarczy obrys aktywnej. */
  rangeSingle: boolean
  activeCol: number
  editorCol: number
  editor: React.ReactNode
  onShowArticle?: (row: DataEntryRow) => void
  onLinkArticle?: (row: DataEntryRow) => void
  onSplitQuantity?: (row: DataEntryRow) => void
  /** Wiersz ma uchwyt do przeciągania (artykuł; bez pustego wiersza, kaucji i dostawy). */
  movable: boolean
  dragged: boolean
  drop: 'before' | 'after' | null
}

/** Kolumna tabeli, w której widać zmianę pola z kartoteki. */
const CHANGED_COLUMNS: Record<CatalogField, ColumnKey[]> = {
  name: ['name'],
  brand: ['brand'],
  salePrice: ['salePrice'],
  subfamilyCode: ['region', 'family', 'subfamily'],
  unit: ['unit'],
  netContent: ['netContent'],
}

/** Wiersz tabeli; odświeża się tylko, gdy zmienia się on sam albo jego część zaznaczenia. */
const GridRow = memo(function GridRow({
  columns,
  row,
  index,
  names,
  pricing,
  rangeLeft,
  rangeRight,
  rangeTop,
  rangeBottom,
  rangeSingle,
  activeCol,
  editorCol,
  editor,
  onShowArticle,
  onLinkArticle,
  onSplitQuantity,
  movable,
  dragged,
  drop,
}: RowProps) {
  const state = nomenclatureState(row)
  const service = serviceKind(row)
  const tooLong = !service && row.name.length > NAME_LIMIT
  const badEan = eanError(row) !== null
  const badDeposit = depositError(row) !== null
  const badVat = vatMismatch(row)
  const changes = catalogChanges(row)
  const changedColumns = new Set(changes.flatMap((field) => CHANGED_COLUMNS[field]))
  const filled = row.ean !== '' || row.name !== ''
  // Szary: zapisany w kartotece; pomarańczowy: z kartoteki, ale zmieniony w tabeli; fioletowy: nowy artykuł.
  const rowTint = service
    ? 'bg-stone-200 text-stone-700 dark:bg-stone-800 dark:text-stone-300'
    : row.known
      ? changes.length > 0
        ? ROW_TINTS.changed
        : ROW_TINTS.known
      : filled
        ? ROW_TINTS.new
        : null
  // Linia wstawienia przy przeciąganiu wiersza: cień w komórkach, bo obramowanie wiersza w tej tabeli nie działa.
  const dropLine =
    drop === 'before'
      ? 'shadow-[inset_0_2px_0_var(--color-primary)]'
      : drop === 'after'
        ? 'shadow-[inset_0_-2px_0_var(--color-primary)]'
        : null
  return (
    <tr aria-rowindex={index + 2} className={cn(dragged && 'opacity-45')}>
      <th
        scope="row"
        data-row-header={index}
        className={cn(
          'group/header sticky left-0 z-10 h-8 cursor-default border-r hover:z-20 border-b bg-muted px-1 text-center text-[0.86em] font-normal text-muted-foreground',
          rangeLeft >= 0 && SELECTED_HEADER,
          tooLong && 'bg-destructive font-medium text-white',
          dropLine,
        )}
      >
        {movable ? (
          <>
            <span className="group-hover/header:hidden">{index + 1}</span>
            <span
              data-row-handle={index}
              title="Przeciągnij, żeby przenieść wiersz (zaznaczone wiersze przenoszą się razem)"
              className="hidden h-full cursor-grab place-items-center group-hover/header:grid"
            >
              <GripVerticalIcon aria-hidden className="size-[1.15em]" />
            </span>
            <button
              type="button"
              tabIndex={-1}
              data-row-insert={index}
              aria-label="Wstaw wiersz poniżej"
              title="Wstaw wiersz poniżej (Ctrl + plus wstawia nad bieżącym)"
              className="absolute -bottom-2 left-1/2 hidden size-4 -translate-x-1/2 cursor-pointer place-items-center rounded-full bg-primary text-primary-foreground group-hover/header:grid"
            >
              <PlusIcon aria-hidden className="size-3" />
            </button>
          </>
        ) : (
          index + 1
        )}
      </th>
      {columns.map((column, c) => {
        const selected = c >= rangeLeft && c <= rangeRight
        const isActive = c === activeCol
        const nomenclature = column.kind === 'nomenclature'
        const list = nomenclature || column.kind === 'unit'
        // Zakres: warstwa z ramką na krawędziach; aktywna komórka bez przyciemnienia, jak w Excelu.
        const inRange = selected && !rangeSingle
        return (
          <td
            key={column.key}
            data-r={index}
            data-c={c}
            role="gridcell"
            aria-selected={selected}
            className={cn(
              'group/cell relative h-8 scroll-mt-(--scroll-top) scroll-ml-(--sticky-width) truncate border-r border-b px-2 whitespace-nowrap',
              column.align === 'right' && 'text-right',
              c === 0 && 'sticky left-(--sticky-left) z-[5] bg-card',
              rowTint,
              list && 'pr-6',
              list && isActive && !service && 'cursor-pointer',
              nomenclature && NOMENCLATURE_TINTS[state],
              changedColumns.has(column.key) && ROW_TINTS.changedCell,
              // Za długa nazwa: cały wiersz na czerwono, a komórka nazwy najmocniej.
              tooLong &&
                (column.key === 'name'
                  ? 'bg-red-500 font-medium text-white dark:bg-red-700'
                  : 'bg-red-200 text-red-950 dark:bg-red-900 dark:text-red-50'),
              badEan && column.key === 'ean' && 'bg-red-500 font-medium text-white dark:bg-red-700',
              badDeposit && column.key === 'deposit' && 'bg-red-500 font-medium text-white dark:bg-red-700',
              badVat && column.key === 'vat' && 'bg-red-500 font-medium text-white dark:bg-red-700',
              inRange && SELECTION_LAYER,
              inRange && !isActive && SELECTION_FILL,
              inRange && rangeTop && 'after:border-t-2',
              inRange && rangeBottom && 'after:border-b-2',
              inRange && c === rangeLeft && 'after:border-l-2',
              inRange && c === rangeRight && 'after:border-r-2',
              isActive && 'outline-2 -outline-offset-2 outline-blue-700 dark:outline-blue-400',
              dropLine,
            )}
          >
            <CellContent
              row={row}
              column={column.key}
              names={names}
              pricing={pricing}
              state={state}
              service={service}
              onShowArticle={onShowArticle}
              onLinkArticle={onLinkArticle}
              onSplitQuantity={onSplitQuantity}
              active={isActive}
            />
            {list && !service && isActive && (
              // Zaznaczona komórka z listą wygląda jak pole wyboru: kliknięcie w nie otwiera listę.
              <span
                aria-hidden
                className="pointer-events-none absolute inset-1 rounded-sm ring-1 ring-blue-700/70 dark:ring-blue-400/70"
              />
            )}
            {list && !service && (
              <ChevronDownIcon
                aria-hidden
                className={cn(
                  'absolute top-1/2 right-1.5 size-3.5 -translate-y-1/2',
                  isActive ? 'text-foreground' : 'text-muted-foreground/70',
                )}
              />
            )}
            {c === editorCol && editor}
          </td>
        )
      })}
    </tr>
  )
})

const quantityFormat = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 3,
})
const percentFormat = new Intl.NumberFormat('pl-PL', {
  maximumFractionDigits: 1,
})
const weightFormat = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
})

function Tag({ children, className, title }: { children: React.ReactNode; className?: string; title?: string }) {
  return (
    <span className={cn('ml-1.5 rounded px-1 py-px align-[0.08em] text-[0.78em] font-medium', className)} title={title}>
      {children}
    </span>
  )
}

type CellProps = {
  row: DataEntryRow
  column: ColumnKey
  names: Names
  pricing: Pricing
  state: string
  service: ServiceKind | null
  onShowArticle?: (row: DataEntryRow) => void
  onLinkArticle?: (row: DataEntryRow) => void
  onSplitQuantity?: (row: DataEntryRow) => void
  active: boolean
}

function CellContent({
  row,
  column,
  names,
  pricing,
  state,
  service,
  onShowArticle,
  onLinkArticle,
  onSplitQuantity,
  active,
}: CellProps) {
  switch (column) {
    case 'ean': {
      const error = eanError(row)
      return (
        // Kod skraca się wielokropkiem, żeby przy wąskiej kolumnie zostały widoczne ikony (błąd, przypięcie).
        <span className="flex min-w-0 items-center" title={error ?? row.ean}>
          {row.ean && row.eanFallback ? (
            <span
              className="min-w-0 truncate italic opacity-75"
              title={`Brak EAN na fakturze, kod z pola: ${row.eanFallback}`}
            >
              {row.ean}
            </span>
          ) : row.ean ? (
            <span className="min-w-0 truncate">{row.ean}</span>
          ) : (
            <span className="truncate text-muted-foreground">{service ? '' : 'brak EAN'}</span>
          )}
          {error && (
            <span className="ml-auto shrink-0 pl-1" title={error}>
              <TriangleAlertIcon aria-label={error} className="size-[1.1em]" />
            </span>
          )}
          {service && (
            <Tag className="bg-background text-muted-foreground ring-1 ring-border">{SERVICE_LABELS[service]}</Tag>
          )}
          {onLinkArticle && !service && row.ean && (row.link || !row.known) && (
            <button
              type="button"
              onClick={() => onLinkArticle(row)}
              title={
                row.link
                  ? `Przypięte u tego dostawcy: „${row.link.code}”. Kliknij, żeby zmienić albo odpiąć.`
                  : 'Przypisz do artykułu z kartoteki'
              }
              aria-label={row.link ? `Przypięte: ${row.link.code}` : `Przypisz do artykułu: ${row.ean}`}
              className={cn(
                '-my-1 -mr-1.5 ml-auto grid size-6 shrink-0 place-items-center rounded hover:bg-foreground/10 hover:text-foreground',
                row.link ? 'text-sky-700 dark:text-sky-300' : 'text-muted-foreground',
              )}
            >
              <Link2Icon className="size-[1.15em]" />
            </button>
          )}
        </span>
      )
    }
    case 'quantity':
      if (row.quantity === null) return null
      // Podział (worek 10 kg na 10 × 1 kg): przycisk przy najechaniu albo w zaznaczonej komórce, żeby nie zasłaniał liczb.
      return onSplitQuantity && canSplitQuantity(row) ? (
        <span className="flex items-center justify-end">
          <button
            type="button"
            onClick={() => onSplitQuantity(row)}
            title="Podziel ilość: opakowanie na sztuki, cena przeliczona (np. 10 × 10 kg na 100 × 1 kg)"
            aria-label={`Podziel ilość: ${row.name || row.ean}`}
            className={cn(
              '-my-1 -ml-1.5 mr-auto grid size-6 shrink-0 place-items-center rounded text-muted-foreground hover:bg-foreground/10 hover:text-foreground focus-visible:opacity-100',
              active ? 'opacity-100' : 'opacity-0 group-hover/cell:opacity-100',
            )}
          >
            <SplitIcon className="size-[1.05em]" />
          </button>
          {quantityFormat.format(row.quantity)}
        </span>
      ) : (
        quantityFormat.format(row.quantity)
      )
    case 'unitNet':
      return formatPrice(row.unitNet)
    case 'name': {
      const tooLong = row.name.length > NAME_LIMIT
      // Plakietki zostają widoczne na końcu komórki, a długa nazwa skraca się wielokropkiem (pełna w podpowiedzi).
      return (
        <span
          className="flex min-w-0 items-center"
          title={row.nameFromInvoice ? `Z faktury: ${row.invoiceName}` : row.name}
        >
          <span className={cn('truncate', row.nameFromInvoice && 'italic')}>{row.name}</span>
          {row.nameFromInvoice && !service && (
            <Tag className="shrink-0 bg-amber-400 text-amber-950 dark:bg-amber-600 dark:text-amber-50">do poprawy</Tag>
          )}
          {tooLong && (
            <Tag className="shrink-0 bg-destructive text-white">
              {row.name.length}/{NAME_LIMIT}
            </Tag>
          )}
          {missingDeposit(row) && (
            <Tag
              className="shrink-0 bg-sky-300 text-sky-950 dark:bg-sky-800 dark:text-sky-50"
              title="Artykuł z kaucją: ustaw kaucję przy zakładaniu w Mercalys"
            >
              kaucja
            </Tag>
          )}
          {onShowArticle && row.known && row.itm8 && (
            <button
              type="button"
              onClick={() => onShowArticle(row)}
              title="Sprzedaż i dostawy artykułu"
              aria-label={`Sprzedaż i dostawy: ${row.name}`}
              className="-my-1 -mr-1.5 ml-auto grid size-6 shrink-0 place-items-center rounded text-muted-foreground hover:bg-foreground/10 hover:text-foreground"
            >
              <ChartColumnIcon className="size-[1.15em]" />
            </button>
          )}
        </span>
      )
    }
    case 'brand':
      return <span title={row.brand}>{row.brand}</span>
    case 'value':
      return formatMoney(netValue(row))
    case 'vat': {
      if (row.vatPercent === null) return null
      const text = `${percentFormat.format(row.vatPercent)}%`
      if (!vatMismatch(row)) return text
      const message = `Błędny VAT w kartotece: ${percentFormat.format(row.catalogVat ?? 0)}%, w dokumencie ${text}. Do pliku idzie stawka z dokumentu.`
      return (
        <span className="flex min-w-0 items-center" title={message}>
          <span className="truncate">{text}</span>
          <TriangleAlertIcon aria-label={message} className="ml-auto size-[1.1em] shrink-0 pl-0.5" />
        </span>
      )
    }
    case 'salePrice': {
      // Trwająca promocja z kasy (pliki TRACEART): kartoteka podaje wtedy cenę promocyjną jako cenę sprzedaży.
      const promo = activePromoPrice(row)
      if (promo !== null && row.salePrice === promo) {
        return (
          <span
            className="font-bold text-orange-600 dark:text-orange-400"
            title={`Cena promocyjna na kasie do ${formatDocumentDate(row.promoTo ?? '')}`}
          >
            {formatMoney(row.salePrice)}
          </span>
        )
      }
      // Artykuł z CENA_CAD (cadencier): cena z kartoteki niebieska i pogrubiona, dopóki nie zmieniona w tabeli.
      const cadencier =
        row.known &&
        row.cadencier &&
        row.salePrice !== null &&
        (!row.catalog || row.catalog.salePrice === row.salePrice)
      return cadencier ? (
        <span className="font-bold text-blue-700 dark:text-blue-300" title="Artykuł z ceną cadencier (CENA_CAD)">
          {formatMoney(row.salePrice)}
        </span>
      ) : (
        formatMoney(row.salePrice)
      )
    }
    case 'margin': {
      const margin = marginPercent(row)
      if (margin === null) return null
      // Poniżej marży wymaganej na nomenklaturze: mocna czerwień, wymagana w podpowiedzi.
      const required = requiredMargin(row, pricing)
      if (!service && belowRequiredMargin(row, pricing)) {
        return (
          <span
            className="rounded bg-red-500 px-1 font-medium text-white dark:bg-red-700"
            title={`Poniżej marży wymaganej ${percentFormat.format(required ?? 0)}% - cena sugerowana obok`}
          >
            {percentFormat.format(margin)}%
          </span>
        )
      }
      const value = (
        <span
          className={cn(margin < 0 && 'font-medium text-destructive')}
          title={required !== null ? `Marża wymagana ${percentFormat.format(required)}%` : undefined}
        >
          {percentFormat.format(margin)}%
        </span>
      )
      if (!canSellSmaller(row)) return value
      // Owoce i warzywa z marżą od 45%: można sprzedawać w mniejszych ilościach; klik otwiera podział ilości.
      const hint = `Marża od ${SMALLER_QUANTITIES_MARGIN}% na owocach i warzywach: artykuł może być sprzedawany na mniejsze ilości`
      return (
        <span className="flex items-center justify-end gap-1">
          {onSplitQuantity && canSplitQuantity(row) ? (
            <button
              type="button"
              onClick={() => onSplitQuantity(row)}
              title={`${hint}. Kliknij, żeby podzielić ilość.`}
              aria-label={hint}
              className="-my-1 grid h-5 shrink-0 place-items-center rounded bg-emerald-300 px-1 text-emerald-950 hover:bg-emerald-400 dark:bg-emerald-800 dark:text-emerald-50 dark:hover:bg-emerald-700"
            >
              <SplitIcon className="size-[1em]" />
            </button>
          ) : (
            <span
              title={hint}
              className="grid h-5 shrink-0 place-items-center rounded bg-emerald-300 px-1 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50"
            >
              <SplitIcon aria-label={hint} className="size-[1em]" />
            </span>
          )}
          {value}
        </span>
      )
    }
    case 'suggestedPrice': {
      if (service) return null
      const price = suggestedPrice(row, pricing)
      if (price === null) return null
      if (supplierPriceOf(row, pricing) !== null) {
        return (
          <span
            className="font-bold text-cyan-800 dark:text-cyan-300"
            title="Stała cena dostawcy z Ustawień sklepu: wszystkie jego artykuły sprzedajemy za tę cenę"
          >
            {formatMoney(price)}
          </span>
        )
      }
      if (cadencierPrice(row) !== null) {
        return (
          <span
            className="font-bold text-blue-700 dark:text-blue-300"
            title="CENA_CAD z kartoteki - cena narzucona przez centralę"
          >
            {formatMoney(price)}
          </span>
        )
      }
      const required = requiredMargin(row, pricing)
      return (
        <span
          className={cn(belowRequiredMargin(row, pricing) && 'font-semibold')}
          title={`Cena brutto dla marży wymaganej ${percentFormat.format(required ?? 0)}%`}
        >
          {formatMoney(price)}
        </span>
      )
    }
    case 'region':
    case 'family':
    case 'subfamily': {
      const code = levelCode(row, column)
      if (code === null) {
        if (column !== 'subfamily') return null
        if (service) return null
        if (row.nomenclaturePrefix)
          return (
            <span className="text-muted-foreground" title="Wybierz podrodzinę">
              wybierz
            </span>
          )
        if (state === 'TO_CHECK') {
          return (
            <span className="font-medium" title={`Wybierz z ${row.suggestion?.proposals.length ?? 0} propozycji`}>
              wybierz ({row.suggestion?.proposals.length ?? 0})
            </span>
          )
        }
        return row.known ? null : <span className="text-muted-foreground">—</span>
      }
      // Tylko własne cyfry poziomu (rejon 66, rodzina 10, podrodzina 17); pełny kod i nazwa w podpowiedzi.
      return <span title={`${code} ${names.get(code) ?? ''}`}>{code.slice(-2)}</span>
    }
    case 'unit':
      return row.unit
    case 'itm8': {
      const itm8 = row.base?.itm8 ?? row.itm8
      if (!itm8) return null
      // Baza wysyła część towaru pod własnym ITM8: kartotekowy w podpowiedzi.
      const catalog = row.itm8?.replace(/^0+/, '')
      const other = catalog && catalog !== itm8.replace(/^0+/, '') ? catalog : null
      return (
        <span title={other ? `W kartotece ITM8 ${other}` : itm8} className={cn(other && 'italic')}>
          {itm8}
        </span>
      )
    }
    case 'deposit': {
      const error = depositError(row)
      const notice = depositNotice(row)
      const label = depositLabel(row.base?.deposit)
      if (error) {
        return (
          <span className="flex min-w-0 items-center" title={error}>
            <span className="truncate">{label ?? 'brak'}</span>
            <TriangleAlertIcon aria-label={error} className="ml-auto size-[1.1em] shrink-0 pl-0.5" />
          </span>
        )
      }
      if (notice) {
        return (
          <span
            className="rounded bg-amber-300 px-1 font-medium text-amber-950 dark:bg-amber-700 dark:text-amber-50"
            title={notice}
          >
            {label}
          </span>
        )
      }
      return label
    }
    case 'stock':
      if (row.stock == null) return null
      return <span className={cn(row.stock < 0 && 'font-medium text-destructive')}>{formatQuantity(row.stock)}</span>
    case 'rotation':
      return row.rotation == null ? null : formatQuantity(row.rotation)
    case 'netContent': {
      const mismatch = measureMismatch(row) || (row.suggestion?.unitMismatch && !row.edited.netContent)
      return (
        <span
          className={cn(mismatch && 'rounded bg-amber-400 px-1 text-amber-950 dark:bg-amber-600 dark:text-amber-50')}
          title={mismatch ? 'Waga nie zgadza się z miarą w nazwie - sprawdź' : undefined}
        >
          {weightFormat.format(row.netContent)}
        </span>
      )
    }
  }
}
