import { useMemo, useState } from 'react'

import { Form, FormActions, FormNote } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useSuppliers } from '@/features/documents/api'
import { AddSupplierForm, SupplierPicker } from '@/features/documents/SupplierPicker'
import type { Supplier } from '@/lib/types'
import type { DraftInvoice } from '@/store/dataEntryDraftSlice'

type Props = {
  storeId: string
  invoice: DraftInvoice | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onChange: (supplier: Supplier) => void
}

/**
 * Dostawca, dla którego jest dokument: wybór z listy dostawców sklepu zamiast sprzedawcy z faktury. Zmiana usuwa
 * numer faktury z nagłówka pliku XML.
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

  // Obecny dostawca dokumentu: wybrany wcześniej albo sprzedawca z faktury po NIP-ie.
  const current = useMemo(() => {
    if (!suppliers || !invoice) return null
    if (invoice.supplier) return suppliers.find((supplier) => supplier.id === invoice.supplier?.id) ?? null
    const nip = invoice.sellerNip?.replace(/\D/g, '')
    return nip ? (suppliers.find((supplier) => supplier.nip?.replace(/\D/g, '') === nip) ?? null) : null
  }, [suppliers, invoice])
  const selectedId = picked ?? current?.id ?? null
  const selected = suppliers?.find((supplier) => supplier.id === selectedId) ?? null

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
        if (selected) onChange(selected)
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">Zmień dostawcę dokumentu</DialogTitle>
        <DialogDescription>
          Dostawca trafia do nagłówka pliku XML (nazwa, NIP) i do nazwy pliku (kod dostawcy).
        </DialogDescription>
      </DialogHeader>

      <SupplierPicker
        storeId={storeId}
        selectedId={selectedId}
        onSelect={setPicked}
        suggested={current}
        onAdd={canAdd ? setAdding : undefined}
      />

      {invoice?.number && (
        <FormNote>
          Zmiana dostawcy usuwa numer faktury {invoice.number} z nagłówka. Ctrl+Z albo „Cofnij” przywraca poprzedniego
          dostawcę razem z numerem.
        </FormNote>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" disabled={!selected || selected.id === current?.id}>
          Zmień dostawcę
        </Button>
      </FormActions>
    </Form>
  )
}
