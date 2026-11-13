import { Stack, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Pressable, RefreshControl, ScrollView, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import { SalesChart } from '@/components/SalesChart'
import { Badge, Card, ErrorText, Loading, Muted, Row, SectionTitle, Tile } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { addDays, date, dateTime, marginPercent, money, percent, quantity } from '@/lib/format'
import { useArticle, useDeliveries, usePrices, useProtocols, useReportsOverview, useSalesHistory } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import type { ArticleDelivery, SalesArticle } from '@/lib/types'

const RANGES = [
  { key: 'month', label: '30 dni', days: 30, weekly: false },
  { key: 'quarter', label: '3 mies.', days: 91, weekly: false },
  { key: 'half', label: '6 mies.', days: 182, weekly: true },
  { key: 'year', label: 'Rok', days: 364, weekly: true },
] as const

export default function ArticleScreen() {
  const { itm8 } = useLocalSearchParams<{ itm8: string }>()
  const { store } = useAuth()
  if (!store || !itm8) return null
  return <ArticleView storeId={store.id} itm8={itm8} />
}

function ArticleView({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const t = useTheme()
  const dockInset = useDockInset()
  const article = useArticle(storeId, itm8)
  const overview = useReportsOverview(storeId)
  const deliveries = useDeliveries(storeId, itm8)
  const [rangeKey, setRangeKey] = useState<(typeof RANGES)[number]['key']>('month')
  const range = RANGES.find((item) => item.key === rangeKey)!
  const lastSaleDate = overview.data?.lastSaleDate ?? null
  const period = lastSaleDate ? { from: addDays(lastSaleDate, -(range.days - 1)), to: lastSaleDate } : null
  const history = useSalesHistory(storeId, itm8, period)
  // Pokrycie stanu liczymy zawsze z ostatnich 30 dni, niezależnie od wybranego okresu wykresu.
  const recent = useSalesHistory(
    storeId,
    itm8,
    lastSaleDate ? { from: addDays(lastSaleDate, -29), to: lastSaleDate } : null,
  )
  const [refreshing, setRefreshing] = useState(false)

  const refresh = async () => {
    setRefreshing(true)
    await Promise.all([article.refetch(), overview.refetch(), history.refetch(), deliveries.refetch()])
    setRefreshing(false)
  }

  const data = article.data
  const dailySales = recent.data ? recent.data.total.quantity / Math.max(1, recent.data.days.length) : null

  return (
    <ScrollView
      contentContainerStyle={{ padding: 16, gap: 16, paddingBottom: dockInset }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} />}
    >
      <Stack.Screen options={{ title: data?.name ?? 'Artykuł' }} />

      {article.isPending ? (
        <Loading />
      ) : article.isError ? (
        <ErrorText>{article.error.message}</ErrorText>
      ) : !data ? (
        <Card>
          <Text style={{ color: t.text }}>
            Artykułu nie ma w kartotece sklepu, więc nie znamy jego stanu ani cen. Poniżej zapisana sprzedaż.
          </Text>
        </Card>
      ) : (
        <ArticleSummary article={data} dailySales={dailySales} deliveries={deliveries.data} />
      )}

      <Card>
        <SectionTitle>Sprzedaż</SectionTitle>
        <View style={{ flexDirection: 'row', gap: 6 }}>
          {RANGES.map((item) => {
            const active = item.key === rangeKey
            return (
              <Pressable
                key={item.key}
                onPress={() => setRangeKey(item.key)}
                accessibilityState={{ selected: active }}
                style={{
                  flex: 1,
                  paddingVertical: 8,
                  borderRadius: 10,
                  alignItems: 'center',
                  backgroundColor: active ? t.primary : t.muted,
                }}
              >
                <Text style={{ color: active ? t.primaryText : t.text, fontWeight: '600', fontSize: 13 }}>
                  {item.label}
                </Text>
              </Pressable>
            )
          })}
        </View>
        {!lastSaleDate && !overview.isPending ? (
          <Muted>Brak wgranej sprzedaży w tym sklepie.</Muted>
        ) : history.data ? (
          <>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
              <Tile label="Ilość" value={quantity(history.data.total.quantity)} />
              <Tile label="Sprzedaż brutto" value={money(history.data.total.saleValueGross)} />
              <Tile
                label="Marża"
                value={money(history.data.total.marginValue)}
                hint={
                  history.data.total.marginValue != null && history.data.total.saleValueGross > 0
                    ? percent((history.data.total.marginValue / history.data.total.saleValueGross) * 100)
                    : null
                }
              />
              <Tile
                label="Straty (zakup netto)"
                value={money(history.data.lossValue)}
                tone={history.data.lossValue > 0 ? 'danger' : undefined}
                hint={
                  history.data.total.saleValueGross > 0 && history.data.lossValue > 0
                    ? `${percent((history.data.lossValue / history.data.total.saleValueGross) * 100)} sprzedaży`
                    : null
                }
              />
            </View>
            <SalesChart history={history.data} weekly={range.weekly} />
            <Muted>
              {date(history.data.from)} – {date(history.data.to)}
            </Muted>
          </>
        ) : history.isError ? (
          <ErrorText>{history.error.message}</ErrorText>
        ) : (
          <Loading />
        )}
      </Card>

      <DeliveriesCard deliveries={deliveries.data} loading={deliveries.isPending} error={deliveries.error} />
      <ProtocolsCard storeId={storeId} itm8={itm8} />
      <PricesCard storeId={storeId} itm8={itm8} />
    </ScrollView>
  )
}

function ArticleSummary({
  article,
  dailySales,
  deliveries,
}: {
  article: SalesArticle
  dailySales: number | null
  deliveries: ArticleDelivery[] | undefined
}) {
  const t = useTheme()
  const promo = article.promoPrice != null
  const currentPrice = article.promoPrice ?? article.salePrice
  const lastPurchase = deliveries?.find((item) => item.purchasePrice != null)?.purchasePrice ?? null
  const margin = marginPercent(article.salePrice, article.purchasePrice, article.vatPercent)
  const coverDays =
    article.stock != null && article.stock > 0 && dailySales != null && dailySales > 0
      ? article.stock / dailySales
      : null
  const path = article.nomenclaturePath.map((item) => item.name.replace(/^\d+_/, '')).join(' › ')

  return (
    <>
      <View style={{ gap: 8 }}>
        <Text style={{ color: t.text, fontSize: 22, fontWeight: '700' }}>{article.name ?? article.itm8}</Text>
        {article.brand ? <Muted style={{ fontSize: 15 }}>{article.brand}</Muted> : null}
        <Text selectable style={{ color: t.mutedText, fontSize: 13, fontVariant: ['tabular-nums'] }}>
          EAN {article.ean} · ITM8 {article.itm8.replace(/^0+/, '')}
        </Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
          {article.cadencier && <Badge tone="danger">Obecny w cadencier</Badge>}
          {promo && <Badge tone="promo">Promocja do {date(article.promoTo)}</Badge>}
          {article.active === false && <Badge>Nieaktywny</Badge>}
        </View>
      </View>

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        <Tile
          label={promo ? 'Cena promocyjna' : 'Cena sprzedaży'}
          value={money(currentPrice)}
          tone={promo ? 'promo' : undefined}
          hint={promo ? `regularna ${money(article.salePrice)}` : null}
        />
        <Tile
          label="Stan"
          value={quantity(article.stock)}
          tone={article.stock != null && article.stock < 0 ? 'danger' : undefined}
          hint={coverDays != null ? `na ok. ${Math.round(coverDays)} dni sprzedaży` : null}
        />
        <Tile
          label="Cena zakupu netto"
          value={money(article.purchasePrice)}
          hint={lastPurchase != null ? `ostatnia dostawa ${money(lastPurchase)}` : null}
        />
        <Tile label="Marża" value={percent(margin)} hint="od sprzedaży brutto" />
        <Tile label="Rotacja" value={quantity(article.rotation)} hint="tygodniowo" />
        {article.cadencierPrice != null && article.cadencierPrice > 0 && (
          <Tile label="Cena cadencier" value={money(article.cadencierPrice)} />
        )}
      </View>

      <Card>
        <SectionTitle>Dane z kartoteki</SectionTitle>
        <View>
          <Row label="Dostawca" value={article.supplier ?? '–'} />
          <Row label="Nomenklatura" value={path || '–'} />
          <Row label="VAT" value={percent(article.vatPercent)} />
          <Row label="Jednostka" value={article.unit ?? '–'} />
          <Row label="Karton" value={quantity(article.caseSize)} />
          <Row label="Raport kartoteki" value={dateTime(article.reportGeneratedAt)} />
          {dailySales != null && (
            <Row label="Sprzedaż dziennie (30 dni)" value={quantity(Math.round(dailySales * 100) / 100)} />
          )}
        </View>
      </Card>
    </>
  )
}

function DeliveriesCard({
  deliveries,
  loading,
  error,
}: {
  deliveries: ArticleDelivery[] | undefined
  loading: boolean
  error: Error | null
}) {
  const t = useTheme()
  const [all, setAll] = useState(false)
  const shown = all ? deliveries : deliveries?.slice(0, 8)
  return (
    <Card>
      <SectionTitle>Dostawy</SectionTitle>
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorText>{error.message}</ErrorText>
      ) : !deliveries?.length ? (
        <Muted>Brak przyjęć tego artykułu.</Muted>
      ) : (
        <View style={{ gap: 10 }}>
          {shown!.map((item, index) => {
            const previous = deliveries[index + 1]?.purchasePrice
            const change =
              item.purchasePrice != null && previous != null && previous > 0
                ? ((item.purchasePrice - previous) / previous) * 100
                : null
            return (
              <View key={`${item.documentId}-${index}`} style={{ gap: 2 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 12 }}>
                  <Text style={{ color: t.text, fontWeight: '600', flexShrink: 1 }} numberOfLines={1}>
                    {date(item.documentDate)} · {item.supplierName ?? item.supplierCode ?? 'dostawca'}
                  </Text>
                  <Text style={{ color: t.text, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
                    {money(item.purchasePrice)}
                  </Text>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 12 }}>
                  <Muted>
                    {quantity(item.quantity)} szt. · bon {item.documentNumber.replace(/00$/, '')}
                    {item.corrected ? ' · po korekcie' : ''}
                  </Muted>
                  {change != null && Math.abs(change) >= 0.05 && (
                    <Text style={{ color: change > 0 ? t.danger : t.okText, fontSize: 13 }}>
                      {change > 0 ? '+' : ''}
                      {percent(change)}
                    </Text>
                  )}
                </View>
                {item.meaCodes.length > 0 && <Muted>MEA {item.meaCodes.join(', ')}</Muted>}
              </View>
            )
          })}
          {deliveries.length > 8 && (
            <Pressable onPress={() => setAll((value) => !value)} hitSlop={8}>
              <Text style={{ color: t.text, fontWeight: '600' }}>
                {all ? 'Pokaż mniej' : `Pokaż wszystkie (${deliveries.length})`}
              </Text>
            </Pressable>
          )}
        </View>
      )}
    </Card>
  )
}

