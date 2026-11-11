import { useState } from 'react'
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'

import { ChevronRightIcon } from '@/components/icons'
import { useDockInset } from '@/components/FloatingDock'
import { ScheduleDayContent } from '@/components/ScheduleDay'
import { Card, ErrorText, Loading, Muted } from '@/components/ui'
import { addDays, shortDate } from '@/lib/format'
import { useAuth } from '@/lib/auth'
import { useMySchedule } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import { hoursLabel, mondayOf, shiftMinutes, todayIso, WEEKDAYS, weekdayIndex } from '@/lib/week'

/** Mój grafik: tydzień pon.-niedz. z opublikowanego grafiku, strzałkami poprzedni i następny. */
export default function ScheduleScreen() {
  const t = useTheme()
  const { store } = useAuth()
  const dockInset = useDockInset()
  const today = todayIso()
  const [monday, setMonday] = useState(() => mondayOf(today))
  const range = { from: monday, to: addDays(monday, 6) }
  const { data, isPending, isError } = useMySchedule(range)

  const minutes =
    data?.days.reduce(
      (sum, day) => sum + day.shifts.reduce((inner, shift) => inner + shiftMinutes(shift.start, shift.end), 0),
      0,
    ) ?? 0

  return (
    <ScrollView contentContainerStyle={{ padding: 20, gap: 16, paddingBottom: dockInset }}>
      <View style={styles.weekBar}>
        <WeekArrow direction="back" onPress={() => setMonday(addDays(monday, -7))} />
        <View style={{ alignItems: 'center', gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 17, fontWeight: '700' }}>
            {shortDate(range.from)} – {shortDate(range.to)}
          </Text>
          {monday === mondayOf(today) ? (
            <Muted>Ten tydzień</Muted>
          ) : (
            <Pressable onPress={() => setMonday(mondayOf(today))} hitSlop={8}>
              <Text style={{ color: t.chartNow, fontSize: 13, fontWeight: '600' }}>Wróć do tego tygodnia</Text>
            </Pressable>
          )}
        </View>
        <WeekArrow direction="forward" onPress={() => setMonday(addDays(monday, 7))} />
      </View>

      {isPending ? (
        <Loading />
      ) : isError ? (
        <ErrorText>Nie udało się wczytać grafiku.</ErrorText>
      ) : !data.employee ? (
        <Card>
          <Muted style={{ fontSize: 15 }}>Twoje konto nie jest przypisane jako pracownik sklepu.</Muted>
        </Card>
      ) : (
        <>
          <View style={[styles.list, { backgroundColor: t.card, borderColor: t.border }]}>
            {data.days.map((day, index) => {
              const isToday = day.date === today
              return (
                <View
                  key={day.date}
                  style={[
                    styles.day,
                    index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderColor: t.border },
                    isToday && { backgroundColor: t.infoBg + '55' },
                  ]}
                >
                  <View style={{ width: 92 }}>
                    <Text style={{ color: t.text, fontSize: 15, fontWeight: isToday ? '700' : '600' }}>
                      {WEEKDAYS[weekdayIndex(day.date)]}
                    </Text>
                    <Text style={{ color: t.mutedText, fontSize: 13 }}>{isToday ? 'dziś' : shortDate(day.date)}</Text>
                  </View>
                  <View style={{ flex: 1 }}>
                    <ScheduleDayContent day={day} currentStore={store?.number} />
                  </View>
                </View>
              )
            })}
          </View>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 4 }}>
            <Muted>Razem w tygodniu</Muted>
            <Text style={{ color: t.text, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
              {hoursLabel(minutes)} h
            </Text>
          </View>
          <Muted style={{ paddingHorizontal: 4, lineHeight: 19 }}>
            Widzisz opublikowany grafik. Zmiany, których kierownik jeszcze nie opublikował, pojawią się po publikacji.
          </Muted>
        </>
      )}
    </ScrollView>
  )
}

function WeekArrow({ direction, onPress }: { direction: 'back' | 'forward'; onPress: () => void }) {
  const t = useTheme()
  return (
    <Pressable
      onPress={onPress}
      hitSlop={8}
      accessibilityRole="button"
      accessibilityLabel={direction === 'back' ? 'Poprzedni tydzień' : 'Następny tydzień'}
      style={({ pressed }) => [styles.arrow, { backgroundColor: pressed ? t.border : t.muted }]}
    >
      <View style={direction === 'back' ? { transform: [{ rotate: '180deg' }] } : undefined}>
        <ChevronRightIcon color={t.text} size={20} />
      </View>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  weekBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  arrow: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  list: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, overflow: 'hidden' },
  day: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    minHeight: 64,
  },
})
