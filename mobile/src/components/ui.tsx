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

export function SectionTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  const t = useTheme()
  return (
    <View style={styles.sectionTitle}>
      <Text style={[styles.sectionText, { color: t.text }]}>{children}</Text>
      {aside}
    </View>
  )
}

export function Muted({ children, style }: { children: ReactNode; style?: object }) {
  const t = useTheme()
  return <Text style={[{ color: t.mutedText, fontSize: 13 }, style]}>{children}</Text>
}

export function Button({
  title,
  onPress,
  variant = 'primary',
  loading,
  disabled,
  icon,
}: {
  title: string
  onPress: () => void
  variant?: 'primary' | 'secondary' | 'ghost'
  loading?: boolean
  disabled?: boolean
  icon?: ReactNode
}) {
  const t = useTheme()
  const background = variant === 'primary' ? t.primary : variant === 'secondary' ? t.muted : 'transparent'
  const color = variant === 'primary' ? t.primaryText : t.text
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: background, opacity: disabled ? 0.5 : pressed ? 0.8 : 1 },
        pressed && { transform: [{ scale: 0.98 }] },
      ]}
    >
      {loading ? (
        <ActivityIndicator color={color} />
      ) : (
        <View style={styles.buttonRow}>
          {icon}
          <Text style={[styles.buttonText, { color }]}>{title}</Text>
        </View>
      )}
    </Pressable>
  )
}

export function Field({ label, ref, ...props }: TextInputProps & { label: string; ref?: Ref<TextInputInstance> }) {
  const t = useTheme()
  return (
    <View style={{ gap: 6 }}>
      <Text style={{ color: t.mutedText, fontSize: 13, fontWeight: '500' }}>{label}</Text>
      <TextInput
        ref={ref}
        placeholderTextColor={t.mutedText}
        {...props}
        style={[styles.input, { color: t.text, borderColor: t.border, backgroundColor: t.card }, props.style]}
      />
    </View>
  )
}

export type BadgeTone = 'neutral' | 'danger' | 'promo' | 'info' | 'ok' | 'warn'

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: BadgeTone }) {
  const t = useTheme()
  const colors = {
    neutral: [t.muted, t.text],
    danger: [t.dangerBg, t.dangerText],
    promo: [t.promoBg, t.promoText],
    info: [t.infoBg, t.infoText],
    ok: [t.okBg, t.okText],
    warn: [t.warnBg, t.warnText],
  }[tone]
  return (
    <View style={[styles.badge, { backgroundColor: colors[0] }]}>
      <Text style={{ color: colors[1], fontSize: 12, fontWeight: '600' }}>{children}</Text>
    </View>
  )
}

/** Kafelek liczby jak `StatTile` w raportach. */
export function Tile({
  label,
  value,
  hint,
  tone,
}: {
  label: string
  value: string
  hint?: string | null
  tone?: 'danger' | 'promo'
}) {
  const t = useTheme()
  const background = tone === 'danger' ? t.dangerBg : tone === 'promo' ? t.promoBg : t.muted
  const color = tone === 'danger' ? t.dangerText : tone === 'promo' ? t.promoText : t.text
  return (
    <View style={[styles.tile, { backgroundColor: background }]}>
      <Text style={{ color, opacity: 0.75, fontSize: 12 }}>{label}</Text>
      <Text style={{ color, fontSize: 20, fontWeight: '700', fontVariant: ['tabular-nums'] }}>{value}</Text>
      {hint ? <Text style={{ color, opacity: 0.75, fontSize: 12 }}>{hint}</Text> : null}
    </View>
  )
}

export function Row({ label, value }: { label: string; value: ReactNode }) {
  const t = useTheme()
  return (
    <View style={[styles.row, { borderColor: t.border }]}>
      <Text style={{ color: t.mutedText, fontSize: 14, flexShrink: 0 }}>{label}</Text>
      {typeof value === 'string' ? (
        <Text style={{ color: t.text, fontSize: 14, flex: 1, textAlign: 'right' }}>{value}</Text>
      ) : (
        value
      )}
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

export function ErrorText({ children }: { children: ReactNode }) {
  const t = useTheme()
  return (
    <Text accessibilityRole="alert" style={{ color: t.danger, fontSize: 14 }}>
      {children}
    </Text>
  )
}

const styles = StyleSheet.create({
  card: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, padding: 16, gap: 12 },
  sectionTitle: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  sectionText: { fontSize: 16, fontWeight: '700' },
  button: { minHeight: 50, borderRadius: 12, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18 },
  buttonRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  buttonText: { fontSize: 16, fontWeight: '600' },
  input: { minHeight: 50, borderWidth: 1, borderRadius: 12, paddingHorizontal: 14, fontSize: 16 },
  badge: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 3, alignSelf: 'flex-start' },
  tile: { flexGrow: 1, flexBasis: '45%', borderRadius: 12, padding: 12, gap: 2 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
})
