import { Pressable, Text, View } from 'react-native'

import { money, quantity } from '@/lib/format'
import { useTheme } from '@/lib/theme'
import type { ProductListItem } from '@/lib/types'

/** Kilka artykułów pasuje do kodu: wybór jak lista w Magazynie. */
export function ProductChoices({ items, onPick }: { items: ProductListItem[]; onPick: (itm8: string) => void }) {
  const t = useTheme()
  return (
    <View style={{ gap: 8 }}>
      {items.map((item) => (
        <Pressable
          key={item.itm8}
          onPress={() => onPick(item.itm8)}
          style={({ pressed }) => ({
            backgroundColor: pressed ? t.muted : t.card,
            borderColor: t.border,
            borderWidth: 1,
            borderRadius: 12,
            padding: 12,
            gap: 2,
            opacity: item.active === false ? 0.6 : 1,
          })}
        >
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={2}>
            {item.name ?? item.itm8}
            {item.brand ? <Text style={{ color: t.mutedText, fontWeight: '400' }}> · {item.brand}</Text> : null}
          </Text>
          <Text style={{ color: t.mutedText, fontSize: 13, fontVariant: ['tabular-nums'] }}>
            EAN {item.ean} · ITM8 {item.itm8.replace(/^0+/, '')}
          </Text>
          <Text style={{ color: t.mutedText, fontSize: 13, fontVariant: ['tabular-nums'] }}>
            Stan {quantity(item.stock)} · {money(item.promoPrice ?? item.salePrice)}
            {item.promoPrice != null ? ' (promocja)' : ''}
          </Text>
        </Pressable>
      ))}
    </View>
  )
}
