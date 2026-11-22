import { listFolderFiles } from '@/lib/saved-folders'
import { folderLocationKey, type StoreLocations } from '@/lib/store-locations'
import type { ImportReportKind } from '@/lib/types'

/*
 * Skąd „Odśwież dane” bierze pliki raportów: folder wybrany raz w przeglądarce (File System Access API, uchwyt
 * w IndexedDB) i opcjonalna maska nazwy pliku, osobno dla każdego sklepu i raportu. Raporty leżą na dysku sieciowym,
 * a w jednym sklepie ścieżki są takie same na każdym komputerze, więc maski, ścieżki folderów i zapisu makr oraz
 * numery z Mercalys są ścieżkami sklepu na serwerze (`lib/store-locations`): ustawione raz działają u wszystkich.
 */

/** Raporty wgrywane codziennie, w kolejności odświeżania. Listę dostawców i nomenklatur wgrywa się tylko ręcznie. */
export const DAILY_REPORTS = ['PRODUCTS', 'PRODUCT_CODES', 'PROMOTIONS', 'CADENCIER', 'SALES', 'DOCUMENTS'] as const

export type DailyReport = (typeof DAILY_REPORTS)[number]

export const REPORT_TITLES: Record<ImportReportKind, string> = {
  PRODUCTS: 'Baza danych',
  PRODUCT_CODES: 'Multikody',
  PROMOTIONS: 'Lista promocji',
  SALES: 'Raport sprzedaży',
  DOCUMENTS: 'Raport dokumentów',
  CADENCIER: 'Obecne w cadencier',
  SUPPLIERS: 'Lista dostawców',
  NOMENCLATURES: 'Lista nomenklatur',
}

export const XLSX_EXTENSIONS = ['.xlsx', '.xlsm']
/** Lista nomenklatur i lista promocji są drukowane z systemu sklepu tylko do PDF. */
export const PDF_EXTENSIONS = ['.pdf']

export const REPORT_EXTENSIONS: Record<DailyReport, string[]> = {
  PRODUCTS: XLSX_EXTENSIONS,
  PRODUCT_CODES: XLSX_EXTENSIONS,
  PROMOTIONS: PDF_EXTENSIONS,
  SALES: XLSX_EXTENSIONS,
  DOCUMENTS: XLSX_EXTENSIONS,
  CADENCIER: XLSX_EXTENSIONS,
}

/** Przykład maski w polu ustawień (nazwy plików z systemu sklepu). */
export const MASK_EXAMPLES: Record<DailyReport, string> = {
  PRODUCTS: 'BAZA DO STRONY*',
  PRODUCT_CODES: 'Multikody*',
  PROMOTIONS: 'Taryfy*',
  SALES: 'sprzedaz*',
  DOCUMENTS: 'Zeszyt*',
  CADENCIER: 'obecne w cad*',
}

export const sourceFolderKey = (storeId: string, kind: DailyReport) => `import:${storeId}:${kind}`

export type SourceMasks = Partial<Record<DailyReport, string>>

/*
 * Zapis raportów z Mercalys makrem (odtwarzacz od wersji 46, polecenie EXCELSAVE): pełna ścieżka pliku na każdy raport,
 * wspólna dla sklepu (ścieżki sklepu na serwerze). Makro zapisuje tam skoroszyt otwarty przyciskiem Excel w Mercalys, a „Odśwież
 * dane” bierze go z folderu raportu. Lista promocji jest drukowana tylko do PDF: zapisuje ją PDFSAVE (od wersji 49).
 */
export const MACRO_REPORTS = ['PRODUCTS', 'PRODUCT_CODES', 'PROMOTIONS', 'CADENCIER', 'SALES', 'DOCUMENTS'] as const

export type MacroReport = (typeof MACRO_REPORTS)[number]

export const isMacroReport = (kind: DailyReport): kind is MacroReport =>
  (MACRO_REPORTS as readonly string[]).includes(kind)

export type MacroPaths = Partial<Record<MacroReport, string>>

