import * as Haptics from 'expo-haptics'
import { router } from 'expo-router'
import { useState, type ReactNode } from 'react'
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import { ChevronRightIcon } from '@/components/icons'
import { PeriodPicker, ReportList, useReportPeriod } from '@/components/report-parts'
import { Card, ErrorText, Loading, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { percent, quantity, zloty } from '@/lib/format'
import { useCounterReport, useCounterStores } from '@/lib/reports'
import { useTheme } from '@/lib/theme'
import type { CounterFigures, CounterReport, CounterValues } from '@/lib/types'

const REGIONS = [
  { code: '24', label: 'Mięso' },
  { code: '36', label: 'Garmaż' },
  { code: '46', label: 'Drób' },
]

type Metric = 'SALE' | 'MARGIN_PERCENT' | 'MARGIN' | 'QUANTITY' | 'LOSSES' | 'SHARE'

const METRICS: { value: Metric; label: string }[] = [
  { value: 'SALE', label: 'Sprzedaż' },
  { value: 'MARGIN_PERCENT', label: 'Marża %' },
  { value: 'MARGIN', label: 'Marża zł' },
  { value: 'QUANTITY', label: 'Ilość' },
  { value: 'LOSSES', label: 'Likwidacje' },
  { value: 'SHARE', label: 'Udział' },
]

/** Jak w przeglądarce: bez kolorów najwyższej i najniższej wartości przy likwidacjach. */
const UNCOLORED: Metric[] = ['LOSSES']

/** Najwyżej 6 sklepów razem z bieżącym (CounterReportService.MAX_STORES). */
const MAX_OTHER_STORES = 5

function value(metric: Metric, figures: CounterFigures | null): number | null {
  if (!figures) return null
  switch (metric) {
    case 'SALE':
      return figures.saleValueGross
    case 'MARGIN':
      return figures.marginValue
    case 'MARGIN_PERCENT':
      return figures.marginPercent
    case 'QUANTITY':
      return figures.quantity
    case 'LOSSES':
      return figures.lossValue
    case 'SHARE':
      return figures.sharePercent
  }
}

function format(metric: Metric, amount: number | null) {
  if (amount == null) return '–'
  if (metric === 'MARGIN_PERCENT' || metric === 'SHARE') return percent(amount)
  if (metric === 'QUANTITY') return quantity(Math.round(amount * 10) / 10)
  return zloty(amount)
}

/**
 * Raport lady (mięso 24, garmaż 36, drób 46; następca arkusza „36 szczegółowo”): rejon → rodzina → podrodzina → artykuł,
 * marża po likwidacjach, porównanie z innymi sklepami (najwyższa wartość zielona, najniższa czerwona).
 */
export default function CounterScreen() {
  const t = useTheme()
  const { store } = useAuth()
  const dockInset = useDockInset()
  const storeId = store?.id ?? ''
  const period = useReportPeriod(storeId, 'week')
  const [regions, setRegions] = useState(REGIONS.map((region) => region.code))
  const [others, setOthers] = useState<string[]>([])
  const [metric, setMetric] = useState<Metric>('SALE')
  const storeOptions = useCounterStores(storeId)
  const report = useCounterReport(storeId, period.range, regions, others)
  const data = report.data

  const toggleRegion = (code: string) => {
    void Haptics.selectionAsync().catch(() => undefined)
    setRegions((current) =>
      current.includes(code)
        ? current.length > 1
          ? current.filter((item) => item !== code)
          : current
        : REGIONS.map((region) => region.code).filter((item) => item === code || current.includes(item)),
    )
  }
  const toggleStore = (id: string) => {
    void Haptics.selectionAsync().catch(() => undefined)
    setOthers((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : current.length < MAX_OTHER_STORES
          ? [...current, id]
          : current,
    )
  }

  return (
    <ScrollView
      contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: dockInset }}
      refreshControl={<RefreshControl refreshing={report.isRefetching} onRefresh={() => void report.refetch()} />}
    >
      <PeriodPicker period={period} />

      <View style={{ gap: 8 }}>
        <ChipRow>
          {REGIONS.map((region) => (
            <Chip
              key={region.code}
              label={`${region.code} ${region.label}`}
              selected={regions.includes(region.code)}
              onPress={() => toggleRegion(region.code)}
            />
          ))}
        </ChipRow>
        {storeOptions.data && storeOptions.data.length > 0 ? (
          <>
            <Muted>Porównaj ze sklepami (do {MAX_OTHER_STORES}):</Muted>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipsScroll}>
              {storeOptions.data.map((option) => (
                <Chip
                  key={option.id}
                  label={option.number}
                  selected={others.includes(option.id)}
                  onPress={() => toggleStore(option.id)}
                />
              ))}
            </ScrollView>
          </>
        ) : null}
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipsScroll}>
          {METRICS.map((item) => (
            <Chip
              key={item.value}
              label={item.label}
              selected={metric === item.value}
              onPress={() => setMetric(item.value)}
              strong
            />
          ))}
        </ScrollView>
      </View>

      {period.overview.isPending || (period.range && report.isPending) ? (
        <Loading />
      ) : report.isError ? (
        <ErrorText>Nie udało się wczytać raportu lady.</ErrorText>
      ) : !data ? (
        <Muted>Sklep nie ma jeszcze wgranej sprzedaży.</Muted>
      ) : (
        <>
          <StoreTotals report={data} />
          {metric === 'MARGIN' || metric === 'MARGIN_PERCENT' ? (
            <Muted>
              Marża po likwidacjach: sprzedaż netto − zakup FIFO − likwidacje z okresu, % od sprzedaży brutto.
            </Muted>
          ) : null}
          <CounterTree report={data} metric={metric} />
        </>
      )}
      <Text style={{ color: t.mutedText, fontSize: 12 }}>Wydruk i łączenie artykułów sklepów są w przeglądarce.</Text>
    </ScrollView>
  )
}

