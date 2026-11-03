import { todayIso } from '@/lib/dates'
import type {
  DataEntryCatalogProduct,
  DataEntryLine,
  PriceRoundingRule,
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
  /** EAN albo, gdy faktura go nie ma, inny kod z faktury lub nazwa (wtedy {@code eanFallback}). */
  ean: string
  /** Skąd wziął się kod zamiast EAN-u (np. „Indeks dostawcy”); {@code null}/brak = prawdziwy EAN albo wpisany. */
  eanFallback?: string | null
  /** EAN, dla którego skończyło się szukanie w kartotece (do tego czasu błędny EAN nie jest oznaczany). Brak w starszych szkicach. */
  eanChecked?: string | null
  /** Indeks dostawcy i kod CN z pozycji faktury (do pliku CADBEZP). Brak w starszych szkicach i wierszach ręcznych. */
  supplierCode?: string | null
  cn?: string | null
  quantity: number | null
  unitNet: number | null
  name: string
  brand: string
  vatPercent: number | null
  salePrice: number | null
  subfamilyCode: string | null
  /** Rejon (2 cyfry) albo rodzina (4) wybrane bez podrodziny: zawęża kolejną listę. Brak w starszych szkicach. */
  nomenclaturePrefix?: string | null
  unit: SaleUnit
  netContent: number
  /** Nazwa i jednostka z faktury (podpowiedź przy wierszu i dane dla AI). */
  invoiceName: string | null
  invoiceUnit: string | null
  /** Artykuł z kartoteki sklepu; {@code false} = nowy. */
  known: boolean
  /** Artykuł z kartoteki z CENA_CAD: cena sprzedaży na niebiesko. Brak w starszych szkicach. */
  cadencier?: boolean
  /** CENA_CAD z kartoteki: cena sugerowana bez liczenia. Brak w starszych szkicach. */
  cadencierPrice?: number | null
  /** Promocja z kasy (plik TRACEART) w chwili wczytania: cena brutto i ostatni dzień. Brak w starszych szkicach. */
  promoPrice?: number | null
  promoTo?: string | null
  itm8: string | null
  /** Stan i rotacja z kartoteki w chwili wczytania. Brak w starszych szkicach. */
  stock?: number | null
  rotation?: number | null
  /** Nazwa jest jeszcze tą z faktury: do poprawy (AI albo ręcznie). */
  nameFromInvoice: boolean
  edited: Partial<Record<AiField, true>>
  suggestion: RowSuggestion | null
  /** Dane artykułu z kartoteki w chwili wczytania: z nimi porównujemy zmiany. Brak w starszych szkicach. */
  catalog?: CatalogSnapshot | null
  /** Pozycja przypięta do artykułu u tego dostawcy (kod z kolumny EAN, np. nazwa bez EAN-u). Brak w starszych szkicach. */
  link?: RowLink | null
  /** Linia dokumentu dostawy z bazy, z której jest wiersz (tylko w „Dostawie z bazy”). */
  base?: BaseLineInfo | null
}

export type RowLink = { id: string; code: string }

/**
 * Dane linii pliku z bazy przy wierszu: ITM8 z pliku (kartoteka bywa pod innym), nośnik, kartony, kaucja z pliku
 * i z kartoteki, termin i cena sprzedaży dla klienta (C_Publi).
 */
export type BaseLineInfo = {
  lineId: string
  itm8: string
  carrier: string
  supplierCode: string
  coliOrdered: number
  coliPrepared: number
  pack: number
  deposit: string | null
  catalogDeposit: number | null
  retailPrice: number | null
  bestBefore: string | null
  requiredBestBefore: string | null
  shortDated: boolean
  regionCode: string | null
}

/** Kaucja z pliku bazy (PLA, PUS, SZK) i kody kartoteki, które jej odpowiadają (sprawdzone na kartotece 10023). */
const DEPOSIT_CODES: Record<string, number[]> = { PLA: [80], PUS: [70], SZK: [1, 2, 60, 92] }
const DEPOSIT_LABELS: Record<string, string> = { PLA: 'PET', PUS: 'puszka', SZK: 'szkło' }
const CATALOG_DEPOSITS: Record<number, string> = {
  1: 'butelka zwrotna',
  2: 'szkło',
  60: 'szkło',
  70: 'puszka',
  80: 'PET',
  92: 'szkło',
}

