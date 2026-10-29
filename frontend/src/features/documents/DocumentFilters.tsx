import { useEffect, useMemo, useState } from 'react'
import { SlidersHorizontalIcon } from 'lucide-react'

import { MultiComboboxField, SegmentedField, TextField, type ComboboxOption } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { type GratisFilter, type HiddenValueRange, type Presence, type ValueField, useSuppliers } from '@/features/documents/api'
import { parseAmount } from '@/features/documents/format'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { useAppDispatch, useAppSelector } from '@/store'
import { hiddenSuppliersKey, persistHiddenDocumentSuppliers, setHiddenDocumentSuppliers } from '@/store/uiSlice'

const NO_SUPPLIERS: string[] = []

/**
 * Dostawcy ukryci na liście dokumentów jednego typu w sklepie (każdy typ ma swoją listę); wybór zostaje
 * w przeglądarce po odświeżeniu strony.
 */
export function useHiddenSuppliers(storeId: string, typeCode: string) {
  const all = useAppSelector((state) => state.ui.hiddenDocumentSuppliers)
  const dispatch = useAppDispatch()
  const key = hiddenSuppliersKey(storeId, typeCode)
  const set = (codes: string[]) => {
    dispatch(setHiddenDocumentSuppliers({ key, codes }))
    persistHiddenDocumentSuppliers({ ...all, [key]: codes })
  }
  return [all[key] ?? NO_SUPPLIERS, set] as const
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

const GRATIS_OPTIONS: { value: GratisFilter; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'candidate', label: 'Może być gratisem' },
  { value: 'with', label: 'Gratisy' },
  { value: 'without', label: 'Nieoznaczone' },
]

const VALUE_FIELD_OPTIONS: { value: ValueField; label: string }[] = [
  { value: 'purchase', label: 'Zakup' },
  { value: 'sale', label: 'Sprzedaż brutto' },
]

const amountText = (value: number | null) => (value === null ? '' : String(value).replace('.', ','))

/** Filtr "ma / nie ma"; {@code null} = niedostępny dla tego typu dokumentów. */
type PresenceFilter = { value: Presence; onChange: (value: Presence) => void } | null

type Props = {
  storeId: string
  invoice: PresenceFilter
  corrections: PresenceFilter
  closed: PresenceFilter
  gratis: { value: GratisFilter; onChange: (value: GratisFilter) => void } | null
  hiddenValues: HiddenValueRange
  onHiddenValuesChange: (range: HiddenValueRange) => void
  hiddenSuppliers: string[]
  onHiddenSuppliersChange: (codes: string[]) => void
  onClear: () => void
}

/**
 * Przycisk "Filtry" nad listą dokumentów i okno z filtrami: faktura, korekty, zamknięcie w raporcie okresowym, gratis
 * (zależnie od typu), ukryty zakres kwot i dostawcy ukryci na liście. Zmiany działają od razu.
 */
export function DocumentFilters({
  storeId,
  invoice,
  corrections,
  closed,
  gratis,
  hiddenValues,
  onHiddenValuesChange,
  hiddenSuppliers,
  onHiddenSuppliersChange,
  onClear,
}: Props) {
  const [open, setOpen] = useState(false)
  const { data: suppliers } = useSuppliers(storeId)
  const active =
    [invoice, corrections, closed, gratis].filter((filter) => filter && filter.value !== 'all').length +
    (hiddenValues.from !== null || hiddenValues.to !== null ? 1 : 0) +
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
            <DialogDescription>Lista zmienia się od razu. Ukryci dostawcy dotyczą tylko tego typu dokumentów i zostają zapamiętani w tej przeglądarce.</DialogDescription>
          </DialogHeader>

          {(invoice || corrections || closed || gratis) && (
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
              {gratis && (
                <FilterRow label="Gratis" hint="Może być gratisem: jeszcze nieoznaczone, z pozycją GRATIS.">
                  <SegmentedField label="Gratis" options={GRATIS_OPTIONS} value={gratis.value} onChange={gratis.onChange} />
                </FilterRow>
              )}
            </div>
          )}

          <ValueRangeFilter value={hiddenValues} onChange={onHiddenValuesChange} />

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

/**
 * Ukrywanie dokumentów z kwotą w podanym przedziale (granice włącznie, np. od -1 do 1 chowa drobne dokumenty).
 * Jedna granica wystarczy: samo "do 0" chowa dokumenty bez wartości i ujemne. Pola trafiają do listy po chwili bez
 * pisania; wyczyszczenie filtrów z zewnątrz czyści też pola.
 */
function ValueRangeFilter({ value, onChange }: { value: HiddenValueRange; onChange: (range: HiddenValueRange) => void }) {
  const [from, setFrom] = useState(() => amountText(value.from))
  const [to, setTo] = useState(() => amountText(value.to))
  const [applied, setApplied] = useState(value)
  // Pola nadążają za zmianą z zewnątrz (np. "Wyczyść filtry"); tekst w trakcie pisania zostaje.
  if (applied.from !== value.from || applied.to !== value.to) {
    setApplied(value)
    if (parseAmount(from) !== value.from) setFrom(amountText(value.from))
    if (parseAmount(to) !== value.to) setTo(amountText(value.to))
  }
  const parsedFrom = parseAmount(useDebouncedValue(from, 400))
  const parsedTo = parseAmount(useDebouncedValue(to, 400))
  useEffect(() => {
    if (parsedFrom === undefined || parsedTo === undefined) return
    if (parsedFrom === value.from && parsedTo === value.to) return
    onChange({ ...value, from: parsedFrom, to: parsedTo })
  }, [parsedFrom, parsedTo, value, onChange])
  const invalid = 'Wpisz kwotę, np. 10 albo -2,50'

  return (
    <div className="grid gap-3">
      <FilterRow label="Ukryj kwoty" hint="Dokumenty z kwotą w tym przedziale znikną z listy i z „Razem”.">
        <SegmentedField
          label="Kwota do ukrycia"
          options={VALUE_FIELD_OPTIONS}
          value={value.field}
          onChange={(field) => onChange({ ...value, field })}
        />
      </FilterRow>
      <div className="grid grid-cols-2 gap-3">
        <TextField
          label="Od zł"
          inputMode="decimal"
          value={from}
          onChange={(event) => setFrom(event.target.value)}
          error={parseAmount(from) === undefined ? invalid : undefined}
        />
        <TextField
          label="Do zł"
          inputMode="decimal"
          value={to}
          onChange={(event) => setTo(event.target.value)}
          error={parseAmount(to) === undefined ? invalid : undefined}
        />
      </div>
    </div>
  )
}