function StoreTotals({ report }: { report: CounterReport }) {
  const t = useTheme()
  return (
    <View style={{ gap: 10 }}>
      {report.stores.map((store, index) => {
        const figures = report.total[index]
        return (
          <Card key={store.id} style={{ gap: 6, padding: 14 }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <Text style={{ color: t.text, fontSize: 15, fontWeight: '700' }}>
                {store.number}
                {index === 0 ? <Text style={{ color: t.mutedText, fontWeight: '400' }}> · ten sklep</Text> : null}
              </Text>
              <Text style={{ color: t.text, fontSize: 18, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
                {zloty(figures?.saleValueGross)}
              </Text>
            </View>
            <Muted style={{ fontVariant: ['tabular-nums'] }}>
              marża {percent(figures?.marginPercent)} · {zloty(figures?.marginValue)} · likwidacje{' '}
              {zloty(figures?.lossValue)}
            </Muted>
          </Card>
        )
      })}
    </View>
  )
}

function CounterTree({ report, metric }: { report: CounterReport; metric: Metric }) {
  const [open, setOpen] = useState<Set<string>>(() => new Set())
  const toggle = (key: string) =>
    setOpen((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  const numbers = report.stores.map((store) => store.number)

  return (
    <View style={{ gap: 16 }}>
      {report.regions.map((region) => (
        <View key={region.code} style={{ gap: 8 }}>
          <ValuesRow
            title={`${region.code} ${region.name ?? ''}`}
            values={region.values}
            metric={metric}
            numbers={numbers}
            level="region"
          />
          <ReportList>
            {region.families.map((family) => {
              const familyKey = `f${family.code}`
              const familyOpen = open.has(familyKey)
              return (
                <View key={family.code}>
                  <ValuesRow
                    title={`${family.code.slice(2)} ${family.name ?? ''}`}
                    values={family.values}
                    metric={metric}
                    numbers={numbers}
                    level="family"
                    expanded={familyOpen}
                    onPress={() => toggle(familyKey)}
                  />
                  {familyOpen
                    ? family.subfamilies.map((subfamily) => {
                        const subKey = `s${subfamily.code}`
                        const subOpen = open.has(subKey)
                        return (
                          <View key={subfamily.code}>
                            <ValuesRow
                              title={`${subfamily.code.slice(4)} ${subfamily.name ?? ''}`}
                              values={subfamily.values}
                              metric={metric}
                              numbers={numbers}
                              level="subfamily"
                              expanded={subOpen}
                              onPress={() => toggle(subKey)}
                            />
                            {subOpen
                              ? subfamily.articles.map((article) => (
                                  <ValuesRow
                                    key={article.itm8}
                                    title={article.name ?? article.itm8}
                                    subtitle={article.brand}
                                    values={article.values}
                                    metric={metric}
                                    numbers={numbers}
                                    level="article"
                                    onPress={() =>
                                      router.push({ pathname: '/article/[itm8]', params: { itm8: article.itm8 } })
                                    }
                                  />
                                ))
                              : null}
                          </View>
                        )
                      })
                    : null}
                </View>
              )
            })}
          </ReportList>
        </View>
      ))}
    </View>
  )
}

const INDENT = { region: 0, family: 14, subfamily: 28, article: 42 }

/** Wiersz drzewa: nazwa, a pod nią wartości miary w każdym sklepie (przy jednym sklepie po prawej). */
function ValuesRow({
  title,
  subtitle,
  values,
  metric,
  numbers,
  level,
  expanded,
  onPress,
}: {
  title: string
  subtitle?: string | null
  values: CounterValues
  metric: Metric
  numbers: string[]
  level: keyof typeof INDENT
  expanded?: boolean
  onPress?: () => void
}) {
  const t = useTheme()
  const amounts = values.map((figures) => value(metric, figures))
  const known = amounts.filter((amount): amount is number => amount != null)
  const colored = numbers.length > 1 && known.length > 1 && !UNCOLORED.includes(metric)
  const max = colored ? Math.max(...known) : null
  const min = colored ? Math.min(...known) : null
  const single = numbers.length === 1
  const isRegion = level === 'region'
  const tree = level === 'family' || level === 'subfamily'

  const amountText = (amount: number | null, index: number) => {
    const color =
      amount != null && max !== min && amount === max
        ? t.up
        : amount != null && max !== min && amount === min
          ? t.danger
          : t.text
    return (
      <Text
        key={index}
        style={{ color, fontSize: single ? 15 : 13, fontWeight: '700', fontVariant: ['tabular-nums'] }}
        numberOfLines={1}
      >
        {format(metric, amount)}
      </Text>
    )
  }

  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityState={tree ? { expanded: !!expanded } : undefined}
      style={({ pressed }) => [
        styles.row,
        { borderColor: t.border, paddingLeft: 14 + INDENT[level] },
        isRegion && styles.regionRow,
        level === 'family' && { backgroundColor: t.muted },
        pressed && onPress ? { opacity: 0.7 } : null,
      ]}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        {tree ? (
          <View style={{ transform: [{ rotate: expanded ? '90deg' : '0deg' }] }}>
            <ChevronRightIcon color={t.mutedText} size={16} />
          </View>
        ) : null}
        <View style={{ flex: 1, gap: 1 }}>
          <Text
            style={{ color: t.text, fontSize: isRegion ? 17 : 14, fontWeight: isRegion || tree ? '700' : '500' }}
            numberOfLines={2}
          >
            {title}
          </Text>
          {subtitle ? <Muted style={{ fontSize: 12 }}>{subtitle}</Muted> : null}
        </View>
        {single ? amountText(amounts[0], 0) : null}
        {level === 'article' ? <ChevronRightIcon color={t.mutedText} size={16} /> : null}
      </View>
      {!single ? (
        <View style={styles.storeValues}>
          {amounts.map((amount, index) => (
            <View key={numbers[index]} style={styles.storeValue}>
              <Text style={{ color: t.mutedText, fontSize: 11 }}>{numbers[index]}</Text>
              {amountText(amount, index)}
            </View>
          ))}
        </View>
      ) : null}
    </Pressable>
  )
}

function ChipRow({ children }: { children: ReactNode }) {
  return <View style={styles.chips}>{children}</View>
}

function Chip({
  label,
  selected,
  onPress,
  strong,
}: {
  label: string
  selected: boolean
  onPress: () => void
  strong?: boolean
}) {
  const t = useTheme()
  const on = strong
    ? { backgroundColor: t.primary, borderColor: t.primary }
    : { backgroundColor: t.infoBg, borderColor: t.infoBg }
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="checkbox"
      accessibilityState={{ checked: selected }}
      style={({ pressed }) => [
        styles.chip,
        { borderColor: t.border, backgroundColor: t.card },
        selected && on,
        pressed && { opacity: 0.8 },
      ]}
    >
      <Text
        style={{
          color: selected ? (strong ? t.primaryText : t.infoText) : t.text,
          fontSize: 14,
          fontWeight: selected ? '700' : '500',
        }}
      >
        {label}
      </Text>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chipsScroll: { flexDirection: 'row', gap: 8 },
  chip: { minHeight: 36, borderRadius: 18, borderWidth: 1, paddingHorizontal: 14, justifyContent: 'center' },
  row: { paddingRight: 14, paddingVertical: 11, gap: 6, borderBottomWidth: StyleSheet.hairlineWidth },
  regionRow: { borderBottomWidth: 0, paddingVertical: 4, paddingLeft: 4 },
  storeValues: { flexDirection: 'row', flexWrap: 'wrap', gap: 12, paddingLeft: 24 },
  storeValue: { minWidth: 70, gap: 1 },
})
