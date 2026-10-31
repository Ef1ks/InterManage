import type { NomenclatureItem, SaleUnit } from '@/lib/types'

import {
  type DataEntryRow,
  marginPercent,
  netValue,
  normalizeBrand,
  normalizeName,
  withCatalogProduct,
  withMeasure,
} from './rows'

export type ColumnKey =
  | 'ean'
  | 'quantity'
  | 'unitNet'
  | 'name'
  | 'brand'
  | 'value'
  | 'vat'
  | 'salePrice'
  | 'margin'
  | 'region'
  | 'family'
  | 'subfamily'
  | 'unit'
  | 'netContent'

/** text/number: edycja w komórce; nomenclature/unit: lista do wyboru; computed: tylko do odczytu. */
export type ColumnKind = 'text' | 'number' | 'nomenclature' | 'unit' | 'computed'

export type Column = { key: ColumnKey; header: string; width: string; align?: 'right'; kind: ColumnKind }

/** Kolumny w kolejności z pliku Excel. Szerokości w rem (klasy Tailwinda), bo interfejs rośnie z rem. */
export const COLUMNS: Column[] = [
  { key: 'ean', header: 'EAN', width: 'w-44', kind: 'text' },
  { key: 'quantity', header: 'Ilość', width: 'w-18', align: 'right', kind: 'number' },
  { key: 'unitNet', header: 'Cena netto', width: 'w-24', align: 'right', kind: 'number' },
  { key: 'name', header: 'Nazwa', width: 'w-76', kind: 'text' },
  { key: 'brand', header: 'Marka', width: 'w-32', kind: 'text' },
  { key: 'value', header: 'Wartość netto', width: 'w-28', align: 'right', kind: 'computed' },
  { key: 'vat', header: 'VAT', width: 'w-16', align: 'right', kind: 'number' },
  { key: 'salePrice', header: 'Cena sprzedaży', width: 'w-28', align: 'right', kind: 'number' },
  { key: 'margin', header: 'Marża', width: 'w-20', align: 'right', kind: 'computed' },
  { key: 'region', header: 'Rejon', width: 'w-44', kind: 'nomenclature' },
  { key: 'family', header: 'Rodzina', width: 'w-48', kind: 'nomenclature' },
  { key: 'subfamily', header: 'Podrodzina', width: 'w-60', kind: 'nomenclature' },
  { key: 'unit', header: 'Rodzaj', width: 'w-20', kind: 'unit' },
  { key: 'netContent', header: 'Waga', width: 'w-20', align: 'right', kind: 'number' },
]

export const UNITS: SaleUnit[] = ['kg', 'L', 'szt.']

/** Kod nomenklatury kolumny (rejon 2, rodzina 4, podrodzina 6 cyfr) dla wiersza. */
export function levelCode(row: DataEntryRow, key: ColumnKey) {
  if (row.subfamilyCode === null) return null
  return key === 'region' ? row.subfamilyCode.slice(0, 2) : key === 'family' ? row.subfamilyCode.slice(0, 4) : row.subfamilyCode
}

export type Names = Map<string, string>

export function nomenclatureNames(items: NomenclatureItem[] | undefined): Names {
  return new Map((items ?? []).map((item) => [item.code, item.name]))
}

const plainNumber = (value: number | null, digits = 4) =>
  value === null
    ? ''
    : new Intl.NumberFormat('pl-PL', { maximumFractionDigits: digits, useGrouping: false }).format(value)

/** Wartość komórki jako tekst: do kopiowania (TSV dla Excela) i jako start edycji. */
export function cellText(row: DataEntryRow, key: ColumnKey, names: Names): string {
  switch (key) {
    case 'ean':
      return row.ean
    case 'quantity':
      return plainNumber(row.quantity, 3)
    case 'unitNet':
      return plainNumber(row.unitNet)
    case 'name':
      return row.name
    case 'brand':
      return row.brand
    case 'value':
      return plainNumber(netValue(row), 2)
    case 'vat':
      return plainNumber(row.vatPercent, 2)
    case 'salePrice':
      return plainNumber(row.salePrice, 2)
    case 'margin': {
      const margin = marginPercent(row)
      return plainNumber(margin === null ? null : Math.round(margin * 10) / 10, 1)
    }
    case 'region':
    case 'family':
    case 'subfamily': {
      const code = levelCode(row, key)
      return code === null ? '' : [code, names.get(code)].filter(Boolean).join(' ')
    }
    case 'unit':
      return row.unit
    case 'netContent':
      return new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 3, maximumFractionDigits: 3, useGrouping: false })
        .format(row.netContent)
  }
}

/** Liczba wpisana albo wklejona: przecinek lub kropka, spacje, "zł" i "%" pomijane; pusta = null, błędna = undefined. */
export function parseNumber(text: string): number | null | undefined {
  const normalized = text.replace(/[\s ]/g, '').replace(/zł|%/gi, '').replace(',', '.')
  if (normalized === '') return null
  return /^-?\d+(\.\d+)?$/.test(normalized) ? Number(normalized) : undefined
}

