import * as Haptics from 'expo-haptics'
import { router, useFocusEffect } from 'expo-router'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Animated, Easing, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'

import { Logo } from '@/components/Logo'
import { UsbCardInput } from '@/components/UsbCardInput'
import { useKiosk } from '@/lib/kiosk'
import { listenForCards, nfcState, openNfcSettings, type NfcState } from '@/lib/nfc'
import { flushQueue, markBreak, pendingCount, punch, type PunchOutcome } from '@/lib/punches'
import { useTheme, type Theme } from '@/lib/theme'

/** Jak długo wynik odbicia stoi na ekranie. */
const RESULT_MS = 4_000
/** Jak długo przy wyjściu czeka wybór „Koniec pracy” / „Przerwa”; bez wyboru zostaje koniec pracy. */
const CHOICE_MS = 8_000
const FLUSH_INTERVAL_MS = 30_000

const timeFormat = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' })
const dateFormat = new Intl.DateTimeFormat('pl-PL', { weekday: 'long', day: 'numeric', month: 'long' })

export default function PunchScreen() {
  const t = useTheme()
  const { width, height } = useWindowDimensions()
  const { reader, revoked } = useKiosk()
  const [nfc, setNfc] = useState<NfcState | null>(null)
  const [outcome, setOutcome] = useState<PunchOutcome | null>(null)
  const [pending, setPending] = useState(pendingCount)
  const [listening, setListening] = useState(true)
  const busy = useRef(false)
  const clearTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [appear] = useState(() => new Animated.Value(0))
  /** Wyjście zamienione na przerwę: `saving` w trakcie zapisu. */
  const [breakChoice, setBreakChoice] = useState<'none' | 'saving' | 'done'>('none')

  const dismissAfter = useCallback((ms: number) => {
    if (clearTimer.current) clearTimeout(clearTimer.current)
    clearTimer.current = setTimeout(() => setOutcome(null), ms)
  }, [])

  const handleCard = useCallback(
    async (uid: string) => {
      if (busy.current) return
      busy.current = true
      try {
        const result = await punch(uid)
        if (result.kind === 'repeat') return
        setOutcome(result)
        setBreakChoice('none')
        setPending(pendingCount())
        void Haptics.notificationAsync(
          result.kind === 'error' ? Haptics.NotificationFeedbackType.Error : Haptics.NotificationFeedbackType.Success,
        )
        appear.setValue(0)
        Animated.timing(appear, { toValue: 1, duration: 180, useNativeDriver: true }).start()
        dismissAfter(result.kind === 'ok' && result.result.breakAvailable ? CHOICE_MS : RESULT_MS)
      } finally {
        busy.current = false
      }
    },
    [appear, dismissAfter],
  )

  const chooseBreak = useCallback(async () => {
    if (outcome?.kind !== 'ok' || breakChoice !== 'none') return
    setBreakChoice('saving')
    if (clearTimer.current) clearTimeout(clearTimer.current)
    const result = await markBreak(outcome.result.punchId)
    if (result.kind === 'ok') {
      setBreakChoice('done')
      void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success)
    } else {
      setBreakChoice('none')
      setOutcome(result)
      void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error)
    }
    dismissAfter(RESULT_MS)
  }, [outcome, breakChoice, dismissAfter])

  const finishWork = useCallback(() => {
    if (clearTimer.current) clearTimeout(clearTimer.current)
    setOutcome(null)
  }, [])

  // NFC urządzenia czyta karty tylko, gdy ekran odbić jest na wierzchu (tryb kart sam przejmuje czytnik).
  useFocusEffect(
    useCallback(() => {
      let stop: (() => void) | null = null
      let cancelled = false
      setListening(true)
      void nfcState().then((state) => {
        if (cancelled) return
        setNfc(state)
        if (state === 'ready') stop = listenForCards((uid) => void handleCard(uid))
      })
      return () => {
        cancelled = true
        setListening(false)
        stop?.()
      }
    }, [handleCard]),
  )

  useEffect(() => {
    const timer = setInterval(() => {
      void flushQueue().then(() => setPending(pendingCount()))
    }, FLUSH_INTERVAL_MS)
    return () => {
      clearInterval(timer)
      if (clearTimer.current) clearTimeout(clearTimer.current)
    }
  }, [])

  const wide = width > height
  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: t.background }]}>
      <UsbCardInput enabled={listening} onCard={(uid) => void handleCard(uid)} />
      <View style={styles.top}>
        <Logo width={Math.min(width * 0.3, 220)} />
        <View style={{ alignItems: 'flex-end' }}>
          <Text style={[styles.store, { color: t.text }]}>{reader?.storeName}</Text>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>
            {reader ? `Sklep ${reader.storeNumber} · ${reader.readerName}` : ''}
          </Text>
        </View>
      </View>

      <View style={[styles.center, wide && { flexDirection: 'row', gap: 48 }]}>
        <Clock t={t} large={Math.min(width, height) > 600} />
        {outcome ? (
          <Animated.View
            style={{
              opacity: appear,
              transform: [{ scale: appear.interpolate({ inputRange: [0, 1], outputRange: [0.96, 1] }) }],
            }}
          >
            <Result
              outcome={outcome}
              t={t}
              breakChoice={breakChoice}
              onBreak={() => void chooseBreak()}
              onFinish={finishWork}
            />
          </Animated.View>
        ) : (
          <Prompt t={t} nfc={nfc} />
        )}
      </View>

      <View style={styles.bottom}>
        <View style={{ gap: 4, flexShrink: 1 }}>
          {revoked ? (
            <Text style={{ color: t.danger, fontSize: 14 }}>
              Klucz czytnika został usunięty w Ustawieniach Czasu pracy. Odbicia nie są przyjmowane.
            </Text>
          ) : null}
          {pending > 0 ? (
            <Text style={{ color: t.warnText, fontSize: 14 }}>
              Bez połączenia: {pending} {pending === 1 ? 'odbicie czeka' : 'odbić czeka'} na wysłanie
            </Text>
          ) : null}
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Tryb kart dla kierownika (przytrzymaj)"
          onLongPress={() => router.push('/login')}
          delayLongPress={800}
          hitSlop={12}
          style={({ pressed }) => [styles.manager, { backgroundColor: pressed ? t.muted : 'transparent' }]}
        >
          <Text style={{ color: t.mutedText, fontSize: 13 }}>Karty (przytrzymaj)</Text>
        </Pressable>
      </View>

      {nfc === 'disabled' ? (
        <Pressable onPress={() => void openNfcSettings()} style={[styles.nfcOff, { backgroundColor: t.warnBg }]}>
          <Text style={{ color: t.warnText, fontSize: 15 }}>NFC jest wyłączone. Dotknij, żeby włączyć.</Text>
        </Pressable>
      ) : null}
    </SafeAreaView>
  )
}

