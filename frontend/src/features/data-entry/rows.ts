import type {
  DataEntryCatalogProduct,
  DataEntryLine,
  SaleUnit,
  Suggestion,
  SuggestionProposal,
  SuggestionStatus,
} from '@/lib/types'

/** Pola, które AI może uzupełnić; wpisane ręcznie zostają nietknięte przy kolejnym „Uzupełnij z AI”. */
export type AiField = 'name' | 'brand' | 'subfamilyCode' | 'unit' | 'netContent'

/** Propozycja AI przy wierszu; {@code confirmed}: człowiek ją zatwierdził albo wybrał podrodzinę z propozycji. */
export type RowSuggestion = {
  id: string
  status: SuggestionStatus
  proposals: SuggestionProposal[]
  confidence: number | null
  unitMismatch: boolean
  confirmed: boolean
  /** Model odpowiedział (bez tego propozycje są tylko z kartoteki i warto zapytać ponownie). */
  modelAnswered: boolean
}

/** Wiersz tabeli wprowadzania danych (jedna pozycja faktury albo wiersz dopisany ręcznie). */
export type DataEntryRow = {
  id: string
  ean: string
  quantity: number | null
  unitNet: number | null
  name: string
  brand: string
  vatPercent: number | null
  salePrice: number | null
  subfamilyCode: string | null
  unit: SaleUnit
  netContent: number
  /** Nazwa i jednostka z faktury (podpowiedź przy wierszu i dane dla AI). */
  invoiceName: string | null
  invoiceUnit: string | null
  /** Artykuł z kartoteki sklepu; {@code false} = nowy. */
  known: boolean
  itm8: string | null
  /** Nazwa jest jeszcze tą z faktury: do poprawy (AI albo ręcznie). */
  nameFromInvoice: boolean
  edited: Partial<Record<AiField, true>>
  suggestion: RowSuggestion | null
}

export const NAME_LIMIT = 30

export function newRowId() {
  return crypto.randomUUID()
}

export function emptyRow(): DataEntryRow {
  return {
    id: newRowId(),
    ean: '',
    quantity: null,
    unitNet: null,
    name: '',
    brand: '',
    vatPercent: null,
    salePrice: null,
    subfamilyCode: null,
    unit: 'szt.',
    netContent: 1,
    invoiceName: null,
    invoiceUnit: null,
    known: false,
    itm8: null,
    nameFromInvoice: false,
    edited: {},
    suggestion: null,
  }
}

/** Pozycja faktury jako wiersz: znany artykuł z danymi z kartoteki, nowy z tym, co wiadomo z faktury. */
export function rowFromLine(line: DataEntryLine): DataEntryRow {
  const product = line.product
  return {
    ...emptyRow(),
    ean: line.ean ?? '',
    quantity: line.quantity,
    unitNet: line.unitNet,
    name: product?.name ?? line.invoiceName ?? '',
    brand: product?.brand ?? '',
    vatPercent: line.vatPercent,
    salePrice: product?.salePrice ?? null,
    subfamilyCode: product?.subfamilyCode ?? null,
    unit: line.unit,
    netContent: line.netContent,
    invoiceName: line.invoiceName,
    invoiceUnit: line.invoiceUnit,
    known: product !== null,
    itm8: product?.itm8 ?? null,
    nameFromInvoice: product === null && Boolean(line.invoiceName),
  }
}

/** Wpisany EAN znaleziony w kartotece: dane artykułu zastępują wiersz, ilość i ceny z faktury zostają. */
export function withCatalogProduct(row: DataEntryRow, product: DataEntryCatalogProduct | null): DataEntryRow {
  if (!product) {
    return { ...row, known: false, itm8: null }
  }
  return {
    ...row,
    known: true,
    itm8: product.itm8,
    name: product.name ?? row.name,
    brand: product.brand ?? '',
    salePrice: product.salePrice,
    subfamilyCode: product.subfamilyCode,
    unit: product.unit,
    netContent: product.netContent,
    nameFromInvoice: false,
    suggestion: null,
    edited: {},
  }
}