export function parseUnit(text: string): SaleUnit | undefined {
  const value = text.trim().toLowerCase()
  if (value === 'kg' || value === 'a.wa') return 'kg'
  if (value === 'l' || value === 'litr' || value === 'litry') return 'L'
  if (value.startsWith('szt')) return 'szt.'
  return undefined
}

export type CellResult = { row: DataEntryRow; eanChanged?: boolean } | { error: string }

/** Wpisany albo wklejony tekst w komórce; zmiana rodzaju albo wagi przepisuje miarę w nazwie nowego artykułu. */
export function applyText(row: DataEntryRow, key: ColumnKey, text: string, names: Names): CellResult {
  const trimmed = text.trim()
  switch (key) {
    case 'ean': {
      const digits = trimmed.replace(/[\s-]/g, '')
      if (digits !== '' && !/^\d{1,14}$/.test(digits)) return { error: 'EAN to do 14 cyfr' }
      if (digits === row.ean) return { row }
      return { row: digits === '' ? withCatalogProduct({ ...row, ean: '' }, null) : { ...row, ean: digits }, eanChanged: true }
    }
    case 'quantity':
    case 'unitNet':
    case 'vat':
    case 'salePrice': {
      const value = parseNumber(trimmed)
      if (value === undefined) return { error: 'Wpisz liczbę' }
      if (value !== null && value < 0 && key !== 'quantity') return { error: 'Wartość nie może być ujemna' }
      if (key === 'vat' && value !== null && value > 100) return { error: 'Stawka VAT to 0-100%' }
      const field = key === 'vat' ? 'vatPercent' : key
      return { row: { ...row, [field]: value } }
    }
    case 'netContent': {
      const value = parseNumber(trimmed)
      if (value === undefined || value === null || value <= 0) return { error: 'Waga musi być większa od zera' }
      if (row.unit === 'szt.' && value !== 1) return { error: 'Przy sztukach waga to zawsze 1' }
      const netContent = Math.round(value * 1000) / 1000
      return { row: remeasured({ ...row, netContent, edited: { ...row.edited, netContent: true } }) }
    }
    case 'unit': {
      const unit = parseUnit(trimmed)
      if (!unit) return { error: 'Rodzaj sprzedaży: kg, L albo szt.' }
      return { row: withUnit(row, unit) }
    }
    case 'name':
      return {
        row: { ...row, name: normalizeName(trimmed), nameFromInvoice: false, edited: { ...row.edited, name: true } },
      }
    case 'brand':
      return { row: { ...row, brand: normalizeBrand(trimmed), edited: { ...row.edited, brand: true } } }
    case 'region':
    case 'family':
    case 'subfamily': {
      if (trimmed === '') return { row: withSubfamily(row, null) }
      const code = /^\d{6}/.exec(trimmed.replace(/\s/g, ''))?.[0]
      if (!code || !names.has(code)) return { error: 'Nieznana podrodzina: wpisz 6-cyfrowy kod' }
      return { row: withSubfamily(row, code) }
    }
    case 'value':
    case 'margin':
      return { row }
  }
}

/** Podrodzina wybrana przez człowieka: propozycja AI zostaje zatwierdzona. */
export function withSubfamily(row: DataEntryRow, code: string | null): DataEntryRow {
  return {
    ...row,
    subfamilyCode: code,
    edited: { ...row.edited, subfamilyCode: true },
    suggestion: row.suggestion ? { ...row.suggestion, confirmed: code !== null } : null,
  }
}

export function withUnit(row: DataEntryRow, unit: SaleUnit): DataEntryRow {
  if (unit === row.unit) return row
  const netContent = unit === 'szt.' ? 1 : row.netContent
  return remeasured({ ...row, unit, netContent, edited: { ...row.edited, unit: true, netContent: true } })
}

function remeasured(row: DataEntryRow): DataEntryRow {
  return row.known || row.name === '' ? row : { ...row, name: withMeasure(row.name, row.unit, row.netContent) }
}

/** Czy komórkę da się wyczyścić klawiszem Delete (rodzaju, wagi i wartości liczonych nie). */
export function clearable(key: ColumnKey) {
  return key !== 'unit' && key !== 'netContent' && key !== 'value' && key !== 'margin'
}

// ---------- schowek: TSV jak w Excelu

export function parseTsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"'
        index++
      } else if (char === '"') {
        quoted = false
      } else {
        cell += char
      }
    } else if (char === '"' && cell === '') {
      quoted = true
    } else if (char === '\t') {
      row.push(cell)
      cell = ''
    } else if (char === '\n') {
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else if (char !== '\r') {
      cell += char
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }
  return rows
}

export function toTsv(cells: string[][]) {
  const quote = (value: string) => (/[\t\n"]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value)
  return cells.map((row) => row.map(quote).join('\t')).join('\r\n')
}
