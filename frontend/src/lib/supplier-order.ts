/**
 * Porządek dostawców w filtrach: po numerze (kod jako liczba, 52 przed 538), dostawcy bez numeru na końcu
 * po nazwie.
 */
export function compareSupplierCodes(codeA: string | null | undefined, codeB: string | null | undefined): number {
  const a = codeA?.trim() ? Number(codeA) : NaN
  const b = codeB?.trim() ? Number(codeB) : NaN
  if (Number.isNaN(a) !== Number.isNaN(b)) return Number.isNaN(a) ? 1 : -1
  if (!Number.isNaN(a) && a !== b) return a - b
  return (codeA ?? '').localeCompare(codeB ?? '', 'pl', { numeric: true })
}
