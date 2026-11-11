import { router } from 'expo-router'
import { useState } from 'react'
import { Image, Pressable, Text, View } from 'react-native'

import { imageSource } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { useTheme } from '@/lib/theme'

/** Inicjały z imienia i nazwiska: pierwsza litera pierwszego i ostatniego słowa (Anna Maria Nowak → AN). */
export function initialsOf(name: string) {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const first = words[0][0] ?? ''
  const last = words.length > 1 ? (words[words.length - 1][0] ?? '') : ''
  return (first + last).toUpperCase()
}

/** Zdjęcie profilowe w kółku; bez zdjęcia albo gdy się nie wczyta, inicjały. */
export function Avatar({ name, size, url }: { name: string; size: number; url?: string | null }) {
  const t = useTheme()
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  if (url && failedUrl !== url) {
    return (
      <Image
        source={imageSource(url)}
        onError={() => setFailedUrl(url)}
        accessibilityIgnoresInvertColors
        style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: t.primary }}
      />
    )
  }
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: t.primary,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: t.primaryText, fontWeight: '700', fontSize: size * 0.38 }}>{initialsOf(name)}</Text>
    </View>
  )
}

/** Inicjały w prawym rogu nagłówka: otwierają panel konta. */
export function AccountButton() {
  const { me } = useAuth()
  if (!me) return null
  return (
    <Pressable
      onPress={() => router.push('/account')}
      hitSlop={8}
      accessibilityRole="button"
      accessibilityLabel={`Konto: ${me.displayName}`}
      style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}
    >
      <Avatar name={me.displayName} size={34} url={me.avatar?.url} />
    </Pressable>
  )
}
