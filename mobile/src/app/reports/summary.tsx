import { router, Stack, useLocalSearchParams } from 'expo-router'
import { useMemo } from 'react'
import { RefreshControl, ScrollView, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import {
  ChangeText,
  CompareTile,
  GoalBar,
  PeriodPicker,
  PointsText,
  ReportList,
  ReportRow,
  TileGrid,
  useReportPeriod,
} from '@/components/report-parts'
import { Card, ErrorText, Loading, Muted } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { date, percent, zloty } from '@/lib/format'
import { yearAgoRange } from '@/lib/periods'
import { usePeriodSummary, usePeriodSummaryChildren, useProfitTargetProgress } from '@/lib/reports'
import { useTheme } from '@/lib/theme'
import type { PeriodSummaryFigures, PeriodSummaryLine } from '@/lib/types'

/**
 * Podsumowanie sprzedaży i strat (następca arkusza „kombajn”): jeden okres, rejony w grupach z zestawienia Intermarché,
 * marża, likwidacje i marża po stratach, kafelki wobec roku temu i cel marży na rok. Rejon otwiera rodziny, dalej
 * podrodziny i artykuły (kolejne ekrany z tym samym okresem).
 */
export default function SummaryScreen() {
  const { store } = useAuth()
  const params = useLocalSearchParams<{ parent?: string; nazwa?: string; brak?: string }>()
  const parent = typeof params.parent === 'string' ? params.parent : ''
  const unassigned = params.brak === 'tak'
  const storeId = store?.id ?? ''
  const period = useReportPeriod(storeId, 'month')
  const root = !parent && !unassigned
  const title = root ? 'Podsumowanie' : (params.nazwa ?? (unassigned ? 'Bez nomenklatury' : parent))

  return (
    <>
      <Stack.Screen options={{ title }} />
      {root ? (
        <SummaryRoot storeId={storeId} period={period} />
      ) : (
        <SummaryChildren storeId={storeId} period={period} parent={parent} unassigned={unassigned} title={title} />
      )}
    </>
  )
}

type Period = ReturnType<typeof useReportPeriod>

function SummaryRoot({ storeId, period }: { storeId: string; period: Period }) {
  const t = useTheme()
  const dockInset = useDockInset()
  const summary = usePeriodSummary(storeId, period.range)
  const yearAgoOf = useMemo(() => (period.range ? yearAgoRange(period.range) : null), [period.range])
  const yearAgo = usePeriodSummary(storeId, yearAgoOf)
  const progress = useProfitTargetProgress(storeId, period.range?.to ?? null)

  const data = summary.data
  const before = yearAgo.data && yearAgo.data.total.saleValueGross > 0 ? yearAgo.data.total : null

  const open = (line: PeriodSummaryLine) =>
    router.push({
      pathname: '/reports/summary',
      params: {
        ...period.params,
        ...(line.code ? { parent: line.code } : { brak: 'tak' }),
        nazwa: line.name ?? (line.code || 'Bez nomenklatury'),
      },
    })

  return (
    <ScrollView
      contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: dockInset }}
      refreshControl={<RefreshControl refreshing={summary.isRefetching} onRefresh={() => void summary.refetch()} />}
    >
      <PeriodPicker period={period} />
      {period.overview.isPending || (period.range && summary.isPending) ? (
        <Loading />
      ) : summary.isError ? (
        <ErrorText>Nie udało się wczytać podsumowania.</ErrorText>
      ) : !data ? (
        <Muted>Sklep nie ma jeszcze wgranej sprzedaży.</Muted>
      ) : (
        <>
          <Totals total={data.total} before={before} />
          {data.documentsCoveredUntil && data.documentsCoveredUntil < data.to ? (
            <Muted>Likwidacje są wgrane do {date(data.documentsCoveredUntil)}, później straty mogą być niepełne.</Muted>
          ) : null}
          {progress.data ? (
            <Card>
              <GoalBar progress={progress.data} label={`Marża po stratach od 1.01 do ${date(progress.data.to)}`} />
            </Card>
          ) : null}

          <Muted>m = marża, s = straty (likwidacje), na końcu marża po stratach; % od sprzedaży brutto.</Muted>
          <ReportList>
            {data.lines.map((line, index) =>
              line.kind === 'GROUP' ? (
                <View
                  key={`group-${index}`}
                  style={{ paddingHorizontal: 14, paddingTop: 14, paddingBottom: 6, backgroundColor: t.muted }}
                >
                  <Text style={{ color: t.text, fontSize: 13, fontWeight: '700', paddingLeft: line.depth * 10 }}>
                    {line.name}
                  </Text>
                </View>
              ) : line.figures ? (
                <SummaryLine
                  key={`${line.kind}-${line.code ?? index}-${index}`}
                  line={line}
                  figures={line.figures}
                  onPress={line.kind === 'REGION' ? () => open(line) : undefined}
                />
              ) : null,
            )}
            <SummaryFiguresRow title="Razem sklep" figures={data.total} tone="total" />
          </ReportList>
          {!data.budgetsSet ? (
            <Muted>
              Budżet rejonów (docelowa marża i limit strat) ustawia się w przeglądarce, w Ustawieniach sklepu.
            </Muted>
          ) : null}
        </>
      )}
    </ScrollView>
  )
}