/** Czy wiersz potrzebuje AI: nowy artykuł bez nomenklatury, z nazwą z faktury albo bez marki. */
export function needsAi(row: DataEntryRow) {
  // Wiersz z odpowiedzią modelu ma już swoje propozycje - ponowne pytanie dałoby to samo.
  if (row.known || (!row.name && !row.invoiceName) || row.suggestion?.modelAnswered) return false
  return (
    (row.subfamilyCode === null && !row.edited.subfamilyCode) ||
    (row.nameFromInvoice && !row.edited.name) ||
    (!row.brand && !row.edited.brand)
  )
}

/** Propozycja AI wpisana w wiersz; pól poprawionych ręcznie nie rusza. */
export function withSuggestion(row: DataEntryRow, suggestion: Suggestion): DataEntryRow {
  const next: DataEntryRow = {
    ...row,
    suggestion: {
      id: suggestion.id,
      status: suggestion.status,
      proposals: suggestion.proposals,
      confidence: suggestion.confidence,
      unitMismatch: suggestion.unitMismatch,
      confirmed: false,
      modelAnswered: suggestion.name !== null,
    },
  }
  if (!row.edited.name && suggestion.name) {
    next.name = suggestion.name
    next.nameFromInvoice = false
  }
  if (!row.edited.brand && suggestion.brand) next.brand = suggestion.brand
  if (!row.edited.unit && !row.edited.netContent) {
    next.unit = suggestion.unit
    next.netContent = suggestion.netContent
  }
  if (!row.edited.subfamilyCode) next.subfamilyCode = suggestion.subfamilyCode
  return next
}

// ---------- miara w nazwie

const PLAIN_FROM = 'ĄĆĘŁŃÓŚŹŻ'
const PLAIN_TO = 'ACELNOSZZ'

/** Wielkie litery bez polskich znaków (jak nazwy i marki w kartotece). */
export function plain(text: string) {
  return text
    .toUpperCase()
    .split('')
    .map((char) => {
      const index = PLAIN_FROM.indexOf(char)
      return index >= 0 ? PLAIN_TO[index] : char
    })
    .join('')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
}

const decimal = (value: number) =>
  new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3, useGrouping: false }).format(value)

/** Miara do nazwy: 250G, 1,5KG, 330ML, 1,5L; przy sztukach pusta. */
export function measureLabel(unit: SaleUnit, netContent: number) {
  if (unit === 'szt.' || !(netContent > 0)) return ''
  const [small, large] = unit === 'kg' ? ['G', 'KG'] : ['ML', 'L']
  if (netContent < 1) return `${Math.max(1, Math.round(netContent * 1000))}${small}`
  return `${decimal(netContent)}${large}`
}

const TRAILING_MEASURE = /\s*(?:\d{1,3}\s*X\s*)?\d+(?:[.,]\d+)?\s*(?:KG|GR|G|ML|LTR|CL|L)\s*$/

/** Nazwa z nową miarą na końcu (stara miara z końca nazwy znika). */
export function withMeasure(name: string, unit: SaleUnit, netContent: number) {
  const base = name.replace(TRAILING_MEASURE, '').trim()
  const label = measureLabel(unit, netContent)
  return [base, label].filter(Boolean).join(' ')
}

/** Miara z końca nazwy jako [rodzaj, waga]; {@code null}, gdy nazwa jej nie ma. */
function trailingMeasure(name: string): [SaleUnit, number] | null {
  const match = /(?:(\d{1,3})\s*X\s*)?(\d+(?:[.,]\d+)?)\s*(KG|GR|G|ML|LTR|CL|L)\s*$/.exec(name)
  if (!match) return null
  const count = match[1] ? Number(match[1]) : 1
  const value = Number(match[2].replace(',', '.'))
  const unit = match[3]
  const content = unit === 'G' || unit === 'GR' || unit === 'ML' ? value / 1000 : unit === 'CL' ? value / 100 : value
  return [unit === 'KG' || unit === 'G' || unit === 'GR' ? 'kg' : 'L', content * count]
}

