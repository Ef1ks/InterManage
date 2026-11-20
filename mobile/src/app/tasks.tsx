import { useState } from 'react'
import { Alert, Linking, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native'

import { CheckIcon } from '@/components/icons'
import { useDockInset } from '@/components/FloatingDock'
import { TaskAttachments } from '@/components/TaskAttachments'
import { Badge, Button, Card, ErrorText, Loading, Muted, SectionTitle } from '@/components/ui'
import { ApiError, currentServer } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { shortDate, withoutLeadingZeros } from '@/lib/format'
import { TASK_STATUSES, isOverdue, useAllTasks, useMyTasks, useTaskActions } from '@/lib/tasks'
import { useTheme } from '@/lib/theme'
import type { StoreTask, TaskStatus } from '@/lib/types'
import { todayIso } from '@/lib/week'

type TasksView = 'mine' | 'assigned'

const MINE_ORDER: Record<TaskStatus, number> = { OPEN: 0, DONE: 1, ACCEPTED: 2 }
const ASSIGNED_ORDER: Record<TaskStatus, number> = { DONE: 0, OPEN: 1, ACCEPTED: 2 }

const fail = (title: string) => (error: unknown) =>
  Alert.alert(title, error instanceof ApiError ? error.message : 'Spróbuj ponownie.')

/**
 * Zadania sklepu jako lista do odhaczania: wykonawca odhacza pozycje i oznacza wykonanie, a zlecający (właściciel)
 * przełącza na zlecone i przyjmuje albo odsyła do poprawy. Nowe zadania zleca się w przeglądarce.
 */
export default function TasksScreen() {
  const t = useTheme()
  const dockInset = useDockInset()
  const { me, store } = useAuth()
  // Zlecający (właściciel albo konto z włączonym zlecaniem) przełącza na zlecone przez siebie.
  const owner = me?.canAssignTasks ?? me?.owner ?? false
  const [view, setView] = useState<TasksView>(owner ? 'assigned' : 'mine')
  const mine = useMyTasks(store?.id)
  const all = useAllTasks(store?.id, owner)
  const query = view === 'assigned' ? all : mine
  const order = view === 'assigned' ? ASSIGNED_ORDER : MINE_ORDER
  const tasks = [...(query.data ?? [])].sort(
    (a, b) =>
      order[a.status] - order[b.status] ||
      (a.dueDate ?? '9999').localeCompare(b.dueDate ?? '9999') ||
      b.createdAt.localeCompare(a.createdAt),
  )

  if (!store) return null

  return (
    <ScrollView
      contentContainerStyle={{ padding: 20, gap: 16, paddingBottom: dockInset }}
      refreshControl={
        <RefreshControl
          refreshing={query.isRefetching}
          onRefresh={() => void query.refetch()}
          tintColor={t.mutedText}
        />
      }
    >
      {owner ? (
        <View style={[styles.switch, { backgroundColor: t.muted }]} accessibilityRole="tablist">
          {(
            [
              ['assigned', 'Zlecone'],
              ['mine', 'Moje'],
            ] as const
          ).map(([value, label]) => (
            <Pressable
              key={value}
              onPress={() => setView(value)}
              accessibilityRole="tab"
              accessibilityState={{ selected: view === value }}
              style={[styles.switchItem, view === value ? { backgroundColor: t.card } : null]}
            >
              <Text style={{ color: view === value ? t.text : t.mutedText, fontWeight: '600', fontSize: 15 }}>
                {label}
              </Text>
            </Pressable>
          ))}
        </View>
      ) : null}

      <SectionTitle>{view === 'assigned' ? 'Zlecone w sklepie' : 'Moje zadania'}</SectionTitle>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorText>Nie udało się wczytać zadań.</ErrorText>
      ) : tasks.length === 0 ? (
        <Card>
          <Muted style={{ fontSize: 15 }}>
            {view === 'assigned' ? 'Nic nie zlecono. Zadania zleca się w przeglądarce.' : 'Nie masz zadań.'}
          </Muted>
        </Card>
      ) : (
        tasks.map((task) => (
          <TaskCard key={task.id} storeId={store.id} task={task} showAssignee={view === 'assigned'} />
        ))
      )}
    </ScrollView>
  )
}

