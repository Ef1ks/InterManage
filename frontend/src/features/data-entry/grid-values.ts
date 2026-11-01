import type { NomenclatureItem, PriceRoundingRule, SaleUnit } from '@/lib/types'

import {
  type DataEntryRow,
  type Pricing,
  marginPercent,
  netValue,
  normalizeBrand,
  normalizeName,
  suggestedPrice,
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
  | 'suggestedPrice'
  | 'region'
  | 'family'
  | 'subfamily'
  | 'unit'
  | 'netContent'
  | 'stock'
  | 'rotation'

/** text/number: edycja w komórce; nomenclature/unit: lista do wyboru; computed: tylko do odczytu. */
export type ColumnKind = 'text' | 'number' | 'nomenclature' | 'unit' | 'computed'

export type Column = {
  key: ColumnKey
  header: string
  /** Pełna nazwa kolumny, gdy nagłówek jest skrócony. */
  title?: string
  /** Szerokość w em czcionki tabeli: tabela zmniejsza czcionkę, żeby zmieścić się na szerokość ekranu. */
  width: number
  align?: 'right'
  kind: ColumnKind
}

/** Kolumny w kolejności z pliku Excel. Nazwa mieści 30 znaków, nomenklatura skraca się (pełna w podpowiedzi). */
export const COLUMNS: Column[] = [
  { key: 'ean', header: 'EAN', width: 9, kind: 'text' },
  { key: 'quantity', header: 'Ilość', width: 4.5, align: 'right', kind: 'number' },
  { key: 'unitNet', header: 'Cena netto', width: 6, align: 'right', kind: 'number' },
  { key: 'name', header: 'Nazwa', width: 21.5, kind: 'text' },
  { key: 'brand', header: 'Marka', width: 8, kind: 'text' },
  { key: 'value', header: 'Wartość', title: 'Wartość netto', width: 6, align: 'right', kind: 'computed' },
  { key: 'vat', header: 'VAT', width: 3.5, align: 'right', kind: 'number' },
  { key: 'salePrice', header: 'Cena sprz.', title: 'Cena sprzedaży brutto', width: 6, align: 'right', kind: 'number' },
  { key: 'margin', header: 'Marża', width: 6.5, align: 'right', kind: 'computed' },
  {
    key: 'suggestedPrice',
    header: 'Cena sug.',
    title: 'Cena sugerowana: cena sprzedaży brutto, przy której artykuł osiąga marżę wymaganą na nomenklaturze',
    width: 6,
    align: 'right',
    kind: 'computed',
  },
  { key: 'region', header: 'Rejon', width: 8, kind: 'nomenclature' },
  { key: 'family', header: 'Rodzina', width: 9, kind: 'nomenclature' },
  { key: 'subfamily', header: 'Podrodzina', width: 12, kind: 'nomenclature' },
  { key: 'unit', header: 'Rodzaj', title: 'Rodzaj sprzedaży', width: 4.5, kind: 'unit' },
  { key: 'netContent', header: 'Waga', width: 5, align: 'right', kind: 'number' },
  // Na końcu, żeby wklejanie kolumn z Excela trafiało tam, gdzie wcześniej.
  { key: 'stock', header: 'Stan', title: 'Stan magazynowy z ostatniego raportu kartoteki', width: 5, align: 'right', kind: 'computed' },
  { key: 'rotation', header: 'Rotacja', title: 'Rotacja z ostatniego raportu kartoteki', width: 5, align: 'right', kind: 'computed' },
]

export const UNITS: SaleUnit[] = ['kg', 'L', 'szt.']

export type NomenclatureKey = 'region' | 'family' | 'subfamily'

/** Długość kodu na poziomie: rejon 2, rodzina 4, podrodzina 6 cyfr. */
export const LEVEL_LENGTH: Record<NomenclatureKey, number> = { region: 2, family: 4, subfamily: 6 }

/** Kod nomenklatury kolumny dla wiersza: z podrodziny albo z wybranego wcześniej rejonu / rodziny. */
export function levelCode(row: DataEntryRow, key: ColumnKey) {
  const length = LEVEL_LENGTH[key as NomenclatureKey]
  const code = row.subfamilyCode ?? row.nomenclaturePrefix ?? null
  return code !== null && code.length >= length ? code.slice(0, length) : null
}

/** Kod wyższego poziomu, którego dzieci pokazuje lista kolumny ({@code null} = cała nomenklatura). */
export function levelScope(row: DataEntryRow, key: NomenclatureKey) {
  return key === 'region' ? null : levelCode(row, key === 'family' ? 'region' : 'family')
}

export type Names = Map<string, string>

export function nomenclatureNames(items: NomenclatureItem[] | undefined): Names {
  return new Map((items ?? []).map((item) => [item.code, item.name]))
}

export function pricingOf(items: NomenclatureItem[] | undefined, rounding: PriceRoundingRule[] | undefined): Pricing {
  return {
    required: new Map(
      (items ?? []).flatMap((item) => (item.requiredMargin === null ? [] : [[item.code, item.requiredMargin] as const])),
    ),
    rounding: rounding ?? [],
  }
}

const plainNumber = (value: number | null, digits = 4) =>
  value === null
    ? ''
    : new Intl.NumberFormat('pl-PL', { maximumFractionDigits: digits, useGrouping: false }).format(value)

/** Wartość komórki jako tekst: do kopiowania (TSV dla Excela) i jako start edycji. */
export function cellText(row: DataEntryRow, key: ColumnKey, names: Names, pricing: Pricing): string {
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
    case 'suggestedPrice':
      return plainNumber(suggestedPrice(row, pricing), 2)
    case 'stock':
      return plainNumber(row.stock ?? null, 3)
    case 'rotation':
      return plainNumber(row.rotation ?? null, 3)
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
      // Kod spoza EAN-u z faktury (indeks, nazwa) zatwierdzony bez zmian zostaje, choć nie jest liczbą.
      if (trimmed === row.ean) return { row }
      const digits = trimmed.replace(/[\s-]/g, '')
      if (digits !== '' && !/^\d{1,14}$/.test(digits)) return { error: 'EAN to do 14 cyfr' }
      if (digits === row.ean) return { row }
      return {
        row: digits === '' ? withCatalogProduct({ ...row, ean: '', eanFallback: null }, null) : { ...row, ean: digits, eanFallback: null },
        eanChanged: true,
      }
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
      const length = LEVEL_LENGTH[key]
      const digits = /^\d+/.exec(trimmed.replace(/\s/g, ''))?.[0]
      const code = digits && digits.length >= length ? digits.slice(0, length) : undefined
      if (!code || !names.has(code)) {
        return { error: `Nieznany kod: wpisz ${length} cyfry ${key === 'region' ? 'rejonu' : key === 'family' ? 'rodziny' : 'podrodziny'}` }
      }
      return { row: withNomenclature(row, code) }
    }
    case 'value':
    case 'margin':
    case 'suggestedPrice':
    case 'stock':
    case 'rotation':
      return { row }
  }
}

/** Podrodzina wybrana przez człowieka: propozycja AI zostaje zatwierdzona. */
export function withSubfamily(row: DataEntryRow, code: string | null): DataEntryRow {
  return {
    ...row,
    subfamilyCode: code,
    nomenclaturePrefix: null,
    edited: { ...row.edited, subfamilyCode: true },
    suggestion: row.suggestion ? { ...row.suggestion, confirmed: code !== null } : null,
  }
}

/**
 * Kod wybrany w kolumnie nomenklatury. Podrodzina jest ostateczna; rejon albo rodzina zgodne z obecną nomenklaturą
 * niczego nie zmieniają, a inne czyszczą podrodzinę i zawężają kolejną listę.
 */
export function withNomenclature(row: DataEntryRow, code: string): DataEntryRow {
  if (code.length === 6) return withSubfamily(row, code)
  if ((row.subfamilyCode ?? row.nomenclaturePrefix ?? '').startsWith(code)) return row
  return { ...withSubfamily(row, null), nomenclaturePrefix: code }
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
  return COLUMNS.find((column) => column.key === key)?.kind !== 'computed' && key !== 'unit' && key !== 'netContent'
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
