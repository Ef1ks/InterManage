import { type ComponentProps, type ReactNode, useState } from 'react'
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
import { type DataEntryRow, withTrailingEmptyRow } from '@/features/data-entry/rows'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import type { DataEntryCatalogProduct } from '@/lib/types'
import { type RootState, useAppDispatch } from '@/store'
import { EMPTY_DATA_ENTRY, setDataEntryDraft, setDataEntrySaved } from '@/store/dataEntryDraftSlice'

const filled = (row: DataEntryRow) => row.ean !== '' || row.name !== ''

type Props = {
  storeId: string
  /** EAN-y artykułów do sprawdzenia w kartotece (jak po wpisaniu w tabeli). */
  eans: string[]
  /** Wiersze tabeli z artykułów z kartoteki (po EAN-ie bez zer wiodących). */
  buildRows: (byEan: Map<string, DataEntryCatalogProduct>) => DataEntryRow[]
  label: ReactNode
  title?: string
  successTitle: string
  successDescription: (count: number) => string
  /** Pytanie przy pełnej tabeli: czym ją zastąpić albo co dopisać. */
  askDescription: string
  variant?: ComponentProps<typeof Button>['variant']
  size?: ComponentProps<typeof Button>['size']
}

/**
 * Przycisk, który wstawia artykuły do tabeli wprowadzania danych i przechodzi do niej. Przy pełnej tabeli pyta
 * Zastąp / Dopisz, a toast „Przywróć” oddaje poprzednią tabelę. Bez `DATA_ENTRY` przycisku nie ma.
 */
export function InsertIntoDataEntry({
  storeId,
  eans,
  buildRows,
  label,
  title,
  successTitle,
  successDescription,
  askDescription,
  variant = 'outline',
  size,
}: Props) {
  const canEnterData = useCan('DATA_ENTRY')
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const navigate = useNavigate()
  const [asking, setAsking] = useState(false)
  const [inserting, setInserting] = useState(false)

  if (!canEnterData || eans.length === 0) return null

  const insert = async (mode: 'replace' | 'append') => {
    setAsking(false)
    setInserting(true)
    try {
      const products = await lookupProducts(storeId, [...new Set(eans)])
      const rows = buildRows(new Map(products.map((product) => [product.ean, product])))
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
      toast.success(successTitle, {
        description: successDescription(rows.length),
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
      <Button variant={variant} size={size} loading={inserting} onClick={start} title={title}>
        <SheetIcon />
        {label}
      </Button>
      <AlertDialog open={asking} onOpenChange={setAsking}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>W tabeli są już wiersze</AlertDialogTitle>
            <AlertDialogDescription>{askDescription}</AlertDialogDescription>
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
