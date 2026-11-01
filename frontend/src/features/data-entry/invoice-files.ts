import { useState } from 'react'
import { toast } from 'sonner'

import { ApiError, downloadFile, printFile } from '@/lib/api'

import { invoicePdfPath, type InvoicePrintVariant, useRefreshDataEntryInvoices } from './api'

const VARIANT_KEY = 'intermanage.dataEntryPrintVariant'

export const PRINT_VARIANT_LABELS: Record<InvoicePrintVariant, string> = {
  BASIC: 'podstawowa',
  FULL: 'pełna',
}

function storedVariant(): InvoicePrintVariant {
  try {
    return localStorage.getItem(VARIANT_KEY) === 'FULL' ? 'FULL' : 'BASIC'
  } catch {
    return 'BASIC'
  }
}

/**
 * Wydruk i pobranie faktury z okna wczytywania i z tabeli. Domyślnie wydruk podstawowy (pozycje, opis, dokumenty
 * WZ); wybrany wariant zostaje w przeglądarce. Pobranie i wydruk dają fakturze status "pobrana" (zapisuje serwer).
 */
export function useInvoiceFiles(storeId: string) {
  const refresh = useRefreshDataEntryInvoices(storeId)
  const [variant, setVariantState] = useState<InvoicePrintVariant>(storedVariant)
  const [busy, setBusy] = useState<'print' | 'download' | null>(null)

  const setVariant = (value: InvoicePrintVariant) => {
    setVariantState(value)
    try {
      localStorage.setItem(VARIANT_KEY, value)
    } catch {
      // Tryb prywatny: wariant obowiązuje do odświeżenia strony.
    }
  }

  const run = async (kind: 'print' | 'download', invoiceId: string) => {
    setBusy(kind)
    try {
      const path = invoicePdfPath(storeId, invoiceId, variant)
      if (kind === 'print') {
        await printFile(path)
      } else {
        await downloadFile(path, 'faktura.pdf')
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać faktury')
    } finally {
      setBusy(null)
      void refresh()
    }
  }

  return {
    variant,
    setVariant,
    busy,
    print: (invoiceId: string) => run('print', invoiceId),
    download: (invoiceId: string) => run('download', invoiceId),
  }
}
