import * as Haptics from 'expo-haptics'
import { router, useLocalSearchParams } from 'expo-router'
import { useState, type ReactNode } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'

import { ChevronRightIcon } from '@/components/icons'
import { Card, Muted } from '@/components/ui'
import { shareOf, zloty } from '@/lib/format'
import {
  canShiftForward,
  currentPeriod,
  isPeriodMode,
  PERIOD_MODES,
  periodLabel,
  resolveAnchor,
  shiftAnchor,
  type PeriodMode,
} from '@/lib/periods'
import { useReportsOverview } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import type { ProfitTargetProgress } from '@/lib/types'
import { changePercent, formatChange } from '@/lib/week'

/** Przełącznik kilku wartości (okres, miara, rodzaj). */
export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  const t = useTheme()
  return (
    <View style={[styles.segmented, { backgroundColor: t.muted }]} accessibilityRole="tablist">
      {options.map((option) => {
        const selected = option.value === value
        return (
          <Pressable
            key={option.value}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            onPress={() => {
              if (selected) return
              void Haptics.selectionAsync().catch(() => undefined)
              onChange(option.value)
            }}
            style={[styles.segment, selected && { backgroundColor: t.card, borderColor: t.border }]}
          >
            <Text
              numberOfLines={1}
              style={{ color: selected ? t.text : t.mutedText, fontSize: 14, fontWeight: selected ? '700' : '500' }}
            >
              {option.label}
            </Text>
          </Pressable>
        )
      })}
    </View>
  )
}

/**
 * Okres raportu: tryb i data z adresu (`okres`, `data`), żeby kolejny poziom drzewa otworzył się z tym samym okresem.
 * Domyślnie ostatni dzień ze sprzedażą; tydzień zawsze pełny, pon.-niedz.
 */
export function useReportPeriod(storeId: string, defaultMode: PeriodMode) {
  const params = useLocalSearchParams<{ okres?: string; data?: string }>()
  const overview = useReportsOverview(storeId)
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const [mode, setMode] = useState<PeriodMode>(isPeriodMode(params.okres) ? params.okres : defaultMode)
  const [chosen, setChosen] = useState<string | null>(typeof params.data === 'string' ? params.data : null)
  const base = chosen ?? lastSaleDate
  const anchor = base ? resolveAnchor(mode, base, lastSaleDate) : null
  const range = anchor ? currentPeriod(mode, anchor, lastSaleDate) : null
  return {
    overview,
    lastSaleDate,
    mode,
    anchor,
    range,
    setMode: (next: PeriodMode) => setMode(next),
    setAnchor: (next: string) => setChosen(next),
    /** Parametry do adresu kolejnego ekranu z tym samym okresem. */
    params: anchor ? { okres: mode, data: anchor } : {},
  }
}

export function PeriodPicker({
  period,
}: {
  period: Pick<
    ReturnType<typeof useReportPeriod>,
    'mode' | 'anchor' | 'range' | 'lastSaleDate' | 'setMode' | 'setAnchor'
  >
}) {
  const t = useTheme()
  const { mode, anchor, range, lastSaleDate } = period
  const forward = anchor != null && canShiftForward(mode, anchor, lastSaleDate)
  const step = (direction: -1 | 1) => {
    if (!anchor) return
    void Haptics.selectionAsync().catch(() => undefined)
    period.setAnchor(shiftAnchor(mode, anchor, direction))
  }
  return (
    <View style={{ gap: 10 }}>
      <Segmented options={PERIOD_MODES} value={mode} onChange={period.setMode} />
      <View style={styles.periodRow}>
        <Arrow direction="back" disabled={!anchor} onPress={() => step(-1)} />
        <View style={{ alignItems: 'center', flex: 1, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 16, fontWeight: '700' }} numberOfLines={1} adjustsFontSizeToFit>
            {range ? periodLabel(mode, range) : '–'}
          </Text>
          {lastSaleDate &&
          range &&
          range.from !== currentPeriod(mode, resolveAnchor(mode, lastSaleDate, lastSaleDate), lastSaleDate).from ? (
            <Pressable onPress={() => period.setAnchor(lastSaleDate)} hitSlop={8}>
              <Text style={{ color: t.chartNow, fontSize: 13, fontWeight: '600' }}>Wróć do ostatniego dnia</Text>
            </Pressable>
          ) : (
            <Muted>wobec tych samych dni rok temu</Muted>
          )}
        </View>
        <Arrow direction="forward" disabled={!forward} onPress={() => step(1)} />
      </View>
    </View>
  )
}