/** Kaucja z pliku bazy do wyświetlenia: PET, puszka, szkło; bez kaucji pusta. */
export function depositLabel(deposit: string | null | undefined) {
  return deposit ? (DEPOSIT_LABELS[deposit] ?? null) : null
}

/**
 * Kaucja z dokumentu bazy niezgodna z kartoteką: dokument ma kaucję, a artykuł w kartotece nie (albo inny rodzaj),
 * albo kartoteka ma kaucję, a dokument nie. Poważny błąd: blokuje pliki z tabeli. Nowy artykuł z kaucją to tylko
 * {@code depositNotice} (kaucję ustawia się przy zakładaniu).
 */
export function depositError(row: DataEntryRow): string | null {
  const base = row.base
  if (!base || !row.known) return null
  const file = base.deposit && base.deposit !== 'NIE' ? base.deposit : null
  const catalog = base.catalogDeposit ?? 0
  const catalogLabel = CATALOG_DEPOSITS[catalog] ?? null
  if (file) {
    const expected = DEPOSIT_CODES[file]
    if (!expected) return null
    if (catalogLabel === null) return `Dokument: kaucja (${DEPOSIT_LABELS[file]}), w kartotece artykuł bez kaucji`
    if (!expected.includes(catalog)) return `Dokument: kaucja (${DEPOSIT_LABELS[file]}), w kartotece: ${catalogLabel}`
    return null
  }
  return catalogLabel === null ? null : `Dokument bez kaucji, w kartotece kaucja (${catalogLabel})`
}

/** Nowy artykuł z kaucją w dokumencie: przy zakładaniu trzeba ustawić kaucję w Mercalys. */
export function depositNotice(row: DataEntryRow): string | null {
  const base = row.base
  if (!base || row.known || !base.deposit || base.deposit === 'NIE') return null
  const label = depositLabel(base.deposit)
  return label ? `Nowy artykuł z kaucją (${label}): ustaw kaucję przy zakładaniu w Mercalys` : null
}

/** Pola artykułu, które pochodzą z kartoteki (ilość, cena zakupu i VAT są z faktury). */
const CATALOG_FIELDS = ['name', 'brand', 'salePrice', 'subfamilyCode', 'unit', 'netContent'] as const

export type CatalogField = (typeof CATALOG_FIELDS)[number]

export type CatalogSnapshot = Pick<DataEntryRow, CatalogField>

function snapshot(row: DataEntryRow): DataEntryRow {
  return {
    ...row,
    catalog: Object.fromEntries(CATALOG_FIELDS.map((field) => [field, row[field]])) as CatalogSnapshot,
  }
}

/** Pola artykułu z kartoteki zmienione w tabeli (powrót do wartości z kartoteki zdejmuje zmianę). */
export function catalogChanges(row: DataEntryRow): CatalogField[] {
  const catalog = row.catalog
  if (!row.known || !catalog) return []
  return CATALOG_FIELDS.filter((field) => row[field] !== catalog[field])
}

/**
 * Artykuł do pliku CADBEZP: nowy albo z kartoteki ze zmienioną nazwą, marką, nomenklaturą, rodzajem sprzedaży albo
 * wagą. Zmieniona cena sprzedaży idzie tylko do pliku CTRLPRIX.
 */
export function needsProductFile(row: DataEntryRow) {
  return !row.known || catalogChanges(row).some((field) => field !== 'salePrice')
}

export const NAME_LIMIT = 30

export function newRowId() {
  return crypto.randomUUID()
}

