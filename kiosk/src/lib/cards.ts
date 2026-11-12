import { useFocusEffect } from 'expo-router'
import { useCallback, useState } from 'react'

import { api, ApiError } from './api'
import { useKiosk } from './kiosk'
import { useManager } from './manager'
import type { EmployeeCard, TimeSettings } from './types'

/**
 * Pracownicy sklepu czytnika z kartami: te same ustawienia co w przeglądarce (Czas pracy → Ustawienia), więc karta
 * przypisana w kiosku od razu jest tam widoczna i odwrotnie.
 */
export function useEmployeeCards() {
  const { reader } = useKiosk()
  const { auth } = useManager()
  const [cards, setCards] = useState<EmployeeCard[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    if (!reader || !auth) return
    try {
      const settings = await api<TimeSettings>(`/stores/${reader.storeId}/staff/time/settings`, { auth })
      setCards(settings.cards)
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Nie udało się wczytać pracowników.')
    }
  }, [reader, auth])

  useFocusEffect(
    useCallback(() => {
      void reload()
    }, [reload]),
  )

  return { cards, error, reload }
}

export function assignCard(
  storeId: string,
  employeeId: string,
  cardUid: string,
  auth: ReturnType<typeof useManager>['auth'],
) {
  return api<TimeSettings>(`/stores/${storeId}/staff/time/settings/cards/${employeeId}`, {
    method: 'PUT',
    json: { cardUid },
    auth,
  })
}

export function removeCard(storeId: string, employeeId: string, auth: ReturnType<typeof useManager>['auth']) {
  return api<TimeSettings>(`/stores/${storeId}/staff/time/settings/cards/${employeeId}`, { method: 'DELETE', auth })
}
