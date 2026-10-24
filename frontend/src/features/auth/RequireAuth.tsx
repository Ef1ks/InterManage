import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { hasPermission, useMe } from '@/features/auth/api'
import type { PermissionCategory, PermissionLevel } from '@/lib/types'

type Props = {
  children: ReactNode
  /** Tylko dla właściciela (ustawienia). */
  ownerOnly?: boolean
  /** Wymagane uprawnienie do kategorii narzędzi. */
  permission?: { category: PermissionCategory; level?: PermissionLevel }
  /** Ekran ustawiania hasła - jedyne miejsce dostępne, dopóki użytkownik nie ustawi własnego hasła. */
  allowPasswordSetup?: boolean
}

export function RequireAuth({ children, ownerOnly, permission, allowPasswordSetup }: Props) {
  const { data: me, isPending, isError, refetch } = useMe()
  const location = useLocation()

  if (isPending) {
    return <FullPageMessage title="Ładowanie…" />
  }
  if (isError) {
    return (
      <FullPageMessage
        title="Brak połączenia z serwerem"
        description="Sprawdź, czy backend działa, i spróbuj ponownie."
        action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
      />
    )
  }
  if (!me) {
    return <Navigate to="/logowanie" replace state={{ from: location.pathname + location.search }} />
  }
  if (me.passwordChangeRequired && !allowPasswordSetup) {
    return <Navigate to="/ustaw-haslo" replace />
  }

  const allowed =
    (!ownerOnly || me.owner) && (!permission || hasPermission(me, permission.category, permission.level))
  if (!allowed) {
    return (
      <FullPageMessage
        title="Brak dostępu"
        description="Twoja rola nie ma uprawnień do tej sekcji. Skontaktuj się z właścicielem sklepu."
        inline
      />
    )
  }

  return children
}
