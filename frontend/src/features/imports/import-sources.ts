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
export const DAILY_REPORTS = ['PRODUCTS', 'PRODUCT_CODES', 'PROMOTIONS', 'SALES', 'DOCUMENTS', 'CADENCIER'] as const

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
export const MACRO_REPORTS = ['PRODUCTS', 'PRODUCT_CODES', 'PROMOTIONS', 'SALES', 'DOCUMENTS', 'CADENCIER'] as const

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
export type ImportField = 'mask' | 'path' | 'print' | 'list'

export const importLocationKey = (kind: DailyReport, field: ImportField) => `import:${kind}:${field}`

/** Ścieżka folderu raportu w ścieżkach sklepu (podpowiedź przy wyborze folderu w przeglądarce). */
export const importFolderLocationKey = (kind: DailyReport) => folderLocationKey(`import:${kind}`)

export type ImportSettings = {
  masks: SourceMasks
  macroPaths: MacroPaths
  printNumbers: MacroPrintNumbers
  listNumbers: MacroPrintNumbers
  folders: Partial<Record<DailyReport, string>>
}

export function importSettingsFrom(locations: StoreLocations | undefined): ImportSettings {
  const settings: ImportSettings = { masks: {}, macroPaths: {}, printNumbers: {}, listNumbers: {}, folders: {} }
  if (!locations) return settings
  for (const kind of DAILY_REPORTS) {
    settings.masks[kind] = locations[importLocationKey(kind, 'mask')]
    settings.folders[kind] = locations[importFolderLocationKey(kind)]
    if (isMacroReport(kind)) {
      settings.macroPaths[kind] = locations[importLocationKey(kind, 'path')]
      settings.printNumbers[kind] = locations[importLocationKey(kind, 'print')]
      settings.listNumbers[kind] = locations[importLocationKey(kind, 'list')]
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
export async function newReportFiles(storeId: string, kind: DailyReport, mask: string, since: number) {
  const files = await reportFiles(storeId, kind, mask)
  const fresh = files.filter((file) => file.lastModified > since)
  return {
    newest: files.length > 0 ? files[files.length - 1] : null,
    files: (PERIOD_REPORTS as string[]).includes(kind) ? fresh : fresh.slice(-1),
  }
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
