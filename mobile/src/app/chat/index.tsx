import { router } from 'expo-router'
import { memo, useCallback, useMemo, useState } from 'react'
import { ActivityIndicator, Alert, Pressable, RefreshControl, SectionList, StyleSheet, Text, View } from 'react-native'

import { Avatar } from '@/components/Avatar'
import { useDockInset } from '@/components/FloatingDock'
import { StoreIcon, UsersIcon } from '@/components/icons'
import { ErrorText, Loading } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { useChatActions, useConversations, usePeople } from '@/lib/chat'
import { conversationTitle, lastSeen, listTime, preview } from '@/lib/chat-format'
import { useTheme, type Theme } from '@/lib/theme'
import type { ChatConversation, ChatPeople } from '@/lib/types'

type Tab = 'chats' | 'people'

/** Kolejność w części: najpierw grupy (czat sklepu na górze), potem rozmowy z osobami, każde od najświeższej. */
function ordered(list: ChatConversation[]) {
  const rank = (conversation: ChatConversation) =>
    conversation.kind === 'STORE' ? 0 : conversation.kind === 'GROUP' ? 1 : 2
  return [...list].sort((a, b) => rank(a) - rank(b))
}

/**
 * Czat w telefonie: rozmowy (najpierw bieżącego sklepu, niżej niezależne od sklepu i z innych sklepów) albo osoby
 * sklepu (w pracy i pozostali). Dotknięcie osoby otwiera rozmowę z nią.
 */
export default function ChatScreen() {
  const t = useTheme()
  const dockInset = useDockInset()
  const { me, store } = useAuth()
  const [tab, setTab] = useState<Tab>('chats')
  const conversations = useConversations(store?.id)
  const people = usePeople(tab === 'people' ? store?.id : undefined)
  const { direct } = useChatActions()

  const sections = useMemo(() => {
    const list = conversations.data ?? []
    return [
      { title: store ? `Sklep ${store.number}` : 'Ten sklep', data: ordered(list.filter((item) => item.local)) },
      { title: 'Niezależne od sklepu', data: ordered(list.filter((item) => !item.local)) },
    ].filter((section) => section.data.length > 0)
  }, [conversations.data, store])

  const open = useCallback((id: string) => router.push({ pathname: '/chat/[id]', params: { id } }), [])
  const openPerson = useCallback(
    (userId: string) =>
      direct.mutate(
        { userId, storeId: store?.id },
        {
          onSuccess: (conversation) => open(conversation.id),
          onError: (error) =>
            Alert.alert('Nie udało się otworzyć rozmowy', error instanceof ApiError ? error.message : undefined),
        },
      ),
    [direct, store?.id, open],
  )

  const meId = me?.id ?? ''
  const renderItem = useCallback(
    ({ item }: { item: ChatConversation }) => (
      <ConversationRow conversation={item} meId={meId} theme={t} onOpen={open} />
    ),
    [meId, t, open],
  )

  return (
    <View style={{ flex: 1 }}>
      <View style={[styles.tabs, { backgroundColor: t.muted }]}>
        {(['chats', 'people'] as const).map((value) => (
          <Pressable
            key={value}
            accessibilityRole="tab"
            accessibilityState={{ selected: tab === value }}
            onPress={() => setTab(value)}
            style={[styles.tab, tab === value ? { backgroundColor: t.card } : null]}
          >
            <Text style={[styles.tabText, { color: tab === value ? t.text : t.mutedText }]}>
              {value === 'chats' ? 'Rozmowy' : 'Osoby w sklepie'}
            </Text>
          </Pressable>
        ))}
      </View>
      {tab === 'chats' ? (
        conversations.isPending ? (
          <Loading />
        ) : conversations.isError ? (
          <View style={{ padding: 20 }}>
            <ErrorText>{conversations.error.message}</ErrorText>
          </View>
        ) : (
          <SectionList
            sections={sections}
            keyExtractor={(item) => item.id}
            renderItem={renderItem}
            renderSectionHeader={({ section }) => (
              <Text style={[styles.section, { color: t.mutedText, backgroundColor: t.background }]}>
                {section.title}
              </Text>
            )}
            stickySectionHeadersEnabled={false}
            contentContainerStyle={{ paddingBottom: dockInset }}
            refreshControl={
              <RefreshControl
                refreshing={conversations.isRefetching}
                onRefresh={() => void conversations.refetch()}
                tintColor={t.mutedText}
              />
            }
          />
        )
      ) : (
        <PeopleList
          data={people.data}
          loading={people.isPending}
          meId={meId}
          theme={t}
          onOpen={openPerson}
          opening={direct.isPending ? (direct.variables?.userId ?? null) : null}
          bottom={dockInset}
        />
      )}
    </View>
  )
}