export function emptyRow(): DataEntryRow {
  return {
    id: newRowId(),
    ean: '',
    eanFallback: null,
    eanChecked: null,
    quantity: null,
    unitNet: null,
    name: '',
    brand: '',
    vatPercent: null,
    salePrice: null,
    subfamilyCode: null,
    nomenclaturePrefix: null,
    unit: 'szt.',
    netContent: 1,
    invoiceName: null,
    invoiceUnit: null,
    known: false,
    itm8: null,
    nameFromInvoice: false,
    edited: {},
    suggestion: null,
    catalog: null,
  }
}

/** Pozycja faktury jako wiersz: znany artykuł z danymi z kartoteki, nowy z tym, co wiadomo z faktury. */
export function rowFromLine(line: DataEntryLine): DataEntryRow {
  const product = line.product
  // Ciąg 8-14 cyfr z innego pola faktury to EAN z błędem dostawcy (np. Indeks 59010058003643 z dodatkową cyfrą):
  // idzie do kolumny jako EAN, żeby tabela pokazała błędny EAN i nie dała zapisać pliku, zamiast zer w pliku.
  const digitFallback = !line.ean && line.fallbackCode !== null && /^\d{8,14}$/.test(line.fallbackCode)
  const row: DataEntryRow = {
    ...emptyRow(),
    // Bez EAN-u kolumna dostaje inny kod z faktury albo nazwę, żeby pozycję dało się rozpoznać.
    ean: line.ean ?? line.fallbackCode ?? '',
    eanFallback: line.ean || digitFallback ? null : line.fallbackSource,
    // Pozycje faktury backend sprawdził już w kartotece.
    eanChecked: line.ean ?? line.fallbackCode ?? '',
    supplierCode: line.supplierCode,
    cn: line.cn,
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
    cadencier: product?.cadencier ?? false,
    cadencierPrice: product?.cadencierPrice ?? null,
    promoPrice: product?.promoPrice ?? null,
    promoTo: product?.promoTo ?? null,
    itm8: product?.itm8 ?? null,
    stock: product?.stock ?? null,
    rotation: product?.rotation ?? null,
    nameFromInvoice: product === null && Boolean(line.invoiceName),
    link: line.linkId && line.linkedCode ? { id: line.linkId, code: line.linkedCode } : null,
  }
  return product ? snapshot(row) : row
}

/** Wpisany EAN znaleziony w kartotece: dane artykułu zastępują wiersz, ilość i ceny z faktury zostają. */
export function withCatalogProduct(row: DataEntryRow, product: DataEntryCatalogProduct | null): DataEntryRow {
  if (!product) {
    return {
      ...row,
      known: false,
      cadencier: false,
      cadencierPrice: null,
      promoPrice: null,
      promoTo: null,
      itm8: null,
      stock: null,
      rotation: null,
      catalog: null,
    }
  }
  return snapshot({
    ...row,
    known: true,
    cadencier: product.cadencier,
    cadencierPrice: product.cadencierPrice,
    promoPrice: product.promoPrice,
    promoTo: product.promoTo,
    itm8: product.itm8,
    stock: product.stock,
    rotation: product.rotation,
    name: product.name ?? row.name,
    brand: product.brand ?? '',
    salePrice: product.salePrice,
    subfamilyCode: product.subfamilyCode,
    nomenclaturePrefix: null,
    unit: product.unit,
    netContent: product.netContent,
    nameFromInvoice: false,
    suggestion: null,
    edited: {},
  })
}

// ---------- EAN wpisany albo zeskanowany

/**
 * Kod z etykiety wagi sklepu: 2 + cyfra 1-9, 5 cyfr artykułu (razem 7 cyfr jak w EAN-ie z kartoteki, np.
 * 2404140000000), w cyfrach 8-12 cena (prefiks 24, w groszach) albo waga (21-23, 25-29, w gramach), na końcu cyfra
 * kontrolna (bywa pominięta). Z zerami w miejscu ceny to zwykły EAN artykułu.
 */
export function scaleCode(ean: string): { kind: 'price' | 'weight'; value: number } | null {
  const digits = ean.replace(/^0+/, '')
  if (!/^2[1-9]\d{10,11}$/.test(digits)) return null
  const value = Number(digits.slice(7, 12))
  if (value === 0) return null
  return digits[1] === '4' ? { kind: 'price', value: value / 100 } : { kind: 'weight', value: value / 1000 }
}