/** Opis błędu ścieżki zapisu albo null, gdy jest dobra (pusta też jest dobra: raport bez makra). */
export function macroPathError(kind: MacroReport, path: string, mask: string): string | null {
  const value = path.trim()
  if (!value) return null
  if (!/^([a-z]:\\|\\\\[^\\]+\\[^\\]+)/i.test(value)) return 'Podaj pełną ścieżkę, np. P:\\Raporty\\multikody.xlsx'
  if (value.includes('|')) return 'Ścieżka nie może mieć znaku |'
  const name = value.slice(value.lastIndexOf('\\') + 1)
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || !REPORT_EXTENSIONS[kind].includes(name.slice(dot).toLowerCase()))
    return `Nazwa pliku musi kończyć się na ${REPORT_EXTENSIONS[kind].join(' albo ')}`
  if (mask.trim() && !maskPattern(mask).test(name.slice(0, dot)))
    return `Nazwa nie pasuje do maski „${mask.trim()}”, więc „Odśwież dane” pominie ten plik`
  if (PERIOD_REPORTS.includes(kind) && mask.trim() && !maskPattern(mask).test(partName(name.slice(0, dot), 1, 2)))
    return `Maska „${mask.trim()}” nie przepuści części zaległego okresu (np. „${partName(name.slice(0, dot), 1, 2)}”), dopisz * na końcu`
  return null
}

/**
 * Raporty z okresem, którego Mercalys nie wydrukuje naraz ponad limit (miesiące): przy dłuższej zaległości makro
 * drukuje je w częściach, a „Odśwież dane” wgrywa wszystkie nowe pliki po kolei.
 */
export const PERIOD_REPORT_MONTHS: Partial<Record<MacroReport, number>> = { SALES: 6, DOCUMENTS: 3 }
const PERIOD_REPORTS = Object.keys(PERIOD_REPORT_MONTHS) as MacroReport[]

export const isPeriodReport = (kind: DailyReport): kind is MacroReport =>
  (PERIOD_REPORTS as readonly string[]).includes(kind)

/** Długość jednej części zaległego okresu: tygodnie albo miesiące. */
export type PartLength = { unit: 'week' | 'month'; count: number }

const PART_LENGTHS: { value: string; label: string; length: PartLength }[] = [
  { value: '1w', label: '1 tydzień', length: { unit: 'week', count: 1 } },
  { value: '2w', label: '2 tygodnie', length: { unit: 'week', count: 2 } },
  { value: '1m', label: '1 miesiąc', length: { unit: 'month', count: 1 } },
  { value: '2m', label: '2 miesiące', length: { unit: 'month', count: 2 } },
  { value: '3m', label: '3 miesiące', length: { unit: 'month', count: 3 } },
  { value: '6m', label: '6 miesięcy', length: { unit: 'month', count: 6 } },
]

const withinLimit = (kind: MacroReport, length: PartLength) =>
  length.unit === 'week' || length.count <= (PERIOD_REPORT_MONTHS[kind] ?? 0)

/** Długości części do wyboru w ustawieniach raportu (do limitu Mercalys); pierwsza to domyślna (limit). */
export function partLengthOptions(kind: MacroReport) {
  const options = PART_LENGTHS.filter((option) => withinLimit(kind, option.length))
  const limit = `${PERIOD_REPORT_MONTHS[kind]}m`
  return options.map((option) => ({
    value: option.value,
    label: option.value === limit ? `${option.label} (domyślnie)` : option.label,
  }))
}

/**
 * Długość części z ustawień sklepu; bez ustawienia (albo z wartością ponad limit) limit Mercalys. Krótsza część
 * pomaga, gdy plik za kilka miesięcy ma milion wierszy i Mercalys albo Excel go nie wygeneruje.
 */
export function partLengthOf(kind: MacroReport, value: string | undefined): PartLength {
  const chosen = PART_LENGTHS.find((option) => option.value === value?.trim())?.length
  return chosen && withinLimit(kind, chosen) ? chosen : { unit: 'month', count: PERIOD_REPORT_MONTHS[kind] ?? 1 }
}

const partName = (base: string, part: number, parts: number) => `${base} ${part} z ${parts}`

