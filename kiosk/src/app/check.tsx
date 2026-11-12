import { router } from 'expo-router'
import { useEffect, useState } from 'react'
import { ScrollView, Text, View } from 'react-native'

import { UsbCardInput } from '@/components/UsbCardInput'
import { Button, Card, Muted, Notice } from '@/components/ui'
import { useEmployeeCards } from '@/lib/cards'
import { useManager } from '@/lib/manager'
import { cancelCardRequest, nfcState, readCard, type CardRead } from '@/lib/nfc'
import { useTheme } from '@/lib/theme'

/** Do kogo należy karta i co jest zapisane w jej pamięci (np. karta znaleziona albo oddana bez opisu). */
export default function CheckScreen() {
  const t = useTheme()
  const { touch } = useManager()
  const { cards } = useEmployeeCards()
  const [read, setRead] = useState<CardRead | null>(null)
  const [source, setSource] = useState<'nfc' | 'usb' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      if ((await nfcState()) !== 'ready' || cancelled) return
      try {
        const card = await readCard()
        if (cancelled) return
        touch()
        setRead(card)
        setSource('nfc')
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      }
    })()
    return () => {
      cancelled = true
      void cancelCardRequest()
    }
  }, [attempt, touch])

  const owner = read ? cards?.find((card) => card.cardUid === read.uid) : null
  const byRcp = read?.rcpNumber != null ? cards?.find((card) => card.rcpNumber === read.rcpNumber) : null

  return (
    <ScrollView
      contentContainerStyle={{ padding: 24, gap: 18, maxWidth: 640, width: '100%', alignSelf: 'center' }}
      onTouchStart={touch}
    >
      <UsbCardInput
        enabled={read == null}
        onCard={(uid) => {
          touch()
          setRead({ uid, rcpNumber: null, memoryProblem: 'Czytnik USB czyta tylko numer karty.' })
          setSource('usb')
        }}
      />
      {read == null ? (
        <Card style={{ alignItems: 'center', paddingVertical: 36 }}>
          <Text style={{ color: t.text, fontSize: 30, fontWeight: '700' }}>Przyłóż kartę</Text>
          <Muted style={{ fontSize: 16, textAlign: 'center' }}>Sprawdzimy, do kogo należy i co ma w pamięci.</Muted>
        </Card>
      ) : (
        <Card>
          <View style={{ gap: 4 }}>
            <Muted>Numer karty ({source === 'usb' ? 'czytnik USB' : 'NFC urządzenia'})</Muted>
            <Text style={{ color: t.text, fontSize: 24, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
              {read.uid}
            </Text>
          </View>
          {owner ? (
            <Notice tone="ok">
              Przypisana: {owner.name}
              {owner.rcpNumber != null ? ` (RCP ${owner.rcpNumber})` : ''}
            </Notice>
          ) : (
            <Notice tone="warn">Karta nie jest przypisana nikomu w tym sklepie.</Notice>
          )}
          {read.rcpNumber != null ? (
            <Notice tone={owner && owner.rcpNumber === read.rcpNumber ? 'info' : 'danger'}>
              Na karcie zapisano RCP {read.rcpNumber}
              {byRcp ? ` (${byRcp.name})` : ''}.
              {owner && owner.rcpNumber !== read.rcpNumber
                ? ' Zapis nie zgadza się z przypisaniem: zaprogramuj kartę ponownie.'
                : ''}
            </Notice>
          ) : read.memoryProblem ? (
            <Muted>{read.memoryProblem}</Muted>
          ) : (
            <Muted>Pamięć karty jest pusta (karta niezaprogramowana w kiosku).</Muted>
          )}
        </Card>
      )}
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <View style={{ gap: 10 }}>
        {read || error ? (
          <Button
            title="Sprawdź inną kartę"
            onPress={() => {
              setRead(null)
              setError(null)
              setSource(null)
              setAttempt((value) => value + 1)
            }}
          />
        ) : null}
        <Button title="Wróć do listy" variant="secondary" onPress={() => router.back()} />
      </View>
    </ScrollView>
  )
}
