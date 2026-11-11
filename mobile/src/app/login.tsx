import { useRef, useState } from 'react'
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
  type TextInputInstance,
} from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'

import { Logo } from '@/components/Logo'
import { Button, ErrorText, Field } from '@/components/ui'
import { ApiError, currentServer, setServer } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { useTheme } from '@/lib/theme'

export default function LoginScreen() {
  const t = useTheme()
  const { width } = useWindowDimensions()
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [server, setServerText] = useState(currentServer)
  const [showServer, setShowServer] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const passwordRef = useRef<TextInputInstance>(null)

  const submit = async () => {
    if (!username.trim() || !password) {
      setError('Podaj login i hasło.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      setServer(server)
      await login(username.trim(), password)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Nie udało się zalogować.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.background }}>
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 24, gap: 28 }}>
          <View style={{ gap: 14 }}>
            <Logo width={Math.min(width - 48, 300)} />
            <Text style={{ color: t.mutedText, fontSize: 15 }}>Zaloguj się tym samym kontem co w przeglądarce.</Text>
          </View>

          <View style={{ gap: 16 }}>
            <Field
              label="Login"
              value={username}
              onChangeText={setUsername}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="username"
              textContentType="username"
              returnKeyType="next"
              onSubmitEditing={() => passwordRef.current?.focus()}
            />
            <Field
              ref={passwordRef}
              label="Hasło"
              value={password}
              onChangeText={setPassword}
              secureTextEntry
              autoComplete="current-password"
              textContentType="password"
              returnKeyType="go"
              onSubmitEditing={submit}
            />
            {showServer && (
              <Field
                label="Adres serwera"
                value={server}
                onChangeText={setServerText}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="url"
              />
            )}
            {error && <ErrorText>{error}</ErrorText>}
            <Button title="Zaloguj" onPress={submit} loading={busy} />
            <Pressable onPress={() => setShowServer((value) => !value)} hitSlop={8}>
              <Text style={{ color: t.mutedText, fontSize: 13, textAlign: 'center' }}>
                {showServer ? 'Ukryj adres serwera' : `Serwer: ${server}`}
              </Text>
            </Pressable>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}