/**
 * Kod z etykiety wagi wpisany bez końca (np. 24213300: 7 cyfr artykułu i część ceny) dostaje zera do 13 cyfr jak EAN
 * z kartoteki (2421330000000). Poprawny EAN-8 zostaje, bo kartoteka ma kilka takich zaczynających się od 2.
 */
export function paddedScaleCode(ean: string) {
  if (!/^2[1-9]\d{5,9}$/.test(ean) || (ean.length === 8 && validGtin(ean))) return ean
  return ean.padEnd(13, '0')
}

/** EAN z samych cyfr o długości innej niż 8, 12, 13, 14 (kod zastępczy z faktury się nie liczy). */
export function incompleteEan(row: DataEntryRow) {
  return !row.eanFallback && /^\d+$/.test(row.ean) && !/^(\d{8}|\d{12,14})$/.test(row.ean)
}

/** Cyfra kontrolna GTIN (EAN-8, UPC-12, EAN-13, GTIN-14), jak {@code ProductCodeSources.validGtin}. */
export function validGtin(code: string) {
  if (!/^(\d{8}|\d{12,14})$/.test(code)) return false
  let sum = 0
  for (let i = 0; i < code.length - 1; i++) {
    const digit = Number(code[code.length - 2 - i])
    sum += i % 2 === 0 ? digit * 3 : digit
  }
  return (10 - (sum % 10)) % 10 === Number(code[code.length - 1])
}

/**
 * Błąd EAN-u: niepełny (zła długość) w każdym wierszu, także z kartoteki (jej EAN-y mają zawsze 13 cyfr), a w nowym
 * artykule też zła cyfra kontrolna. Cyfry kontrolnej nie sprawdzamy w artykułach z kartoteki (ok. 4% ich EAN-ów to
 * kody wewnętrzne bez poprawnej cyfry kontrolnej), w kodach z etykiety wagi (2 + cyfra 1-9, cyfra kontrolna bywa
 * pominięta), kodach zastępczych z faktury ani kaucjach i dostawie. W tabeli dopiero po sprawdzeniu w kartotece
 * ({@code checkedOnly}), żeby kod wewnętrzny nie mignął na czerwono w trakcie szukania.
 */
export function eanError(row: DataEntryRow, checkedOnly = true): string | null {
  if (row.ean === '' || row.eanFallback || serviceKind(row)) return null
  if (checkedOnly && row.eanChecked !== undefined && row.eanChecked !== row.ean) return null
  if (incompleteEan(row)) return `Niepełny EAN: ${row.ean.length} cyfr (EAN ma 8 albo 13 cyfr)`
  if (row.known) return null
  const digits = row.ean.replace(/^0+/, '')
  if (/^2[1-9]\d{10,11}$/.test(digits)) return null
  if (!/^\d+$/.test(row.ean)) return 'EAN to same cyfry'
  return validGtin(row.ean) ? null : 'Błędny EAN: zła cyfra kontrolna'
}

/**
 * Artykuł z kartoteki dla EAN-u wpisanego w tabeli: dane jak przy fakturze, a do tego cena zakupu i VAT, gdy wiersz
 * ich nie ma, i ilość: z kodu etykiety wagi (waga w kg albo cena z etykiety / cena sprzedaży 1 kg), inaczej 1 (także
 * przy EAN-ie spoza kartoteki).
 * Kod z etykiety zamienia się na EAN artykułu z kartoteki, więc kilka etykiet jednego artykułu łączy się w pary.
 * Wpisany ITM8 też, bo do pliku XML idzie kod kreskowy.
 */
