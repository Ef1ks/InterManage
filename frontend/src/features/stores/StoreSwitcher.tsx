import { StoreIcon } from 'lucide-react'

import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { useCurrentUser } from '@/features/auth/api'
import { storeLabel, useCurrentStore, useSelectStore } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'

/** Wybór sklepu w menu bocznym. Przy jednym sklepie to tylko informacja, przy kilku lista wyboru. */
export function StoreSwitcher() {
  const me = useCurrentUser()
  const current = useCurrentStore()
  const selectStore = useSelectStore()

  if (!current) {
    return (
      <p className="rounded-xl border border-dashed px-3 py-2.5 text-xs leading-relaxed text-muted-foreground">
        {me.owner
          ? 'Nie ma jeszcze żadnego sklepu. Dodaj go w Ustawieniach.'
          : 'Nie masz przypisanego sklepu. Poproś właściciela o dostęp.'}
      </p>
    )
  }

  if (me.stores.length === 1) {
    return (
      <div className="flex items-center gap-2.5 rounded-xl bg-muted/60 py-2.5 pr-2.5 pl-2">
        <StoreIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        <CurrentStore store={current} />
      </div>
    )
  }

  const items = me.stores.map((store) => ({ value: store.id, label: storeLabel(store) }))

  return (
    <Select items={items} value={current.id} onValueChange={(value) => value && selectStore(value)}>
      <SelectTrigger
        aria-label={`Sklep: ${storeLabel(current)}. Zmień sklep`}
        className="w-full gap-2.5 rounded-xl border-transparent bg-muted/60 py-2.5 pr-2.5 pl-2 hover:bg-muted data-popup-open:bg-muted data-[size=default]:h-auto"
      >
        <StoreIcon aria-hidden className="size-4 text-muted-foreground" />
        <CurrentStore store={current} />
      </SelectTrigger>
      <SelectContent alignItemWithTrigger={false} sideOffset={6} className="min-w-(--anchor-width)">
        {me.stores.map((store) => (
          <SelectItem key={store.id} value={store.id} className="gap-3 px-3 py-2.5">
            <span className="tabular-nums text-muted-foreground">{store.number}</span>
            <span className="truncate">{store.name}</span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function CurrentStore({ store }: { store: StoreSummary }) {
  // Sklep bez własnej nazwy (np. "Sklep 10023" z migracji) nie powtarza numeru w dwóch liniach.
  const nameIsNumber = store.name.trim() === `Sklep ${store.number}`
  return (
    <span className="grid min-w-0 flex-1 gap-0.5 text-left">
      <span className="text-xs leading-4 text-muted-foreground">{nameIsNumber ? 'Sklep' : `Sklep ${store.number}`}</span>
      <span className="truncate text-sm leading-5 font-medium tabular-nums">
        {nameIsNumber ? store.number : store.name}
      </span>
    </span>
  )
}