function Totals({ total, before }: { total: PeriodSummaryFigures; before: PeriodSummaryFigures | null }) {
  return (
    <TileGrid>
      <CompareTile
        label="Sprzedaż brutto"
        value={zloty(total.saleValueGross)}
        change={<ChangeText previous={before?.saleValueGross} current={total.saleValueGross} />}
        previousLabel={before ? zloty(before.saleValueGross) : null}
      />
      <CompareTile
        label="Marża"
        value={`${percent(total.marginPercent)} · ${zloty(total.marginValue)}`}
        change={<PointsText previous={before?.marginPercent} current={total.marginPercent} />}
        previousLabel={before ? percent(before.marginPercent) : null}
      />
      <CompareTile
        label="Straty (likwidacje)"
        value={`${percent(total.lossPercent)} · ${zloty(total.lossValue)}`}
        change={<PointsText previous={before?.lossPercent} current={total.lossPercent} inverted />}
        previousLabel={before ? percent(before.lossPercent) : null}
      />
      <CompareTile
        label="Marża po stratach"
        value={`${percent(total.marginAfterLossesPercent)} · ${zloty(total.marginAfterLossesValue)}`}
        change={<PointsText previous={before?.marginAfterLossesPercent} current={total.marginAfterLossesPercent} />}
        previousLabel={before ? percent(before.marginAfterLossesPercent) : null}
      />
    </TileGrid>
  )
}

function SummaryLine({
  line,
  figures,
  onPress,
}: {
  line: PeriodSummaryLine
  figures: PeriodSummaryFigures
  onPress?: () => void
}) {
  return (
    <SummaryFiguresRow
      code={line.kind === 'REGION' ? line.code : null}
      title={line.name ?? (line.code || 'Bez nomenklatury')}
      figures={figures}
      tone={line.kind === 'SUBTOTAL' ? 'group' : undefined}
      marginBudget={line.marginBudgetPercent}
      maxLoss={line.maxLossPercent}
      onPress={onPress}
    />
  )
}

/** Wiersz z kwotą sprzedaży i marżą, stratami i marżą po stratach w %, w kolorze budżetu rejonu, gdy jest. */
function SummaryFiguresRow({
  code,
  title,
  subtitle,
  figures,
  tone,
  marginBudget,
  maxLoss,
  onPress,
}: {
  code?: string | null
  title: string
  subtitle?: string | null
  figures: PeriodSummaryFigures
  tone?: 'group' | 'total'
  marginBudget?: number | null
  maxLoss?: number | null
  onPress?: () => void
}) {
  const t = useTheme()
  const marginBad = marginBudget != null && figures.marginPercent != null && figures.marginPercent < marginBudget
  const lossBad = maxLoss != null && figures.lossPercent != null && figures.lossPercent > maxLoss
  return (
    <ReportRow
      code={code}
      title={title}
      tone={tone}
      subtitle={
        [subtitle, figures.sharePercent != null ? `udział ${percent(figures.sharePercent)}` : null]
          .filter(Boolean)
          .join(' · ') || null
      }
      value={
        <Text style={{ color: t.text, fontSize: 15, fontWeight: '700', fontVariant: ['tabular-nums'] }}>
          {zloty(figures.saleValueGross)}
        </Text>
      }
      aside={
        <Text style={{ fontSize: 12, fontVariant: ['tabular-nums'], color: t.mutedText }}>
          <Text style={{ color: marginBad ? t.danger : t.mutedText, fontWeight: marginBad ? '700' : '400' }}>
            m {percent(figures.marginPercent)}
          </Text>
          {' · '}
          <Text style={{ color: lossBad ? t.danger : t.mutedText, fontWeight: lossBad ? '700' : '400' }}>
            s {percent(figures.lossPercent)}
          </Text>
          {' · '}
          <Text style={{ color: t.text, fontWeight: '600' }}>{percent(figures.marginAfterLossesPercent)}</Text>
        </Text>
      }
      onPress={onPress}
    />
  )
}

function SummaryChildren({
  storeId,
  period,
  parent,
  unassigned,
  title,
}: {
  storeId: string
  period: Period
  parent: string
  unassigned: boolean
  title: string
}) {
  const t = useTheme()
  const dockInset = useDockInset()
  const children = usePeriodSummaryChildren(storeId, period.range, parent, unassigned)
  const data = children.data
  const total = data?.rows.reduce((sum, row) => sum + row.figures.saleValueGross, 0) ?? 0

  const open = (code: string, name: string | null) => {
    if (data?.level === 'PRODUCT') {
      router.push({ pathname: '/article/[itm8]', params: { itm8: code } })
      return
    }
    router.push({ pathname: '/reports/summary', params: { ...period.params, parent: code, nazwa: name ?? code } })
  }

  return (
    <ScrollView
      contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: dockInset }}
      refreshControl={<RefreshControl refreshing={children.isRefetching} onRefresh={() => void children.refetch()} />}
    >
      <PeriodPicker period={period} />
      <Text style={{ color: t.text, fontSize: 18, fontWeight: '700' }}>{title}</Text>
      <Muted>m = marża, s = straty (likwidacje), na końcu marża po stratach; % od sprzedaży brutto.</Muted>
      {period.overview.isPending || (period.range && children.isPending) ? (
        <Loading />
      ) : children.isError ? (
        <ErrorText>Nie udało się wczytać pozycji.</ErrorText>
      ) : !data || data.rows.length === 0 ? (
        <Muted>Brak sprzedaży i strat w tym okresie.</Muted>
      ) : (
        <ReportList>
          {[...data.rows]
            .sort((a, b) => b.figures.saleValueGross - a.figures.saleValueGross)
            .map((row) => (
              <SummaryFiguresRow
                key={row.code || 'brak'}
                code={data.level === 'PRODUCT' ? null : row.code}
                title={row.name ?? row.code}
                subtitle={data.level === 'PRODUCT' ? row.brand : null}
                figures={{
                  ...row.figures,
                  sharePercent: total ? (row.figures.saleValueGross / total) * 100 : null,
                }}
                onPress={() => open(row.code, row.name)}
              />
            ))}
        </ReportList>
      )}
    </ScrollView>
  )
}
