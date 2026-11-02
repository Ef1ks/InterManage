import type { Supplier } from '@/lib/types'
import type { DraftInvoice } from '@/store/dataEntryDraftSlice'

/** Dostawca dokumentu z listy sklepu: wybrany w „Zmień dostawcę” albo sprzedawca z faktury po NIP-ie (jak w pliku XML). */
export function documentSupplier(suppliers: Supplier[] | undefined, invoice: DraftInvoice | null | undefined) {
  if (!suppliers || !invoice) return null
  if (invoice.supplier) return suppliers.find((supplier) => supplier.id === invoice.supplier?.id) ?? null
  const nip = invoice.sellerNip?.replace(/\D/g, '')
  return nip ? (suppliers.find((supplier) => supplier.nip?.replace(/\D/g, '') === nip) ?? null) : null
}
