// Liczby po polsku bez zależności od Intl (Hermes na starszych Androidach ma okrojone locale).

function grouped(value: number, digits: number) {
  const fixed = Math.abs(value).toFixed(digits)
  const [whole, fraction] = fixed.split('.')
  const withSpaces = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
  return `${value < 0 && Number(fixed) !== 0 ? '−' : ''}${withSpaces}${fraction ? `,${fraction}` : ''}`
}

export const money = (value: number | null | undefined) => (value == null ? '–' : `${grouped(value, 2)} zł`)

/** Ilość: całe bez miejsc po przecinku, ułamki do 3 miejsc bez zbędnych zer. */
export function quantity(value: number | null | undefined) {
  if (value == null) return '–'
  if (Number.isInteger(value)) return grouped(value, 0)
  return grouped(value, 3).replace(/,?0+$/, '')
}

export const percent = (value: number | null | undefined) => (value == null ? '–' : `${grouped(value, 1)}%`)

/** RRRR-MM-DD (albo z godziną) → DD.MM.RRRR. */
export function date(iso: string | null | undefined) {
  if (!iso) return '–'
  const [y, m, d] = iso.slice(0, 10).split('-')
  return `${d}.${m}.${y}`
}

export function shortDate(iso: string) {
  const [, m, d] = iso.slice(0, 10).split('-')
  return `${d}.${m}`
}

export function dateTime(iso: string | null | undefined) {
  if (!iso) return '–'
  return `${date(iso)} ${iso.slice(11, 16)}`
}

export function addDays(iso: string, days: number) {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  const value = new Date(Date.UTC(y, m - 1, d + days))
  return value.toISOString().slice(0, 10)
}

export const withoutLeadingZeros = (code: string | null | undefined) => code?.replace(/^0+/, '') ?? ''

/** Marża % od sprzedaży netto, jak w aplikacji. */
export function marginPercent(salePriceGross: number | null, purchaseNet: number | null, vatPercent: number | null) {
  if (salePriceGross == null || purchaseNet == null || vatPercent == null || salePriceGross <= 0) return null
  const net = salePriceGross / (1 + vatPercent / 100)
  return ((net - purchaseNet) / net) * 100
}
