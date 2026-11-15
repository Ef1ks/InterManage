import { listFolderFiles } from '@/lib/saved-folders'
import type { ImportReportKind } from '@/lib/types'

/*
 * Skąd „Odśwież dane” bierze pliki raportów: folder wybrany raz w przeglądarce (File System Access API, uchwyt
 * w IndexedDB) i opcjonalna maska nazwy pliku (w localStorage), osobno dla każdego sklepu i raportu, bo raporty
 * różnych sklepów leżą w różnych folderach, a kilka raportów bywa w jednym (np. Pobrane).
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

const masksKey = (storeId: string) => `intermanage.importMasks.${storeId}`

export type SourceMasks = Partial<Record<DailyReport, string>>

export function readMasks(storeId: string): SourceMasks {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(masksKey(storeId)) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as SourceMasks) : {}
  } catch {
    return {}
  }
}

export function writeMasks(storeId: string, masks: SourceMasks) {
  try {
    localStorage.setItem(masksKey(storeId), JSON.stringify(masks))
  } catch {
    // Bez localStorage maski działają do odświeżenia strony.
  }
}

/*
 * Zapis raportów z Mercalys makrem (odtwarzacz od wersji 46, polecenie EXCELSAVE): pełna ścieżka pliku na każdy raport,
 * osobno dla sklepu, w tej przeglądarce. Makro zapisuje tam skoroszyt otwarty przyciskiem Excel w Mercalys, a „Odśwież
 * dane” bierze go z folderu raportu. Lista promocji jest drukowana tylko do PDF: zapisuje ją PDFSAVE (od wersji 49).
 */
export const MACRO_REPORTS = ['PRODUCTS', 'PRODUCT_CODES', 'PROMOTIONS', 'SALES', 'DOCUMENTS', 'CADENCIER'] as const

export type MacroReport = (typeof MACRO_REPORTS)[number]

export const isMacroReport = (kind: DailyReport): kind is MacroReport =>
  (MACRO_REPORTS as readonly string[]).includes(kind)

const macroPathsKey = (storeId: string) => `intermanage.importMacroPaths.${storeId}`

export type MacroPaths = Partial<Record<MacroReport, string>>

export function readMacroPaths(storeId: string): MacroPaths {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(macroPathsKey(storeId)) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as MacroPaths) : {}
  } catch {
    return {}
  }
}

export function writeMacroPaths(storeId: string, paths: MacroPaths) {
  try {
    localStorage.setItem(macroPathsKey(storeId), JSON.stringify(paths))
  } catch {
    // Bez localStorage ścieżki działają do odświeżenia strony.
  }
}

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
  return null
}

const macroPrintsKey = (storeId: string) => `intermanage.importMacroPrints.${storeId}`

/** Numery wydruków ustawialnych w Mercalys (Baza danych), osobno dla sklepu. */
export type MacroPrintNumbers = Partial<Record<MacroReport, string>>

export function readMacroPrintNumbers(storeId: string): MacroPrintNumbers {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(macroPrintsKey(storeId)) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as MacroPrintNumbers) : {}
  } catch {
    return {}
  }
}

export function writeMacroPrintNumbers(storeId: string, numbers: MacroPrintNumbers) {
  try {
    localStorage.setItem(macroPrintsKey(storeId), JSON.stringify(numbers))
  } catch {
    // Bez localStorage numery działają do odświeżenia strony.
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
  const extensions = REPORT_EXTENSIONS[kind]
  const pattern = maskPattern(mask)
  const handles = await listFolderFiles(sourceFolderKey(storeId, kind), (name) => {
    const dot = name.lastIndexOf('.')
    if (dot < 0 || !extensions.includes(name.slice(dot).toLowerCase())) return false
    // Pliki tymczasowe Excela (~$Raport.xlsx) leżą obok otwartego pliku.
    return !name.startsWith('~$') && pattern.test(name.slice(0, dot))
  })
  let newest: File | null = null
  for (const handle of handles) {
    const file = await handle.getFile().catch(() => null)
    if (file && (!newest || file.lastModified > newest.lastModified)) newest = file
  }
  return newest
}
