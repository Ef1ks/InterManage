import { router } from 'expo-router'
import { useMemo } from 'react'
import { Pressable, Text, View } from 'react-native'

import { ChevronRightIcon } from '@/components/icons'
import { ScheduleDayContent } from '@/components/ScheduleDay'
import { Loading, Muted } from '@/components/ui'
import { addDays, shortDate } from '@/lib/format'
import { useMySchedule } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import { todayIso, WEEKDAYS, weekdayIndex } from '@/lib/week'

/** Skrót „Mój grafik” na ekranie głównym: dziś i najbliższa zmiana. Kto nie jest pracownikiem, nie widzi karty. */
export function MyScheduleCard({ storeNumber }: { storeNumber?: string }) {
  const t = useTheme()
  const range = useMemo(() => {
    const today = todayIso()
    return { from: today, to: addDays(today, 13) }
  }, [])
  const { data, isPending, isError } = useMySchedule(range)

  if (data && !data.employee) return null

  const today = data?.days[0]
  const next = data?.days.slice(1).find((day) => day.shifts.length > 0 || day.leave)

  return (
    <Pressable
      onPress={() => router.push('/schedule')}
      accessibilityRole="button"
      accessibilityLabel="Mój grafik"
      style={({ pressed }) => ({
        borderRadius: 14,
        borderWidth: 1,
        borderColor: t.border,
        backgroundColor: pressed ? t.muted : t.card,
        padding: 16,
        gap: 12,
      })}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <Text style={{ color: t.text, fontSize: 17, fontWeight: '700' }}>Mój grafik</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>Cały tydzień</Text>
          <ChevronRightIcon color={t.mutedText} size={18} />
        </View>
      </View>
      {isPending ? (
        <Loading />
      ) : isError || !today ? (
        <Muted>Nie udało się wczytać grafiku.</Muted>
      ) : (
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <View style={{ flex: 1, gap: 4 }}>
            <Muted>Dziś</Muted>
            <ScheduleDayContent day={today} currentStore={storeNumber} />
          </View>
          <View style={{ width: 1, backgroundColor: t.border }} />
          <View style={{ flex: 1, gap: 4 }}>
            <Muted>{next ? `${WEEKDAYS[weekdayIndex(next.date)]} ${shortDate(next.date)}` : 'Następna zmiana'}</Muted>
            {next ? (
              <ScheduleDayContent day={next} currentStore={storeNumber} />
            ) : (
              <Text style={{ color: t.mutedText, fontSize: 15 }}>Brak w opublikowanym grafiku</Text>
            )}
          </View>
        </View>
      )}
    </Pressable>
  )
}
