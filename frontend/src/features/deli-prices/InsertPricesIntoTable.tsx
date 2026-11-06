import { useState } from 'react'
import { SheetIcon } from 'lucide-react'
import { useStore } from 'react-redux'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { lookupProducts } from '@/features/data-entry/api'
import { type DataEntryRow, emptyRow, withScannedProduct, withTrailingEmptyRow } from '@/features/data-entry/rows'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import type { DataEntryCatalogProduct } from '@/lib/types'
import { type RootState, useAppDispatch } from '@/store'
import { EMPTY_DATA_ENTRY, setDataEntryDraft, setDataEntrySaved } from '@/store/dataEntryDraftSlice'

export type PriceLine = { ean: string; salePrice: number }

const filled = (row: DataEntryRow) => row.ean !== '' || row.name !== ''

/**
 * Wiersze jak po wpisaniu EAN-u, z ceną sprzedaży z kalkulacji. `catalog` zostaje z kartoteki, więc tabela widzi zmianę
 * ceny i „Zmień ceny” wystawia z niej plik. Ilość na sztywno, bo kod z etykiety wagi przeliczałby ją z ceny.
 */
function rowsFromPrices(lines: PriceLine[], products: DataEntryCatalogProduct[]): DataEntryRow[] {
  const byEan = new Map(products.map((product) => [product.ean, product]))
  return lines.map(({ ean, salePrice }) => ({
    ...withScannedProduct({ ...emptyRow(), ean }, byEan.get(ean.replace(/^0+/, '')) ?? null),
    quantity: 1,
    salePrice,
  }))
}

/** Produkty ze zmienioną ceną trafiają do tabeli wprowadzania danych, gdzie „Zmień ceny” wystawia plik zmian cen. */
export function InsertPricesIntoTable({ storeId, lines }: { storeId: string; lines: PriceLine[] }) {
  const canEnterData = useCan('DATA_ENTRY')
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const navigate = useNavigate()
  const [asking, setAsking] = useState(false)
  const [inserting, setInserting] = useState(false)

  if (!canEnterData || lines.length === 0) return null

  const insert = async (mode: 'replace' | 'append') => {
    setAsking(false)
    setInserting(true)
    try {
      const rows = rowsFromPrices(lines, await lookupProducts(storeId, [...new Set(lines.map((line) => line.ean))]))
      const state = reduxStore.getState()
      const before = state.dataEntryDraft.drafts[storeId] ?? EMPTY_DATA_ENTRY
      const beforeSaved = state.dataEntryDraft.saved[storeId] ?? null
      const kept = mode === 'append' ? before.rows.filter(filled) : []
      if (mode === 'replace') dispatch(setDataEntrySaved({ storeId, saved: null }))
      dispatch(
        setDataEntryDraft({
          storeId,
          draft: {
            invoice: mode === 'append' ? before.invoice : null,
            deliOrderId: mode === 'append' ? before.deliOrderId : null,
            rows: withTrailingEmptyRow([...kept, ...rows]),
          },
        }),
      )
      navigate(TOOL_ROUTES.DATA_ENTRY.path)
      toast.success('Zmienione ceny w tabeli', {
        description: `Produkty: ${rows.length}. Wskaż dostawcę i kliknij „Zmień ceny”, żeby wystawić plik.`,
        action: before.rows.some(filled)
          ? {
              label: 'Przywróć',
              onClick: () => {
                dispatch(setDataEntryDraft({ storeId, draft: before }))
                dispatch(setDataEntrySaved({ storeId, saved: beforeSaved }))
              },
            }
          : undefined,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wstawić produktów do tabeli')
    } finally {
      setInserting(false)
    }
  }

  const start = () => {
    const draft = reduxStore.getState().dataEntryDraft.drafts[storeId]
    if (draft?.rows.some(filled)) setAsking(true)
    else void insert('replace')
  }

  return (
    <>
      <Button
        variant="outline"
        loading={inserting}
        onClick={start}
        title="Produkty, których cena różni się od ceny na półce"
      >
        <SheetIcon />
        Do wprowadzania danych ({lines.length})
      </Button>
      <AlertDialog open={asking} onOpenChange={setAsking}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>W tabeli są już wiersze</AlertDialogTitle>
            <AlertDialogDescription>
              Zastąpić je produktami ze zmienioną ceną czy dopisać produkty na końcu?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction variant="outline" onClick={() => void insert('append')}>
              Dopisz
            </AlertDialogAction>
            <AlertDialogAction onClick={() => void insert('replace')}>Zastąp</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