function Clock({ t, large }: { t: Theme; large: boolean }) {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(timer)
  }, [])
  return (
    <View style={{ alignItems: 'center' }}>
      <Text style={{ color: t.text, fontSize: large ? 120 : 84, fontWeight: '200', fontVariant: ['tabular-nums'] }}>
        {timeFormat.format(now)}
      </Text>
      <Text style={{ color: t.mutedText, fontSize: large ? 24 : 18 }}>{dateFormat.format(now)}</Text>
    </View>
  )
}

function Prompt({ t, nfc }: { t: Theme; nfc: NfcState | null }) {
  return (
    <View style={[styles.panel, { backgroundColor: t.card, borderColor: t.border }]}>
      <Text style={[styles.panelTitle, { color: t.text }]}>Przyłóż kartę</Text>
      <Text style={{ color: t.mutedText, fontSize: 17, textAlign: 'center' }}>
        {nfc === 'ready' ? 'do czytnika albo z tyłu urządzenia' : 'do czytnika'}
      </Text>
    </View>
  )
}

type ResultProps = {
  outcome: PunchOutcome
  t: Theme
  breakChoice: 'none' | 'saving' | 'done'
  onBreak: () => void
  onFinish: () => void
}

function Result({ outcome, t, breakChoice, onBreak, onFinish }: ResultProps) {
  if (outcome.kind === 'ok') {
    const { result } = outcome
    const entry = result.direction === 'IN'
    const firstName = result.employeeName.split(' ')[0]
    const time = timeFormat.format(new Date(result.punchedAt))
    if (breakChoice === 'done') {
      return (
        <View style={[styles.panel, { backgroundColor: t.warnBg, borderColor: 'transparent' }]}>
          <Text style={{ color: t.warnText, fontSize: 18, fontWeight: '600' }}>PRZERWA · {time}</Text>
          <Text style={[styles.panelTitle, { color: t.warnText }]}>Miłej przerwy, {firstName}</Text>
          <Text style={{ color: t.warnText, fontSize: 17, textAlign: 'center' }}>
            Po powrocie przyłóż kartę jak zwykle.
          </Text>
        </View>
      )
    }
    const tone = entry ? { bg: t.okBg, text: t.okText } : { bg: t.infoBg, text: t.infoText }
    return (
      <View style={[styles.panel, { backgroundColor: tone.bg, borderColor: 'transparent' }]}>
        <Text style={{ color: tone.text, fontSize: 18, fontWeight: '600' }}>
          {entry ? (result.breakEnded ? 'KONIEC PRZERWY' : 'WEJŚCIE') : 'WYJŚCIE'} · {time}
        </Text>
        <Text style={[styles.panelTitle, { color: tone.text }]}>
          {entry
            ? result.breakEnded
              ? `Witaj z powrotem, ${firstName}`
              : `Dzień dobry, ${firstName}`
            : `Do widzenia, ${firstName}`}
        </Text>
        <Text style={{ color: tone.text, fontSize: 17 }}>{result.employeeName}</Text>
        {!entry && result.breakAvailable ? (
          <BreakChoice t={t} saving={breakChoice === 'saving'} onBreak={onBreak} onFinish={onFinish} />
        ) : null}
      </View>
    )
  }
  if (outcome.kind === 'queued') {
    return (
      <View style={[styles.panel, { backgroundColor: t.warnBg, borderColor: 'transparent' }]}>
        <Text style={[styles.panelTitle, { color: t.warnText }]}>Odbicie zapisane</Text>
        <Text style={{ color: t.warnText, fontSize: 17, textAlign: 'center' }}>
          Brak połączenia z serwerem. Godzina jest zapamiętana, odbicie wyślemy samo.
        </Text>
      </View>
    )
  }
  const message = outcome.kind === 'error' ? outcome.message : ''
  return (
    <View style={[styles.panel, { backgroundColor: t.dangerBg, borderColor: 'transparent' }]}>
      <Text style={[styles.panelTitle, { color: t.dangerText }]}>Nie odbito</Text>
      <Text style={{ color: t.dangerText, fontSize: 17, textAlign: 'center' }}>
        {message === 'Nieznana karta' ? 'Ta karta nie jest przypisana. Zgłoś się do kierownika.' : message}
      </Text>
    </View>
  )
}

