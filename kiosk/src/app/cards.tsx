import { router } from 'expo-router'
import { useMemo, useState } from 'react'
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { Badge, Button, Field, Loading, Muted, Notice } from '@/components/ui'
import { useEmployeeCards } from '@/lib/cards'
import { useKiosk } from '@/lib/kiosk'
import { useManager } from '@/lib/manager'
import { useTheme } from '@/lib/theme'
import type { EmployeeCard } from '@/lib/types'

const plain = (text: string) =>
  text.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/ł/g, 'l').replace(/Ł/g, 'L').toLowerCase()

/** Pracownicy sklepu czytnika: kto ma kartę, wybór osoby do zaprogramowania karty. */
export default function CardsScreen() {
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const { reader } = useKiosk()
  const { session, logout, touch } = useManager()
  const { cards, error } = useEmployeeCards()
  const [query, setQuery] = useState('')
  const [onlyWithout, setOnlyWithout] = useState(false)

  const visible = useMemo(() => {
    const words = plain(query).split(/\s+/).filter(Boolean)
    return (cards ?? [])
      .filter((card) => !onlyWithout || !card.cardUid)
      .filter((card) => {
        const text = plain(`${card.name} ${card.departmentName ?? ''} ${card.rcpNumber ?? ''}`)
        return words.every((word) => text.includes(word))
      })
      .sort((a, b) => a.name.localeCompare(b.name, 'pl'))
  }, [cards, query, onlyWithout])

  const withoutCard = (cards ?? []).filter((card) => !card.cardUid).length

  return (
    <View style={{ flex: 1 }} onTouchStart={touch}>
      <View style={[styles.header, { borderColor: t.border }]}>
        <View style={{ flex: 1, minWidth: 240, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 17, fontWeight: '600' }}>{reader?.storeName}</Text>
          <Muted>Zalogowany: {session?.me.displayName}</Muted>
        </View>
        <View style={styles.actions}>
          <Button title="Sprawdź kartę" variant="secondary" onPress={() => router.push('/check')} />
          <Button title="Ustawienia kiosku" variant="secondary" onPress={() => router.push('/setup')} />
          <Button title="Zakończ" onPress={logout} />
        </View>
      </View>

      <View style={styles.filters}>
        <View style={{ flex: 1, minWidth: 240 }}>
          <Field label="Szukaj pracownika" value={query} onChangeText={setQuery} autoCorrect={false} />
        </View>
        <Pressable
          accessibilityRole="switch"
          accessibilityState={{ checked: onlyWithout }}
          onPress={() => setOnlyWithout((value) => !value)}
          style={[styles.toggle, { backgroundColor: onlyWithout ? t.primary : t.muted }]}
        >
          <Text style={{ color: onlyWithout ? t.primaryText : t.text, fontSize: 15, fontWeight: '600' }}>
            Bez karty ({withoutCard})
          </Text>
        </Pressable>
      </View>

      {error ? (
        <View style={{ paddingHorizontal: 20 }}>
          <Notice tone="danger">{error}</Notice>
        </View>
      ) : null}
      {cards == null && !error ? <Loading /> : null}

      <FlatList
        data={visible}
        keyExtractor={(item) => item.employeeId}
        renderItem={({ item }) => <EmployeeRow card={item} />}
        contentContainerStyle={{ paddingHorizontal: 20, paddingBottom: insets.bottom + 20 }}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={cards ? <Muted style={{ padding: 20 }}>Nikogo nie znaleziono.</Muted> : undefined}
      />
    </View>
  )
}

function EmployeeRow({ card }: { card: EmployeeCard }) {
  const t = useTheme()
  return (
    <Pressable
      accessibilityRole="button"
      onPress={() =>
        router.push({
          pathname: '/program',
          params: {
            employeeId: card.employeeId,
            name: card.name,
            rcpNumber: card.rcpNumber != null ? String(card.rcpNumber) : '',
            cardUid: card.cardUid ?? '',
          },
        })
      }
      style={({ pressed }) => [
        styles.row,
        { borderColor: t.border, backgroundColor: pressed ? t.muted : 'transparent' },
      ]}
    >
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: t.text, fontSize: 17, fontWeight: '600' }}>{card.name}</Text>
        <Muted>
          {[card.departmentName, card.rcpNumber != null ? `RCP ${card.rcpNumber}` : null].filter(Boolean).join(' · ')}
        </Muted>
      </View>
      {card.cardUid ? <Badge tone="ok">Karta {card.cardUid}</Badge> : <Badge tone="warn">Bez karty</Badge>}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: 12, padding: 20 },
  toggle: { minHeight: 54, borderRadius: 14, justifyContent: 'center', paddingHorizontal: 18 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 14,
    paddingHorizontal: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderRadius: 8,
  },
})
