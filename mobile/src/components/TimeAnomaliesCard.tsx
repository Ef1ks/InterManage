import { router } from 'expo-router'
import { Pressable, Text, View } from 'react-native'

import { ChevronRightIcon, InboxIcon } from '@/components/icons'
import { useMyAnomalies } from '@/lib/requests'
import { useTheme } from '@/lib/theme'

/** Na ekranie głównym tylko wtedy, gdy różnice w karcie czekają na wniosek: prowadzi do ekranu Wnioski. */
export function TimeAnomaliesCard() {
  const t = useTheme()
  const { data } = useMyAnomalies()
  const open = data?.open ?? 0
  if (open === 0) return null
  const ones = open % 10
  const tens = open % 100
  const word = open === 1 ? 'różnica' : ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? 'różnice' : 'różnic'

  return (
    <Pressable
      onPress={() => router.push('/requests')}
      accessibilityRole="button"
      accessibilityLabel={`${open} ${word} w czasie pracy do wyjaśnienia`}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
        borderRadius: 14,
        padding: 16,
        backgroundColor: t.warnBg,
        opacity: pressed ? 0.85 : 1,
      })}
    >
      <InboxIcon color={t.warnText} size={22} />
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: t.warnText, fontSize: 16, fontWeight: '700' }}>
          {open} {word} w czasie pracy
        </Text>
        <Text style={{ color: t.warnText, fontSize: 14 }}>Odbicia nie zgadzają się z grafikiem. Złóż wniosek.</Text>
      </View>
      <ChevronRightIcon color={t.warnText} size={20} />
    </Pressable>
  )
}