const ConversationRow = memo(function ConversationRow({
  conversation,
  meId,
  theme: t,
  onOpen,
}: {
  conversation: ChatConversation
  meId: string
  theme: Theme
  onOpen: (id: string) => void
}) {
  const title = conversationTitle(conversation)
  const unread = conversation.unread > 0
  return (
    <Pressable
      onPress={() => onOpen(conversation.id)}
      style={({ pressed }) => [styles.row, pressed ? { backgroundColor: t.muted } : null]}
    >
      {conversation.kind === 'DIRECT' ? (
        <Avatar name={title} size={44} url={conversation.peer?.avatarUrl} />
      ) : (
        <View style={[styles.groupIcon, { backgroundColor: conversation.kind === 'STORE' ? t.infoBg : t.promoBg }]}>
          {conversation.kind === 'STORE' ? (
            <StoreIcon color={t.infoText} size={22} />
          ) : (
            <UsersIcon color={t.promoText} size={22} />
          )}
        </View>
      )}
      <View style={{ flex: 1, minWidth: 0 }}>
        <View style={styles.rowTop}>
          <Text numberOfLines={1} style={[styles.title, { color: t.text, fontWeight: unread ? '700' : '600' }]}>
            {title}
          </Text>
          {conversation.lastMessageAt ? (
            <Text style={[styles.time, { color: unread ? t.text : t.mutedText }]}>
              {listTime(conversation.lastMessageAt)}
            </Text>
          ) : null}
        </View>
        <View style={styles.rowTop}>
          <Text numberOfLines={1} style={[styles.preview, { color: unread ? t.text : t.mutedText }]}>
            {preview(conversation, meId)}
          </Text>
          {unread ? (
            <View style={[styles.unread, { backgroundColor: t.primary }]}>
              <Text style={[styles.unreadText, { color: t.primaryText }]}>
                {conversation.unread > 99 ? '99+' : conversation.unread}
              </Text>
            </View>
          ) : null}
        </View>
      </View>
    </Pressable>
  )
})

function PeopleList({
  data,
  loading,
  meId,
  theme: t,
  onOpen,
  opening,
  bottom,
}: {
  data: ChatPeople | undefined
  loading: boolean
  meId: string
  theme: Theme
  onOpen: (userId: string) => void
  /** Osoba, z którą rozmowa właśnie się otwiera: kółko przy niej, reszta zablokowana do końca. */
  opening: string | null
  bottom: number
}) {
  if (loading || !data) return <Loading />
  const short = (value: string | null) => (value ? value.slice(0, 5) : '')
  const sections = [
    {
      title: `W pracy (${data.working.filter((person) => person.punched).length} z ${data.working.length})`,
      data: data.working.map((person) => ({
        id: person.userId,
        name: person.name,
        avatarUrl: person.avatarUrl,
        active: person.punched,
        // Godziny zmian i odbić tylko z dostępem do Czasu pracy (`data.hours`).
        detail: data.hours
          ? `${person.start ? `${short(person.start)}–${short(person.end)}` : 'poza grafikiem'} · ${
              person.since ? `od ${person.since.slice(11, 16)}` : 'nie odbił(a) karty'
            }`
          : `${person.position ? `${person.position} · ` : ''}${person.punched ? 'w pracy' : 'nie odbił(a) karty'}`,
      })),
    },
    {
      title: 'Pozostali',
      data: data.others.map((person) => ({
        id: person.userId,
        name: person.name,
        avatarUrl: person.avatarUrl,
        active: person.online,
        detail: lastSeen(person.lastSeenAt, person.online),
      })),
    },
  ].filter((section) => section.data.length > 0)
  return (
    <SectionList
      sections={sections}
      keyExtractor={(item) => item.id}
      stickySectionHeadersEnabled={false}
      contentContainerStyle={{ paddingBottom: bottom }}
      renderSectionHeader={({ section }) => (
        <Text style={[styles.section, { color: t.mutedText, backgroundColor: t.background }]}>{section.title}</Text>
      )}
      renderItem={({ item }) => (
        <Pressable
          disabled={item.id === meId || opening !== null}
          onPress={() => onOpen(item.id)}
          style={({ pressed }) => [styles.row, pressed ? { backgroundColor: t.muted } : null]}
        >
          <View>
            <Avatar name={item.name} size={40} url={item.avatarUrl} />
            {opening === item.id && (
              <View style={[StyleSheet.absoluteFill, styles.opening, { backgroundColor: `${t.background}b3` }]}>
                <ActivityIndicator size="small" color={t.text} />
              </View>
            )}
            <View
              style={[styles.dot, { borderColor: t.background, backgroundColor: item.active ? '#22c55e' : t.barMuted }]}
            />
          </View>
          <View style={{ flex: 1, minWidth: 0 }}>
            <Text numberOfLines={1} style={[styles.title, { color: t.text }]}>
              {item.id === meId ? `${item.name} (Ty)` : item.name}
            </Text>
            <Text numberOfLines={1} style={[styles.preview, { color: t.mutedText }]}>
              {item.detail}
            </Text>
          </View>
        </Pressable>
      )}
    />
  )
}

const styles = StyleSheet.create({
  opening: { alignItems: 'center', justifyContent: 'center', borderRadius: 20 },
  tabs: { flexDirection: 'row', margin: 16, marginBottom: 4, padding: 3, borderRadius: 12 },
  tab: { flex: 1, paddingVertical: 8, borderRadius: 9, alignItems: 'center' },
  tabText: { fontSize: 14, fontWeight: '600' },
  section: {
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 6,
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 10 },
  rowTop: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  groupIcon: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  title: { flex: 1, fontSize: 16 },
  time: { fontSize: 12, fontVariant: ['tabular-nums'] },
  preview: { flex: 1, fontSize: 14, marginTop: 2 },
  unread: {
    minWidth: 20,
    height: 20,
    borderRadius: 10,
    paddingHorizontal: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  unreadText: { fontSize: 12, fontWeight: '700', fontVariant: ['tabular-nums'] },
  dot: { position: 'absolute', right: -1, bottom: -1, width: 13, height: 13, borderRadius: 7, borderWidth: 2 },
})
