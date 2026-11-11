import * as Haptics from 'expo-haptics'
import { useEffect, useRef, useState } from 'react'
import {
  Animated,
  Easing,
  StyleSheet,
  Text,
  View,
  type GestureResponderEvent,
  type LayoutChangeEvent,
} from 'react-native'

import { Card, Loading, Muted } from '@/components/ui'
import { shortDate } from '@/lib/format'
import { useReduceMotion } from '@/lib/motion'
import { useDashboardSettings, useReportsOverview, useStoreHistory } from '@/lib/queries'
import { useTheme, type Theme } from '@/lib/theme'
import type { DashboardChartMetric, SalesHistory } from '@/lib/types'
import {
  changePercent,
  compactMetric,
  formatChange,
  formatMetric,
  hasNoSales,
  METRICS,
  metricValue,
  storeWindow,
  TREND_WEEKS,
  WEEKDAYS_SHORT,
  weekdayIndex,
} from '@/lib/week'

const PLOT = 156
const EASE_OUT = Easing.bezier(0.23, 1, 0.32, 1)
const DURATION = 480
const STAGGER = 40

/**
 * Wyniki sklepu na ekranie głównym (na razie tylko właściciel), jak panel na pulpicie w przeglądarce: ostatnie 7 dni
 * ze sprzedażą wobec tych samych dni tygodnia rok temu, dwa wykresy nałożone na siebie i pasek trendu z 12 tygodni.
 * Co pokazuje wykres, wybiera się w panelu konta.
 */
export function StoreResultsCard({ storeId, storeNumber }: { storeId: string; storeNumber: string }) {
  const t = useTheme()
  const overview = useReportsOverview(storeId)
  const settings = useDashboardSettings()
  const metric: DashboardChartMetric = settings.data?.salesChartMetric ?? 'SALES'
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const range = lastSaleDate ? storeWindow(lastSaleDate) : null
  const current = useStoreHistory(storeId, range?.current ?? null)
  const yearAgo = useStoreHistory(storeId, range?.yearAgo ?? null)
  const label = METRICS.find((item) => item.value === metric)?.label ?? ''

  let body
  if (overview.isPending || (range && (current.isPending || yearAgo.isPending))) {
    body = <Loading />
  } else if (overview.isError || current.isError || yearAgo.isError) {
    body = <Muted>Nie udało się wczytać sprzedaży.</Muted>
  } else if (!range || !current.data || !yearAgo.data) {
    body = <Muted>Sklep nie ma jeszcze wgranej sprzedaży.</Muted>
  } else {
    body = <Results metric={metric} current={current.data} yearAgo={yearAgo.data} />
  }

  return (
    <Card style={styles.card}>
      <View style={{ gap: 2 }}>
        <Text style={[styles.title, { color: t.text }]}>Wyniki sklepu {storeNumber}</Text>
        <Muted>{label} · ostatnie 7 dni wobec roku temu</Muted>
      </View>
      {body}
    </Card>
  )
}

/** {@code skipped}: dni pominięte w zmianie %, bo one albo ich odpowiednik rok temu nie mają sprzedaży. */
type Week = { from: string; to: string; now: number; before: number; change: number | null; skipped: string[] }

/**
 * Jak w przeglądarce: zmiana liczy się tylko z par dni, w których obie strony mają sprzedaż, więc zamknięte święto
 * (np. 1.11) nie zabiera tygodniowi porównania. Sumy są pełne.
 */
function weeklyChanges(metric: DashboardChartMetric, current: SalesHistory, yearAgo: SalesHistory): Week[] {
  const weeks: Week[] = []
  for (let start = 0; start + 7 <= current.days.length; start += 7) {
    const days = current.days.slice(start, start + 7)
    const before = yearAgo.days.slice(start, start + 7)
    const now = days.reduce((sum, day) => sum + metricValue(metric, day.figures), 0)
    const then = before.reduce((sum, day) => sum + metricValue(metric, day.figures), 0)
    let pairedNow = 0
    let pairedBefore = 0
    const skipped: string[] = []
    days.forEach((day, index) => {
      const previous = before[index]
      if (!previous || hasNoSales(day.figures) || hasNoSales(previous.figures)) {
        skipped.push(day.date)
        return
      }
      pairedNow += metricValue(metric, day.figures)
      pairedBefore += metricValue(metric, previous.figures)
    })
    weeks.push({
      from: days[0].date,
      to: days[6].date,
      now,
      before: then,
      change: skipped.length < days.length ? changePercent(pairedNow, pairedBefore) : null,
      skipped,
    })
  }
  return weeks
}

