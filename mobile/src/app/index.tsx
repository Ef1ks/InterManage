import { router } from 'expo-router'
import { useState } from 'react'
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'

import { ArrowRightIcon, SearchIcon } from '@/components/icons'
import { useDockInset } from '@/components/FloatingDock'
import { MyScheduleCard } from '@/components/MyScheduleCard'
import { ProductChoices } from '@/components/ProductChoices'
import { StoreResultsCard } from '@/components/StoreResultsCard'
import { Card, ErrorText, Muted } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { lookupArticle } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import type { ProductListItem } from '@/lib/types'

/**
 * Ekran główny: wyszukanie artykułu (skaner jest w pływającym pasku), pod spodem mój grafik, a u właściciela wyniki sklepu.
 * Konto jest w panelu konta.
 */
export default function HomeScreen() {
  const t = useTheme()
  const { me, store, canSeeArticles } = useAuth()
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [choices, setChoices] = useState<ProductListItem[] | null>(null)
  const [focused, setFocused] = useState(false)
  const dockInset = useDockInset()

  if (!me) return null

  const search = async () => {
    const wanted = code.trim()
    if (!wanted || !store || busy) return
    setBusy(true)
    setMessage(null)
    setChoices(null)
    try {
      const result = await lookupArticle(store.id, wanted)
      if (result.kind === 'found') {
        setCode('')
        router.push({ pathname: '/article/[itm8]', params: { itm8: result.itm8 } })
      } else if (result.kind === 'several') {
        setChoices(result.items)
      } else {
        setMessage(`Nie znaleziono artykułu „${wanted}”.`)
      }
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : 'Nie udało się wyszukać artykułu.')
    } finally {
      setBusy(false)
    }
  }

  if (!canSeeArticles) {
    return (
      <ScrollView contentContainerStyle={{ padding: 20, gap: 20, paddingBottom: dockInset }}>
        <MyScheduleCard storeNumber={store?.number} />
        <Card>
          <Text style={{ color: t.text, fontSize: 17, fontWeight: '700' }}>Brak dostępu do artykułów</Text>
          <Muted style={{ fontSize: 15, lineHeight: 21 }}>
            Twoja rola nie ma dostępu do Magazynu ani raportów sprzedaży. Poproś właściciela o uprawnienie.
          </Muted>
        </Card>
      </ScrollView>
    )
  }

  const canSearch = code.trim().length > 0

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView
        contentContainerStyle={{ padding: 20, gap: 28, paddingBottom: dockInset }}
        keyboardShouldPersistTaps="handled"
      >
        <View style={{ gap: 10 }}>
          <Text style={{ color: t.mutedText, fontSize: 13, fontWeight: '600', paddingHorizontal: 4 }}>
            Wyszukaj artykuł
          </Text>
          <View style={[styles.search, { backgroundColor: t.card, borderColor: focused ? t.text : t.border }]}>
            <SearchIcon color={t.mutedText} size={20} />
            <TextInput
              value={code}
              onChangeText={(value) => {
                setCode(value)
                setMessage(null)
              }}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              placeholder="EAN, ITM8 albo nazwa"
              placeholderTextColor={t.mutedText}
              autoCapitalize="characters"
              autoCorrect={false}
              returnKeyType="search"
              onSubmitEditing={search}
              accessibilityLabel="Wyszukaj artykuł po EAN, ITM8 albo nazwie"
              style={{ flex: 1, color: t.text, fontSize: 16, paddingVertical: 0 }}
            />
            <Pressable
              onPress={search}
              disabled={!canSearch || busy}
              accessibilityRole="button"
              accessibilityLabel="Szukaj"
              hitSlop={6}
              style={({ pressed }) => [
                styles.searchButton,
                { backgroundColor: canSearch ? t.primary : t.muted, opacity: pressed ? 0.8 : 1 },
              ]}
            >
              {busy ? (
                <ActivityIndicator color={t.primaryText} size="small" />
              ) : (
                <ArrowRightIcon color={canSearch ? t.primaryText : t.mutedText} size={18} />
              )}
            </Pressable>
          </View>
          {message ? <ErrorText>{message}</ErrorText> : null}
        </View>

        {choices ? (
          <View style={{ gap: 10 }}>
            <Muted style={{ paddingHorizontal: 4 }}>Pasuje kilka artykułów, wybierz właściwy:</Muted>
            <ProductChoices
              items={choices}
              onPick={(itm8) => router.push({ pathname: '/article/[itm8]', params: { itm8 } })}
            />
          </View>
        ) : null}

        <MyScheduleCard storeNumber={store?.number} />
        {/* Na razie tylko właściciel; dostęp dla innych ról ustalimy później. */}
        {me.owner && store ? <StoreResultsCard storeId={store.id} storeNumber={store.number} /> : null}
      </ScrollView>
    </KeyboardAvoidingView>
  )
}

const styles = StyleSheet.create({
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minHeight: 54,
    borderRadius: 14,
    borderWidth: 1,
    paddingLeft: 14,
    paddingRight: 7,
  },
  searchButton: { width: 40, height: 40, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
})