function TaskCard({ storeId, task, showAssignee }: { storeId: string; task: StoreTask; showAssignee: boolean }) {
  const t = useTheme()
  const actions = useTaskActions(storeId)
  const [open, setOpen] = useState(task.status === 'OPEN' && task.canComplete)
  const [comment, setComment] = useState('')
  const status = TASK_STATUSES[task.status]
  const overdue = isOverdue(task, todayIso())
  const canCheck = (task.canComplete || task.canManage) && task.status !== 'ACCEPTED'
  const meta = [
    showAssignee ? task.assignee.displayName : null,
    task.dueDate ? `${overdue ? 'po terminie' : 'do'} ${shortDate(task.dueDate)}` : null,
    task.itemCount > 0 ? `${task.checkedCount} / ${task.itemCount} poz.` : null,
    task.sourceLabel ? `z: ${task.sourceLabel}` : null,
  ].filter((part): part is string => part !== null)

  const confirmSendBack = () =>
    Alert.alert('Odesłać do poprawy?', 'Zadanie wróci do wykonawcy z Twoim komentarzem.', [
      { text: 'Nie', style: 'cancel' },
      {
        text: 'Odeślij',
        onPress: () =>
          actions.sendBack.mutate(
            { id: task.id, comment: comment.trim() || null },
            { onSuccess: () => setComment(''), onError: fail('Nie udało się odesłać') },
          ),
      },
    ])

  return (
    <Card>
      <Pressable
        onPress={() => setOpen((value) => !value)}
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
        style={styles.header}
      >
        <View style={{ flex: 1, gap: 4 }}>
          <Text
            style={{
              color: task.status === 'ACCEPTED' ? t.mutedText : t.text,
              fontSize: 16,
              fontWeight: '700',
              textDecorationLine: task.status === 'ACCEPTED' ? 'line-through' : 'none',
            }}
          >
            {task.priority === 'URGENT' ? <Text style={{ color: t.danger }}>Pilne · </Text> : null}
            {task.title}
          </Text>
          {meta.length > 0 ? (
            <Text style={{ color: overdue ? t.danger : t.mutedText, fontSize: 14 }}>{meta.join(' · ')}</Text>
          ) : null}
        </View>
        <Badge tone={status.tone}>{status.label}</Badge>
      </Pressable>

      {open ? (
        <View style={{ gap: 12 }}>
          {task.description ? (
            <Text style={{ color: t.text, fontSize: 15, lineHeight: 21 }}>{task.description}</Text>
          ) : null}
          {task.returnComment && task.status === 'OPEN' ? (
            <View style={{ backgroundColor: t.warnBg, borderRadius: 10, padding: 10 }}>
              <Text style={{ color: t.warnText, fontSize: 14 }}>Do poprawy: {task.returnComment}</Text>
            </View>
          ) : null}
          {task.doneComment && task.status !== 'OPEN' ? (
            <Muted style={{ fontSize: 14 }}>
              {task.doneBy?.displayName ?? 'Wykonawca'}: {task.doneComment}
            </Muted>
          ) : null}

          <TaskAttachments storeId={storeId} taskId={task.id} attachments={task.attachments ?? []} />

          {task.sourceUrl ? (
            <Button
              title={`Otwórz miejsce zlecenia${task.sourceLabel ? `: ${task.sourceLabel}` : ''}`}
              variant="ghost"
              onPress={() => {
                const url = task.sourceUrl!
                // Strona w przeglądarce, w sklepie zadania (przeglądarka mogła mieć wybrany inny sklep).
                const target = `${currentServer()}${url}${url.includes('?') ? '&' : '?'}w_sklepie=${encodeURIComponent(storeId)}`
                Linking.openURL(target).catch(fail('Nie udało się otworzyć przeglądarki'))
              }}
            />
          ) : null}

          {task.items.length > 0 ? (
            <View style={[styles.items, { borderColor: t.border }]}>
              {task.items.map((item, index) => (
                <Pressable
                  key={item.id}
                  disabled={!canCheck || actions.checkItem.isPending}
                  onPress={() =>
                    actions.checkItem.mutate(
                      { id: task.id, itemId: item.id, checked: !item.checked },
                      { onError: fail('Nie udało się odhaczyć') },
                    )
                  }
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: item.checked, disabled: !canCheck }}
                  accessibilityLabel={item.name}
                  style={({ pressed }) => [
                    styles.item,
                    index > 0 ? { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border } : null,
                    { opacity: pressed ? 0.7 : 1 },
                  ]}
                >
                  <View
                    style={[
                      styles.box,
                      item.checked
                        ? { backgroundColor: t.okText, borderColor: t.okText }
                        : { borderColor: t.mutedText },
                    ]}
                  >
                    {item.checked ? <CheckIcon color={t.card} size={14} /> : null}
                  </View>
                  <View style={{ flex: 1, gap: 2 }}>
                    <Text
                      style={{
                        color: item.checked ? t.mutedText : t.text,
                        fontSize: 15,
                        textDecorationLine: item.checked ? 'line-through' : 'none',
                      }}
                    >
                      {item.name}
                    </Text>
                    <Text style={{ color: t.mutedText, fontSize: 13 }}>
                      {[item.itm8 ? `ITM8 ${withoutLeadingZeros(item.itm8)}` : null, item.ean, item.details]
                        .filter(Boolean)
                        .join(' · ')}
                    </Text>
                  </View>
                </Pressable>
              ))}
            </View>
          ) : null}

          {(task.canComplete && task.status === 'OPEN') || (task.canManage && task.status !== 'OPEN') ? (
            <TextInput
              value={comment}
              onChangeText={setComment}
              placeholder={
                task.canManage && task.status !== 'OPEN' ? 'Komentarz do odesłania' : 'Komentarz (opcjonalnie)'
              }
              placeholderTextColor={t.mutedText}
              multiline
              style={[styles.comment, { color: t.text, borderColor: t.border, backgroundColor: t.background }]}
            />
          ) : null}

          {task.canComplete && task.status === 'OPEN' ? (
            <Button
              title="Wykonane"
              loading={actions.done.isPending}
              onPress={() =>
                actions.done.mutate(
                  { id: task.id, comment: comment.trim() || null },
                  { onSuccess: () => setComment(''), onError: fail('Nie udało się zapisać') },
                )
              }
            />
          ) : null}
          {task.canComplete && !task.canManage && task.status === 'DONE' ? (
            <Button
              title="Cofnij wykonanie"
              variant="ghost"
              loading={actions.undone.isPending}
              onPress={() => actions.undone.mutate(task.id, { onError: fail('Nie udało się cofnąć') })}
            />
          ) : null}
          {task.canManage && task.status === 'DONE' ? (
            <Button
              title="Przyjmij"
              loading={actions.accept.isPending}
              onPress={() => actions.accept.mutate(task.id, { onError: fail('Nie udało się przyjąć') })}
            />
          ) : null}
          {task.canManage && task.status !== 'OPEN' ? (
            <Button
              title="Odeślij do poprawy"
              variant="secondary"
              loading={actions.sendBack.isPending}
              onPress={confirmSendBack}
            />
          ) : null}
        </View>
      ) : null}
    </Card>
  )
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  switch: { flexDirection: 'row', borderRadius: 12, padding: 3 },
  switchItem: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 10 },
  items: { borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, overflow: 'hidden' },
  item: { flexDirection: 'row', alignItems: 'flex-start', gap: 12, paddingHorizontal: 12, paddingVertical: 10 },
  box: { width: 22, height: 22, borderRadius: 6, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  comment: { borderWidth: 1, borderRadius: 12, padding: 12, fontSize: 15, minHeight: 64, textAlignVertical: 'top' },
})
