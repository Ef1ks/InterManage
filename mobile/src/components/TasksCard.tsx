import { router } from 'expo-router'
import { Pressable, Text, View } from 'react-native'

import { CheckIcon, ChevronRightIcon } from '@/components/icons'
import { useTaskCounts } from '@/lib/tasks'
import { useTheme } from '@/lib/theme'

function tasksWord(count: number) {
  const ones = count % 10
  const tens = count % 100
  if (count === 1) return 'zadanie'
  return ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? 'zadania' : 'zadań'
}

/**
 * Na ekranie głównym, gdy coś czeka: moje zadania do zrobienia, a u zlecającego wykonane czekające na przyjęcie.
 * Prowadzi do ekranu Zadania.
 */
export function TasksCard({ storeId }: { storeId: string }) {
  const t = useTheme()
  const { data } = useTaskCounts(storeId)
  const open = data?.open ?? 0
  const waiting = data?.waiting ?? 0
  if (open === 0 && waiting === 0) return null

  const title = open > 0 ? `${open} ${tasksWord(open)} do zrobienia` : `${waiting} do przyjęcia`
  const detail =
    open > 0 && waiting > 0
      ? `I ${waiting} wykonanych czeka na Twoje przyjęcie.`
      : open > 0
        ? 'Odhacz, co zrobione.'
        : 'Wykonane zadania czekają na Twoje przyjęcie.'

  return (
    <Pressable
      onPress={() => router.push('/tasks')}
      accessibilityRole="button"
      accessibilityLabel={`${title}. ${detail}`}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
        borderRadius: 14,
        padding: 16,
        backgroundColor: t.infoBg,
        opacity: pressed ? 0.85 : 1,
      })}
    >
      <CheckIcon color={t.infoText} size={22} />
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: t.infoText, fontSize: 16, fontWeight: '700' }}>{title}</Text>
        <Text style={{ color: t.infoText, fontSize: 14 }}>{detail}</Text>
      </View>
      <ChevronRightIcon color={t.infoText} size={20} />
    </Pressable>
  )
}
