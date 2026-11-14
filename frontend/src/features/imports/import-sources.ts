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
