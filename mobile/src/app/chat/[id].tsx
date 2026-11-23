import { Stack, useLocalSearchParams } from 'expo-router'
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ActivityIndicator,
  Alert,
  FlatList,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { Avatar } from '@/components/Avatar'
import { CheckIcon, FileIcon, SendIcon } from '@/components/icons'
import { ErrorText, Loading } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { sendTyping, useChatActions, useThread } from '@/lib/chat'
import { conversationTitle, dayLabel, fileSize, isSameDay, messageTime } from '@/lib/chat-format'
import { useTheme, type Theme } from '@/lib/theme'
import type { ChatMessage } from '@/lib/types'

const RUN_GAP_MS = 5 * 60_000
const TYPING_EVERY_MS = 3_000

type Row = {
  message: ChatMessage
  mine: boolean
  startsRun: boolean
  newDay: boolean
  read: boolean
}

/**
 * Rozmowa w telefonie. Lista jest odwrócona (najnowsze na dole, przy polu pisania), a rozmowa odpytuje serwer co kilka
 * sekund. Przytrzymanie własnej wiadomości cofa ją dla wszystkich. Pliki pobiera się na razie w przeglądarce.
 */
export default function ChatThreadScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const t = useTheme()
  const insets = useSafeAreaInsets()
  const { me } = useAuth()
  const meId = me?.id ?? ''
  const thread = useThread(id)
  const { read, retract, restore } = useChatActions()

  const pages = thread.data?.pages
  const first = pages?.[0]
  const messages = useMemo(() => (pages ? [...pages].reverse().flatMap((page) => page.messages) : []), [pages])
  const newest = messages.at(-1)
  const othersCount = first ? first.members.filter((member) => member.id !== meId).length : 0

  const markRead = read.mutate
  useEffect(() => {
    if (!newest || !first) return
    const myRead = first.reads.find((entry) => entry.userId === meId)?.lastReadAt
    if (newest.authorId === meId || (myRead && myRead >= newest.createdAt)) return
    markRead(id)
  }, [id, newest, first, meId, markRead])

  // Odwrócona lista: od najnowszej.
  const rows = useMemo<Row[]>(() => {
    const reads = first?.reads ?? []
    const result = messages.map((message, index) => {
      const previous = messages[index - 1]
      const newDay = !previous || !isSameDay(previous.createdAt, message.createdAt)
      const mine = message.authorId === meId
      const readers = mine
        ? reads.filter((entry) => entry.userId !== meId && entry.lastReadAt >= message.createdAt).length
        : 0
      return {
        message,
        mine,
        newDay,
        startsRun:
          newDay ||
          previous.authorId !== message.authorId ||
          new Date(message.createdAt).getTime() - new Date(previous.createdAt).getTime() > RUN_GAP_MS,
        read: mine && othersCount > 0 && readers >= othersCount,
      }
    })
    return result.reverse()
  }, [messages, first?.reads, meId, othersCount])

  const onLongPress = useCallback(
    (message: ChatMessage) => {
      const fail = (error: Error) => Alert.alert('Nie udało się', error instanceof ApiError ? error.message : undefined)
      if (message.canRetract) {
        Alert.alert('Cofnąć wiadomość?', 'Zniknie u wszystkich, zostanie napis „Wiadomość cofnięta”.', [
          { text: 'Anuluj', style: 'cancel' },
          {
            text: 'Cofnij dla wszystkich',
            style: 'destructive',
            onPress: () => retract.mutate(message.id, { onError: fail }),
          },
        ])
      } else if (message.canRestore) {
        Alert.alert('Przywrócić wiadomość?', undefined, [
          { text: 'Anuluj', style: 'cancel' },
          { text: 'Przywróć', onPress: () => restore.mutate(message.id, { onError: fail }) },
        ])
      }
    },
    [retract, restore],
  )

  const showAuthors = first?.conversation.kind !== 'DIRECT'
  const renderItem = useCallback(
    ({ item }: { item: Row }) => <MessageRow row={item} theme={t} showAuthor={showAuthors} onLongPress={onLongPress} />,
    [t, showAuthors, onLongPress],
  )

  if (thread.isPending) return <Loading />
  if (thread.isError || !first) {
    return (
      <View style={{ padding: 20 }}>
        <ErrorText>{thread.error?.message ?? 'Nie udało się wczytać rozmowy.'}</ErrorText>
      </View>
    )
  }

  const typing = first.typing.filter((person) => person.id !== meId)
  const title = conversationTitle(first.conversation)

  return (
    <KeyboardAvoidingView
      style={{ flex: 1 }}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={Platform.OS === 'ios' ? insets.top + 44 : 0}
    >
      <Stack.Screen
        options={{
          title,
          headerTitle: () => (
            <View>
              <Text numberOfLines={1} style={[styles.headerTitle, { color: t.text }]}>
                {title}
              </Text>
              {typing.length > 0 ? (
                <Text style={[styles.headerSub, { color: t.okText }]}>
                  {typing.length === 1 ? `${typing[0].name.split(' ')[0]} pisze…` : 'kilka osób pisze…'}
                </Text>
              ) : null}
            </View>
          ),
        }}
      />
      <FlatList
        inverted
        data={rows}
        keyExtractor={(item) => item.message.id}
        renderItem={renderItem}
        contentContainerStyle={{ paddingHorizontal: 12, paddingVertical: 12 }}
        onEndReached={() => {
          if (thread.hasNextPage && !thread.isFetchingNextPage) void thread.fetchNextPage()
        }}
        onEndReachedThreshold={0.3}
        ListFooterComponent={thread.isFetchingNextPage ? <ActivityIndicator style={{ margin: 12 }} /> : undefined}
        ListEmptyComponent={
          <Text style={[styles.empty, { color: t.mutedText }]}>Nie ma jeszcze wiadomości. Napisz pierwszą.</Text>
        }
        keyboardDismissMode="interactive"
        keyboardShouldPersistTaps="handled"
      />
      <Composer conversationId={id} theme={t} bottom={insets.bottom} />
    </KeyboardAvoidingView>
  )
}

