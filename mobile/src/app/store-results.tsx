import * as Haptics from 'expo-haptics'
import { router } from 'expo-router'
import { useMemo, useRef, useState } from 'react'
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View, type ScrollViewInstance } from 'react-native'
import { useQueryClient } from '@tanstack/react-query'

import { useDockInset } from '@/components/FloatingDock'
import { ChevronRightIcon } from '@/components/icons'
import { ChangeText, CompareTile, GoalBar, marginShare, PointsText, TileGrid } from '@/components/report-parts'
import { StoreResultsCard } from '@/components/StoreResultsCard'
import { Card, Loading, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { date, percent, zloty } from '@/lib/format'
import { yearAgoRange } from '@/lib/periods'
import { useReportsOverview } from '@/lib/queries'
import { useProfitTargetProgress, useSalesComparison } from '@/lib/reports'
import { useTheme } from '@/lib/theme'
import type { StoreSummary } from '@/lib/types'

/**
 * Wyniki wszystkich sklepów konta z uprawnieniem Wyniki sklepów, jak strona w przeglądarce: w każdym sklepie cel marży,
 * wykres tygodnia wobec roku temu i ostatni dzień ze sprzedażą. U góry numery sklepów do szybkiego przejścia.
 */
export default function StoreResultsScreen() {
  const t = useTheme()
  const { resultStores, store } = useAuth()
  const dockInset = useDockInset()
  const queryClient = useQueryClient()
  const scroll = useRef<ScrollViewInstance>(null)
  const positions = useRef<Record<string, number>>({})
  const [refreshing, setRefreshing] = useState(false)

  const refresh = async () => {
    setRefreshing(true)
    await queryClient.invalidateQueries({ queryKey: ['stores'] })
    setRefreshing(false)
  }

  if (resultStores.length === 0) {
    return (
      <View style={{ padding: 20 }}>
        <Card>
          <Muted style={{ fontSize: 15 }}>Żaden z Twoich sklepów nie pokazuje Ci wyników.</Muted>
        </Card>
      </View>
    )
  }

  return (
    <View style={{ flex: 1 }}>
      {resultStores.length > 1 ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={{ flexGrow: 0, borderBottomWidth: StyleSheet.hairlineWidth, borderColor: t.border }}
          contentContainerStyle={styles.jumpBar}
        >
          {resultStores.map((item) => (
            <Pressable
              key={item.id}
              onPress={() => {
                void Haptics.selectionAsync().catch(() => undefined)
                scroll.current?.scrollTo({ y: Math.max(0, (positions.current[item.id] ?? 0) - 8) })
              }}
              style={({ pressed }) => [
                styles.jump,
                { borderColor: t.border, backgroundColor: pressed ? t.muted : t.card },
              ]}
            >
              <Text style={{ color: t.text, fontWeight: '600' }}>{item.number}</Text>
            </Pressable>
          ))}
        </ScrollView>
      ) : null}
      <ScrollView
        ref={scroll}
        contentContainerStyle={{ padding: 20, gap: 36, paddingBottom: dockInset }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
      >
        {resultStores.map((item) => (
          <View
            key={item.id}
            onLayout={(event) => (positions.current[item.id] = event.nativeEvent.layout.y)}
            style={{ gap: 12 }}
          >
            <StoreSection store={item} current={item.id === store?.id} />
          </View>
        ))}
      </ScrollView>
    </View>
  )
}

function StoreSection({ store, current }: { store: StoreSummary; current: boolean }) {
  const t = useTheme()
  const overview = useReportsOverview(store.id)
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const progress = useProfitTargetProgress(store.id, lastSaleDate)

  return (
    <>
      <View style={{ gap: 2, paddingHorizontal: 4 }}>
        <Text style={{ color: t.text, fontSize: 20, fontWeight: '700' }}>
          {store.number} {store.name}
        </Text>
        {current ? <Muted>bieżący sklep</Muted> : null}
      </View>
      {progress.data ? (
        <Card>
          <GoalBar progress={progress.data} label="Marża po likwidacjach wobec celu roku" />
        </Card>
      ) : null}
      <StoreResultsCard storeId={store.id} storeNumber={store.number} />
      {overview.isPending ? <Loading /> : lastSaleDate ? <LastDay store={store} day={lastSaleDate} /> : null}
    </>
  )
}

/** Ostatni dzień ze sprzedażą wobec tego samego dnia tygodnia rok temu; klik otwiera porównanie w tym sklepie. */
function LastDay({ store, day }: { store: StoreSummary; day: string }) {
  const t = useTheme()
  const { me, selectStore } = useAuth()
  const periods = useMemo(() => {
    const current = { from: day, to: day }
    return { current, previous: yearAgoRange(current) }
  }, [day])
  const comparison = useSalesComparison(store.id, periods)
  const canCompare = !!me && (me.owner || !!me.storePermissions?.[store.id]?.SALES)
  const data = comparison.data
  const now = data?.currentTotal
  const before = data?.previousTotal

  const open = () => {
    selectStore(store.id)
    router.push({ pathname: '/reports/comparison', params: { okres: 'day', data: day } })
  }

  return (
    <Pressable
      onPress={canCompare ? open : undefined}
      disabled={!canCompare}
      accessibilityRole={canCompare ? 'link' : undefined}
    >
      {({ pressed }) => (
        <View style={{ gap: 8, opacity: pressed ? 0.8 : 1 }}>
          <View style={styles.dayHeader}>
            <Muted>
              Ostatni dzień: <Text style={{ color: t.text, fontWeight: '600' }}>{date(day)}</Text> wobec{' '}
              {date(periods.previous.from)}
            </Muted>
            {canCompare ? <ChevronRightIcon color={t.mutedText} size={16} /> : null}
          </View>
          {comparison.isError ? (
            <Muted>Nie udało się wczytać porównania dnia.</Muted>
          ) : !now || !before ? (
            <Loading />
          ) : (
            <TileGrid>
              <CompareTile
                label="Sprzedaż brutto"
                value={zloty(now.saleValueGross)}
                change={<ChangeText previous={before.saleValueGross} current={now.saleValueGross} />}
                previousLabel={zloty(before.saleValueGross)}
              />
              <CompareTile
                label="Marża"
                value={`${zloty(now.marginValue)} · ${percent(marginShare(now.marginValue, now.saleValueGross))}`}
                change={
                  <PointsText
                    previous={marginShare(before.marginValue, before.saleValueGross)}
                    current={marginShare(now.marginValue, now.saleValueGross)}
                  />
                }
                previousLabel={zloty(before.marginValue)}
              />
            </TileGrid>
          )}
        </View>
      )}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  jumpBar: { flexDirection: 'row', gap: 8, paddingHorizontal: 20, paddingVertical: 10 },
  jump: {
    minHeight: 36,
    minWidth: 64,
    borderRadius: 18,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dayHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 4 },
})
