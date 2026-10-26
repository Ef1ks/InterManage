import { useMemo, useState } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { ChevronDownIcon, SearchIcon } from 'lucide-react'

import { useFinishedProducts } from '@/features/deli-prices/api'
import { formatMoney } from '@/features/deli-prices/format'
import { matchesSearch } from '@/lib/search'
import type { FinishedProduct } from '@/lib/types'
import { cn } from '@/lib/utils'

type Props = {
  storeId: string
  /** Kalkulacja, którą edytujemy - jej własny wyrób nie jest oznaczany jako zajęty. */
  calculationId?: string
  value: FinishedProduct | null
  onChange: (product: FinishedProduct) => void
  invalid?: boolean
  disabled?: boolean
}

/**
 * Wybór gotowego wyrobu z działów garmażu (nomenklatury 24, 36, 46). Wszystkie przychodzą naraz (kilkaset), więc
 * lista rozwija się od razu, a wpisane słowa zawężają ją po nazwie, marce, EAN albo ITM8. Wyroby, które mają już
 * kalkulację, są wyłączone - jeden wyrób to jedna cena w eksporcie.
 */
export function FinishedProductPicker({ storeId, calculationId, value, onChange, invalid, disabled }: Props) {
  const { data: products = [], isPending } = useFinishedProducts(storeId)
  const [query, setQuery] = useState('')
  const items = useMemo(
    () => products.filter((product) => matchesSearch([product.name, product.brand, product.ean, product.itm8], query)),
    [products, query],
  )

  return (
    <Combobox.Root
      items={items}
      filter={null}
      value={value}
      inputValue={query}
      onInputValueChange={setQuery}
      onValueChange={(product: FinishedProduct | null) => {
        if (product) {
          onChange(product)
          setQuery('')
        }
      }}
      itemToStringLabel={(product: FinishedProduct) => product.name ?? product.ean}
      isItemEqualToValue={(a: FinishedProduct, b: FinishedProduct) => a.productId === b.productId}
      disabled={disabled}
    >
      <div className="relative">
        <SearchIcon
          aria-hidden
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
        />
        <Combobox.Input
          aria-label="Wyrób z kartoteki"
          aria-invalid={invalid || undefined}
          placeholder={value ? 'Zmień wyrób: nazwa, marka, EAN' : 'Wybierz wyrób: nazwa, marka, EAN'}
          className={cn(
            'h-11 w-full rounded-xl border border-input bg-background pr-10 pl-9 text-sm transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground focus:border-ring focus:ring-3 focus:ring-ring/30 disabled:opacity-60',
            invalid && 'border-destructive focus:border-destructive focus:ring-destructive/20',
          )}
        />
        <Combobox.Trigger
          aria-label="Pokaż wyroby"
          className="absolute top-1/2 right-1.5 flex size-8 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40"
        >
          <ChevronDownIcon className="size-4" />
        </Combobox.Trigger>
      </div>

      <Combobox.Portal>
        <Combobox.Positioner sideOffset={6} align="start" className="isolate z-50">
          <Combobox.Popup className="max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) origin-(--transform-origin) overflow-hidden rounded-xl bg-popover text-popover-foreground shadow-[0_8px_24px_-8px_rgb(0_0_0/0.18)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.98] data-ending-style:opacity-0 data-starting-style:scale-[0.98] data-starting-style:opacity-0 motion-reduce:transition-none">
            <Combobox.Empty className="px-3 py-3 text-sm text-muted-foreground empty:hidden">
              {isPending
                ? 'Wczytuję wyroby…'
                : products.length === 0
                  ? 'W kartotece nie ma wyrobów z działów 24, 36 i 46. Wgraj raport produktów.'
                  : 'Żaden wyrób nie pasuje.'}
            </Combobox.Empty>
            <Combobox.List className="max-h-80 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
              {(product: FinishedProduct) => {
                const taken = product.calculationId !== null && product.calculationId !== calculationId
                return (
                  <Combobox.Item
                    key={product.productId}
                    value={product}
                    disabled={taken}
                    className="grid cursor-pointer grid-cols-[1fr_auto] items-center gap-x-3 rounded-lg px-2.5 py-2 text-sm outline-none select-none data-disabled:cursor-default data-disabled:opacity-50 data-highlighted:bg-muted"
                  >
                    <span className="min-w-0">
                      <span className="block truncate font-medium">
                        {product.name ?? 'Bez nazwy'}
                        {product.brand && <span className="ml-2 font-normal text-muted-foreground">{product.brand}</span>}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground tabular-nums">
                        EAN {product.ean} · NOM {product.nomenclature}
                      </span>
                    </span>
                    <span className="text-xs text-muted-foreground tabular-nums">
                      {taken ? 'ma kalkulację' : formatMoney(product.salePrice)}
                    </span>
                  </Combobox.Item>
                )
              }}
            </Combobox.List>
          </Combobox.Popup>
        </Combobox.Positioner>
      </Combobox.Portal>
    </Combobox.Root>
  )
}
