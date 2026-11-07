import { useMemo, useState } from 'react'

import { Form, FormActions, FormNote, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useSuppliers } from '@/features/documents/api'
import { AddSupplierForm, SupplierPicker } from '@/features/documents/SupplierPicker'
import type { Supplier } from '@/lib/types'
import type { DraftInvoice } from '@/store/dataEntryDraftSlice'

import { documentSupplier } from './document-supplier'

type Props = {
  storeId: string
  invoice: DraftInvoice | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onChange: (supplier: Supplier, number: string | null) => void
}

/**
 * Dostawca i numer dokumentu: wybór z listy dostawców sklepu zamiast sprzedawcy z faktury. Numer faktury znika z nagłówka
 * pliku XML, a numer wpisuje się tutaj; pusty nadaje serwer z czasu zapisu.
 */
export function SupplierChangeDialog({ open, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        {open && <SupplierChangeBody {...props} onCancel={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function SupplierChangeBody({
  storeId,
  invoice,
  onChange,
  onCancel,
}: Omit<Props, 'open' | 'onOpenChange'> & { onCancel: () => void }) {
  const { data: suppliers } = useSuppliers(storeId)
  const canAddSupplier = useCan('SUPPLIERS', 'EDIT')
  const canAddInDocuments = useCan('DOCUMENTS', 'EDIT')
  const canAdd = canAddSupplier || canAddInDocuments
  const [adding, setAdding] = useState<string | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  // Numer faktury należy do sprzedawcy, więc w polu jest tylko numer wpisany przy wybranym dostawcy.
  const ownNumber = invoice?.supplier ? (invoice.number ?? '') : ''
  const [number, setNumber] = useState(ownNumber)

  const current = useMemo(() => documentSupplier(suppliers, invoice), [suppliers, invoice])
  const selectedId = picked ?? current?.id ?? null
  const selected = suppliers?.find((supplier) => supplier.id === selectedId) ?? null
  const supplierChanged = selected?.id !== current?.id
  const typedNumber = number.trim()

  if (adding != null) {
    return (
      <AddSupplierForm
        storeId={storeId}
        initialName={adding}
        onAdded={(supplier) => {
          setPicked(supplier.id)
          setAdding(null)
        }}
        onBack={() => setAdding(null)}
      />
    )
  }

  return (
    <Form
      onSubmit={(event) => {
        event.preventDefault()
        if (selected) onChange(selected, typedNumber || null)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">Dostawca i numer dokumentu</DialogTitle>
        <DialogDescription>
          Dostawca trafia do nagłówka pliku XML (nazwa, NIP) i do nazwy pliku (kod dostawcy), a numer do nagłówka i
          nazwy pliku.
        </DialogDescription>
      </DialogHeader>

      <SupplierPicker
        storeId={storeId}
        selectedId={selectedId}
        onSelect={setPicked}
        suggested={current}
        onAdd={canAdd ? setAdding : undefined}
      />

      <TextField
        label="Numer dokumentu"
        value={number}
        maxLength={100}
        autoComplete="off"
        onChange={(event) => setNumber(event.target.value)}
        hint="Puste pole: numer z czasu zapisu (WPR/…)."
      />

      {invoice?.number && !invoice.supplier && (
        <FormNote>
          Wybrany dostawca zastępuje numer faktury {invoice.number} numerem z tego pola. Ctrl+Z albo „Cofnij” przywraca
          poprzedniego dostawcę razem z numerem.
        </FormNote>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" disabled={!selected || (!supplierChanged && typedNumber === ownNumber)}>
          {supplierChanged ? 'Zmień dostawcę' : 'Zapisz numer'}
        </Button>
      </FormActions>
    </Form>
  )
}
