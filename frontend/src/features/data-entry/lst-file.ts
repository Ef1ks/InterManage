import { todayIso } from '@/lib/dates'
import type { SavedFolderSpec } from '@/lib/saved-folders'

/** Folder plików .lst z wprowadzania danych, wybrany raz w przeglądarce (Ustawienia zapisu). */
export const LST_FOLDER: SavedFolderSpec = { key: 'data-entry-lst', label: 'Lista EAN (plik .lst)' }

/**
 * Plik .LST: EAN w każdej linii, CRLF między liniami, ale bez końca linii po ostatnim kodzie, bo program sklepu takiej
 * listy nie wczytuje (jak lista EAN-ów zamówienia garmażu).
 */
export function lstFile(eans: string[]) {
  return new Blob([eans.join('\r\n')], { type: 'text/plain' })
}

/** Nazwa pliku: rodzaj listy i numer dokumentu, a bez numeru dzisiejsza data. */
export function lstFileName(prefix: string, documentNumber: string | null | undefined) {
  return `${prefix}_${(documentNumber || todayIso()).replace(/[^\p{L}\d-]+/gu, '_')}.LST`
}

/** Kody z pliku .lst: pierwsza kolumna każdej linii, puste linie pominięte. */
export function parseLst(text: string) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim().split(/[\s;,]+/)[0])
    .filter(Boolean)
}
