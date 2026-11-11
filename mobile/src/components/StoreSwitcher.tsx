import { useState } from 'react'
import { Modal, Pressable, ScrollView, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { useAuth } from '@/lib/auth'
import { useTheme } from '@/lib/theme'

/** Zmiana sklepu w prawym rogu nagłówka: numer bieżącego sklepu, dotknięcie otwiera listę dostępnych. */
export function StoreSwitcher() {
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const { me, store, selectStore } = useAuth()
  const [open, setOpen] = useState(false)

  if (!me || !store) return null
  const several = me.stores.length > 1

  return (
    <>
      <Pressable
        onPress={() => setOpen(true)}
        disabled={!several}
        hitSlop={8}
        accessibilityRole="button"
        accessibilityLabel={`Sklep ${store.number}${several ? ', zmień sklep' : ''}`}
        style={({ pressed }) => ({
          flexDirection: 'row',
          alignItems: 'center',
          gap: 8,
          paddingLeft: 12,
          paddingRight: several ? 8 : 12,
          paddingVertical: 6,
          borderRadius: 8,
          borderWidth: 1,
          borderColor: t.border,
          backgroundColor: pressed ? t.muted : t.card,
        })}
      >
        <View>
          <Text style={{ color: t.mutedText, fontSize: 10, lineHeight: 12 }}>Sklep</Text>
          <Text style={{ color: t.text, fontWeight: '700', fontSize: 14, lineHeight: 17 }}>{store.number}</Text>
        </View>
        {several && <Text style={{ color: t.mutedText, fontSize: 13 }}>▾</Text>}
      </Pressable>

      <Modal visible={open} transparent animationType="fade" onRequestClose={() => setOpen(false)}>
        <Pressable style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.25)' }} onPress={() => setOpen(false)}>
          <View
            onStartShouldSetResponder={() => true}
            style={{
              position: 'absolute',
              top: insets.top + 52,
              right: 12,
              minWidth: 220,
              maxWidth: 320,
              maxHeight: 420,
              borderRadius: 14,
              backgroundColor: t.card,
              borderWidth: 1,
              borderColor: t.border,
              paddingVertical: 6,
              shadowColor: '#000',
              shadowOpacity: 0.18,
              shadowRadius: 16,
              shadowOffset: { width: 0, height: 6 },
              elevation: 8,
            }}
          >
            <Text style={{ color: t.mutedText, fontSize: 12, paddingHorizontal: 14, paddingVertical: 6 }}>
              Wybierz sklep
            </Text>
            <ScrollView>
              {me.stores.map((item) => {
                const active = item.id === store.id
                return (
                  <Pressable
                    key={item.id}
                    onPress={() => {
                      selectStore(item.id)
                      setOpen(false)
                    }}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    style={({ pressed }) => ({
                      flexDirection: 'row',
                      alignItems: 'center',
                      gap: 10,
                      paddingHorizontal: 14,
                      paddingVertical: 11,
                      backgroundColor: pressed ? t.muted : 'transparent',
                    })}
                  >
                    <Text style={{ color: t.text, fontWeight: '700', width: 52 }}>{item.number}</Text>
                    <Text style={{ color: t.text, flex: 1 }} numberOfLines={1}>
                      {item.name}
                    </Text>
                    {active && <Text style={{ color: t.text, fontWeight: '700' }}>✓</Text>}
                  </Pressable>
                )
              })}
            </ScrollView>
          </View>
        </Pressable>
      </Modal>
    </>
  )
}
