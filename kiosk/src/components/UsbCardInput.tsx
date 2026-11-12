import { useIsFocused } from 'expo-router'
import { useEffect, useRef } from 'react'
import { StyleSheet, TextInput, type TextInputInstance } from 'react-native'

import { config } from '@/lib/config'
import { uidFromUsb } from '@/lib/uid'

/**
 * Czytnik USB na kablu działa jak klawiatura: wypisuje numer karty i Enter. Niewidoczne pole trzyma fokus (bez
 * klawiatury ekranowej) i oddaje UID po Enterze. Fokus wraca sam, dopóki ekran jest na wierzchu i pole włączone.
 */
export function UsbCardInput({
  enabled,
  onCard,
  raw = false,
}: {
  enabled: boolean
  onCard: (uid: string) => void
  /** Oddaje tekst z czytnika bez przeliczania (test formatu w ustawieniach). */
  raw?: boolean
}) {
  const ref = useRef<TextInputInstance>(null)
  const buffer = useRef('')
  const focused = useIsFocused()
  const active = enabled && focused

  useEffect(() => {
    if (!active) return
    ref.current?.focus()
    // Dotyk innego miejsca na ekranie zabiera fokus; co chwilę go oddajemy.
    const timer = setInterval(() => {
      if (!ref.current?.isFocused()) ref.current?.focus()
    }, 1000)
    return () => clearInterval(timer)
  }, [active])

  if (!active) return null
  return (
    <TextInput
      ref={ref}
      style={styles.hidden}
      autoFocus
      showSoftInputOnFocus={false}
      autoCorrect={false}
      autoCapitalize="none"
      caretHidden
      blurOnSubmit={false}
      importantForAccessibility="no"
      onChangeText={(text) => {
        buffer.current = text
      }}
      onSubmitEditing={() => {
        const text = buffer.current.trim()
        const uid = raw ? text : uidFromUsb(text, config().usbFormat)
        buffer.current = ''
        ref.current?.clear()
        if (uid) onCard(uid)
      }}
    />
  )
}

const styles = StyleSheet.create({
  hidden: { position: 'absolute', width: 1, height: 1, opacity: 0, left: -10, top: -10 },
})
