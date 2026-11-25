import { Link } from 'react-router'

import { useCan } from '@/features/auth/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { useCurrentStore } from '@/features/stores/current-store'
import { rememberedAgentStatus, useAgentState } from '@/lib/store-agent'
import { cn } from '@/lib/utils'

/**
 * Mała kropka stanu agenta w nagłówku aplikacji, tylko w sklepie, który pracuje przez agenta: zielona = agent online
 * (zapisuje pliki i pilnuje folderów), bursztynowa = offline (wszystko robi przeglądarka, jak dotąd).
 */
export function AgentStatusDot() {
  const state = useAgentState()
  const canSeeAgents = useCan('STORE_AGENT')
  const canEditFeatures = useCan('DATA_ENTRY', 'EDIT')
  const store = useCurrentStore()
  const known = state && state.storeId === store?.id ? state.status : null
  const status = known ?? (store ? rememberedAgentStatus(store.id) : null)
  if (!status?.enabled || !status.present) return null
  const online = status.online
  const label = `${known ? '' : 'Ostatnio znany stan: '}${
    online
      ? `Agent sklepu online${status.machineName ? ` (${status.machineName})` : ''}: zapisuje pliki i pilnuje folderów`
      : 'Agent sklepu offline: pliki zapisuje i foldery sprawdza przeglądarka'
  }`
  const dot = (
    <span
      className={cn(
        'block size-2.5 rounded-full ring-2 ring-background',
        online ? 'bg-emerald-500' : 'bg-amber-500',
        !known && 'opacity-50',
      )}
    />
  )
  return canSeeAgents || canEditFeatures ? (
    <Link to={storeSettingsPaths.agent} aria-label={label} title={label} className="grid size-6 place-items-center">
      {dot}
    </Link>
  ) : (
    <span role="img" aria-label={label} title={label} className="grid size-6 place-items-center">
      {dot}
    </span>
  )
}
