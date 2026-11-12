/**
 * Numer karty (UID) w postaci, której używa backend: wielkie litery szesnastkowe bez separatorów, kolejność bajtów
 * jak w NFC telefonu (`04A2B3C4`). Czytnik USB działa jak klawiatura i często wypisuje UID dziesiętnie, zwykle jako
 * liczbę z bajtów odwróconych (10 cyfr dla 4 bajtów), więc przed wysłaniem trzeba go przeliczyć, inaczej karta
 * przypisana telefonem nie odbije się na tablecie.
 */
export type UsbFormat = 'AUTO' | 'HEX' | 'DEC_REVERSED' | 'DEC'

export const USB_FORMATS: { value: UsbFormat; label: string; hint: string }[] = [
  { value: 'AUTO', label: 'Automatycznie', hint: '10 cyfr = dziesiętny odwrócony, z literami A-F = szesnastkowy' },
  { value: 'HEX', label: 'Szesnastkowy', hint: 'np. 04A2B3C4' },
  { value: 'DEC_REVERSED', label: 'Dziesiętny, bajty odwrócone', hint: 'np. 3300106756 (najczęstszy, 10 cyfr)' },
  { value: 'DEC', label: 'Dziesiętny', hint: 'np. 77771716' },
]

/** UID z NFC telefonu albo wpisany ręcznie: same znaki szesnastkowe, wielkie litery. */
export function normalizeUid(raw: string) {
  return raw.replace(/[^0-9a-f]/gi, '').toUpperCase()
}

/** Tekst z czytnika USB (bez Entera) zamieniony na UID; `null`, gdy nie wygląda na numer karty. */
export function uidFromUsb(raw: string, format: UsbFormat): string | null {
  const text = raw.trim()
  if (!text) return null
  const digitsOnly = /^\d+$/.test(text)
  const resolved: UsbFormat = format !== 'AUTO' ? format : digitsOnly && text.length === 10 ? 'DEC_REVERSED' : 'HEX'
  if (resolved === 'HEX') {
    const hex = normalizeUid(text)
    return hex.length >= 8 && hex.length % 2 === 0 ? hex : null
  }
  if (!digitsOnly) return null
  let hex = BigInt(text).toString(16).toUpperCase()
  // 4-bajtowy UID MIFARE to zawsze 8 znaków, dłuższe dopełniamy do pełnych bajtów.
  const length = Math.max(8, hex.length + (hex.length % 2))
  hex = hex.padStart(length, '0')
  if (resolved === 'DEC') return hex
  const bytes = hex.match(/../g) ?? []
  return bytes.reverse().join('')
}
