import { Stack } from 'expo-router'
import { useKeepAwake } from 'expo-keep-awake'
import { StatusBar } from 'expo-status-bar'
import { View } from 'react-native'
import { SafeAreaProvider } from 'react-native-safe-area-context'

import { Loading } from '@/components/ui'
import { KioskProvider, useKiosk } from '@/lib/kiosk'
import { ManagerProvider, useManager } from '@/lib/manager'
import { useTheme } from '@/lib/theme'

export default function RootLayout() {
  // Kiosk stoi przy wejściu cały dzień: ekran nie gaśnie.
  useKeepAwake()
  return (
    <SafeAreaProvider>
      <KioskProvider>
        <ManagerProvider>
          <Navigator />
        </ManagerProvider>
      </KioskProvider>
    </SafeAreaProvider>
  )
}

function Navigator() {
  const t = useTheme()
  const { config, reader } = useKiosk()
  const { session } = useManager()

  if (config === undefined) {
    return (
      <View style={{ flex: 1, justifyContent: 'center', backgroundColor: t.background }}>
        <Loading />
      </View>
    )
  }

  const configured = reader != null
  return (
    <>
      <StatusBar hidden />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: t.background },
          headerTintColor: t.text,
          headerShadowVisible: false,
          contentStyle: { backgroundColor: t.background },
          headerBackTitle: 'Wstecz',
          // Kiosk: pracownik nie wychodzi gestem z ekranu odbić.
          gestureEnabled: false,
        }}
      >
        <Stack.Protected guard={configured}>
          <Stack.Screen name="index" options={{ headerShown: false }} />
          <Stack.Screen name="login" options={{ title: 'Tryb kart', presentation: 'modal', gestureEnabled: true }} />
          <Stack.Protected guard={session != null}>
            <Stack.Screen name="cards" options={{ title: 'Karty pracowników' }} />
            <Stack.Screen name="program" options={{ title: 'Programowanie karty' }} />
            <Stack.Screen name="check" options={{ title: 'Sprawdź kartę' }} />
          </Stack.Protected>
        </Stack.Protected>
        {/* Konfiguracja: przy pierwszym uruchomieniu każdy, potem tylko zalogowany kierownik. */}
        <Stack.Protected guard={!configured || session != null}>
          <Stack.Screen name="setup" options={{ title: 'Ustawienia kiosku', headerShown: configured }} />
        </Stack.Protected>
      </Stack>
    </>
  )
}