/** Ścieżka części okresu („sprzedaz 1 z 3.xlsx”); ostatnia, najnowsza część ma ścieżkę z ustawień. */
export function partPath(path: string, part: number, parts: number) {
  if (part === parts) return path
  const dot = path.lastIndexOf('.')
  return `${partName(path.slice(0, dot), part, parts)}${path.slice(dot)}`
}

/** Numery wydruków ustawialnych (Baza danych) albo list artykułów (Obecne w cadencier) w Mercalys. */
export type MacroPrintNumbers = Partial<Record<MacroReport, string>>

/** Pole ustawień raportu w ścieżkach sklepu. */
export type ImportField = 'mask' | 'path' | 'print' | 'list' | 'part'

export const importLocationKey = (kind: DailyReport, field: ImportField) => `import:${kind}:${field}`

/**
 * Dzień otwarcia sklepu (RRRR-MM-DD) w ścieżkach sklepu: wcześniejszych dni makro nie drukuje (puste raporty
 * sprzedaży i dokumentów sprzed otwarcia).
 */
export const OPENED_ON_KEY = 'import:openedOn'

/** Ścieżka folderu raportu w ścieżkach sklepu (podpowiedź przy wyborze folderu w przeglądarce). */
export const importFolderLocationKey = (kind: DailyReport) => folderLocationKey(`import:${kind}`)

export type ImportSettings = {
  masks: SourceMasks
  macroPaths: MacroPaths
  printNumbers: MacroPrintNumbers
  listNumbers: MacroPrintNumbers
  /** Długość części zaległego okresu (sprzedaż, dokumenty), wartość z `partLengthOptions`. */
  partLengths: MacroPrintNumbers
  folders: Partial<Record<DailyReport, string>>
  /** Dzień otwarcia sklepu; puste = bez ograniczenia (od HISTORY_START). */
  openedOn?: string
}

export function importSettingsFrom(locations: StoreLocations | undefined): ImportSettings {
  const settings: ImportSettings = {
    masks: {},
    macroPaths: {},
    printNumbers: {},
    listNumbers: {},
    partLengths: {},
    folders: {},
  }
  if (!locations) return settings
  settings.openedOn = locations[OPENED_ON_KEY] || undefined
  for (const kind of DAILY_REPORTS) {
    settings.masks[kind] = locations[importLocationKey(kind, 'mask')]
    settings.folders[kind] = locations[importFolderLocationKey(kind)]
    if (isMacroReport(kind)) {
      settings.macroPaths[kind] = locations[importLocationKey(kind, 'path')]
      settings.printNumbers[kind] = locations[importLocationKey(kind, 'print')]
      settings.listNumbers[kind] = locations[importLocationKey(kind, 'list')]
      settings.partLengths[kind] = locations[importLocationKey(kind, 'part')]
    }
  }
  return settings
}

/** Ustawienia zapisane wcześniej w tej przeglądarce (localStorage), do jednorazowego przeniesienia na serwer. */
const LEGACY_KEYS: [string, ImportField][] = [
  ['intermanage.importMasks.', 'mask'],
  ['intermanage.importMacroPaths.', 'path'],
  ['intermanage.importMacroPrints.', 'print'],
  ['intermanage.importMacroLists.', 'list'],
]

export function legacyImportLocations(storeId: string): StoreLocations {
  const result: StoreLocations = {}
  for (const [prefix, field] of LEGACY_KEYS) {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(prefix + storeId) ?? '{}')
      if (!parsed || typeof parsed !== 'object') continue
      for (const [kind, value] of Object.entries(parsed as Record<string, unknown>)) {
        if ((DAILY_REPORTS as readonly string[]).includes(kind) && typeof value === 'string' && value.trim()) {
          result[importLocationKey(kind as DailyReport, field)] = value.trim()
        }
      }
    } catch {
      // Uszkodzony zapis pomijamy.
    }
  }
  return result
}

export function clearLegacyImportLocations(storeId: string) {
  for (const [prefix] of LEGACY_KEYS) {
    try {
      localStorage.removeItem(prefix + storeId)
    } catch {
      // Bez localStorage nie ma czego czyścić.
    }
  }
}

