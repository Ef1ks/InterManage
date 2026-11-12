import * as Haptics from 'expo-haptics'
import { router, useLocalSearchParams } from 'expo-router'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, ScrollView, Text, View } from 'react-native'

import { UsbCardInput } from '@/components/UsbCardInput'
import { Button, Card, Muted, Notice } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { assignCard, removeCard, useEmployeeCards } from '@/lib/cards'
import { useKiosk } from '@/lib/kiosk'
import { useManager } from '@/lib/manager'
import { cancelCardRequest, nfcState, programCard, readCard, type NfcState } from '@/lib/nfc'
import { useTheme } from '@/lib/theme'

type Step =
  | { kind: 'waiting' }
  | { kind: 'saving' }
  | { kind: 'done'; uid: string; written: boolean; note: string | null }
  | { kind: 'error'; message: string }
  | { kind: 'removed'; previousUid: string }

/**
 * Programowanie karty pracownika: przyłożona karta dostaje w pamięci numer RCP (NFC urządzenia, MIFARE Classic)
 * i zostaje przypisana pracownikowi po UID. Czytnik USB czyta sam numer, więc z nim karta jest tylko przypisana.
 */
export default function ProgramScreen() {
  const t = useTheme()
  const params = useLocalSearchParams<{ employeeId: string; name: string; rcpNumber: string; cardUid: string }>()
  const rcpNumber = params.rcpNumber ? Number(params.rcpNumber) : null
  const { reader } = useKiosk()
  const { auth, touch } = useManager()
  const { cards } = useEmployeeCards()
  const [nfc, setNfc] = useState<NfcState | null>(null)
  const [step, setStep] = useState<Step>({ kind: 'waiting' })
  const [currentUid, setCurrentUid] = useState(params.cardUid || null)
  const [attempt, setAttempt] = useState(0)
  const cardsRef = useRef(cards)
  useEffect(() => {
    cardsRef.current = cards
  }, [cards])

  /** Karta innego pracownika: nie zapisujemy na niej nic, serwer i tak by ją odrzucił (409). */
  const ownerCheck = useCallback(
    (uid: string) => {
      const owner = cardsRef.current?.find((card) => card.cardUid === uid && card.employeeId !== params.employeeId)
      if (owner) throw new Error(`Ta karta jest już przypisana: ${owner.name}. Najpierw odbierz ją tej osobie.`)
    },
    [params.employeeId],
  )

  const save = useCallback(
    async (uid: string, written: boolean, note: string | null) => {
      if (!reader || !auth) return
      setStep({ kind: 'saving' })
      try {
        await assignCard(reader.storeId, params.employeeId, uid, auth)
        setCurrentUid(uid)
        setStep({ kind: 'done', uid, written, note })
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success)
      } catch (e) {
        setStep({ kind: 'error', message: e instanceof ApiError ? e.message : 'Nie udało się przypisać karty.' })
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error)
      }
    },
    [reader, auth, params.employeeId],
  )

  // Czekanie na kartę przy NFC urządzenia; wyjście z ekranu albo ponowna próba przerywa czekanie.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const state = await nfcState()
      if (cancelled) return
      setNfc(state)
      if (state !== 'ready') return
      try {
        if (rcpNumber == null) {
          const card = await readCard()
          if (cancelled) return
          touch()
          ownerCheck(card.uid)
          await save(card.uid, false, 'Pracownik nie ma numeru RCP, więc na karcie nic nie zapisano.')
        } else {
          const result = await programCard(rcpNumber, ownerCheck)
          if (cancelled) return
          touch()
          await save(result.uid, result.written, result.problem)
        }
      } catch (e) {
        if (cancelled) return
        setStep({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error)
      }
    })()
    return () => {
      cancelled = true
      void cancelCardRequest()
    }
  }, [attempt, rcpNumber, ownerCheck, save, touch])

  const onUsbCard = (uid: string) => {
    touch()
    try {
      ownerCheck(uid)
    } catch (e) {
      setStep({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      return
    }
    void save(uid, false, 'Czytnik USB czyta tylko numer karty. Zapis na kartę wymaga NFC urządzenia.')
  }

  const retry = () => {
    setStep({ kind: 'waiting' })
    setAttempt((value) => value + 1)
  }

  const remove = () => {
    if (!reader || !auth || !currentUid) return
    Alert.alert('Odebrać kartę?', `${params.name} nie odbije się już kartą ${currentUid}.`, [
      { text: 'Anuluj', style: 'cancel' },
      {
        text: 'Odbierz',
        style: 'destructive',
        onPress: async () => {
          try {
            await removeCard(reader.storeId, params.employeeId, auth)
            setStep({ kind: 'removed', previousUid: currentUid })
            setCurrentUid(null)
          } catch (e) {
            setStep({ kind: 'error', message: e instanceof ApiError ? e.message : 'Nie udało się odebrać karty.' })
          }
        },
      },
    ])
  }

  return (
    <ScrollView
      contentContainerStyle={{ padding: 24, gap: 18, maxWidth: 640, width: '100%', alignSelf: 'center' }}
      onTouchStart={touch}
    >
      <UsbCardInput enabled={step.kind === 'waiting'} onCard={onUsbCard} />
      <View style={{ gap: 4 }}>
        <Text style={{ color: t.text, fontSize: 28, fontWeight: '700' }}>{params.name}</Text>
        <Muted style={{ fontSize: 16 }}>
          {rcpNumber != null ? `Numer RCP ${rcpNumber}` : 'Bez numeru RCP'}
          {currentUid ? ` · obecna karta ${currentUid}` : ' · bez karty'}
        </Muted>
      </View>

      {step.kind === 'waiting' || step.kind === 'saving' ? (
        <Card style={{ alignItems: 'center', paddingVertical: 36 }}>
          <Text style={{ color: t.text, fontSize: 30, fontWeight: '700', textAlign: 'center' }}>
            {step.kind === 'saving' ? 'Zapisuję…' : 'Przyłóż kartę'}
          </Text>
          <Muted style={{ fontSize: 16, textAlign: 'center' }}>
            {nfc === 'ready'
              ? 'Przytrzymaj ją z tyłu urządzenia, aż pojawi się wynik. Czytnik USB też działa (bez zapisu na kartę).'
              : 'Przyłóż kartę do czytnika USB. To urządzenie nie ma włączonego NFC, więc na kartę nic nie zapiszemy.'}
          </Muted>
          {currentUid ? (
            <Muted style={{ textAlign: 'center' }}>Nowa karta zastąpi obecną, stara przestanie działać.</Muted>
          ) : null}
        </Card>
      ) : null}

      {step.kind === 'done' ? (
        <>
          <Notice tone="ok">
            Karta {step.uid} przypisana: {params.name}.
            {step.written ? ` Na karcie zapisano numer RCP ${rcpNumber}.` : ''}
          </Notice>
          {step.note ? <Notice tone="warn">{step.note}</Notice> : null}
        </>
      ) : null}

      {step.kind === 'error' ? <Notice tone="danger">{step.message}</Notice> : null}

      {step.kind === 'removed' ? (
        <View style={{ gap: 10 }}>
          <Notice tone="info">Karta {step.previousUid} odebrana.</Notice>
          <Button title="Przywróć" variant="secondary" onPress={() => void save(step.previousUid, false, null)} />
        </View>
      ) : null}

      <View style={{ gap: 10 }}>
        {step.kind !== 'waiting' && step.kind !== 'saving' ? (
          <Button title={step.kind === 'done' ? 'Zaprogramuj inną kartę' : 'Spróbuj jeszcze raz'} onPress={retry} />
        ) : null}
        {currentUid && step.kind !== 'saving' ? (
          <Button title="Odbierz kartę" variant="danger" onPress={remove} />
        ) : null}
        <Button title="Wróć do listy" variant="secondary" onPress={() => router.back()} />
      </View>
    </ScrollView>
  )
}