export function withScannedProduct(row: DataEntryRow, product: DataEntryCatalogProduct | null): DataEntryRow {
  const next = withCatalogProduct(row, product)
  const scale = scaleCode(row.ean)
  let quantity = row.quantity ?? 1
  if (scale?.kind === 'weight') quantity = scale.value
  if (scale?.kind === 'price' && product?.salePrice)
    quantity = Math.round((scale.value / product.salePrice) * 1000) / 1000
  // Nowy artykuł też przyszedł w sztuce; tylko etykiety z ceną nie da się przeliczyć bez ceny 1 kg z kartoteki.
  if (!product)
    return scale?.kind === 'price' ? { ...next, eanChecked: row.ean } : { ...next, eanChecked: row.ean, quantity }
  // Kod artykułu z etykiety wagi dopełniony zerami (paddedScaleCode) też bierze EAN z kartoteki.
  const scaleArticle = /^2[1-9]\d{5}0{6}$/.test(row.ean)
  const ean = (scale || scaleArticle || product.byItm8) && product.catalogEan ? product.catalogEan : next.ean
  return {
    ...next,
    ean,
    eanChecked: ean,
    quantity,
    unitNet: row.unitNet ?? product.purchasePrice,
    vatPercent: row.vatPercent ?? product.vatPercent,
  }
}

/**
 * Pozycja przypięta do artykułu z kartoteki (z okna „Przypisz do artykułu”): kolumna EAN dostaje EAN artykułu zamiast
 * kodu z faktury, dane z kartoteki jak po wpisaniu EAN-u, ilość i cena zakupu z faktury zostają (bez ceny - z
 * kartoteki). Bez przeliczania kodu z etykiety wagi: EAN z kartoteki ma zera w miejscu ceny.
 */
export function withLinkedProduct(
  row: DataEntryRow,
  product: DataEntryCatalogProduct,
  link: RowLink | null,
): DataEntryRow {
  const ean = product.catalogEan ?? product.ean
  return {
    ...withCatalogProduct({ ...row, ean, eanFallback: null, eanChecked: ean, link }, product),
    unitNet: row.unitNet ?? product.purchasePrice,
    vatPercent: row.vatPercent ?? product.vatPercent,
  }
}

/** Wiersz dopisany do tabeli (np. z listy towarów): nad pustym wierszem na kolejny EAN, gdy jest na końcu. */
export function withAddedRow(rows: DataEntryRow[], row: DataEntryRow) {
  const last = rows.length - 1
  const at = last >= 0 && rows[last].ean === '' && rows[last].name === '' ? last : rows.length
  return [...rows.slice(0, at), row, ...rows.slice(at)]
}

// ---------- kaucje i dostawa

/** Pozycja faktury, która nie jest artykułem: kaucja za opakowanie albo koszt dostawy. */
export type ServiceKind = 'deposit' | 'delivery'

export const SERVICE_LABELS: Record<ServiceKind, string> = {
  deposit: 'kaucja',
  delivery: 'dostawa',
}

const DEPOSIT_PRICE = 0.5

const near = (value: number, target: number) => Math.abs(value - target) < 0.005

/**
 * Kaucja (nazwa z „kaucja”, 50 gr za sztukę netto albo brutto) i dostawa (nazwa z „dostawa” / „transport”, dowolna
 * kwota) idą na dół tabeli i nie trafiają do Excela. Artykuł z kartoteki nigdy nią nie jest.
 */
export function serviceKind(row: DataEntryRow): ServiceKind | null {
  if (row.known) return null
  const name = plain(row.invoiceName ?? row.name)
  if (/KAUCJ/.test(name) && row.unitNet !== null) {
    const gross = row.vatPercent === null ? null : row.unitNet * (1 + row.vatPercent / 100)
    if (near(row.unitNet, DEPOSIT_PRICE) || (gross !== null && near(gross, DEPOSIT_PRICE))) return 'deposit'
  }
  if (/\bDOSTAW[AYE]?\b|\bTRANSPORT\b/.test(name)) return 'delivery'
  return null
}

/** Kolejność w tabeli: artykuły, a pod nimi kaucje i dostawa (bez zmiany kolejności w obu grupach). */
export function withServicesLast(rows: DataEntryRow[]) {
  const services = rows.filter((row) => serviceKind(row) !== null)
  if (services.length === 0) return rows
  const items = rows.filter((row) => serviceKind(row) === null)
  const ordered = [...items, ...services]
  return ordered.every((row, index) => row === rows[index]) ? rows : ordered
}

