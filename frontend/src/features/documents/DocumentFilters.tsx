import { useMemo, useState } from 'react'
import { SlidersHorizontalIcon } from 'lucide-react'

import { MultiComboboxField, SegmentedField, type ComboboxOption } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { type Presence, useSuppliers } from '@/features/documents/api'
import { useAppDispatch, useAppSelector } from '@/store'
import { persistHiddenDocumentSuppliers, setHiddenDocumentSuppliers } from '@/store/uiSlice'

const NO_SUPPLIERS: string[] = []

/** Dostawcy ukryci na listach dokumentów sklepu; wybór zostaje w przeglądarce po odświeżeniu strony. */
export function useHiddenSuppliers(storeId: string) {
  const all = useAppSelector((state) => state.ui.hiddenDocumentSuppliers)
  const dispatch = useAppDispatch()
  const set = (codes: string[]) => {
    dispatch(setHiddenDocumentSuppliers({ storeId, codes }))
    persistHiddenDocumentSuppliers({ ...all, [storeId]: codes })
  }
  return [all[storeId] ?? NO_SUPPLIERS, set] as const
}

const INVOICE_OPTIONS: { value: Presence; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'with', label: 'Z fakturą' },
  { value: 'without', label: 'Bez faktury' },
]

const CORRECTION_OPTIONS: { value: Presence; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'with', label: 'Z korektami' },
  { value: 'without', label: 'Bez korekt' },
]

const CLOSED_OPTIONS: { value: Presence; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'without', label: 'Niezakończone' },
  { value: 'with', label: 'Zakończone' },
]

/** Filtr "ma / nie ma"; {@code null} = niedostępny dla tego typu dokumentów. */
type PresenceFilter = { value: Presence; onChange: (value: Presence) => void } | null

type Props = {
  storeId: string
  invoice: PresenceFilter
  corrections: PresenceFilter
  closed: PresenceFilter
  hiddenSuppliers: string[]
  onHiddenSuppliersChange: (codes: string[]) => void
  onClear: () => void
}

/**
 * Przycisk "Filtry" nad listą dokumentów i okno z filtrami: faktura, korekty, zamknięcie w raporcie okresowym
 * (zależnie od typu) i dostawcy ukryci na liście. Zmiany działają od razu.
 */
export function DocumentFilters({
  storeId,
  invoice,
  corrections,
  closed,
  hiddenSuppliers,
  onHiddenSuppliersChange,
  onClear,
}: Props) {
  const [open, setOpen] = useState(false)
  const { data: suppliers } = useSuppliers(storeId)
  const active =
    [invoice, corrections, closed].filter((filter) => filter && filter.value !== 'all').length +
    (hiddenSuppliers.length > 0 ? 1 : 0)

  const options = useMemo<ComboboxOption[]>(() => {
    const listed = (suppliers ?? [])
      .filter((supplier) => supplier.code !== null)
      .map((supplier) => ({
        value: supplier.code!,
        label: supplier.name ? `${supplier.code} ${supplier.name}` : supplier.code!,
        hint: [supplier.city, supplier.nip && `NIP ${supplier.nip}`].filter(Boolean).join(' · ') || undefined,
      }))
    // Ukryty dostawca, którego nie ma już na liście dostawców, też musi mieć swoją etykietę.
    const missing = hiddenSuppliers
      .filter((code) => !listed.some((option) => option.value === code))
      .map((code) => ({ value: code, label: code }))
    return [...missing, ...listed]
  }, [suppliers, hiddenSuppliers])

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <SlidersHorizontalIcon />
        Filtry
        {active > 0 && <Badge variant="secondary">{active}</Badge>}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-xl">
          <DialogHeader>
            <DialogTitle className="text-lg">Filtry listy</DialogTitle>
            <DialogDescription>Lista zmienia się od razu. Ukryci dostawcy zostają zapamiętani w tej przeglądarce.</DialogDescription>
          </DialogHeader>

          {(invoice || corrections || closed) && (
            <div className="grid gap-4">
              {closed && (
                <FilterRow label="Raport okresowy" hint="Zakończone poszły w zamkniętym raporcie za okres.">
                  <SegmentedField label="Raport okresowy" options={CLOSED_OPTIONS} value={closed.value} onChange={closed.onChange} />
                </FilterRow>
              )}
              {invoice && (
                <FilterRow label="Faktura">
                  <SegmentedField label="Faktura" options={INVOICE_OPTIONS} value={invoice.value} onChange={invoice.onChange} />
                </FilterRow>
              )}
              {corrections && (
                <FilterRow label="Korekty">
                  <SegmentedField
                    label="Przypięte korekty"
                    options={CORRECTION_OPTIONS}
                    value={corrections.value}
                    onChange={corrections.onChange}
                  />
                </FilterRow>
              )}
            </div>
          )}

          <MultiComboboxField
            label="Ukryci dostawcy"
            description="Ich dokumentów nie widać na liście ani w sumie „Razem”."
            options={options}
            value={hiddenSuppliers}
            placeholder={suppliers ? 'Wszyscy dostawcy widoczni. Szukaj po kodzie albo nazwie…' : 'Wczytywanie…'}
            emptyText="Żaden dostawca nie pasuje"
            onChange={onHiddenSuppliersChange}
          />

          <div className="flex flex-wrap items-center justify-between gap-3">
            <Button variant="link" className="h-auto p-0 text-sm" disabled={active === 0} onClick={onClear}>
              Wyczyść filtry
            </Button>
            <Button onClick={() => setOpen(false)}>Gotowe</Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

function FilterRow({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div>
        <p className="text-sm font-medium">{label}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </div>
      {children}
    </div>
  )
}
