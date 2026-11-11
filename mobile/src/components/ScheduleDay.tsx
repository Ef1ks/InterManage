import { Text, View } from 'react-native'

import { date as formatDate } from '@/lib/format'
import { useTheme } from '@/lib/theme'
import type { MySchedule } from '@/lib/types'
import { hour, hoursLabel, LEAVE_LABELS, shiftMinutes } from '@/lib/week'

export type ScheduleDayData = MySchedule['days'][number]

/** Co jest danego dnia w grafiku, jednym zdaniem: godziny, dzień wolny albo urlop. */
export function ScheduleDayContent({ day, currentStore }: { day: ScheduleDayData; currentStore?: string }) {
  const t = useTheme()
  if (day.leave) {
    return <Text style={{ color: t.infoText, fontSize: 16, fontWeight: '600' }}>{LEAVE_LABELS[day.leave]}</Text>
  }
  if (day.shifts.length > 0) {
    return (
      <View style={{ gap: 2 }}>
        {day.shifts.map((shift) => (
          <View key={`${shift.start}-${shift.storeNumber}`}>
            <Text style={{ color: t.text, fontSize: 17, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
              {hour(shift.start)}–{hour(shift.end)}
              <Text style={{ color: t.mutedText, fontSize: 14, fontWeight: '500' }}>
                {'  '}
                {hoursLabel(shiftMinutes(shift.start, shift.end))} h
              </Text>
            </Text>
            <Text style={{ color: t.mutedText, fontSize: 13 }} numberOfLines={1}>
              {shift.department}
              {shift.storeNumber !== currentStore ? ` · sklep ${shift.storeNumber}` : ''}
            </Text>
          </View>
        ))}
      </View>
    )
  }
  if (day.dayOff) {
    return (
      <Text style={{ color: t.text, fontSize: 16 }}>
        Wolne{' '}
        <Text style={{ color: t.mutedText }}>
          {day.dayOff.kind === 'WN'
            ? `(WN, za ${day.dayOff.forDate ? formatDate(day.dayOff.forDate) : 'niedzielę'})`
            : '(W5)'}
        </Text>
      </Text>
    )
  }
  return <Text style={{ color: t.mutedText, fontSize: 16 }}>Wolne</Text>
}