function Results({
  metric,
  current,
  yearAgo,
}: {
  metric: DashboardChartMetric
  current: SalesHistory
  yearAgo: SalesHistory
}) {
  const t = useTheme()
  const weeks = weeklyChanges(metric, current, yearAgo)
  const week = weeks[weeks.length - 1]

  return (
    <View style={{ gap: 18 }}>
      <View style={{ gap: 4 }}>
        <Muted>
          {shortDate(week.from)} – {shortDate(week.to)}
        </Muted>
        <View style={styles.heroRow}>
          <Text style={[styles.hero, { color: t.text }]} adjustsFontSizeToFit numberOfLines={1}>
            {formatMetric(metric, week.now)}
          </Text>
          {week.change != null ? <ChangePill change={week.change} /> : null}
        </View>
        <Muted style={{ fontSize: 14 }}>
          rok temu <Text style={{ color: t.text, fontWeight: '600' }}>{formatMetric(metric, week.before)}</Text>
        </Muted>
        {week.change != null && week.skipped.length > 0 ? (
          <Muted style={{ fontSize: 12 }}>
            Zmiana bez dni bez sprzedaży (teraz albo rok temu): {week.skipped.map(shortDate).join(', ')}
          </Muted>
        ) : null}
      </View>

      <TrendStrip weeks={weeks} metric={metric} />

      <View style={styles.legend} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        <View style={styles.legendItem}>
          <View style={[styles.legendNow, { backgroundColor: t.chartNow }]} />
          <Text style={[styles.legendText, { color: t.mutedText }]}>Ostatnie 7 dni</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.legendBefore, { borderColor: t.chartYearAgo, backgroundColor: t.chartYearAgoWash }]} />
          <Text style={[styles.legendText, { color: t.mutedText }]}>Rok temu</Text>
        </View>
      </View>

      <DayChart metric={metric} days={current.days.slice(-7)} daysBefore={yearAgo.days.slice(-7)} />
    </View>
  )
}

function ChangePill({ change }: { change: number }) {
  const t = useTheme()
  const up = change >= 0
  return (
    <View style={[styles.pill, { backgroundColor: up ? t.okBg : t.dangerBg }]}>
      <Text style={{ color: up ? t.okText : t.dangerText, fontWeight: '700', fontSize: 14 }}>
        {up ? '▲ ' : '▼ '}
        {formatChange(change)}
      </Text>
    </View>
  )
}

/** Niski stopień skali: krok 1, 2, 2,5 albo 5 razy potęga dziesięciu, góra osi tuż nad najwyższym słupkiem. */
function niceScale(value: number) {
  const rough = value / 3
  const power = 10 ** Math.floor(Math.log10(rough))
  const step = ([1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= rough) ?? 10) * power
  const max = Math.ceil(value / step) * step
  const ticks: number[] = []
  for (let tick = step; tick <= max + step / 2; tick += step) ticks.push(tick)
  return { max, ticks }
}

/**
 * Słupki to widoki wysokie na cały wykres, wysuwane spod osi przez translateY (tylko transform, na natywnym
 * sterowniku), więc zaokrąglone rogi nie rozciągają się, a zmiana miary przesuwa je płynnie.
 */