const MessageRow = memo(function MessageRow({
  row,
  theme: t,
  showAuthor,
  onLongPress,
}: {
  row: Row
  theme: Theme
  showAuthor: boolean
  onLongPress: (message: ChatMessage) => void
}) {
  const { message, mine, startsRun, newDay, read } = row
  const hidden = message.retracted && message.body === null
  const bubbleColor = hidden ? 'transparent' : mine && !message.retracted ? t.primary : t.muted
  const textColor = mine && !message.retracted ? t.primaryText : message.retracted ? t.mutedText : t.text
  return (
    <View>
      {newDay ? (
        <View style={styles.dayWrap}>
          <Text style={[styles.day, { backgroundColor: t.muted, color: t.mutedText }]}>
            {dayLabel(message.createdAt)}
          </Text>
        </View>
      ) : null}
      <View style={[styles.line, mine ? styles.lineMine : null, startsRun ? { marginTop: 10 } : null]}>
        {!mine ? (
          <View style={{ width: 32 }}>
            {startsRun ? <Avatar name={message.authorName ?? '?'} size={32} url={message.authorAvatarUrl} /> : null}
          </View>
        ) : null}
        <View style={[styles.column, mine ? { alignItems: 'flex-end' } : null]}>
          {showAuthor && !mine && startsRun ? (
            <Text style={[styles.author, { color: t.mutedText }]}>{message.authorName}</Text>
          ) : null}
          <Pressable
            onLongPress={message.canRetract || message.canRestore ? () => onLongPress(message) : undefined}
            delayLongPress={350}
            style={[
              styles.bubble,
              { backgroundColor: bubbleColor },
              hidden ? { borderWidth: 1, borderStyle: 'dashed', borderColor: t.border } : null,
            ]}
          >
            {hidden ? (
              <Text style={[styles.body, { color: t.mutedText, fontStyle: 'italic' }]}>Wiadomość cofnięta</Text>
            ) : (
              <>
                {message.body ? (
                  <Text
                    style={[
                      styles.body,
                      { color: textColor },
                      message.retracted ? { textDecorationLine: 'line-through' } : null,
                    ]}
                  >
                    {message.body}
                  </Text>
                ) : null}
                {message.attachments.map((file) => (
                  <View
                    key={file.id}
                    style={[styles.file, { backgroundColor: mine ? 'rgba(255,255,255,0.15)' : t.card }]}
                  >
                    <FileIcon color={textColor} size={16} />
                    <Text numberOfLines={1} style={[styles.fileName, { color: textColor }]}>
                      {file.fileName}
                    </Text>
                    <Text style={[styles.meta, { color: textColor, opacity: 0.75 }]}>{fileSize(file.sizeBytes)}</Text>
                  </View>
                ))}
              </>
            )}
            <View style={styles.metaRow}>
              <Text style={[styles.meta, { color: textColor, opacity: 0.7 }]}>{messageTime(message.createdAt)}</Text>
              {mine && !message.retracted ? (
                <View style={{ flexDirection: 'row' }}>
                  <CheckIcon color={read ? '#38bdf8' : textColor} size={13} />
                  {read ? (
                    <View style={{ marginLeft: -8 }}>
                      <CheckIcon color="#38bdf8" size={13} />
                    </View>
                  ) : null}
                </View>
              ) : null}
            </View>
          </Pressable>
          {message.retracted && !hidden ? (
            <Text style={[styles.author, { color: t.mutedText }]}>
              Cofnięta{message.retractedByName ? ` przez ${message.retractedByName}` : ''}
            </Text>
          ) : null}
          {message.attachments.length > 0 && !hidden ? (
            <Text style={[styles.author, { color: t.mutedText }]}>Pliki pobierzesz w przeglądarce</Text>
          ) : null}
        </View>
      </View>
    </View>
  )
})

