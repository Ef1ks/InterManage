import * as Haptics from 'expo-haptics'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { CheckIcon, ChevronRightIcon } from '@/components/icons'
import { Button, ErrorText, Field, Muted } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { addDays, shortDate } from '@/lib/format'
import { clock, hm, REQUEST_KINDS, useSubmitRequest } from '@/lib/requests'
import { useTheme } from '@/lib/theme'
import type { TimeRequestKind } from '@/lib/types'
import { todayIso, WEEKDAYS, weekdayIndex } from '@/lib/week'

type Params = {
  mode?: string
  part?: string
  date?: string
  start?: string
  end?: string
  minutes?: string
  title?: string
  kinds?: string
}

const dayLabel = (iso: string) => `${WEEKDAYS[weekdayIndex(iso)]}, ${shortDate(iso)}`

/**
 * Nowy wniosek (arkusz): na odcinek z karty (rodzaj do wyboru z pasujących, przyczyna, gdy wymagana) albo zgłoszenie
 * L4 na dni od-do. Po wysłaniu wraca do listy wniosków.
 */
export default function NewRequestScreen() {
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const params = useLocalSearchParams<Params>()
  const sick = params.mode === 'sick'
  const kinds = (params.kinds ?? '').split(',').filter(Boolean) as TimeRequestKind[]
  const [kind, setKind] = useState<TimeRequestKind | null>(sick ? 'SICK_LEAVE' : (kinds[0] ?? null))
  const [reason, setReason] = useState('')
  const [from, setFrom] = useState(todayIso)
  const [to, setTo] = useState(todayIso)
  const [error, setError] = useState<string | null>(null)
  const submit = useSubmitRequest()

  const reasonRequired = kind ? REQUEST_KINDS[kind].reason : false
  const missingReason = reasonRequired && reason.trim().length === 0
  const disabled = !kind || missingReason || (sick && to < from)

  const send = () => {
    if (!kind || disabled) return
    setError(null)
    submit.mutate(
      sick
        ? { kind, from, to, reason: reason.trim() || null }
        : { kind, start: params.start, end: params.end, reason: reason.trim() || null },
      {
        onSuccess: () => {
          void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success).catch(() => undefined)
          router.back()
        },
        onError: (e) => setError(e instanceof ApiError ? e.message : 'Nie udało się wysłać wniosku.'),
      },
    )
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView
        contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: insets.bottom + 24 }}
        keyboardShouldPersistTaps="handled"
      >
        <View style={{ gap: 4 }}>
          <Text style={{ color: t.text, fontSize: 22, fontWeight: '700' }}>
            {sick ? 'Zgłoszenie L4' : 'Nowy wniosek'}
          </Text>
          {sick ? (
            <Muted style={{ fontSize: 14, lineHeight: 20 }}>
              Zgłoś, że jesteś chory/a, najpóźniej drugiego dnia nieobecności. Zwolnienie lekarskie pracodawca dostaje z
              ZUS.
            </Muted>
          ) : (
            <Muted style={{ fontSize: 15 }}>
              {params.title} · {params.date ? dayLabel(params.date) : ''} · {params.start ? clock(params.start) : ''}–
              {params.end ? clock(params.end) : ''} ({hm(Number(params.minutes ?? 0))} h)
            </Muted>
          )}
        </View>

        {sick ? (
          <View style={{ gap: 12 }}>
            <DateStepper
              label="Od"
              value={from}
              onChange={(value) => {
                setFrom(value)
                if (to < value) setTo(value)
              }}
            />
            <DateStepper label="Do" value={to} min={from} onChange={setTo} />
          </View>
        ) : (
          <View style={{ gap: 10 }} accessibilityRole="radiogroup">
            {kinds.map((option) => (
              <KindOption key={option} kind={option} selected={kind === option} onPress={() => setKind(option)} />
            ))}
          </View>
        )}

        <Field
          label={reasonRequired ? 'Przyczyna (wymagana)' : 'Przyczyna (opcjonalnie)'}
          value={reason}
          onChangeText={setReason}
          multiline
          maxLength={1000}
          placeholder={reasonRequired ? 'Napisz krótko, co się stało' : undefined}
          style={{ minHeight: 88, textAlignVertical: 'top' }}
        />
        {error ? <ErrorText>{error}</ErrorText> : null}
        <Button title="Wyślij do kierownika" onPress={send} loading={submit.isPending} disabled={disabled} />
      </ScrollView>
    </KeyboardAvoidingView>
  )
}

function KindOption({ kind, selected, onPress }: { kind: TimeRequestKind; selected: boolean; onPress: () => void }) {
  const t = useTheme()
  const option = REQUEST_KINDS[kind]
  return (
    <Pressable
      onPress={() => {
        void Haptics.selectionAsync().catch(() => undefined)
        onPress()
      }}
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      style={({ pressed }) => [
        styles.option,
        {
          borderColor: selected ? t.text : t.border,
          backgroundColor: pressed ? t.muted : t.card,
        },
      ]}
    >
      <View
        style={[
          styles.radio,
          { borderColor: selected ? t.text : t.border, backgroundColor: selected ? t.text : 'transparent' },
        ]}
      >
        {selected ? <CheckIcon color={t.background} size={14} strokeWidth={3} /> : null}
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }}>{option.label}</Text>
        <Text style={{ color: t.mutedText, fontSize: 13, lineHeight: 18 }}>{option.description}</Text>
      </View>
    </Pressable>
  )
}

/** Dzień ze strzałkami: wybór daty bez systemowego kalendarza (L4 zgłasza się zwykle na kilka dni od dziś). */
function DateStepper({
  label,
  value,
  min,
  onChange,
}: {
  label: string
  value: string
  min?: string
  onChange: (value: string) => void
}) {
  const t = useTheme()
  const canBack = !min || value > min
  return (
    <View style={[styles.stepper, { borderColor: t.border, backgroundColor: t.card }]}>
      <Text style={{ color: t.mutedText, fontSize: 13, width: 28 }}>{label}</Text>
      <Pressable
        onPress={() => canBack && onChange(addDays(value, -1))}
        disabled={!canBack}
        accessibilityRole="button"
        accessibilityLabel={`${label}: dzień wcześniej`}
        hitSlop={8}
        style={({ pressed }) => [styles.stepButton, { opacity: canBack ? (pressed ? 0.6 : 1) : 0.3 }]}
      >
        <View style={{ transform: [{ rotate: '180deg' }] }}>
          <ChevronRightIcon color={t.text} size={20} />
        </View>
      </Pressable>
      <Text style={{ flex: 1, textAlign: 'center', color: t.text, fontSize: 16, fontWeight: '600' }}>
        {dayLabel(value)}
      </Text>
      <Pressable
        onPress={() => onChange(addDays(value, 1))}
        accessibilityRole="button"
        accessibilityLabel={`${label}: dzień później`}
        hitSlop={8}
        style={({ pressed }) => [styles.stepButton, { opacity: pressed ? 0.6 : 1 }]}
      >
        <ChevronRightIcon color={t.text} size={20} />
      </Pressable>
    </View>
  )
}

const styles = StyleSheet.create({
  option: { flexDirection: 'row', gap: 12, padding: 14, borderRadius: 14, borderWidth: 1.5, alignItems: 'flex-start' },
  radio: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 1,
  },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 10, borderRadius: 14, borderWidth: 1 },
  stepButton: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
})
