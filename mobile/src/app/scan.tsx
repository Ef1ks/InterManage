import { CameraView, useCameraPermissions, type BarcodeScanningResult } from 'expo-camera'
import { router } from 'expo-router'
import { useRef, useState } from 'react'
import { ActivityIndicator, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { ProductChoices } from '@/components/ProductChoices'
import { Button } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { lookupArticle } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import type { ProductListItem } from '@/lib/types'

// Kody na artykułach w sklepie: EAN-13/8 i UPC, a Code 128 dla etykiet wewnętrznych (np. wydruk zerówek).
const BARCODE_TYPES = ['ean13', 'ean8', 'upc_a', 'upc_e', 'code128'] as const

type State =
  | { kind: 'scanning' }
  | { kind: 'checking'; code: string }
  | { kind: 'several'; code: string; items: ProductListItem[] }
  | { kind: 'error'; code: string; message: string }

export default function ScanScreen() {
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const { store } = useAuth()
  const [permission, requestPermission] = useCameraPermissions()
  const [state, setState] = useState<State>({ kind: 'scanning' })
  const [torch, setTorch] = useState(false)
  // Aparat zgłasza ten sam kod kilka razy na sekundę: przyjmujemy jeden, aż użytkownik zeskanuje ponownie.
  const locked = useRef(false)

  const onScanned = async ({ data }: BarcodeScanningResult) => {
    const code = data.trim()
    if (locked.current || !code || !store) return
    locked.current = true
    setState({ kind: 'checking', code })
    try {
      const result = await lookupArticle(store.id, code)
      if (result.kind === 'found') {
        router.replace({ pathname: '/article/[itm8]', params: { itm8: result.itm8 } })
      } else if (result.kind === 'several') {
        setState({ kind: 'several', code, items: result.items })
      } else {
        setState({ kind: 'error', code, message: 'Nie ma artykułu z tym kodem w kartotece sklepu.' })
      }
    } catch (e) {
      setState({
        kind: 'error',
        code,
        message: e instanceof ApiError ? e.message : 'Nie udało się sprawdzić kodu.',
      })
    }
  }

  const again = () => {
    locked.current = false
    setState({ kind: 'scanning' })
  }

  if (!permission) return <View style={{ flex: 1, backgroundColor: '#000' }} />

  if (!permission.granted) {
    return (
      <View style={[styles.center, { backgroundColor: t.background, paddingTop: insets.top + 60 }]}>
        <Text style={{ color: t.text, fontSize: 17, fontWeight: '600', textAlign: 'center' }}>
          Aplikacja potrzebuje aparatu, żeby skanować kody EAN.
        </Text>
        {permission.canAskAgain ? (
          <Button title="Zezwól na aparat" onPress={requestPermission} />
        ) : (
          <Button title="Otwórz ustawienia" onPress={() => void Linking.openSettings()} />
        )}
      </View>
    )
  }

  return (
    <View style={{ flex: 1, backgroundColor: '#000' }}>
      <CameraView
        style={StyleSheet.absoluteFill}
        facing="back"
        enableTorch={torch}
        barcodeScannerSettings={{ barcodeTypes: [...BARCODE_TYPES] }}
        onBarcodeScanned={state.kind === 'scanning' ? onScanned : undefined}
      />

      {/* Ramka celownika: kod poziomo, w środku ekranu. */}
      <View pointerEvents="none" style={styles.frameWrap}>
        <View style={styles.frame} />
        <Text style={styles.hint}>Nakieruj aparat na kod kreskowy</Text>
      </View>

      <View style={[styles.bottom, { paddingBottom: insets.bottom + 16 }]}>
        <Pressable
          onPress={() => setTorch((value) => !value)}
          style={[styles.torch, torch && { backgroundColor: '#fff' }]}
          accessibilityRole="button"
          accessibilityLabel={torch ? 'Wyłącz latarkę' : 'Włącz latarkę'}
        >
          <Text style={{ color: torch ? '#000' : '#fff', fontWeight: '600' }}>
            {torch ? 'Latarka: wł.' : 'Latarka'}
          </Text>
        </Pressable>

        {state.kind !== 'scanning' && (
          <View style={[styles.sheet, { backgroundColor: t.card }]}>
            <Text style={{ color: t.mutedText, fontSize: 13, fontVariant: ['tabular-nums'] }}>Kod {state.code}</Text>
            {state.kind === 'checking' && <ActivityIndicator color={t.mutedText} />}
            {state.kind === 'error' && <Text style={{ color: t.danger, fontSize: 15 }}>{state.message}</Text>}
            {state.kind === 'several' && (
              <ScrollView style={{ maxHeight: 320 }}>
                <ProductChoices
                  items={state.items}
                  onPick={(itm8) => router.replace({ pathname: '/article/[itm8]', params: { itm8 } })}
                />
              </ScrollView>
            )}
            {state.kind !== 'checking' && <Button title="Skanuj ponownie" onPress={again} />}
          </View>
        )}
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  center: { flex: 1, padding: 24, gap: 20 },
  frameWrap: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16,
  },
  frame: { width: '78%', height: 150, borderWidth: 3, borderColor: '#fff', borderRadius: 16 },
  hint: { color: '#fff', fontSize: 15, fontWeight: '500', textShadowColor: '#000', textShadowRadius: 6 },
  bottom: { position: 'absolute', left: 0, right: 0, bottom: 0, padding: 16, gap: 12 },
  torch: {
    alignSelf: 'center',
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 999,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  sheet: { borderRadius: 16, padding: 16, gap: 12 },
})
