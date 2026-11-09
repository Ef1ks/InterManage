import { todayIso } from '@/lib/dates'
import type { SavedFolderSpec } from '@/lib/saved-folders'

/** Folder plików .lst z wprowadzania danych, wybrany raz w przeglądarce (Ustawienia zapisu). */
export const LST_FOLDER: SavedFolderSpec = { key: 'data-entry-lst', label: 'Lista EAN (plik .lst)' }

/**
 * Plik .LST 1:1 jak plik z Impeka (`backend/ImpEklst.lst`): nagłówek `E` + liczba kodów na 7 cyfrach (`E0000025`),
 * potem EAN na 13 znakach (dopełniony zerami) w każdej linii, CRLF po każdej linii, także po ostatniej.
 */
export function lstFile(eans: string[]) {
  const lines = [`E${String(eans.length).padStart(7, '0')}`, ...eans.map((ean) => ean.padStart(13, '0'))]
  return new Blob([lines.map((line) => `${line}\r\n`).join('')], { type: 'text/plain' })
}

/** Nazwa pliku: rodzaj listy i numer dokumentu, a bez numeru dzisiejsza data. */
export function lstFileName(prefix: string, documentNumber: string | null | undefined) {
  return `${prefix}_${(documentNumber || todayIso()).replace(/[^\p{L}\d-]+/gu, '_')}.LST`
}

/** Kody z pliku .lst: pierwsza kolumna każdej linii, puste linie i nagłówek `E0000025` pominięte. */
export function parseLst(text: string) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim().split(/[\s;,]+/)[0])
    .filter((code) => Boolean(code) && !/^E\d+$/i.test(code))
}
