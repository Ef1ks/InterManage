import Constants from 'expo-constants'
import { useState, type ReactNode } from 'react'
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { Avatar } from '@/components/Avatar'
import { CheckIcon, LogOutIcon, ServerIcon } from '@/components/icons'
import { currentServer } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { useDashboardSettings, useSaveDashboardSettings } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import { METRICS } from '@/lib/week'

/** Panel konta (arkusz z dołu): kto jest zalogowany, wybór sklepu, serwer i wylogowanie. */
export default function AccountScreen() {
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const { me, store, selectStore, logout } = useAuth()
  const [leaving, setLeaving] = useState(false)

  if (!me) return null

  const confirmLogout = () =>
    Alert.alert('Wylogować się?', 'Żeby znów korzystać z aplikacji, trzeba będzie podać login i hasło.', [
      { text: 'Anuluj', style: 'cancel' },
      {
        text: 'Wyloguj',
        style: 'destructive',
        onPress: async () => {
          // Ekrany za logowaniem (Stack.Protected) same znikają, łącznie z tym arkuszem.
          setLeaving(true)
          await logout()
        },
      },
    ])

  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: t.background }}
      contentContainerStyle={{ padding: 20, paddingTop: 28, gap: 24, paddingBottom: insets.bottom + 24 }}
    >
      <View style={{ alignItems: 'center', gap: 10 }}>
        <Avatar name={me.displayName} size={72} url={me.avatar?.url} />
        <View style={{ alignItems: 'center', gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 21, fontWeight: '700' }}>{me.displayName}</Text>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>
            {me.username} · {me.owner ? 'Właściciel' : me.roleName}
          </Text>
        </View>
      </View>

      {me.stores.length > 0 && (
        <Group title={me.stores.length > 1 ? 'Sklep' : 'Twój sklep'}>
          {me.stores.map((item, index) => {
            const active = item.id === store?.id
            return (
              <GroupRow
                key={item.id}
                first={index === 0}
                onPress={me.stores.length > 1 ? () => selectStore(item.id) : undefined}
                selected={active}
              >
                <Text style={{ color: t.text, fontSize: 16, fontWeight: '700', width: 58 }}>{item.number}</Text>
                <Text style={{ color: t.text, fontSize: 16, flex: 1 }} numberOfLines={1}>
                  {item.name}
                </Text>
                {active && me.stores.length > 1 ? <CheckIcon color={t.text} size={20} /> : null}
              </GroupRow>
            )
          })}
        </Group>
      )}

      {me.owner ? <ChartMetricSetting /> : null}

      <Group title="Aplikacja">
        <GroupRow first>
          <ServerIcon color={t.mutedText} size={18} />
          <Text style={{ color: t.text, fontSize: 16, flex: 1 }} numberOfLines={1}>
            {currentServer().replace(/^https?:\/\//, '')}
          </Text>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>wersja {Constants.expoConfig?.version ?? '-'}</Text>
        </GroupRow>
      </Group>

      <Group>
        <GroupRow first onPress={leaving ? undefined : confirmLogout}>
          {leaving ? <ActivityIndicator color={t.danger} /> : <LogOutIcon color={t.danger} size={18} />}
          <Text style={{ color: t.danger, fontSize: 16, fontWeight: '600', flex: 1 }}>Wyloguj</Text>
        </GroupRow>
      </Group>
    </ScrollView>
  )
}

/** Co pokazuje wykres wyników sklepu na ekranie głównym; ustawienie konta, to samo w przeglądarce. */
function ChartMetricSetting() {
  const t = useTheme()
  const settings = useDashboardSettings()
  const save = useSaveDashboardSettings()
  const current = settings.data?.salesChartMetric ?? 'SALES'
  return (
    <Group title="Wykres wyników sklepu">
      <View style={{ padding: 6, gap: 6 }}>
        <View style={[styles.segments, { backgroundColor: t.muted }]}>
          {METRICS.map((item) => {
            const active = item.value === current
            return (
              <Pressable
                key={item.value}
                onPress={() => save.mutate({ salesChartMetric: item.value })}
                disabled={settings.isPending}
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                style={[styles.segment, active && { backgroundColor: t.card }]}
              >
                <Text
                  style={{ color: active ? t.text : t.mutedText, fontWeight: active ? '700' : '500', fontSize: 15 }}
                >
                  {item.label}
                </Text>
              </Pressable>
            )
          })}
        </View>
        <Text style={{ color: save.isError ? t.danger : t.mutedText, fontSize: 12, paddingHorizontal: 6 }}>
          {save.isError
            ? 'Nie udało się zapisać wyboru.'
            : 'Ostatni tydzień i ten sam tydzień rok temu. Ten sam wybór działa na pulpicie w przeglądarce.'}
        </Text>
      </View>
    </Group>
  )
}

function Group({ title, children }: { title?: string; children: ReactNode }) {
  const t = useTheme()
  return (
    <View style={{ gap: 8 }}>
      {title ? (
        <Text style={{ color: t.mutedText, fontSize: 13, fontWeight: '600', paddingHorizontal: 4 }}>{title}</Text>
      ) : null}
      <View style={[styles.group, { backgroundColor: t.card, borderColor: t.border }]}>{children}</View>
    </View>
  )
}

function GroupRow({
  children,
  first,
  onPress,
  selected,
}: {
  children: ReactNode
  first?: boolean
  onPress?: () => void
  selected?: boolean
}) {
  const t = useTheme()
  const content = (pressed: boolean) => [
    styles.row,
    !first && { borderTopWidth: StyleSheet.hairlineWidth, borderColor: t.border },
    pressed && { backgroundColor: t.muted },
  ]
  if (!onPress) return <View style={content(false)}>{children}</View>
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={selected === undefined ? undefined : { selected }}
      style={({ pressed }) => content(pressed)}
    >
      {children}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  group: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 52, paddingHorizontal: 16 },
  segments: { flexDirection: 'row', borderRadius: 10, padding: 3, gap: 3 },
  segment: { flex: 1, minHeight: 38, borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
})