/**
 * Pod artykułami zawsze jest pusty wiersz na kolejny EAN (po wczytaniu faktury, po zatwierdzeniu kodu kliknięciem
 * obok). Pusta tabela zostaje pusta.
 */
export function withTrailingEmptyRow(rows: DataEntryRow[]) {
  const items = rows.filter((row) => serviceKind(row) === null)
  if (items.length === 0) return rows
  const last = items[items.length - 1]
  return last.ean === '' && last.name === '' ? rows : [...rows, emptyRow()]
}

/** Czy wiersz potrzebuje AI: nowy artykuł bez nomenklatury, z nazwą z faktury albo bez marki. */
export function needsAi(row: DataEntryRow) {
  // Wiersz z odpowiedzią modelu ma już swoje propozycje - ponowne pytanie dałoby to samo.
  if (row.known || (!row.name && !row.invoiceName) || row.suggestion?.modelAnswered || serviceKind(row)) return false
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
  new Intl.NumberFormat('pl-PL', {
    maximumFractionDigits: 3,
    useGrouping: false,
  }).format(value)

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

/**
 * Cena zakupu netto sprzedawanej sztuki: cena za jedną sztukę z kolumny Ilość bez względu na jednostkę na fakturze
 * (szt., op., pak, karton, kg), więc opakowanie zbiorcze rozbija się podziałem ilości. Waga (miara) nie zmienia ceny
 * zakupu, marży ani ceny sugerowanej.
 */
export function purchasePerPiece(row: DataEntryRow) {
  return row.unitNet
}

/** Marża % od sprzedaży netto przy obecnej cenie sprzedaży (jak w raportach sprzedaży). */
export function marginPercent(row: DataEntryRow) {
  const purchase = purchasePerPiece(row)
  if (purchase === null || row.salePrice === null || row.salePrice <= 0 || row.vatPercent === null) return null
  const saleNet = row.salePrice / (1 + row.vatPercent / 100)
  return ((saleNet - purchase) / saleNet) * 100
}

// ---------- marża wymagana i zaokrąglanie cen (Ustawienia sklepu)

/**
 * Ustawienia cen sugerowanych: kod nomenklatury -> marża wymagana, która na nim obowiązuje (serwer liczy ją już
 * z rodziny i rejonu), przedziały zaokrąglania i stała cena sprzedaży dostawcy dokumentu (Ustawienia sklepu).
 */
export type Pricing = {
  required: Map<string, number>
  rounding: PriceRoundingRule[]
  supplierPrice: number | null
  /** Cena sugerowana wprost z dokumentu (np. cena dla klienta z pliku bazy przy warzywach); ma pierwszeństwo. */
  rowPrice?: (row: DataEntryRow) => number | null
}

/** Stała cena dostawcy dokumentu dla artykułu (nowego i z kartoteki; bez kaucji i dostawy). */
export function supplierPriceOf(row: DataEntryRow, pricing: Pricing) {
  return pricing.supplierPrice !== null && serviceKind(row) === null ? pricing.supplierPrice : null
}

/**
 * Artykuły (bez kaucji, dostawy i pustych wierszy) ze stałą ceną dostawcy jako ceną sprzedaży. Te same wiersze,
 * gdy nic się nie zmienia.
 */
export function withSupplierPrice(rows: DataEntryRow[], price: number | null) {
  if (price === null) return rows
  let changed = false
  const next = rows.map((row) => {
    if ((row.ean === '' && row.name === '') || serviceKind(row) !== null || row.salePrice === price) return row
    changed = true
    return { ...row, salePrice: price }
  })
  return changed ? next : rows
}

/** Marża wymagana na nomenklaturze wiersza: podrodzina albo wybrany dopiero rejon / rodzina. */
export function requiredMargin(row: DataEntryRow, pricing: Pricing) {
  const code = row.subfamilyCode ?? row.nomenclaturePrefix
  return code ? (pricing.required.get(code) ?? null) : null
}

/**
 * Cena w górę do końcówki przedziału, w którym jest (krok = końcówka + 1 gr: 0,49 daje ,49 i ,99, 0,99 zawsze ,99);
 * poza przedziałami bez zmian. Ten sam wzór w {@code PriceRoundingRule.round}.
 */
export function roundPrice(price: number, rules: PriceRoundingRule[]) {
  const rule = rules.find((item) => price >= item.priceFrom && (item.priceTo === null || price < item.priceTo))
  if (!rule) return price
  // W groszach, żeby uniknąć błędów liczb zmiennoprzecinkowych.
  const cents = Math.round(price * 100)
  const step = Math.round(rule.ending * 100) + 1
  return (Math.ceil((cents + 1) / step) * step - 1) / 100
}

/**
 * Cena sugerowana: stała cena dostawcy dokumentu, jeśli ją ma; artykuł z CENA_CAD ma ją bez liczenia. Inaczej cena brutto, przy której artykuł osiąga marżę
 * wymaganą (% od sprzedaży netto), w górę do grosza, potem do końcówki z przedziałów zaokrąglania; ten sam wzór
 * w eksporcie ({@code DataEntryService.suggestedPrice}).
 */
export function suggestedPrice(row: DataEntryRow, pricing: Pricing) {
  const fromDocument = pricing.rowPrice?.(row) ?? null
  if (fromDocument !== null) return fromDocument
  const fixed = supplierPriceOf(row, pricing)
  if (fixed !== null) return fixed
  const cadencier = cadencierPrice(row)
  if (cadencier !== null) return cadencier
  const required = requiredMargin(row, pricing)
  const purchase = purchasePerPiece(row)
  if (required === null || purchase === null || row.vatPercent === null) return null
  const gross = (purchase / (1 - required / 100)) * (1 + row.vatPercent / 100)
  return roundPrice(Math.ceil(gross * 100 - 1e-6) / 100, pricing.rounding)
}

/**
 * Nowy artykuł po potwierdzeniu nomenklatury dostaje cenę sugerowaną jako cenę sprzedaży. Cenę wpisaną ręcznie
 * zostawia: zmienia tylko pustą albo równą cenie sugerowanej sprzed zmiany ({@code before}).
 */
export function withAutoSalePrice(before: DataEntryRow, after: DataEntryRow, pricing: Pricing) {
  if (after.known || serviceKind(after) !== null || after.subfamilyCode === null) return after
  if (after.salePrice !== null && after.salePrice !== suggestedPrice(before, pricing)) return after
  const price = suggestedPrice(after, pricing)
  return price === null || price === after.salePrice ? after : { ...after, salePrice: price }
}

/** CENA_CAD artykułu z kartoteki (cena narzucona przez centralę); {@code null}, gdy jej nie ma. */
/**
 * Cena z kasy z bieżącej kartoteki: plik TRACEART wczytany po dodaniu wiersza zmienia promocję i cenę sprzedaży
 * (w promocji to cena promocyjna), cenę zmienioną w tabeli zostawia. Ten sam wiersz, gdy nic się nie zmieniło.
 */
export function withCurrentPromo(row: DataEntryRow, product: DataEntryCatalogProduct | null): DataEntryRow {
  const promoPrice = product?.promoPrice ?? null
  const promoTo = product?.promoTo ?? null
  const catalog = row.catalog
  const salePrice = product && catalog && row.salePrice === catalog.salePrice ? product.salePrice : row.salePrice
  if (row.promoPrice === promoPrice && row.promoTo === promoTo && row.salePrice === salePrice) return row
  return {
    ...row,
    promoPrice,
    promoTo,
    salePrice,
    catalog: catalog && salePrice !== row.salePrice ? { ...catalog, salePrice } : catalog,
  }
}

/** Cena promocyjna z kasy, póki promocja trwa (szkic z localStorage bywa z poprzednich dni). */
export function activePromoPrice(row: DataEntryRow) {
  if (!row.known || row.promoPrice == null || !row.promoTo || row.promoTo < todayIso()) return null
  return row.promoPrice
}

export function cadencierPrice(row: DataEntryRow) {
  return row.known && row.cadencierPrice != null && row.cadencierPrice > 0 ? row.cadencierPrice : null
}

/** Rejon owoców i warzyw oraz marża, od której artykuł można sprzedawać w mniejszych ilościach. */
export const SMALLER_QUANTITIES_REGION = '14'
export const SMALLER_QUANTITIES_MARGIN = 45

/**
 * Owoce i warzywa (rejon 14) z marżą od 45%: zapas pozwala sprzedawać artykuł w mniejszych ilościach (np. worek
 * na sztuki albo na wagę), więc przy marży jest oznaczenie.
 */
export function canSellSmaller(row: DataEntryRow) {
  const code = row.subfamilyCode ?? row.nomenclaturePrefix
  if (serviceKind(row) !== null || !code?.startsWith(SMALLER_QUANTITIES_REGION)) return false
  const margin = marginPercent(row)
  return margin !== null && margin >= SMALLER_QUANTITIES_MARGIN - 1e-9
}

/** Marża artykułu poniżej wymaganej na jego nomenklaturze. */
export function belowRequiredMargin(row: DataEntryRow, pricing: Pricing) {
  const required = requiredMargin(row, pricing)
  const margin = marginPercent(row)
  return required !== null && margin !== null && margin < required - 1e-9
}

export function netValue(row: DataEntryRow) {
  return row.quantity !== null && row.unitNet !== null ? Math.round(row.quantity * row.unitNet * 100) / 100 : null
}

// ---------- podział ilości (opakowanie na sztuki)

export type QuantitySplit = {
  quantity: number
  /** Cena po podziale: wartość netto / nowa ilość, do 4 miejsc (jak cena średnia przy łączeniu wierszy). */
  unitNet: number
  /** Wartość netto przed podziałem (jak w kolumnie „Wartość netto”). */
  value: number
  /** Wartość w pliku XML, gdzie cena ma 2 miejsca (może różnić się od {@code value} o grosze). */
  xmlValue: number
}

/** Pozycję da się podzielić: ma dodatnią ilość i cenę zakupu (kaucje i dostawa nie). */
export function canSplitQuantity(row: DataEntryRow) {
  return serviceKind(row) === null && row.quantity !== null && row.quantity > 0 && row.unitNet !== null
}

/**
 * Podział każdej sztuki pozycji na {@code factor} części (worek 10 kg na 10 × 1 kg): ilość × factor, a cena to
 * wartość netto / nowa ilość, więc pozycja zostaje jednym wierszem z tą samą wartością (10 × 25,55 zł na
 * 100 × 2,555 zł).
 */
export function splitQuantity(row: DataEntryRow, factor: number): QuantitySplit | null {
  if (!canSplitQuantity(row) || !(factor > 0)) return null
  const quantity = Math.round((row.quantity ?? 0) * factor * 1000) / 1000
  if (quantity <= 0) return null
  const value = netValue(row) ?? 0
  const unitNet = Math.round((value / quantity) * 10000) / 10000
  const xmlValue = Math.round(quantity * Math.round(unitNet * 100)) / 100
  return { quantity, unitNet, value, xmlValue }
}

/** Wiersz po podziale; {@code invoiceUnit} to jednostka ilości po podziale (kg albo szt.), z niej liczy się marża. */
export function withSplitQuantity(rows: DataEntryRow[], rowId: string, split: QuantitySplit, invoiceUnit: string) {
  return rows.map((row) =>
    row.id === rowId
      ? {
          ...row,
          quantity: split.quantity,
          unitNet: split.unitNet,
          invoiceUnit,
        }
      : row,
  )
}

// ---------- walidacja przed eksportem

export type RowProblem = 'invalid-ean' | 'name-too-long' | 'no-subfamily'

export function rowProblems(row: DataEntryRow): RowProblem[] {
  const problems: RowProblem[] = []
  if (serviceKind(row)) return problems
  if (eanError(row, false)) problems.push('invalid-ean')
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
