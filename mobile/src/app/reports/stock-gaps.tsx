import { router } from 'expo-router'
import { memo, useCallback, useMemo, useState } from 'react'
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import { ChevronRightIcon } from '@/components/icons'
import { Segmented } from '@/components/report-parts'
import { Badge, ErrorText, Loading, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { date, quantity, zloty } from '@/lib/format'
import { useStockGaps } from '@/lib/reports'
import { useTheme } from '@/lib/theme'
import type { StockGapItem, StockGapKind } from '@/lib/types'

const KINDS: { value: StockGapKind; label: string; hint: string }[] = [
  {
    value: 'MISSING',
    label: 'Brakuje',
    hint: 'Towar stale w ofercie, niski stan, cisza w sprzedaży i brak świeżej dostawy. Najpewniej nie ma go na półce.',
  },
  { value: 'RUNNING_OUT', label: 'Kończy się', hint: 'Jeszcze się sprzedaje, ale stan wystarczy najwyżej na 3 dni.' },
  {
    value: 'WRONG_STOCK',
    label: 'Stan',
    hint: 'Stan 0 albo ujemny, a artykuł dalej się sprzedaje: towar jest, tylko stan się nie zgadza.',
  },
  {
    value: 'ONE_OFF',
    label: 'Jednorazowe',
    hint: 'Skończyły się, ale to towar z 1-2 dostaw spoza cadencier (zwykle akcja).',
  },
]

/**
 * Braki towaru do sprawdzenia na sali, z domyślnymi progami z przeglądarki, od największej marży tygodniowej.
 * Zamówienie i zmiana progów są w przeglądarce.
 */
export default function StockGapsScreen() {
  const { store } = useAuth()
  const dockInset = useDockInset()
  const report = useStockGaps(store?.id ?? '')
  const [kind, setKind] = useState<StockGapKind>('MISSING')
  const counts = useMemo(() => {
    const result: Record<StockGapKind, number> = { MISSING: 0, ONE_OFF: 0, RUNNING_OUT: 0, WRONG_STOCK: 0 }
    report.data?.items.forEach((item) => (result[item.kind] += 1))
    return result
  }, [report.data])
  const items = useMemo(
    () =>
      (report.data?.items ?? []).filter((item) => item.kind === kind).sort((a, b) => b.weeklyMargin - a.weeklyMargin),
    [report.data, kind],
  )
  const open = useCallback((itm8: string) => router.push({ pathname: '/article/[itm8]', params: { itm8 } }), [])
  const spec = KINDS.find((item) => item.value === kind)!

  const header = (
    <View style={{ gap: 12, paddingBottom: 12 }}>
      <Segmented
        options={KINDS.map((item) => ({ value: item.value, label: `${item.label} ${counts[item.value]}` }))}
        value={kind}
        onChange={setKind}
      />
      <Muted style={{ lineHeight: 18 }}>{spec.hint}</Muted>
      {report.data?.stockReportGeneratedAt ? (
        <Muted>Stany z kartoteki z {date(report.data.stockReportGeneratedAt)}.</Muted>
      ) : null}
    </View>
  )

  if (report.isPending) return <Loading />
  if (report.isError)
    return (
      <View style={{ padding: 20 }}>
        <ErrorText>Nie udało się wczytać braków towaru.</ErrorText>
      </View>
    )

  return (
    <FlatList
      data={items}
      keyExtractor={(item) => item.itm8}
      renderItem={({ item }) => <GapRow item={item} kind={kind} onPress={open} />}
      ListHeaderComponent={header}
      ListEmptyComponent={<Muted>Nic w tej zakładce.</Muted>}
      contentContainerStyle={{ padding: 20, paddingBottom: dockInset }}
      onRefresh={() => void report.refetch()}
      refreshing={report.isRefetching}
    />
  )
}

const GapRow = memo(function GapRow({
  item,
  kind,
  onPress,
}: {
  item: StockGapItem
  kind: StockGapKind
  onPress: (itm8: string) => void
}) {
  const t = useTheme()
  return (
    <Pressable
      onPress={() => onPress(item.itm8)}
      accessibilityRole="button"
      style={({ pressed }) => [styles.row, { backgroundColor: pressed ? t.muted : t.card, borderColor: t.border }]}
    >
      <View style={{ flex: 1, gap: 3 }}>
        <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={2}>
          {item.name ?? item.itm8}
        </Text>
        <Muted>{[item.brand, item.ean, item.supplierName].filter(Boolean).join(' · ')}</Muted>
        <View style={styles.facts}>
          <Fact label="stan" value={quantity(item.stock)} />
          <Fact label="tydzień" value={`${quantity(Math.round(item.typicalWeek * 10) / 10)} szt.`} />
          {kind === 'RUNNING_OUT' && item.coverDays != null ? (
            <Fact label="zapas" value={`${quantity(Math.round(item.coverDays * 10) / 10)} dni`} />
          ) : (
            <Fact label="sprzedaż" value={date(item.lastSale)} />
          )}
          <Fact label="marża/tydz." value={zloty(item.weeklyMargin)} />
        </View>
        <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
          {item.cadencier ? <Badge tone="info">cadencier</Badge> : null}
          {item.lastDeliveryDate ? <Badge>dostawa {date(item.lastDeliveryDate)}</Badge> : <Badge>bez dostawy</Badge>}
        </View>
      </View>
      <ChevronRightIcon color={t.mutedText} size={18} />
    </Pressable>
  )
})

function Fact({ label, value }: { label: string; value: string }) {
  const t = useTheme()
  return (
    <Text style={{ color: t.mutedText, fontSize: 12 }}>
      {label} <Text style={{ color: t.text, fontWeight: '600', fontVariant: ['tabular-nums'] }}>{value}</Text>
    </Text>
  )
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    padding: 14,
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    marginBottom: 10,
  },
  facts: { flexDirection: 'row', flexWrap: 'wrap', columnGap: 12, rowGap: 2 },
})
