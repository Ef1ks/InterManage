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
import { lookupProducts } from '@/features/data-entry/api'
import { rowsFromOrder } from '@/features/data-entry/deli-order-rows'
import { type DataEntryRow, withTrailingEmptyRow } from '@/features/data-entry/rows'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import type { DeliOrder, DeliOrderItem } from '@/lib/types'
import { type RootState, useAppDispatch } from '@/store'
import { EMPTY_DATA_ENTRY, setDataEntryDraft, setDataEntrySaved } from '@/store/dataEntryDraftSlice'

type Props = {
  storeId: string
  order: DeliOrder
  /** Pozycje z ilościami ze strony zamówienia, także niezapisanymi poprawkami. */
  lines: { item: DeliOrderItem; quantity: number }[]
  disabled?: boolean
}

const filled = (row: DataEntryRow) => row.ean !== '' || row.name !== ''

/**
 * Pozycje zamówienia trafiają do tabeli wprowadzania danych, gdzie dochodzi towar jadący razem z garmażem. Pasek nad
 * tabelą kończy potem zamówienie ilościami z tabeli (`DeliOrderBanner`).
 */
export function InsertOrderIntoTable({ storeId, order, lines, disabled }: Props) {
  const dispatch = useAppDispatch()
  const reduxStore = useStore<RootState>()
  const navigate = useNavigate()
  const [asking, setAsking] = useState(false)
  const [inserting, setInserting] = useState(false)

  const insert = async (mode: 'replace' | 'append') => {
    setAsking(false)
    setInserting(true)
    try {
      const eans = [...new Set(lines.map(({ item }) => item.ean).filter(Boolean))]
      const rows = rowsFromOrder(lines, await lookupProducts(storeId, eans))
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
            deliOrderId: order.id,
            rows: withTrailingEmptyRow([...kept, ...rows]),
          },
        }),
      )
      navigate(TOOL_ROUTES.DATA_ENTRY.path)
      toast.success('Zamówienie w tabeli', {
        description: `Pozycje: ${rows.length}. Dopisz towar, który jedzie razem z garmażem, i potwierdź zakończenie w pasku nad tabelą.`,
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
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wstawić zamówienia do tabeli')
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
        size="xl"
        className="max-lg:hidden"
        loading={inserting}
        disabled={disabled}
        onClick={start}
      >
        <SheetIcon />
        Wstaw do tabeli
      </Button>
      <AlertDialog open={asking} onOpenChange={setAsking}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>W tabeli są już wiersze</AlertDialogTitle>
            <AlertDialogDescription>
              Zastąpić je pozycjami zamówienia czy dopisać pozycje na końcu? Przy zakończeniu zamówienia towary spoza
              niego zostaną pominięte.
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
