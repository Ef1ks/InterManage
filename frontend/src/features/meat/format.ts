const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

export function formatPrice(value: number | null | undefined) {
  return value == null ? '–' : money.format(value)
}

export function formatPercent(value: number | null | undefined) {
  return value == null ? '–' : `${percent.format(value * 100)}%`
}

/** Cena wpisana przez użytkownika: przecinek albo kropka, najwyżej 4 miejsca po przecinku, więcej niż zero. */
export function parsePrice(raw: string): number | null {
  const text = raw.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,4})?$/.test(text)) return null
  const value = Number(text)
  return value > 0 ? value : null
}

/** Marża jak w całej aplikacji: od sprzedaży netto (brutto bez VAT); null, gdy brakuje którejś ceny. */
export function margin(purchase: number | null, saleGross: number | null, vatPercent: number): number | null {
  if (purchase == null || saleGross == null || saleGross <= 0) return null
  const saleNet = saleGross / (1 + vatPercent / 100)
  return (saleNet - purchase) / saleNet
}

const MAX_IMAGE_SIDE = 2400

/**
 * Zdjęcie z aparatu telefonu (często 4000 px i kilka MB) zmniejszone do 2400 px po dłuższym boku jako JPEG:
 * tekst WZ zostaje czytelny, a wysyłka i odczyt są szybsze. PDF i małe obrazy idą bez zmian.
 */
export async function prepareScan(file: File): Promise<File> {
  if (!file.type.startsWith('image/') || file.type === 'image/gif') return file
  try {
    const bitmap = await createImageBitmap(file)
    const scale = Math.min(1, MAX_IMAGE_SIDE / Math.max(bitmap.width, bitmap.height))
    if (scale === 1 && file.size < 3 * 1024 * 1024 && file.type !== 'image/heic') {
      bitmap.close()
      return file
    }
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bitmap.width * scale)
    canvas.height = Math.round(bitmap.height * scale)
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85))
    if (!blob) return file
    const name = file.name.replace(/\.[^.]+$/, '') || 'wz'
    return new File([blob], `${name}.jpg`, { type: 'image/jpeg' })
  } catch {
    return file
  }
}
