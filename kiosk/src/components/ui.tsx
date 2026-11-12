import type { ReactNode, Ref } from 'react'
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextInputInstance,
  type TextInputProps,
  type ViewStyle,
} from 'react-native'

import { useTheme } from '@/lib/theme'

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const t = useTheme()
  return <View style={[styles.card, { backgroundColor: t.card, borderColor: t.border }, style]}>{children}</View>
}

export function Muted({ children, style }: { children: ReactNode; style?: object }) {
  const t = useTheme()
  return <Text style={[{ color: t.mutedText, fontSize: 14 }, style]}>{children}</Text>
}

export function Button({
  title,
  onPress,
  variant = 'primary',
  loading,
  disabled,
}: {
  title: string
  onPress: () => void
  variant?: 'primary' | 'secondary' | 'danger'
  loading?: boolean
  disabled?: boolean
}) {
  const t = useTheme()
  const background = variant === 'primary' ? t.primary : variant === 'danger' ? t.dangerBg : t.muted
  const color = variant === 'primary' ? t.primaryText : variant === 'danger' ? t.dangerText : t.text
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: background, opacity: disabled ? 0.5 : 1 },
        pressed && { transform: [{ scale: 0.98 }], opacity: 0.85 },
      ]}
    >
      {loading ? <ActivityIndicator color={color} /> : <Text style={[styles.buttonText, { color }]}>{title}</Text>}
    </Pressable>
  )
}

export function Field({ label, ref, ...props }: TextInputProps & { label: string; ref?: Ref<TextInputInstance> }) {
  const t = useTheme()
  return (
    <View style={{ gap: 6 }}>
      <Text style={{ color: t.mutedText, fontSize: 14, fontWeight: '500' }}>{label}</Text>
      <TextInput
        ref={ref}
        placeholderTextColor={t.mutedText}
        {...props}
        style={[styles.input, { color: t.text, borderColor: t.border, backgroundColor: t.card }, props.style]}
      />
    </View>
  )
}

export type BadgeTone = 'neutral' | 'danger' | 'info' | 'ok' | 'warn'

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: BadgeTone }) {
  const t = useTheme()
  const colors = {
    neutral: [t.muted, t.text],
    danger: [t.dangerBg, t.dangerText],
    info: [t.infoBg, t.infoText],
    ok: [t.okBg, t.okText],
    warn: [t.warnBg, t.warnText],
  }[tone]
  return (
    <View style={[styles.badge, { backgroundColor: colors[0] }]}>
      <Text style={{ color: colors[1], fontSize: 13, fontWeight: '600' }}>{children}</Text>
    </View>
  )
}

export function Notice({ children, tone }: { children: ReactNode; tone: 'danger' | 'warn' | 'info' | 'ok' }) {
  const t = useTheme()
  const [background, color] = {
    danger: [t.dangerBg, t.dangerText],
    warn: [t.warnBg, t.warnText],
    info: [t.infoBg, t.infoText],
    ok: [t.okBg, t.okText],
  }[tone]
  return (
    <View accessibilityRole="alert" style={[styles.notice, { backgroundColor: background }]}>
      <Text style={{ color, fontSize: 15 }}>{children}</Text>
    </View>
  )
}

export function Loading() {
  const t = useTheme()
  return (
    <View style={{ padding: 24, alignItems: 'center' }}>
      <ActivityIndicator color={t.mutedText} />
    </View>
  )
}

const styles = StyleSheet.create({
  card: { borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, padding: 18, gap: 14 },
  button: { minHeight: 54, borderRadius: 14, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 20 },
  buttonText: { fontSize: 17, fontWeight: '600' },
  input: { minHeight: 54, borderWidth: 1, borderRadius: 14, paddingHorizontal: 14, fontSize: 17 },
  badge: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 3, alignSelf: 'flex-start' },
  notice: { borderRadius: 14, padding: 14 },
})