function Arrow({
  direction,
  disabled,
  onPress,
}: {
  direction: 'back' | 'forward'
  disabled: boolean
  onPress: () => void
}) {
  const t = useTheme()
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      hitSlop={8}
      accessibilityRole="button"
      accessibilityLabel={direction === 'back' ? 'Poprzedni okres' : 'Następny okres'}
      style={({ pressed }) => [
        styles.arrow,
        { backgroundColor: pressed ? t.border : t.muted, opacity: disabled ? 0.35 : 1 },
      ]}
    >
      <View style={direction === 'back' ? { transform: [{ rotate: '180deg' }] } : undefined}>
        <ChevronRightIcon color={t.text} size={20} />
      </View>
    </Pressable>
  )
}

/** Zmiana % kwoty ze strzałką; `neutral` = bez koloru dobra / zła (np. zakup). */
export function ChangeText({
  previous,
  current,
  neutral,
  size = 13,
}: {
  previous: number | null | undefined
  current: number | null | undefined
  neutral?: boolean
  size?: number
}) {
  const t = useTheme()
  const change = previous == null || current == null ? null : changePercent(current, previous)
  if (change == null) return <Text style={{ color: t.mutedText, fontSize: size }}>–</Text>
  const color = neutral ? t.mutedText : change >= 0 ? t.up : t.danger
  return (
    <Text style={{ color, fontSize: size, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
      {change >= 0 ? '▲ ' : '▼ '}
      {formatChange(change)}
    </Text>
  )
}

/** Zmiana w punktach procentowych; `inverted` = wzrost jest zły (straty). */
export function PointsText({
  previous,
  current,
  inverted,
}: {
  previous: number | null | undefined
  current: number | null | undefined
  inverted?: boolean
}) {
  const t = useTheme()
  if (previous == null || current == null) return <Text style={{ color: t.mutedText, fontSize: 13 }}>–</Text>
  const delta = current - previous
  const good = inverted ? delta <= 0 : delta >= 0
  return (
    <Text style={{ color: good ? t.up : t.danger, fontSize: 13, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
      {`${delta >= 0 ? '+' : '−'}${Math.abs(delta).toFixed(1).replace('.', ',')} p.p.`}
    </Text>
  )
}

/** Kafelek sumy wobec roku temu: kwota, zmiana i wartość rok temu. */
export function CompareTile({
  label,
  value,
  previousLabel,
  change,
  note,
}: {
  label: string
  value: string
  previousLabel: string | null
  change: ReactNode
  note?: ReactNode
}) {
  const t = useTheme()
  return (
    <View style={[styles.tile, { backgroundColor: t.card, borderColor: t.border }]}>
      <Text style={{ color: t.mutedText, fontSize: 12 }}>{label}</Text>
      <Text
        style={{ color: t.text, fontSize: 20, fontWeight: '700', fontVariant: ['tabular-nums'] }}
        numberOfLines={1}
        adjustsFontSizeToFit
      >
        {value}
      </Text>
      {change}
      {previousLabel ? <Text style={{ color: t.mutedText, fontSize: 12 }}>rok temu {previousLabel}</Text> : null}
      {note}
    </View>
  )
}

export function TileGrid({ children }: { children: ReactNode }) {
  return <View style={styles.tileGrid}>{children}</View>
}

/** Dzień roku jako część roku (1.01 = 1/365), do planu celu „na ten dzień”, jak `yearShare` w przeglądarce. */
export function yearShare(date: string) {
  const [year, month, day] = date.split('-').map(Number)
  const start = Date.UTC(year, 0, 1)
  const days = (Date.UTC(year + 1, 0, 1) - start) / 86_400_000
  return ((Date.UTC(year, month - 1, day) - start) / 86_400_000 + 1) / days
}

/**
 * Cel marży po likwidacjach na rok: osiągnięta kwota, % celu, kreska planu (cel rozłożony równo na dni roku) i ile zł
 * przed albo za planem. `null`, gdy sklep nie ma celu.
 */
export function GoalBar({ progress, label }: { progress: ProfitTargetProgress; label?: string }) {
  const t = useTheme()
  const target = progress.target?.marginAfterLossesValue ?? null
  const achieved = progress.marginAfterLossesValue
  if (target == null || achieved == null || target <= 0) {
    return <Muted>Brak celu marży na {progress.year}. Cel ustawia się w przeglądarce.</Muted>
  }
  const share = achieved / target
  const planned = yearShare(progress.to)
  const difference = achieved - target * planned
  const ahead = difference >= 0
  return (
    <View
      style={{ gap: 6 }}
      accessible
      accessibilityLabel={`${label ?? 'Cel marży'}: ${Math.round(share * 1000) / 10}% celu, ${
        ahead ? `${zloty(difference)} przed planem` : `${zloty(-difference)} do planu`
      }`}
    >
      <View style={styles.goalHeader}>
        <Text style={{ color: t.text, fontSize: 14, fontWeight: '600' }}>{label ?? `Cel marży ${progress.year}`}</Text>
        <View style={[styles.goalPill, { backgroundColor: ahead ? t.okBg : t.warnBg }]}>
          <Text style={{ color: ahead ? t.okText : t.warnText, fontSize: 12, fontWeight: '700' }}>
            {ahead ? `+${zloty(difference)}` : `−${zloty(-difference)}`}
          </Text>
        </View>
      </View>
      <View style={[styles.goalTrack, { backgroundColor: t.muted }]}>
        <View
          style={[
            styles.goalFill,
            { width: `${Math.min(Math.max(share, 0), 1) * 100}%`, backgroundColor: ahead ? t.up : t.chartYearAgo },
          ]}
        />
        <View style={[styles.goalPlan, { left: `${Math.min(planned, 1) * 100}%`, backgroundColor: t.text }]} />
      </View>
      <View style={styles.goalHeader}>
        <Text style={{ color: t.mutedText, fontSize: 12, fontVariant: ['tabular-nums'] }}>
          <Text style={{ color: t.text, fontWeight: '600' }}>{zloty(achieved)}</Text> ·{' '}
          {(share * 100).toFixed(1).replace('.', ',')}% z {zloty(target)}
        </Text>
        <Text style={{ color: t.mutedText, fontSize: 12 }}>plan {(planned * 100).toFixed(1).replace('.', ',')}%</Text>
      </View>
    </View>
  )
}

/** Wiersz listy z kodem, nazwą, liczbami po prawej i strzałką, gdy prowadzi dalej. */
export function ReportRow({
  code,
  title,
  subtitle,
  value,
  aside,
  onPress,
  tone,
}: {
  code?: string | null
  title: string
  subtitle?: string | null
  value: ReactNode
  aside?: ReactNode
  onPress?: () => void
  tone?: 'group' | 'total'
}) {
  const t = useTheme()
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      style={({ pressed }) => [
        styles.reportRow,
        { borderColor: t.border },
        tone === 'total' && { backgroundColor: t.muted },
        pressed && { backgroundColor: t.muted },
      ]}
    >
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: t.text, fontSize: 15, fontWeight: tone ? '700' : '600' }} numberOfLines={2}>
          {code ? <Text style={{ color: t.mutedText, fontVariant: ['tabular-nums'] }}>{code} </Text> : null}
          {title}
        </Text>
        {subtitle ? <Muted>{subtitle}</Muted> : null}
      </View>
      <View style={{ alignItems: 'flex-end', gap: 2 }}>
        {value}
        {aside}
      </View>
      {onPress ? <ChevronRightIcon color={t.mutedText} size={18} /> : <View style={{ width: 18 }} />}
    </Pressable>
  )
}

export function ReportList({ children }: { children: ReactNode }) {
  const t = useTheme()
  return <View style={[styles.list, { backgroundColor: t.card, borderColor: t.border }]}>{children}</View>
}

/** Karta z linkiem do raportu albo ekranu (lista raportów, ekran główny). */
export function LinkCard({ title, description, href }: { title: string; description: string; href: string }) {
  const t = useTheme()
  return (
    <Pressable onPress={() => router.push(href as never)} accessibilityRole="link">
      {({ pressed }) => (
        <Card style={[{ flexDirection: 'row', alignItems: 'center', gap: 12 }, pressed && { opacity: 0.8 }]}>
          <View style={{ flex: 1, gap: 4 }}>
            <Text style={{ color: t.text, fontSize: 16, fontWeight: '700' }}>{title}</Text>
            <Muted style={{ lineHeight: 18 }}>{description}</Muted>
          </View>
          <ChevronRightIcon color={t.mutedText} size={20} />
        </Card>
      )}
    </Pressable>
  )
}

/** Marża % od brutto z sum figur sprzedaży. */
export const marginShare = (margin: number | null | undefined, gross: number) => shareOf(margin, gross)

const styles = StyleSheet.create({
  segmented: { flexDirection: 'row', borderRadius: 12, padding: 3, gap: 3 },
  segment: {
    flex: 1,
    minHeight: 38,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 6,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'transparent',
  },
  periodRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  arrow: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  tile: { flexGrow: 1, flexBasis: '45%', borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, padding: 12, gap: 3 },
  tileGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  goalHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  goalPill: { borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2 },
  goalTrack: { height: 8, borderRadius: 4, overflow: 'hidden' },
  goalFill: { height: '100%', borderRadius: 4 },
  goalPlan: { position: 'absolute', top: 0, bottom: 0, width: 2 },
  reportRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 14,
    paddingVertical: 12,
    minHeight: 56,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  list: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, overflow: 'hidden' },
})
