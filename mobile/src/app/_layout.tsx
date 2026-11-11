import { BlurTargetView } from 'expo-blur'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Stack } from 'expo-router'
import * as NativeSplash from 'expo-splash-screen'
import { StatusBar } from 'expo-status-bar'
import { useRef, useState, type ComponentRef } from 'react'
import { View } from 'react-native'
import { SafeAreaProvider } from 'react-native-safe-area-context'

import { AccountButton } from '@/components/Avatar'
import { FloatingDock } from '@/components/FloatingDock'
import { Logo } from '@/components/Logo'
import { SplashAnimation } from '@/components/SplashAnimation'
import { StoreSwitcher } from '@/components/StoreSwitcher'
import { Loading } from '@/components/ui'
import { AuthProvider, useAuth } from '@/lib/auth'
import { useTheme } from '@/lib/theme'

// Systemowy ekran startowy zostaje, dopóki nie stanie animacja logo (SplashAnimation go chowa).
void NativeSplash.preventAutoHideAsync().catch(() => undefined)

export default function RootLayout() {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { staleTime: 5 * 60 * 1000, retry: 1 } },
      }),
  )
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <Navigator />
          <SplashAnimation />
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  )
}

function Navigator() {
  const { me } = useAuth()
  const t = useTheme()
  // Android rozmywa pod paskiem tylko ten widok, więc ekrany stoją w nim.
  const screens = useRef<ComponentRef<typeof View>>(null)

  if (me === undefined) {
    return (
      <View
        style={{
          flex: 1,
          justifyContent: 'center',
          backgroundColor: t.background,
        }}
      >
        <Loading />
      </View>
    )
  }

  return (
    <>
      <StatusBar style="auto" />
      <BlurTargetView ref={screens} style={{ flex: 1, backgroundColor: t.background }}>
        <Stack
          screenOptions={{
            headerStyle: { backgroundColor: t.background },
            headerTintColor: t.text,
            headerShadowVisible: false,
            contentStyle: { backgroundColor: t.background },
            headerBackTitle: 'Wstecz',
          }}
        >
          <Stack.Protected guard={me != null}>
            <Stack.Screen
              name="index"
              options={{
                title: 'Intermanage',
                headerTitle: () => <Logo width={130} />,
                headerRight: () => (
                  <View
                    style={{
                      flexDirection: 'row',
                      alignItems: 'center',
                      gap: 10,
                    }}
                  >
                    <StoreSwitcher />
                    <AccountButton />
                  </View>
                ),
              }}
            />
            <Stack.Screen
              name="scan"
              options={{
                title: 'Skanuj EAN',
                headerTransparent: true,
                headerTintColor: '#fff',
              }}
            />
            <Stack.Screen name="article/[itm8]" options={{ title: 'Artykuł' }} />
            <Stack.Screen name="schedule" options={{ title: 'Mój grafik' }} />
            <Stack.Screen
              name="account"
              options={{
                title: 'Konto',
                headerShown: false,
                presentation: 'formSheet',
                sheetGrabberVisible: true,
                sheetAllowedDetents: [0.7, 1],
                sheetCornerRadius: 20,
                contentStyle: { backgroundColor: t.background },
              }}
            />
          </Stack.Protected>
          <Stack.Protected guard={me == null}>
            <Stack.Screen name="login" options={{ headerShown: false }} />
          </Stack.Protected>
        </Stack>
      </BlurTargetView>
      {me ? <FloatingDock blurTarget={screens} /> : null}
    </>
  )
}