function useBarOffsets(targets: number[], reduce: boolean) {
  const [offsets] = useState(() => targets.map(() => new Animated.Value(PLOT + 6)))
  const first = useRef(true)
  const key = targets.map((value) => value.toFixed(1)).join('|')

  useEffect(() => {
    if (reduce) {
      targets.forEach((value, index) => offsets[index].setValue(value))
      first.current = false
      return
    }
    const animations = targets.map((value, index) =>
      Animated.timing(offsets[index], { toValue: value, duration: DURATION, easing: EASE_OUT, useNativeDriver: true }),
    )
    if (first.current) {
      first.current = false
      Animated.stagger(STAGGER, animations).start()
    } else {
      Animated.parallel(animations).start()
    }
    // targets są opisane przez key
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, reduce, offsets])

  return offsets
}

function DayChart({
  metric,
  days,
  daysBefore,
}: {
  metric: DashboardChartMetric
  days: SalesHistory['days']
  daysBefore: SalesHistory['days']
}) {
  const t = useTheme()
  const reduce = useReduceMotion()
  const [width, setWidth] = useState(0)
  const [active, setActive] = useState<number | null>(null)
  const lastHaptic = useRef<number | null>(null)

  const now = days.map((day) => Math.max(0, metricValue(metric, day.figures)))
  const before = daysBefore.map((day) => Math.max(0, metricValue(metric, day.figures)))
  const { max, ticks } = niceScale(Math.max(1, ...now, ...before))
  const offset = (value: number) => PLOT * (1 - value / max)
  // Kolejność: rok temu za każdy dzień, potem ostatnie 7 dni, żeby stagger szedł tłem, a potem bieżącymi.
  const offsets = useBarOffsets([...before.map(offset), ...now.map(offset)], reduce)

  const slot = width / 7
  const back = Math.min(36, slot * 0.66)
  const front = Math.min(18, slot * 0.34)

  const pick = (event: GestureResponderEvent) => {
    if (!slot) return
    const index = Math.max(0, Math.min(6, Math.floor(event.nativeEvent.locationX / slot)))
    if (lastHaptic.current !== index) {
      lastHaptic.current = index
      void Haptics.selectionAsync().catch(() => undefined)
    }
    setActive(index)
  }

  return (
    <View style={{ gap: 10 }}>
      <View
        onLayout={(event: LayoutChangeEvent) => setWidth(event.nativeEvent.layout.width)}
        onStartShouldSetResponder={() => true}
        onMoveShouldSetResponder={() => true}
        onResponderGrant={pick}
        onResponderMove={pick}
        accessible
        accessibilityRole="adjustable"
        accessibilityLabel={`Wykres ostatnich 7 dni: ${formatMetric(
          metric,
          now.reduce((a, b) => a + b, 0),
        )}, rok temu ${formatMetric(
          metric,
          before.reduce((a, b) => a + b, 0),
        )}`}
        style={{ height: PLOT + 1 }}
      >
        {ticks.map((tick) => (
          <View key={tick} style={[styles.gridLine, { bottom: (tick / max) * PLOT, borderColor: t.border }]}>
            <Text style={[styles.tick, { color: t.mutedText }]}>{compactMetric(metric, tick)}</Text>
          </View>
        ))}
        <View style={[styles.plot, { borderColor: t.border }]}>
          {width > 0
            ? days.map((day, index) => {
                const center = slot * index + slot / 2
                return (
                  <View key={day.date} pointerEvents="none" style={StyleSheet.absoluteFill}>
                    {active === index ? (
                      <View
                        style={[styles.band, { left: slot * index + 2, width: slot - 4, backgroundColor: t.muted }]}
                      />
                    ) : null}
                    <Animated.View
                      style={[
                        styles.barBefore,
                        {
                          left: center - back / 2,
                          width: back,
                          borderColor: t.chartYearAgo,
                          backgroundColor: t.chartYearAgoWash,
                          transform: [{ translateY: offsets[index] }],
                        },
                      ]}
                    />
                    <Animated.View
                      style={[
                        styles.barNow,
                        {
                          left: center - front / 2,
                          width: front,
                          backgroundColor: t.chartNow,
                          borderColor: t.card,
                          transform: [{ translateY: offsets[7 + index] }],
                        },
                      ]}
                    />
                  </View>
                )
              })
            : null}
        </View>
      </View>

      <View style={styles.row}>
        {days.map((day, index) => {
          const missing = hasNoSales(day.figures)
          const change = missing ? null : changePercent(now[index], before[index])
          const selected = active === index
          return (
            <View key={day.date} style={styles.dayLabel}>
              <Text
                style={[
                  styles.weekday,
                  { color: selected ? t.text : t.mutedText, fontWeight: selected ? '700' : '500' },
                ]}
              >
                {WEEKDAYS_SHORT[weekdayIndex(day.date)]}
              </Text>
              <Text style={[styles.delta, { color: t.text }]} numberOfLines={1} adjustsFontSizeToFit>
                {missing ? (
                  <Text style={{ color: t.mutedText }}>brak</Text>
                ) : change == null ? (
                  <Text style={{ color: t.mutedText }}>–</Text>
                ) : (
                  <>
                    <Text style={{ color: change >= 0 ? t.up : t.danger }}>{change >= 0 ? '▲' : '▼'}</Text>
                    {formatChange(change).replace('+', '').replace('−', '')}
                  </>
                )}
              </Text>
            </View>
          )
        })}
      </View>

      <DayDetail
        metric={metric}
        t={t}
        day={active == null ? null : days[active]}
        dayBefore={active == null ? null : daysBefore[active]}
        now={active == null ? 0 : now[active]}
        before={active == null ? 0 : before[active]}
      />
    </View>
  )
}

