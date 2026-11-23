import { router, Stack, useLocalSearchParams } from 'expo-router'
import { useMemo, useState } from 'react'
import { RefreshControl, ScrollView, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import {
  ChangeText,
  CompareTile,
  marginShare,
  PeriodPicker,
  PointsText,
  ReportList,
  ReportRow,
  Segmented,
  TileGrid,
  useReportPeriod,
} from '@/components/report-parts'
import { Badge, ErrorText, Loading, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { percent, quantity, zloty } from '@/lib/format'
import { yearAgoRange } from '@/lib/periods'
import { useSalesComparison, useSalesPromotionShare } from '@/lib/reports'
import { useTheme } from '@/lib/theme'
import type { SalesComparisonLevel, SalesComparisonRow, SalesFigures } from '@/lib/types'

type Order = 'sales' | 'drops' | 'growth'

const ORDERS: { value: Order; label: string }[] = [
  { value: 'sales', label: 'Sprzedaż' },
  { value: 'drops', label: 'Spadki' },
  { value: 'growth', label: 'Wzrosty' },
]

const LEVEL_TITLES: Record<SalesComparisonLevel, string> = {
  REGION: 'Rejony',
  FAMILY: 'Rodziny',
  SUBFAMILY: 'Podrodziny',
  PRODUCT: 'Artykuły',
}

/**
 * Porównanie sprzedaży jak w przeglądarce: okres wobec tych samych dni rok temu, kafelki sum i drzewo nomenklatury.
 * Każdy poziom to kolejny ekran (`parent` w adresie), artykuł otwiera kartę artykułu.
 */
export default function ComparisonScreen() {
  const t = useTheme()
  const { store } = useAuth()
  const dockInset = useDockInset()
  const params = useLocalSearchParams<{ parent?: string; nazwa?: string; brak?: string }>()
  const parent = typeof params.parent === 'string' ? params.parent : ''
  const unassigned = params.brak === 'tak'
  const storeId = store?.id ?? ''
  const period = useReportPeriod(storeId, 'week')
  const periods = useMemo(
    () => (period.range ? { current: period.range, previous: yearAgoRange(period.range) } : null),
    [period.range],
  )
  const comparison = useSalesComparison(storeId, periods, parent, unassigned)
  const promotions = useSalesPromotionShare(storeId, parent || unassigned ? null : periods)
  const [order, setOrder] = useState<Order>('sales')
  const root = !parent && !unassigned
  const title = root ? 'Porównanie sprzedaży' : (params.nazwa ?? (unassigned ? 'Bez nomenklatury' : parent))

  const data = comparison.data
  const rows = useMemo(() => (data ? arrange(data.rows, order) : []), [data, order])

  const open = (row: SalesComparisonRow) => {
    if (!data) return
    if (data.level === 'PRODUCT') {
      router.push({ pathname: '/article/[itm8]', params: { itm8: row.code } })
      return
    }
    router.push({
      pathname: '/reports/comparison',
      params: {
        ...period.params,
        ...(row.code ? { parent: row.code } : { brak: 'tak' }),
        nazwa: row.name ?? (row.code ? row.code : 'Bez nomenklatury'),
      },
    })
  }

  return (
    <>
      <Stack.Screen options={{ title: root ? 'Porównanie' : title }} />
      <ScrollView
        contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: dockInset }}
        refreshControl={
          <RefreshControl refreshing={comparison.isRefetching} onRefresh={() => void comparison.refetch()} />
        }
      >
        <PeriodPicker period={period} />

        {period.overview.isPending || (periods && comparison.isPending) ? (
          <Loading />
        ) : comparison.isError ? (
          <ErrorText>Nie udało się wczytać porównania.</ErrorText>
        ) : !periods || !data ? (
          <Muted>Sklep nie ma jeszcze wgranej sprzedaży.</Muted>
        ) : (
          <>
            {!root ? <Text style={{ color: t.text, fontSize: 18, fontWeight: '700' }}>{title}</Text> : null}
            <TotalTiles previous={data.previousTotal} current={data.currentTotal} promotions={promotions.data} />

            <View style={{ gap: 10 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <Text style={{ color: t.text, fontSize: 16, fontWeight: '700' }}>{LEVEL_TITLES[data.level]}</Text>
                <Muted>{rows.length} poz.</Muted>
              </View>
              <Segmented options={ORDERS} value={order} onChange={setOrder} />
              {rows.length === 0 ? (
                <Muted>Brak pozycji w tym okresie.</Muted>
              ) : (
                <ReportList>
                  {rows.map((row) => (
                    <ComparisonRow
                      key={row.code || 'brak'}
                      row={row}
                      product={data.level === 'PRODUCT'}
                      onPress={() => open(row)}
                    />
                  ))}
                  <ReportRow
                    title="Razem"
                    tone="total"
                    value={<Amount figures={data.currentTotal} />}
                    aside={
                      <ChangeText
                        previous={data.previousTotal.saleValueGross}
                        current={data.currentTotal.saleValueGross}
                      />
                    }
                  />
                </ReportList>
              )}
            </View>
          </>
        )}
      </ScrollView>
    </>
  )
}

/** Najpierw największa sprzedaż; spadki i wzrosty po zmianie sprzedaży w zł, bez pozycji w drugą stronę. */
function arrange(rows: SalesComparisonRow[], order: Order) {
  const delta = (row: SalesComparisonRow) => row.current.saleValueGross - row.previous.saleValueGross
  if (order === 'sales') return [...rows].sort((a, b) => b.current.saleValueGross - a.current.saleValueGross)
  if (order === 'drops') return rows.filter((row) => delta(row) < 0).sort((a, b) => delta(a) - delta(b))
  return rows.filter((row) => delta(row) > 0).sort((a, b) => delta(b) - delta(a))
}

function Amount({ figures }: { figures: SalesFigures }) {
  const t = useTheme()
  return (
    <Text style={{ color: t.text, fontSize: 15, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
      {zloty(figures.saleValueGross)}
    </Text>
  )
}

function ComparisonRow({ row, product, onPress }: { row: SalesComparisonRow; product: boolean; onPress: () => void }) {
  const margin = marginShare(row.current.marginValue, row.current.saleValueGross)
  const leafletNow = row.leaflets.some((leaflet) => leaflet.current)
  const leafletBefore = row.leaflets.some((leaflet) => leaflet.previous)
  const subtitle = [
    product ? row.brand : null,
    `rok temu ${zloty(row.previous.saleValueGross)}`,
    product ? `${quantity(row.current.quantity)} szt.` : null,
    margin != null ? `marża ${percent(margin)}` : null,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <ReportRow
      code={product ? null : row.code || null}
      title={row.name ?? (row.code ? row.code : 'Bez nomenklatury')}
      subtitle={subtitle}
      value={<Amount figures={row.current} />}
      aside={
        <View style={{ alignItems: 'flex-end', gap: 4 }}>
          <ChangeText previous={row.previous.saleValueGross} current={row.current.saleValueGross} />
          {leafletNow ? <Badge tone="info">gazetka teraz</Badge> : null}
          {!leafletNow && leafletBefore ? <Badge tone="warn">gazetka rok temu</Badge> : null}
        </View>
      }
      onPress={onPress}
    />
  )
}

function TotalTiles({
  previous,
  current,
  promotions,
}: {
  previous: SalesFigures
  current: SalesFigures
  promotions: { previousSaleValueGross: number; currentSaleValueGross: number } | undefined
}) {
  const marginNow = marginShare(current.marginValue, current.saleValueGross)
  const marginBefore = marginShare(previous.marginValue, previous.saleValueGross)
  const promoShare = promotions ? marginShare(promotions.currentSaleValueGross, current.saleValueGross) : null
  return (
    <TileGrid>
      <CompareTile
        label="Sprzedaż brutto"
        value={zloty(current.saleValueGross)}
        change={<ChangeText previous={previous.saleValueGross} current={current.saleValueGross} />}
        previousLabel={zloty(previous.saleValueGross)}
      />
      <CompareTile
        label="Marża"
        value={zloty(current.marginValue)}
        change={<ChangeText previous={previous.marginValue} current={current.marginValue} />}
        previousLabel={zloty(previous.marginValue)}
      />
      <CompareTile
        label="Marża % od brutto"
        value={percent(marginNow)}
        change={<PointsText previous={marginBefore} current={marginNow} />}
        previousLabel={percent(marginBefore)}
      />
      <CompareTile
        label="W promocji"
        value={promotions ? zloty(promotions.currentSaleValueGross) : '…'}
        change={
          promotions ? (
            <ChangeText
              previous={promotions.previousSaleValueGross}
              current={promotions.currentSaleValueGross}
              neutral
            />
          ) : null
        }
        previousLabel={promotions ? zloty(promotions.previousSaleValueGross) : null}
        note={promoShare != null ? <Muted style={{ fontSize: 12 }}>{percent(promoShare)} sprzedaży</Muted> : null}
      />
    </TileGrid>
  )
}
