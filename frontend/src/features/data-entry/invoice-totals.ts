import { formatMoney } from '@/features/documents/format'
import type { DraftInvoice } from '@/store/dataEntryDraftSlice'

import { type DataEntryRow, netValue } from './rows'

/** Różnica do 2 gr to zaokrąglenia cen jednostkowych, nie błąd ilości. */
export const TOTALS_TOLERANCE = 0.02

const round2 = (value: number) => Math.round((value + Number.EPSILON) * 100) / 100

/**
 * Sumy tabeli (wszystkie wiersze, z kaucjami i dostawą, bo są na fakturze) i, gdy jest faktura z sumami, różnice
 * tabela − faktura. Program sklepu nie przyjmie dokumentu z błędną ilością, więc różnicę widać od razu nad tabelą,
 * a zapis pliku XML o nią pyta (nie blokuje).
 */
export type InvoiceTotals = {
  net: number
  vat: number
  invoiceNet: number | null
  invoiceVat: number | null
  netDiff: number | null
  vatDiff: number | null
  mismatch: boolean
}

export function invoiceTotals(rows: DataEntryRow[], invoice: DraftInvoice | null): InvoiceTotals {
  let net = 0
  // VAT liczony od sumy netto w każdej stawce, jak w podsumowaniu faktury.
  const byRate = new Map<number, number>()
  for (const row of rows) {
    const value = netValue(row)
    if (value === null) continue
    net += value
    if (row.vatPercent !== null) byRate.set(row.vatPercent, (byRate.get(row.vatPercent) ?? 0) + value)
  }
  const vat = [...byRate].reduce((sum, [rate, value]) => sum + round2((value * rate) / 100), 0)
  const invoiceNet = invoice?.netTotal ?? null
  const invoiceVat = invoice?.vatTotal ?? null
  const netDiff = invoiceNet === null ? null : round2(net - invoiceNet)
  const vatDiff = invoiceVat === null ? null : round2(vat - invoiceVat)
  return {
    net: round2(net),
    vat: round2(vat),
    invoiceNet,
    invoiceVat,
    netDiff,
    vatDiff,
    mismatch: differs(netDiff) || differs(vatDiff),
  }
}

export const differs = (diff: number | null) => diff !== null && Math.abs(diff) > TOTALS_TOLERANCE

/** Suma dwóch faktur w jednej tabeli (dopisanie); brak sumy w którejkolwiek daje brak. */
export const addTotals = (a: number | null | undefined, b: number | null) =>
  a === null || a === undefined || b === null ? null : round2(a + b)

/** Różnica ze znakiem (+ tabela ma więcej niż faktura). */
export const signedMoney = (value: number) => `${value > 0 ? '+' : ''}${formatMoney(value)}`