/**
 * Wybór po wyjściu: odbicie jest już zapisane jako koniec pracy, „Przerwa” je zamienia. Pasek pod przyciskami
 * odmierza czas do zamknięcia (samo `scaleX`, na natywnym sterowniku).
 */
function BreakChoice({
  t,
  saving,
  onBreak,
  onFinish,
}: {
  t: Theme
  saving: boolean
  onBreak: () => void
  onFinish: () => void
}) {
  const [left] = useState(() => new Animated.Value(1))
  useEffect(() => {
    const animation = Animated.timing(left, {
      toValue: 0,
      duration: CHOICE_MS,
      easing: Easing.linear,
      useNativeDriver: true,
    })
    animation.start()
    return () => animation.stop()
  }, [left])
  useEffect(() => {
    if (saving) left.stopAnimation()
  }, [saving, left])
  return (
    <View style={{ alignSelf: 'stretch', marginTop: 16, gap: 12 }}>
      <View style={{ flexDirection: 'row', gap: 12 }}>
        <Pressable
          accessibilityRole="button"
          onPress={onFinish}
          disabled={saving}
          style={({ pressed }) => [
            styles.choice,
            { backgroundColor: t.primary, opacity: pressed ? 0.85 : 1, transform: [{ scale: pressed ? 0.97 : 1 }] },
          ]}
        >
          <Text style={[styles.choiceText, { color: t.primaryText }]}>Koniec pracy</Text>
        </Pressable>
        <Pressable
          accessibilityRole="button"
          onPress={onBreak}
          disabled={saving}
          style={({ pressed }) => [
            styles.choice,
            {
              backgroundColor: t.warnBg,
              opacity: saving ? 0.6 : pressed ? 0.85 : 1,
              transform: [{ scale: pressed ? 0.97 : 1 }],
            },
          ]}
        >
          <Text style={[styles.choiceText, { color: t.warnText }]}>{saving ? 'Zapisuję…' : 'Przerwa'}</Text>
        </Pressable>
      </View>
      <View style={{ height: 4, borderRadius: 2, overflow: 'hidden', backgroundColor: t.card }}>
        <Animated.View
          style={{
            flex: 1,
            backgroundColor: t.primary,
            transformOrigin: 'left',
            transform: [{ scaleX: left }],
          }}
        />
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  choice: { flex: 1, minHeight: 72, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  choiceText: { fontSize: 22, fontWeight: '700' },
  screen: { flex: 1, padding: 24 },
  top: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 16 },
  store: { fontSize: 18, fontWeight: '600' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 32 },
  panel: {
    minWidth: 320,
    maxWidth: 520,
    borderRadius: 24,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 32,
    paddingVertical: 28,
    alignItems: 'center',
    gap: 8,
  },
  panelTitle: { fontSize: 34, fontWeight: '700', textAlign: 'center' },
  bottom: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 },
  manager: { paddingHorizontal: 10, paddingVertical: 8, borderRadius: 10 },
  nfcOff: { position: 'absolute', top: 24, alignSelf: 'center', borderRadius: 12, padding: 12 },
})