/** Maska jak w Eksploratorze: `*` = dowolny ciąg, `?` = jeden znak, bez wielkości liter; pusta = każdy plik. */
function maskPattern(mask: string) {
  const source = mask
    .trim()
    .split('')
    .map((char) => (char === '*' ? '.*' : char === '?' ? '.' : char.replace(/[.+^${}()|[\]\\]/g, '\\$&')))
    .join('')
  // Maska bez gwiazdki pasuje też do nazwy z dopiskiem (np. „Multikody (1).xlsx”).
  return new RegExp(`^${source}${mask.includes('*') ? '' : '.*'}$`, 'i')
}

/** Najnowszy (po dacie zmiany) plik raportu w jego folderze; podfolderów nie przeszukuje. */
export async function newestReportFile(storeId: string, kind: DailyReport, mask: string): Promise<File | null> {
  const files = await reportFiles(storeId, kind, mask)
  return files.length > 0 ? files[files.length - 1] : null
}

/**
 * Pliki do wgrania przy odświeżeniu, od najstarszego: raport z okresem (sprzedaż, dokumenty) bierze wszystkie nowsze
 * niż ostatnie wgranie, bo makro zapisuje zaległość w częściach; pozostałe tylko najnowszy.
 */
export async function newReportFiles(
  storeId: string,
  kind: DailyReport,
  mask: string,
  since: number,
  history: ImportedFile[] = [],
) {
  const files = await reportFiles(storeId, kind, mask)
  const newest = files.length > 0 ? files[files.length - 1] : null
  if (!(PERIOD_REPORTS as string[]).includes(kind)) {
    return { newest, files: files.filter((file) => file.lastModified > since).slice(-1) }
  }
  return { newest, files: files.filter((file) => file.lastModified > since || notImportedYet(file, history)) }
}

/** Wgranie raportu z historii: nazwa pliku i chwila wgrania. */
export type ImportedFile = { fileName: string; importedAt: string }

/** Pełna lista historii z serwera ma tyle pozycji; przy pełnej starsze wgrania mogły z niej wypaść. */
const HISTORY_SIZE = 30

/**
 * Część zaległości zapisana przez makro, której jeszcze nie wgrano: żadne wgranie pliku o tej nazwie nie było po
 * jego ostatniej zmianie. Samo „nowszy niż ostatnie wgranie” gubiło części, gdy po przerwanym odświeżeniu ktoś wgrał
 * ponownie część 1 (06713, 19.11.2026: części 4-24 uznane za wgrane). Przy pełnej historii pliki starsze niż
 * najstarsze widoczne wgranie zostają pominięte, bo ich wgrania mogły z niej wypaść.
 */
function notImportedYet(file: File, history: ImportedFile[]) {
  const times = history.map((item) => Date.parse(item.importedAt)).filter((time) => !Number.isNaN(time))
  if (history.length >= HISTORY_SIZE && times.length > 0 && file.lastModified <= Math.min(...times)) return false
  const name = file.name.toLowerCase()
  return !history.some(
    (item) => item.fileName.toLowerCase() === name && Date.parse(item.importedAt) >= file.lastModified,
  )
}

/** Pliki raportu w jego folderze od najstarszego (po dacie zmiany); podfolderów nie przeszukuje. */
async function reportFiles(storeId: string, kind: DailyReport, mask: string): Promise<File[]> {
  const extensions = REPORT_EXTENSIONS[kind]
  const pattern = maskPattern(mask)
  const handles = await listFolderFiles(sourceFolderKey(storeId, kind), (name) => {
    const dot = name.lastIndexOf('.')
    if (dot < 0 || !extensions.includes(name.slice(dot).toLowerCase())) return false
    // Pliki tymczasowe Excela (~$Raport.xlsx) leżą obok otwartego pliku.
    return !name.startsWith('~$') && pattern.test(name.slice(0, dot))
  })
  const files: File[] = []
  for (const handle of handles) {
    const file = await handle.getFile().catch(() => null)
    if (file) files.push(file)
  }
  return files.sort((a, b) => a.lastModified - b.lastModified)
}
