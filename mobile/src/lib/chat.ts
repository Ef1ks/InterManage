import { useEffect } from 'react'
import { AppState } from 'react-native'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './api'
import type { ChatConversation, ChatMessage, ChatPeople, ChatThread } from './types'

/*
 * Czat w telefonie. Aplikacja nie ma strumienia na żywo, więc otwarta rozmowa odpytuje serwer co kilka sekund
 * (odpowiedź niesie też „pisze…”), a lista rozmów rzadziej. Powiadomienia push dojdą razem z Firebase.
 */

const KEY = ['chat'] as const

export function useConversations(storeId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'conversations', storeId ?? null],
    queryFn: () => api<ChatConversation[]>(`/chat/conversations${storeId ? `?storeId=${storeId}` : ''}`),
    staleTime: 10_000,
    refetchInterval: 15_000,
  })
}

export function useChatUnread(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'unread'],
    queryFn: () => api<{ total: number }>('/chat/unread'),
    enabled,
    staleTime: 15_000,
    refetchInterval: 60_000,
  })
}

export function useThread(conversationId: string) {
  return useInfiniteQuery({
    queryKey: [...KEY, 'thread', conversationId],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      api<ChatThread>(
        `/chat/conversations/${conversationId}/messages${pageParam ? `?before=${encodeURIComponent(pageParam)}` : ''}`,
      ),
    getNextPageParam: (page) => (page.hasMore && page.messages.length > 0 ? page.messages[0].createdAt : null),
    staleTime: 0,
    refetchInterval: 4_000,
  })
}

export function usePeople(storeId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'people', storeId],
    enabled: !!storeId,
    queryFn: () => api<ChatPeople>(`/stores/${storeId}/chat/people`),
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useChatActions() {
  const queryClient = useQueryClient()
  const onSuccess = () => queryClient.invalidateQueries({ queryKey: KEY })
  return {
    // Telefon wysyła na razie sam tekst; pliki i zdjęcia dojdą z mediami.
    send: useMutation({
      mutationFn: ({ conversationId, body }: { conversationId: string; body: string }) =>
        sendText(conversationId, body),
      onSuccess,
    }),
    retract: useMutation({
      mutationFn: (messageId: string) => api<ChatMessage>(`/chat/messages/${messageId}/retract`, { method: 'POST' }),
      onSuccess,
    }),
    restore: useMutation({
      mutationFn: (messageId: string) => api<ChatMessage>(`/chat/messages/${messageId}/restore`, { method: 'POST' }),
      onSuccess,
    }),
    direct: useMutation({
      mutationFn: ({ userId, storeId }: { userId: string; storeId: string | undefined }) =>
        api<ChatConversation>(`/chat/direct${storeId ? `?storeId=${storeId}` : ''}`, {
          method: 'POST',
          json: { userId },
        }),
      onSuccess,
    }),
    read: useMutation({
      mutationFn: (conversationId: string) =>
        api<void>(`/chat/conversations/${conversationId}/read`, { method: 'POST' }),
      onSuccess,
    }),
  }
}

/** Sam tekst idzie jako JSON (przeglądarka z plikami wysyła multipart). */
function sendText(conversationId: string, body: string) {
  return api<ChatMessage>(`/chat/conversations/${conversationId}/messages`, { method: 'POST', json: { body } })
}

export function sendTyping(conversationId: string) {
  void api<void>(`/chat/conversations/${conversationId}/typing`, { method: 'POST' }).catch(() => undefined)
}

/** Obecność do czatu: co minutę, gdy aplikacja jest na ekranie. */
export function usePresenceHeartbeat(enabled: boolean) {
  useEffect(() => {
    if (!enabled) return
    const beat = () => {
      if (AppState.currentState !== 'active') return
      void api<void>('/chat/presence', { method: 'POST' }).catch(() => undefined)
    }
    beat()
    const timer = setInterval(beat, 60_000)
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') beat()
    })
    return () => {
      clearInterval(timer)
      subscription.remove()
    }
  }, [enabled])
}
