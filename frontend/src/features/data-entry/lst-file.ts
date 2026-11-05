import { todayIso } from '@/lib/dates'
import type { SavedFolderSpec } from '@/lib/saved-folders'

/** Folder plików .lst z wprowadzania danych, wybrany raz w przeglądarce (Ustawienia zapisu). */
export const LST_FOLDER: SavedFolderSpec = { key: 'data-entry-lst', label: 'Lista EAN (plik .lst)' }

/** Plik .lst: EAN w każdej linii, CRLF (jak lista EAN-ów zamówienia garmażu). */
export function lstFile(eans: string[]) {
  return new Blob([eans.map((ean) => `${ean}\r\n`).join('')], { type: 'text/plain' })
}

/** Nazwa pliku: rodzaj listy i numer dokumentu, a bez numeru dzisiejsza data. */
export function lstFileName(prefix: string, documentNumber: string | null | undefined) {
  return `${prefix}_${(documentNumber || todayIso()).replace(/[^\p{L}\d-]+/gu, '_')}.lst`
}

/** Kody z pliku .lst: pierwsza kolumna każdej linii, puste linie pominięte. */
export function parseLst(text: string) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim().split(/[\s;,]+/)[0])
    .filter(Boolean)
}
