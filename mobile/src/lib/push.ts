import { useQueryClient } from '@tanstack/react-query'
import Constants from 'expo-constants'
import * as Device from 'expo-device'
import * as Notifications from 'expo-notifications'
import { AndroidImportance } from 'expo-notifications'
import { router, type Href } from 'expo-router'
import { useEffect } from 'react'
import { Platform } from 'react-native'

import { api } from './api'
import { REQUEST_QUERY_KEY } from './requests'

// Powiadomienie przy otwartej aplikacji też pokazuje baner (domyślnie system by je przemilczał).
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
})

/** Ekrany, które może otworzyć dotknięcie powiadomienia (`data.url` z serwera). */
const OPENABLE: Href[] = ['/requests', '/']

let registeredToken: string | null = null

/** Projekt EAS: bez niego (aplikacja nie jest jeszcze połączona z EAS) token push się nie da wygenerować. */
function projectId(): string | null {
  const extra = Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined
  return extra?.eas?.projectId ?? Constants.easConfig?.projectId ?? null
}

/**
 * Zgoda na powiadomienia i token Expo push, wysłany na serwer (`PUT /me/push-token`). Bez projektu EAS, na emulatorze
 * i na Androidzie bez skonfigurowanego Firebase (FCM) token się nie wygeneruje: wtedy po cichu nic, a aplikacja
 * działa bez powiadomień (różnice i tak widać na ekranie Wnioski).
 */
async function register() {
  if (!Device.isDevice || (Platform.OS !== 'ios' && Platform.OS !== 'android')) return
  const id = projectId()
  if (!id) return
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('default', {
      name: 'Powiadomienia',
      importance: AndroidImportance.HIGH,
    })
  }
  const current = await Notifications.getPermissionsAsync()
  let granted = current.granted
  if (!granted && current.canAskAgain) granted = (await Notifications.requestPermissionsAsync()).granted
  if (!granted) return
  const { data: token } = await Notifications.getExpoPushTokenAsync({ projectId: id })
  await api('/me/push-token', { method: 'PUT', json: { token, platform: Platform.OS } })
  registeredToken = token
}

/** Przy wylogowaniu urządzenie przestaje dostawać powiadomienia tego konta. */
export async function unregisterPush() {
  const token = registeredToken
  registeredToken = null
  if (!token) return
  try {
    await api('/me/push-token/remove', { method: 'POST', json: { token } })
  } catch {
    // Bez internetu serwer wyłączy token sam, gdy urządzenie go odrzuci.
  }
}

/**
 * Powiadomienia zalogowanego konta: rejestracja tokenu, odświeżenie różnic po przyjściu powiadomienia i otwarcie
 * ekranu z `data.url` po dotknięciu (także gdy aplikacja startuje z powiadomienia).
 */
export function usePushNotifications(signedIn: boolean) {
  const queryClient = useQueryClient()
  const response = Notifications.useLastNotificationResponse()

  useEffect(() => {
    if (signedIn) void register().catch(() => undefined)
  }, [signedIn])

  useEffect(() => {
    const subscription = Notifications.addNotificationReceivedListener(() => {
      void queryClient.invalidateQueries({ queryKey: REQUEST_QUERY_KEY })
    })
    return () => subscription.remove()
  }, [queryClient])

  useEffect(() => {
    if (!signedIn || !response) return
    const url = response.notification.request.content.data?.url
    const target = OPENABLE.find((href) => href === url)
    if (target) router.push(target)
  }, [response, signedIn])
}
