import { useState } from 'react'
import { ShoppingBasketIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { TableCell, TableHead } from '@/components/ui/table'

/** Zaznaczone wiersze raportu po ITM8; {@code chosen} tylko z tych, które są teraz na liście. */
export function useRowSelection<T extends { itm8: string }>(items: T[]) {
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const chosen = items.filter((item) => selected.has(item.itm8))
  return {
    selected,
    chosen,
    all: chosen.length > 0 && chosen.length === items.length,
    some: chosen.length > 0 && chosen.length < items.length,
    toggle: (itm8: string) =>
      setSelected((current) => {
        const next = new Set(current)
        if (!next.delete(itm8)) next.add(itm8)
        return next
      }),
    setAll: (checked: boolean) => setSelected(checked ? new Set(items.map((item) => item.itm8)) : new Set()),
    clear: () => setSelected(new Set()),
  }
}

type SelectHeadProps = { selection: { all: boolean; some: boolean; setAll: (checked: boolean) => void } }

/** Nagłówek kolumny zaznaczania: wszystkie wiersze listy, także jeszcze nienarysowane. */
export function SelectHead({ selection }: SelectHeadProps) {
  return (
    <TableHead className="w-10 pl-4">
      <input
        type="checkbox"
        aria-label="Zaznacz wszystkie"
        className="size-4 accent-foreground"
        checked={selection.all}
        ref={(element) => {
          if (element) element.indeterminate = selection.some
        }}
        onChange={(event) => selection.setAll(event.target.checked)}
      />
    </TableHead>
  )
}

export function SelectCell({ label, checked, onToggle }: { label: string; checked: boolean; onToggle: () => void }) {
  return (
    <TableCell className="w-10 pl-4">
      <input
        type="checkbox"
        aria-label={`Zaznacz ${label}`}
        className="size-4 accent-foreground"
        checked={checked}
        onChange={onToggle}
      />
    </TableCell>
  )
}

/** Ikona koszyka przy artykule; gdy już w nim jest, niebieska. */
export function BasketRowButton({ inBasket, onClick }: { inBasket: boolean; onClick: () => void }) {
  const label = inBasket ? 'W koszyku, dodaj jeszcze' : 'Dodaj do koszyka'
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      type="button"
      aria-label={label}
      title={label}
      className={
        inBasket
          ? '-my-0.5 shrink-0 text-blue-700 hover:text-blue-800 dark:text-blue-400 dark:hover:text-blue-300'
          : '-my-0.5 shrink-0 text-muted-foreground hover:text-foreground'
      }
      onClick={(event) => {
        event.stopPropagation()
        onClick()
      }}
    >
      <ShoppingBasketIcon />
    </Button>
  )
}
