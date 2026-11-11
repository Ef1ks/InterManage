import { useState } from 'react'

import { useCurrentUser } from '@/features/auth/api'
import type { UserBackground } from '@/lib/types'

/**
 * Własne zdjęcie konta pod całą aplikacją (Moje konto → Tło aplikacji). Leży za treścią (`-z-10`, układ ma `isolate`),
 * a warstwa w kolorze tła motywu przesłania je o `veilPercent`, więc w jasnym motywie zdjęcie jest rozjaśnione,
 * a w ciemnym przyciemnione. Pojawia się płynnie dopiero po wczytaniu.
 */
export function AppBackground() {
  const { background } = useCurrentUser()
  if (!background) return null
  // key: nowe zdjęcie to nowa warstwa, która znów przenika po wczytaniu.
  return <BackgroundLayers key={background.id} background={background} className="app-photo fixed" />
}

export function BackgroundLayers({
  background,
  className = 'fixed',
}: {
  background: UserBackground
  className?: string
}) {
  const [loaded, setLoaded] = useState(false)

  return (
    <div aria-hidden className={`pointer-events-none inset-0 -z-10 overflow-hidden ${className}`}>
      <img
        src={background.url}
        alt=""
        onLoad={() => setLoaded(true)}
        className="size-full object-cover transition-opacity duration-500 ease-out motion-reduce:transition-none"
        style={{ opacity: loaded ? 1 : 0 }}
      />
      <div className="absolute inset-0 bg-background" style={{ opacity: background.veilPercent / 100 }} />
    </div>
  )
}
