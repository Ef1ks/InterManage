import { router } from 'expo-router'
import { useRef, useState } from 'react'
import { KeyboardAvoidingView, ScrollView, Text, View, type TextInputInstance } from 'react-native'

import { Button, Field, Muted, Notice } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useKiosk } from '@/lib/kiosk'
import { useManager } from '@/lib/manager'
import { useTheme } from '@/lib/theme'

/** Wejście w tryb kart: konto z Kadrami (edycja) i wglądem w Czas pracy w sklepie czytnika. */
export default function ManagerLogin() {
  const t = useTheme()
  const { reader } = useKiosk()
  const { login } = useManager()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const passwordRef = useRef<TextInputInstance>(null)

  const submit = async () => {
    if (!reader) return
    if (!username.trim() || !password) {
      setError('Podaj login i hasło.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password, reader.storeId)
      setPassword('')
      router.replace('/cards')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Nie udało się zalogować.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior="height">
      <ScrollView
        contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 24 }}
        keyboardShouldPersistTaps="handled"
      >
        <View style={{ gap: 16, width: '100%', maxWidth: 440, alignSelf: 'center' }}>
          <Text style={{ color: t.text, fontSize: 24, fontWeight: '700' }}>Tryb kart</Text>
          <Muted>
            Przypisywanie i programowanie kart pracowników. Zaloguj się kontem kierownika lub kadr. Tryb zamknie się sam
            po 3 minutach bez dotyku.
          </Muted>
          <Field
            label="Login"
            value={username}
            onChangeText={setUsername}
            autoCapitalize="none"
            autoCorrect={false}
            autoComplete="off"
            returnKeyType="next"
            onSubmitEditing={() => passwordRef.current?.focus()}
          />
          <Field
            ref={passwordRef}
            label="Hasło"
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            autoComplete="off"
            returnKeyType="go"
            onSubmitEditing={submit}
          />
          {error ? <Notice tone="danger">{error}</Notice> : null}
          <Button title="Zaloguj" onPress={submit} loading={busy} />
          <Button title="Anuluj" variant="secondary" onPress={() => router.back()} />
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  )
}
