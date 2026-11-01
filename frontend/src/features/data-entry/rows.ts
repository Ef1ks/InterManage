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
}

export type RowLink = { id: string; code: string }

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
  const row: DataEntryRow = {
    ...emptyRow(),
    // Bez EAN-u kolumna dostaje inny kod z faktury albo nazwę, żeby pozycję dało się rozpoznać.
    ean: line.ean ?? line.fallbackCode ?? '',
    eanFallback: line.ean ? null : line.fallbackSource,
    // Pozycje faktury backend sprawdził już w kartotece.
    eanChecked: line.ean ?? line.fallbackCode ?? '',
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
 * Błąd EAN-u nowego artykułu: zła długość albo cyfra kontrolna. Nie sprawdzamy artykułów z kartoteki (ok. 4% ich
 * EAN-ów to kody wewnętrzne bez poprawnej cyfry kontrolnej), kodów z etykiety wagi (2 + cyfra 1-9, cyfra kontrolna
 * bywa pominięta), kodów zastępczych z faktury ani kaucji i dostawy. W tabeli dopiero po sprawdzeniu w kartotece
 * ({@code checkedOnly}), żeby kod wewnętrzny nie mignął na czerwono w trakcie szukania.
 */
export function eanError(row: DataEntryRow, checkedOnly = true): string | null {
  if (row.known || row.ean === '' || row.eanFallback || serviceKind(row)) return null
  if (checkedOnly && row.eanChecked !== undefined && row.eanChecked !== row.ean) return null
  const digits = row.ean.replace(/^0+/, '')
  if (/^2[1-9]\d{10,11}$/.test(digits)) return null
  if (!/^\d+$/.test(row.ean)) return 'EAN to same cyfry'
  if (!/^(\d{8}|\d{12,14})$/.test(row.ean)) return 'Błędny EAN: zła długość (EAN ma 8 albo 13 cyfr)'
  return validGtin(row.ean) ? null : 'Błędny EAN: zła cyfra kontrolna'
}

/**
 * Artykuł z kartoteki dla EAN-u wpisanego w tabeli: dane jak przy fakturze, a do tego cena zakupu i VAT, gdy wiersz
 * ich nie ma, i ilość: z kodu etykiety wagi (waga w kg albo cena z etykiety / cena sprzedaży 1 kg), inaczej 1 (także
 * przy EAN-ie spoza kartoteki).
 * Kod z etykiety zamienia się na EAN artykułu z kartoteki, więc kilka etykiet jednego artykułu łączy się w pary.
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
  const ean = scale && product.catalogEan ? product.catalogEan : next.ean
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

const KG_UNITS = new Set(['kg', 'kg.', 'kilogram'])

/**
 * Cena zakupu netto sprzedawanej sztuki. Przy kg na fakturze razy waga sztuki sprzedawanej w kg; każda inna jednostka
 * (szt., op., pak, karton) to cena za jedną sztukę z kolumny Ilość, więc opakowanie zbiorcze rozbija się podziałem ilości.
 */
export function purchasePerPiece(row: DataEntryRow) {
  if (row.unitNet === null) return null
  const unit = (row.invoiceUnit ?? '').trim().toLowerCase()
  if (KG_UNITS.has(unit) && row.unit === 'kg') return row.unitNet * row.netContent
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
 * z rodziny i rejonu), i przedziały zaokrąglania.
 */
export type Pricing = {
  required: Map<string, number>
  rounding: PriceRoundingRule[]
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
 * Cena sugerowana: artykuł z CENA_CAD ma ją bez liczenia. Inaczej cena brutto, przy której artykuł osiąga marżę
 * wymaganą (% od sprzedaży netto), w górę do grosza, potem do końcówki z przedziałów zaokrąglania; ten sam wzór
 * w eksporcie ({@code DataEntryService.suggestedPrice}).
 */
export function suggestedPrice(row: DataEntryRow, pricing: Pricing) {
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
