import { router } from 'expo-router'
import { useState } from 'react'
import { KeyboardAvoidingView, Pressable, ScrollView, Text, View } from 'react-native'

import { Logo } from '@/components/Logo'
import { UsbCardInput } from '@/components/UsbCardInput'
import { Button, Card, Field, Muted, Notice } from '@/components/ui'
import { ApiError, fetchReader } from '@/lib/api'
import { normalizeServer } from '@/lib/config'
import { useKiosk } from '@/lib/kiosk'
import { useManager } from '@/lib/manager'
import { useTheme } from '@/lib/theme'
import { USB_FORMATS, uidFromUsb, type UsbFormat } from '@/lib/uid'

/**
 * Konfiguracja urządzenia: serwer i klucz czytnika z przeglądarki (Czas pracy → Ustawienia → Czytniki, klucz
 * widać raz po dodaniu czytnika) oraz format numeru z czytnika USB.
 */
export default function SetupScreen() {
  const t = useTheme()
  const { config, reader, configure } = useKiosk()
  const { session, touch } = useManager()
  const [server, setServer] = useState(config?.server ?? '')
  const [key, setKey] = useState('')
  const [usbFormat, setUsbFormat] = useState<UsbFormat>(config?.usbFormat ?? 'AUTO')
  const [usbTest, setUsbTest] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!config) return
    const address = normalizeServer(server)
    const readerKey = key.trim() || config.readerKey
    if (!address || !readerKey) {
      setError('Podaj adres serwera i klucz czytnika.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const info = await fetchReader(readerKey, address)
      await configure({ server: address, readerKey, usbFormat }, info)
      setKey('')
      if (router.canGoBack()) router.back()
      else router.replace('/')
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 403
          ? 'Serwer nie zna tego klucza. Skopiuj go jeszcze raz z Ustawień Czasu pracy (albo dodaj nowy czytnik).'
          : e instanceof ApiError
            ? e.message
            : 'Nie udało się sprawdzić klucza.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior="height">
      <ScrollView
        contentContainerStyle={{ padding: 24, gap: 18, maxWidth: 640, width: '100%', alignSelf: 'center' }}
        keyboardShouldPersistTaps="handled"
        onTouchStart={session ? touch : undefined}
      >
        <UsbCardInput
          enabled={testing}
          raw
          onCard={(text) => {
            setUsbTest(text)
            setTesting(false)
          }}
        />
        {!reader ? (
          <View style={{ gap: 12, paddingTop: 24 }}>
            <Logo width={220} />
            <Text style={{ color: t.text, fontSize: 26, fontWeight: '700' }}>Kiosk czasu pracy</Text>
            <Muted style={{ fontSize: 16 }}>
              W przeglądarce otwórz Zespół → Czas pracy → Ustawienia (koło zębate), dodaj czytnik i przepisz jego klucz.
              Klucz widać tylko raz.
            </Muted>
          </View>
        ) : (
          <Notice tone="info">
            Kiosk działa w sklepie {reader.storeNumber} {reader.storeName} jako „{reader.readerName}”. Nowy klucz
            przenosi go do innego czytnika albo sklepu.
          </Notice>
        )}

        <Field
          label="Adres serwera"
          value={server}
          onChangeText={setServer}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
        />
        <Field
          label={reader ? 'Nowy klucz czytnika (puste = bez zmian)' : 'Klucz czytnika'}
          value={key}
          onChangeText={setKey}
          autoCapitalize="none"
          autoCorrect={false}
          secureTextEntry={!!reader}
        />

        <Card>
          <Text style={{ color: t.text, fontSize: 17, fontWeight: '600' }}>Czytnik USB na kablu</Text>
          <Muted>
            Czytnik wpisuje numer karty jak klawiatura. Wybierz jego format, żeby numer zgadzał się z kartą przypisaną
            telefonem. „Sprawdź czytnik USB” pokaże, jaki numer trafi na serwer.
          </Muted>
          <View style={{ gap: 8 }}>
            {USB_FORMATS.map((format) => {
              const selected = format.value === usbFormat
              return (
                <Pressable
                  key={format.value}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  onPress={() => setUsbFormat(format.value)}
                  style={{
                    borderRadius: 12,
                    borderWidth: selected ? 2 : 1,
                    borderColor: selected ? t.primary : t.border,
                    padding: 12,
                    gap: 2,
                  }}
                >
                  <Text style={{ color: t.text, fontSize: 16, fontWeight: selected ? '700' : '500' }}>
                    {format.label}
                  </Text>
                  <Muted>{format.hint}</Muted>
                </Pressable>
              )
            })}
          </View>
          <Button
            title={testing ? 'Przyłóż kartę do czytnika USB…' : 'Sprawdź czytnik USB'}
            variant="secondary"
            onPress={() => {
              setUsbTest(null)
              setTesting((value) => !value)
            }}
          />
          {usbTest ? (
            <Notice tone="info">
              Czytnik USB podał: {usbTest}. Numer na serwer:{' '}
              {uidFromUsb(usbTest, usbFormat) ?? 'nie wygląda na numer karty w tym formacie'}
            </Notice>
          ) : null}
        </Card>

        {error ? <Notice tone="danger">{error}</Notice> : null}
        <Button title="Sprawdź i zapisz" onPress={save} loading={busy} />
        {reader ? <Button title="Anuluj" variant="secondary" onPress={() => router.back()} /> : null}
      </ScrollView>
    </KeyboardAvoidingView>
  )
}