function DayDetail({
  metric,
  t,
  day,
  dayBefore,
  now,
  before,
}: {
  metric: DashboardChartMetric
  t: Theme
  day: SalesHistory['days'][number] | null
  dayBefore: SalesHistory['days'][number] | null
  now: number
  before: number
}) {
  if (!day || !dayBefore) {
    return (
      <View style={[styles.detail, { backgroundColor: t.muted }]}>
        <Text style={{ color: t.mutedText, fontSize: 13 }}>Przesuń palcem po wykresie, żeby porównać dni.</Text>
      </View>
    )
  }
  return (
    <View style={[styles.detail, { backgroundColor: t.muted }]}>
      <Text style={{ color: t.text, fontWeight: '700' }}>
        {WEEKDAYS_SHORT[weekdayIndex(day.date)]} {shortDate(day.date)}
      </Text>
      <View style={{ alignItems: 'flex-end', gap: 2 }}>
        <View style={styles.detailValue}>
          <View style={[styles.dot, { backgroundColor: t.chartNow }]} />
          <Text style={{ color: t.text, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
            {hasNoSales(day.figures) ? 'brak danych' : formatMetric(metric, now)}
          </Text>
        </View>
        <View style={styles.detailValue}>
          <View style={[styles.dot, { backgroundColor: t.chartYearAgo }]} />
          <Text style={{ color: t.mutedText, fontVariant: ['tabular-nums'] }}>
            {formatMetric(metric, before)} · {shortDate(dayBefore.date)}
          </Text>
        </View>
      </View>
    </View>
  )
}

const STRIP_HEIGHT = 34

/** Mały pasek trendu: zmiana rok do roku każdego z ostatnich 12 tygodni, bieżący wyróżniony. */
function TrendStrip({ weeks, metric }: { weeks: Week[]; metric: DashboardChartMetric }) {
  const t = useTheme()
  const changes = weeks.map((week) => week.change ?? 0)
  const extent = Math.max(5, ...changes.map(Math.abs))
  const anyDown = changes.some((change) => change < 0)
  const anyUp = changes.some((change) => change > 0)
  const split = anyDown && anyUp
  const room = split ? STRIP_HEIGHT / 2 - 2 : STRIP_HEIGHT - 2
  const growing = weeks.filter((week) => (week.change ?? 0) > 0).length
  const latest = weeks[weeks.length - 1]

  return (
    <View
      style={{ gap: 6 }}
      accessible
      accessibilityLabel={`Rok do roku w ostatnich ${weeks.length} tygodniach: ${growing} na plus`}
    >
      <View style={styles.stripHeader}>
        <Text style={{ color: t.mutedText, fontSize: 12 }}>Rok do roku, {TREND_WEEKS} tygodni</Text>
        <Text style={{ color: t.text, fontSize: 12, fontWeight: '700' }}>
          {growing}/{weeks.length} na plus
        </Text>
      </View>
      <View style={[styles.strip, { height: STRIP_HEIGHT }]}>
        <View
          style={[
            styles.stripAxis,
            { backgroundColor: t.border, top: split ? STRIP_HEIGHT / 2 : anyDown ? 0 : STRIP_HEIGHT - 1 },
          ]}
        />
        {weeks.map((week, index) => {
          const value = week.change ?? 0
          const length = week.change == null ? 0 : Math.max(2, (Math.abs(value) / extent) * room)
          const isLatest = index === weeks.length - 1
          const up = value >= 0
          const axis = split ? STRIP_HEIGHT / 2 : anyDown ? 0 : STRIP_HEIGHT - 1
          return (
            <View key={week.from} style={styles.stripSlot}>
              <View
                style={[
                  styles.stripBar,
                  {
                    height: length,
                    top: up ? axis - length : axis,
                    backgroundColor: isLatest ? t.chartNow : t.chartMuted,
                  },
                ]}
              />
            </View>
          )
        })}
      </View>
      <Text style={{ color: t.mutedText, fontSize: 12 }}>
        Ostatni tydzień:{' '}
        <Text style={{ color: t.text, fontWeight: '700' }}>
          {latest.change == null ? 'brak danych' : formatChange(latest.change)}
        </Text>{' '}
        · {compactMetric(metric, latest.now)}
      </Text>
    </View>
  )
}

const styles = StyleSheet.create({
  card: { gap: 16 },
  title: { fontSize: 17, fontWeight: '700' },
  heroRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  hero: { fontSize: 38, fontWeight: '700', letterSpacing: -0.8, flexShrink: 1 },
  pill: { borderRadius: 8, paddingHorizontal: 8, paddingVertical: 3 },
  legend: { flexDirection: 'row', gap: 16 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  legendNow: { width: 7, height: 12, borderTopLeftRadius: 3, borderTopRightRadius: 3 },
  legendBefore: {
    width: 13,
    height: 12,
    borderWidth: 1.5,
    borderBottomWidth: 0,
    borderTopLeftRadius: 3,
    borderTopRightRadius: 3,
  },
  legendText: { fontSize: 12 },
  gridLine: { position: 'absolute', left: 0, right: 0, borderTopWidth: StyleSheet.hairlineWidth },
  tick: { position: 'absolute', left: 0, bottom: 2, fontSize: 10, fontVariant: ['tabular-nums'] },
  plot: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    overflow: 'hidden',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  band: { position: 'absolute', top: 0, bottom: 0, borderRadius: 8 },
  barBefore: {
    position: 'absolute',
    top: 0,
    height: PLOT + 8,
    borderWidth: 1.5,
    borderTopLeftRadius: 5,
    borderTopRightRadius: 5,
  },
  barNow: {
    position: 'absolute',
    top: 0,
    height: PLOT + 8,
    borderWidth: 2,
    borderTopLeftRadius: 5,
    borderTopRightRadius: 5,
  },
  row: { flexDirection: 'row' },
  dayLabel: { flex: 1, alignItems: 'center', gap: 2 },
  weekday: { fontSize: 12 },
  delta: { fontSize: 11, fontWeight: '600', fontVariant: ['tabular-nums'] },
  detail: {
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    minHeight: 52,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 8,
  },
  detailValue: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  stripHeader: { flexDirection: 'row', justifyContent: 'space-between' },
  strip: { flexDirection: 'row' },
  stripAxis: { position: 'absolute', left: 0, right: 0, height: StyleSheet.hairlineWidth },
  stripSlot: { flex: 1, alignItems: 'center' },
  stripBar: { position: 'absolute', width: 10, borderRadius: 2 },
})
