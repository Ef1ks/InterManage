import { ArrowDownWideNarrowIcon, ArrowUpNarrowWideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

import { MultiComboboxField, SegmentedField, SelectField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import {
  activeFilterCount,
  type CadencierFilter,
  EMPTY_FILTERS,
  type ProductFilters,
  type ProductSort,
  type StockSign,
} from '@/features/inventory/filters'
import { useSalesNomenclatures } from '@/features/sales/api'
import { displayCode, LEVEL_STYLES } from '@/features/sales/nomenclature-levels'

const SORTS: { value: ProductSort; label: string }[] = [
  { value: 'name', label: 'Nazwa' },
  { value: 'stock', label: 'Stan' },
  { value: 'rotation', label: 'Rotacja' },
  { value: 'price', label: 'Cena sprzedaży' },
  { value: 'itm8', label: 'ITM8' },
]

const STOCK_SIGNS: { value: StockSign; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'positive', label: 'Na stanie' },
  { value: 'zero', label: 'Zero' },
  { value: 'negative', label: 'Ujemny' },
]

const CADENCIER: { value: CadencierFilter; label: string }[] = [
  { value: 'all', label: 'Wszystkie' },
  { value: 'with', label: 'W cadencier' },
  { value: 'without', label: 'Poza' },
]

/**
 * Rozwijany panel filtrów nad listą artykułów: sortowanie z kierunkiem, stan (znak i przedział), rotacja, nomenklatura
 * i obecność na liście cadencier. Każda zmiana od razu zawęża listę (zapis w adresie).
 */
export function ProductFiltersPanel({
  storeId,
  filters,
  onChange,
}: {
  storeId: string
  filters: ProductFilters
  onChange: (filters: ProductFilters) => void
}) {
  const nomenclatures = useSalesNomenclatures(storeId)
  const update = (patch: Partial<ProductFilters>) => onChange({ ...filters, ...patch })

  return (
    <div className="grid gap-4 border-b bg-muted/40 p-3">
      <Group label="Sortuj">
        <div className="flex gap-2">
          <SelectField
            label="Sortuj według"
            options={SORTS}
            value={filters.sort}
            onChange={(value) => update({ sort: (value || 'name') as ProductSort })}
            className="min-w-0 flex-1"
          />
          <Button
            variant="outline"
            className="h-auto shrink-0 self-stretch"
            aria-label={filters.direction === 'asc' ? 'Rosnąco, zmień na malejąco' : 'Malejąco, zmień na rosnąco'}
            onClick={() => update({ direction: filters.direction === 'asc' ? 'desc' : 'asc' })}
          >
            {filters.direction === 'asc' ? <ArrowUpNarrowWideIcon /> : <ArrowDownWideNarrowIcon />}
            {filters.direction === 'asc' ? 'Rosnąco' : 'Malejąco'}
          </Button>
        </div>
      </Group>

      <Group label="Stan">
        <SegmentedField
          label="Stan"
          options={STOCK_SIGNS}
          value={filters.stock}
          onChange={(stock) => update({ stock })}
          className="w-full [&>*]:flex-1"
        />
        <Range
          label="Stan"
          from={filters.stockFrom}
          to={filters.stockTo}
          onChange={(stockFrom, stockTo) => update({ stockFrom, stockTo })}
        />
      </Group>

      <Group label="Rotacja">
        <Range
          label="Rotacja"
          from={filters.rotationFrom}
          to={filters.rotationTo}
          onChange={(rotationFrom, rotationTo) => update({ rotationFrom, rotationTo })}
        />
      </Group>

      <MultiComboboxField
        label="Nomenklatura"
        description="Rejony, rodziny albo podrodziny. Puste = cała kartoteka."
        options={(nomenclatures.data ?? []).map((item) => ({
          value: item.code,
          label: `${displayCode(item.code)} ${item.name.replace(/^\d+_/, '')}`,
          hint: LEVEL_STYLES[item.level].label,
        }))}
        value={filters.nomenclatures}
        onChange={(codes) => update({ nomenclatures: codes })}
        placeholder={nomenclatures.data?.length === 0 ? 'Brak wgranej nomenklatury' : 'Szukaj po kodzie albo nazwie…'}
      />

      <Group label="Cadencier">
        <SegmentedField
          label="Obecność w cadencier"
          options={CADENCIER}
          value={filters.cadencier}
          onChange={(cadencier) => update({ cadencier })}
          className="w-full [&>*]:flex-1"
        />
      </Group>

      {(activeFilterCount(filters) > 0 || filters.sort !== 'name' || filters.direction !== 'asc') && (
        <Button variant="ghost" size="sm" className="justify-self-start" onClick={() => onChange(EMPTY_FILTERS)}>
          Wyczyść filtry i sortowanie
        </Button>
      )}
    </div>
  )
}

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <fieldset className="grid gap-2">
      <legend className="mb-2 text-xs font-medium text-muted-foreground">{label}</legend>
      {children}
    </fieldset>
  )
}

function Range({
  label,
  from,
  to,
  onChange,
}: {
  label: string
  from: string
  to: string
  onChange: (from: string, to: string) => void
}) {
  return (
    <div className="grid grid-cols-2 gap-2">
      <TextField
        label="od"
        aria-label={`${label} od`}
        inputMode="decimal"
        value={from}
        onChange={(event) => onChange(event.target.value, to)}
      />
      <TextField
        label="do"
        aria-label={`${label} do`}
        inputMode="decimal"
        value={to}
        onChange={(event) => onChange(from, event.target.value)}
      />
    </div>
  )
}