function Composer({ conversationId, theme: t, bottom }: { conversationId: string; theme: Theme; bottom: number }) {
  const [text, setText] = useState('')
  const lastTyping = useRef(0)
  const { send } = useChatActions()

  const submit = () => {
    const body = text.trim()
    if (!body || send.isPending) return
    send.mutate(
      { conversationId, body },
      {
        onSuccess: () => {
          setText('')
          lastTyping.current = 0
        },
        onError: (error) => Alert.alert('Nie udało się wysłać', error instanceof ApiError ? error.message : undefined),
      },
    )
  }

  return (
    <View
      style={[
        styles.composer,
        { borderColor: t.border, backgroundColor: t.background, paddingBottom: Math.max(bottom, 8) },
      ]}
    >
      <TextInput
        value={text}
        onChangeText={(value) => {
          setText(value)
          const now = Date.now()
          if (value.trim() && now - lastTyping.current > TYPING_EVERY_MS) {
            lastTyping.current = now
            sendTyping(conversationId)
          }
        }}
        placeholder="Napisz wiadomość"
        placeholderTextColor={t.mutedText}
        multiline
        maxLength={4000}
        style={[styles.input, { backgroundColor: t.card, borderColor: t.border, color: t.text }]}
      />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Wyślij"
        onPress={submit}
        disabled={!text.trim() || send.isPending}
        style={({ pressed }) => [
          styles.send,
          { backgroundColor: t.primary, opacity: !text.trim() ? 0.4 : pressed ? 0.8 : 1 },
          pressed ? { transform: [{ scale: 0.95 }] } : null,
        ]}
      >
        {send.isPending ? <ActivityIndicator color={t.primaryText} /> : <SendIcon color={t.primaryText} size={20} />}
      </Pressable>
    </View>
  )
}

const styles = StyleSheet.create({
  headerTitle: { fontSize: 17, fontWeight: '600', maxWidth: 240 },
  headerSub: { fontSize: 12 },
  empty: { textAlign: 'center', marginTop: 60, fontSize: 14, transform: [{ scaleY: -1 }] },
  dayWrap: { alignItems: 'center', marginVertical: 12 },
  day: {
    fontSize: 12,
    fontWeight: '600',
    paddingHorizontal: 12,
    paddingVertical: 3,
    borderRadius: 999,
    overflow: 'hidden',
  },
  line: { flexDirection: 'row', alignItems: 'flex-end', gap: 8, marginTop: 2 },
  lineMine: { flexDirection: 'row-reverse' },
  column: { maxWidth: '78%' },
  author: { fontSize: 12, marginBottom: 2, marginHorizontal: 4, marginTop: 2 },
  bubble: { borderRadius: 18, paddingHorizontal: 12, paddingVertical: 7 },
  body: { fontSize: 15, lineHeight: 20 },
  file: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderRadius: 10,
    paddingHorizontal: 8,
    paddingVertical: 6,
    marginTop: 4,
  },
  fileName: { flexShrink: 1, fontSize: 14 },
  metaRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: 3, marginTop: 2 },
  meta: { fontSize: 11, fontVariant: ['tabular-nums'] },
  composer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 8,
    paddingHorizontal: 10,
    paddingTop: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  input: {
    flex: 1,
    minHeight: 40,
    maxHeight: 140,
    borderRadius: 20,
    borderWidth: 1,
    paddingHorizontal: 14,
    paddingTop: 10,
    paddingBottom: 10,
    fontSize: 15,
  },
  send: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
})
