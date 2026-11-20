import { Link } from 'react-router'

import { useCurrentUser } from '@/features/auth/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { useAgentState } from '@/lib/store-agent'
import { cn } from '@/lib/utils'

/**
 * Mała kropka stanu agenta w nagłówku aplikacji, tylko w sklepie, który pracuje przez agenta: zielona = agent online
 * (zapisuje pliki i pilnuje folderów), bursztynowa = offline (wszystko robi przeglądarka, jak dotąd).
 */
export function AgentStatusDot() {
  const state = useAgentState()
  const me = useCurrentUser()
  if (!state?.status.enabled || !state.status.present) return null
  const online = state.status.online
  const label = online
    ? `Agent sklepu online${state.status.machineName ? ` (${state.status.machineName})` : ''}: zapisuje pliki i pilnuje folderów`
    : 'Agent sklepu offline: pliki zapisuje i foldery sprawdza przeglądarka'
  const dot = (
    <span
      className={cn('block size-2.5 rounded-full ring-2 ring-background', online ? 'bg-emerald-500' : 'bg-amber-500')}
    />
  )
  return me.owner ? (
    <Link to={storeSettingsPaths.agent} aria-label={label} title={label} className="grid size-6 place-items-center">
      {dot}
    </Link>
  ) : (
    <span role="img" aria-label={label} title={label} className="grid size-6 place-items-center">
      {dot}
    </span>
  )
}