function ProtocolsCard({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const t = useTheme()
  const protocols = useProtocols(storeId, itm8)
  return (
    <Card>
      <SectionTitle>Protokoły</SectionTitle>
      {protocols.isPending ? (
        <Loading />
      ) : protocols.isError ? (
        <ErrorText>{protocols.error.message}</ErrorText>
      ) : !protocols.data.length ? (
        <Muted>Brak protokołów (likwidacje, przeniesienia, potrzeby sklepu…).</Muted>
      ) : (
        protocols.data.map((item, index) => (
          <View key={`${item.documentId}-${index}`} style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text style={{ color: t.text, flexShrink: 1 }}>
              {date(item.documentDate)} · {item.gratis ? 'Gratis' : item.label}
            </Text>
            <Text style={{ color: t.text, fontVariant: ['tabular-nums'] }}>{quantity(item.quantity)}</Text>
          </View>
        ))
      )}
    </Card>
  )
}

function PricesCard({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const t = useTheme()
  const prices = usePrices(storeId, itm8)
  if (prices.isError) return null
  const today = new Date().toISOString().slice(0, 10)
  const promotions = prices.data?.promotions.filter((item) => !item.cancelled).slice(0, 6) ?? []
  const changes =
    prices.data?.changes.filter((item) => item.ean == null || item.ean === prices.data?.mainEan).slice(0, 8) ?? []
  return (
    <Card>
      <SectionTitle>Ceny i promocje</SectionTitle>
      {prices.isPending ? (
        <Loading />
      ) : (
        <View style={{ gap: 12 }}>
          {promotions.length > 0 && (
            <View style={{ gap: 6 }}>
              <Muted>Promocje z listy promocji</Muted>
              {promotions.map((item) => {
                const status = item.endDate < today ? 'zakończona' : item.startDate > today ? 'zaplanowana' : 'trwa'
                return (
                  <View
                    key={`${item.startDate}-${item.endDate}`}
                    style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}
                  >
                    <Text style={{ color: t.text, flexShrink: 1 }}>
                      {date(item.startDate)} – {date(item.endDate)} · {status}
                    </Text>
                    <Text style={{ color: t.text, fontVariant: ['tabular-nums'] }}>{money(item.promoPrice)}</Text>
                  </View>
                )
              })}
            </View>
          )}
          {changes.length > 0 && (
            <View style={{ gap: 6 }}>
              <Muted>Zmiany ceny sprzedaży</Muted>
              {changes.map((item, index) => (
                <View
                  key={`${item.changedAt}-${index}`}
                  style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}
                >
                  <Text style={{ color: t.text, flexShrink: 1 }}>
                    {dateTime(item.changedAt)} ·{' '}
                    {item.source === 'TILL' ? 'kasa' : item.source === 'REPORT' ? 'kartoteka' : 'zmiana cen'}
                  </Text>
                  <Text style={{ color: t.text, fontVariant: ['tabular-nums'] }}>
                    {item.previousSalePrice != null ? `${money(item.previousSalePrice)} → ` : ''}
                    {money(item.promoPrice ?? item.salePrice)}
                  </Text>
                </View>
              ))}
            </View>
          )}
          {promotions.length === 0 && changes.length === 0 && <Muted>Brak zapisanych zmian cen.</Muted>}
        </View>
      )}
    </Card>
  )
}
