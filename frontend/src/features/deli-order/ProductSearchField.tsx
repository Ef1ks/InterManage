import { useState } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { LoaderCircleIcon, PlusIcon, SearchIcon } from 'lucide-react'

import { isSearchLongEnough, MIN_SEARCH_LENGTH, useProductSearch, type ProductSearchSource } from '@/features/deli-order/api'
import type { ProductSearchResult } from '@/lib/types'

type Props = {
  source: ProductSearchSource
  /** Produkty już dodane - na liście podpowiedzi są oznaczone. */
  addedProductIds: Set<string>
  onAdd: (product: ProductSearchResult) => void
  disabled?: boolean
  placeholder?: string
}

/**
 * Dodawanie produktu z kartoteki sklepu do kategorii. Kartoteka ma dziesiątki tysięcy pozycji, więc szukamy
 * na serwerze i dopiero od {@link MIN_SEARCH_LENGTH} znaków. Słowa w dowolnej kolejności i jako fragmenty
 * ("fil swie" znajdzie "FILET SWIEZY KL A"): nazwa, marka, początek EAN albo fragment ITM8.
 */
export function ProductSearchField({
  source,
  addedProductIds,
  onAdd,
  disabled,
  placeholder = 'Dodaj produkt: nazwa, marka, EAN lub ITM8',
}: Props) {
  const [query, setQuery] = useState('')
  const { data: results = [], isFetching } = useProductSearch(source, query)
  const tooShort = !isSearchLongEnough(query)
  const items = tooShort ? [] : results

  return (
    <Combobox.Root
      items={items}
      filter={null}
      value={null}
      inputValue={query}
      onInputValueChange={setQuery}
      onValueChange={(product: ProductSearchResult | null) => {
        if (product && !addedProductIds.has(product.id)) {
          onAdd(product)
          setQuery('')
        }
      }}
      itemToStringLabel={(product: ProductSearchResult) => product.name ?? product.ean}
      isItemEqualToValue={(a: ProductSearchResult, b: ProductSearchResult) => a.id === b.id}
      disabled={disabled}
    >
      <div className="relative">
        <SearchIcon
          aria-hidden
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
        />
        <Combobox.Input
          aria-label={placeholder}
          placeholder={placeholder}
          className="h-10 w-full rounded-xl border border-input bg-background pr-9 pl-9 text-sm transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground focus:border-ring focus:ring-3 focus:ring-ring/30 disabled:opacity-60"
        />
        {isFetching && !tooShort && (
          <LoaderCircleIcon
            aria-hidden
            className="absolute top-1/2 right-3 size-4 -translate-y-1/2 animate-spin text-muted-foreground motion-reduce:animate-none"
          />
        )}
      </div>

      <Combobox.Portal>
        <Combobox.Positioner sideOffset={6} align="start" className="isolate z-50">
          <Combobox.Popup className="max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) origin-(--transform-origin) overflow-hidden rounded-xl bg-popover text-popover-foreground shadow-[0_8px_24px_-8px_rgb(0_0_0/0.18)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.98] data-ending-style:opacity-0 data-starting-style:scale-[0.98] data-starting-style:opacity-0 motion-reduce:transition-none">
            <Combobox.Empty className="px-3 py-3 text-sm text-muted-foreground empty:hidden">
              {tooShort
                ? `Wpisz co najmniej ${MIN_SEARCH_LENGTH} znaki`
                : isFetching
                  ? 'Szukam…'
                  : 'Nie ma takiego produktu w kartotece sklepu'}
            </Combobox.Empty>
            <Combobox.List className="max-h-80 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
              {(product: ProductSearchResult) => {
                const added = addedProductIds.has(product.id)
                return (
                  <Combobox.Item
                    key={product.id}
                    value={product}
                    disabled={added}
                    className="grid cursor-pointer grid-cols-[1fr_auto] items-center gap-x-3 rounded-lg px-2.5 py-2 text-sm outline-none select-none data-disabled:opacity-50 data-highlighted:bg-muted"
                  >
                    <span className="min-w-0">
                      <span className="block truncate font-medium">
                        {product.name ?? 'Bez nazwy'}
                        {product.brand && <span className="ml-2 font-normal text-muted-foreground">{product.brand}</span>}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground tabular-nums">
                        EAN {product.ean} · ITM8 {product.itm8}
                        {product.active === false && ' · nieaktywny'}
                      </span>
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {added ? 'Już dodany' : <PlusIcon aria-label="Dodaj" className="size-3.5" />}
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