/** Miara na końcu nazwy nie zgadza się z rodzajem sprzedaży i wagą. */
export function measureMismatch(row: DataEntryRow) {
  // Kartoteka i surowa nazwa z faktury mają miarę w różnych miejscach - sprawdzamy tylko nazwy nowych artykułów.
  if (row.known || row.nameFromInvoice || row.name === '') return false
  const found = trailingMeasure(row.name)
  if (!found) return row.unit !== 'szt.'
  return found[0] !== row.unit || Math.abs(found[1] - row.netContent) > 0.0005
}

/** Nazwa po edycji: wielkie litery bez polskich znaków, bez kropek (ułamek z przecinkiem). */
export function normalizeName(text: string) {
  return plain(text)
    .replace(/(\d)\.(\d)/g, '$1,$2')
    .replace(/\./g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function normalizeBrand(text: string) {
  return plain(text).replace(/\s+/g, ' ').trim()
}

// ---------- marża

const PIECE_UNITS = new Set(['szt', 'szt.', 'sztuka', 'sztuki', 'sz'])
const KG_UNITS = new Set(['kg', 'kg.', 'kilogram'])

/** Cena zakupu netto sprzedawanej sztuki; inna jednostka na fakturze (karton, opakowanie) = {@code null}. */
export function purchasePerPiece(row: DataEntryRow) {
  if (row.unitNet === null) return null
  const unit = (row.invoiceUnit ?? 'szt.').trim().toLowerCase()
  if (PIECE_UNITS.has(unit)) return row.unitNet
  if (KG_UNITS.has(unit)) return row.unit === 'kg' ? row.unitNet * row.netContent : row.unitNet
  return null
}

/** Jednostka na fakturze, przy której marży nie liczymy (np. „op.”); {@code null}, gdy jest w porządku. */
export function foreignInvoiceUnit(row: DataEntryRow) {
  if (row.unitNet === null || !row.invoiceUnit) return null
  return purchasePerPiece(row) === null ? row.invoiceUnit : null
}

/** Marża % od sprzedaży netto przy obecnej cenie sprzedaży (jak w raportach sprzedaży). */
export function marginPercent(row: DataEntryRow) {
  const purchase = purchasePerPiece(row)
  if (purchase === null || row.salePrice === null || row.salePrice <= 0 || row.vatPercent === null) return null
  const saleNet = row.salePrice / (1 + row.vatPercent / 100)
  return ((saleNet - purchase) / saleNet) * 100
}

export function netValue(row: DataEntryRow) {
  return row.quantity !== null && row.unitNet !== null ? Math.round(row.quantity * row.unitNet * 100) / 100 : null
}

// ---------- walidacja przed eksportem

export type RowProblem = 'name-too-long' | 'no-subfamily'

export function rowProblems(row: DataEntryRow): RowProblem[] {
  const problems: RowProblem[] = []
  if (row.name.length > NAME_LIMIT) problems.push('name-too-long')
  if (!row.known && row.subfamilyCode === null && (row.ean !== '' || row.name !== '')) problems.push('no-subfamily')
  return problems
}

/** Status nomenklatury do koloru komórki: z kartoteki, propozycja AI albo wpisana ręcznie. */
export function nomenclatureState(row: DataEntryRow): 'known' | 'manual' | 'confirmed' | SuggestionStatus | 'empty' {
  if (row.known) return 'known'
  if (row.suggestion && !row.suggestion.confirmed && !row.edited.subfamilyCode) return row.suggestion.status
  if (row.subfamilyCode === null) return 'empty'
  return row.suggestion?.confirmed ? 'confirmed' : 'manual'
}
