import { router } from 'expo-router'
import { Alert, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native'

import { useDockInset } from '@/components/FloatingDock'
import { Badge, Button, Card, ErrorText, Loading, Muted, SectionTitle } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { shortDate } from '@/lib/format'
import {
  clock,
  describeAnomaly,
  hm,
  REQUEST_KINDS,
  REQUEST_STATUSES,
  useCancelRequest,
  useMyAnomalies,
  useMyRequests,
} from '@/lib/requests'
import { useTheme } from '@/lib/theme'
import type { MyTimeAnomalies, TimeRequest } from '@/lib/types'
import { WEEKDAYS, weekdayIndex } from '@/lib/week'

type Anomaly = MyTimeAnomalies['items'][number]

const dayLabel = (iso: string) => `${WEEKDAYS[weekdayIndex(iso)]}, ${shortDate(iso)}`

/**
 * Wnioski pracownika: różnice między odbiciami a grafikiem z ostatnich 14 dni (na każdą można złożyć wniosek),
 * zgłoszenie L4 i moje wnioski ze stanem.
 */
export default function RequestsScreen() {
  const t = useTheme()
  const dockInset = useDockInset()
  const anomalies = useMyAnomalies()
  const requests = useMyRequests()
  const refreshing = anomalies.isRefetching || requests.isRefetching

  if (anomalies.data && !anomalies.data.employee) {
    return (
      <View style={{ padding: 20 }}>
        <Card>
          <Muted style={{ fontSize: 15 }}>Twoje konto nie jest przypisane jako pracownik sklepu.</Muted>
        </Card>
      </View>
    )
  }

  return (
    <ScrollView
      contentContainerStyle={{ padding: 20, gap: 24, paddingBottom: dockInset }}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => {
            void anomalies.refetch()
            void requests.refetch()
          }}
          tintColor={t.mutedText}
        />
      }
    >
      <View style={{ gap: 12 }}>
        <SectionTitle>Do wyjaśnienia</SectionTitle>
        {anomalies.isPending ? (
          <Loading />
        ) : anomalies.isError ? (
          <ErrorText>Nie udało się wczytać karty czasu pracy.</ErrorText>
        ) : anomalies.data.items.length === 0 ? (
          <Card>
            <Muted style={{ fontSize: 15 }}>
              W ostatnich 14 dniach odbicia zgadzają się z grafikiem. Nie ma nic do wyjaśnienia.
            </Muted>
          </Card>
        ) : (
          anomalies.data.items.map((item) => <AnomalyCard key={`${item.part}-${item.start}`} item={item} />)
        )}
      </View>

      <Button
        title="Zgłoś zwolnienie lekarskie (L4)"
        variant="secondary"
        onPress={() => router.push({ pathname: '/request-new', params: { mode: 'sick' } })}
      />

      <View style={{ gap: 12 }}>
        <SectionTitle>Moje wnioski</SectionTitle>
        {requests.isPending ? (
          <Loading />
        ) : requests.isError ? (
          <ErrorText>Nie udało się wczytać wniosków.</ErrorText>
        ) : requests.data.length === 0 ? (
          <Muted style={{ fontSize: 15 }}>Nie masz jeszcze żadnych wniosków.</Muted>
        ) : (
          requests.data.map((request) => <RequestCard key={request.id} request={request} />)
        )}
      </View>
    </ScrollView>
  )
}

function AnomalyCard({ item }: { item: Anomaly }) {
  const t = useTheme()
  const status = item.request ? REQUEST_STATUSES[item.request.status] : null
  // Wycofany albo odrzucony wniosek: można złożyć nowy.
  const canSubmit =
    item.part !== 'MISSING_EXIT' &&
    (!item.request || item.request.status === 'CANCELLED' || item.request.status === 'REJECTED')
  const planned =
    item.plannedStart && item.plannedEnd
      ? `Grafik ${clock(item.plannedStart)}–${clock(item.plannedEnd)}`
      : 'Bez grafiku'

  return (
    <Card>
      <View style={styles.header}>
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 16, fontWeight: '700' }}>{describeAnomaly(item)}</Text>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>{dayLabel(item.date)}</Text>
        </View>
        {status ? <Badge tone={status.tone}>{status.label}</Badge> : null}
      </View>
      {item.part === 'MISSING_EXIT' ? (
        <Muted style={{ fontSize: 14, lineHeight: 20 }}>
          Wejście o {clock(item.start)} bez odbicia wyjścia. Powiedz kierownikowi, o której wyszedłeś/wyszłaś, żeby
          poprawił kartę.
        </Muted>
      ) : (
        <Text style={{ color: t.text, fontSize: 15, fontVariant: ['tabular-nums'] }}>
          {clock(item.start)}–{item.end ? clock(item.end) : ''} · {hm(item.minutes)} h · {planned}
        </Text>
      )}
      {canSubmit ? (
        <Button
          title={item.request ? 'Złóż nowy wniosek' : 'Złóż wniosek'}
          onPress={() =>
            router.push({
              pathname: '/request-new',
              params: {
                mode: 'segment',
                part: item.part,
                date: item.date,
                start: item.start,
                end: item.end ?? '',
                minutes: String(item.minutes),
                title: describeAnomaly(item),
                kinds: item.kinds.join(','),
              },
            })
          }
        />
      ) : null}
    </Card>
  )
}

function RequestCard({ request }: { request: TimeRequest }) {
  const t = useTheme()
  const cancel = useCancelRequest()
  const status = REQUEST_STATUSES[request.status]
  const when =
    request.start && request.end
      ? `${dayLabel(request.workDate)} · ${clock(request.start)}–${clock(request.end)}`
      : request.dateTo && request.dateTo !== request.workDate
        ? `${shortDate(request.workDate)} – ${shortDate(request.dateTo)}`
        : dayLabel(request.workDate)
  const notes = [request.manager?.note, request.hr?.note].filter((note): note is string => !!note)

  const confirmCancel = () =>
    Alert.alert('Wycofać wniosek?', 'Kierownik nie będzie go już rozpatrywał.', [
      { text: 'Nie', style: 'cancel' },
      {
        text: 'Wycofaj',
        style: 'destructive',
        onPress: () =>
          cancel.mutate(request.id, {
            onError: (error) =>
              Alert.alert('Nie udało się wycofać', error instanceof ApiError ? error.message : 'Spróbuj ponownie.'),
          }),
      },
    ])

  return (
    <Card>
      <View style={styles.header}>
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }}>{REQUEST_KINDS[request.kind].label}</Text>
          <Text style={{ color: t.mutedText, fontSize: 14 }}>{when}</Text>
        </View>
        <Badge tone={status.tone}>{status.label}</Badge>
      </View>
      {request.reason ? <Text style={{ color: t.text, fontSize: 14 }}>„{request.reason}”</Text> : null}
      {notes.map((note) => (
        <Muted key={note} style={{ fontSize: 14 }}>
          Uwaga: {note}
        </Muted>
      ))}
      {request.canCancel ? (
        <Button title="Wycofaj wniosek" variant="ghost" loading={cancel.isPending} onPress={confirmCancel} />
      ) : null}
    </Card>
  )
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
})
