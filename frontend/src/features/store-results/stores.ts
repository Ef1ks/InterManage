import { canInStore, useCurrentUser } from '@/features/auth/api'
import type { StoreSummary } from '@/lib/types'

/** Sklepy konta, których wyniki widać (uprawnienie Wyniki sklepów w danym sklepie). */
export function useResultStores(): StoreSummary[] {
  const me = useCurrentUser()
  return me.stores.filter((store) => canInStore(me, store.id, 'STORE_RESULTS'))
}
