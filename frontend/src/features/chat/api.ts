import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { ChatConversation, ChatLogoChange, ChatMessage, ChatPeople, ChatPerson, ChatThread } from '@/lib/types'

/*
 * Czat: rozmowy nie należą do jednego sklepu, więc API jest pod /api/chat. Zmiany przychodzą na żywo (temat `chat`
 * w use-live-events), dlatego mutacje nie unieważniają wszystkich zapytań aplikacji, tylko zapytania czatu.
 */

export const chatKey = ['chat'] as const
const NO_GLOBAL_INVALIDATION = { invalidates: false }

/**
 * Najwięcej plików w jednej wiadomości i ich łączny rozmiar (tak samo w API): idą jednym żądaniem, a przy łączu sklepu
 * ok. 200 kB/s 10 MB to ok. 50 s, poniżej limitu Cloudflare.
 */
export const MAX_CHAT_FILES = 10
export const MAX_CHAT_FILE_BYTES = 10 * 1024 * 1024

export function useConversations(storeId: string | undefined) {
  return useQuery({
    queryKey: [...chatKey, 'conversations', storeId ?? null],
    queryFn: () => api<ChatConversation[]>(`/chat/conversations${storeId ? `?storeId=${storeId}` : ''}`),
    // Zapas, gdyby strumień na żywo się rozłączył.
    refetchInterval: 60_000,
  })
}

/** Licznik nieprzeczytanych w menu. */
export function useChatUnread() {
  return useQuery({
    queryKey: [...chatKey, 'unread'],
    queryFn: () => api<{ total: number }>('/chat/unread'),
    refetchInterval: 2 * 60_000,
  })
}

/** Wiadomości rozmowy porcjami: pierwsza strona to najnowsze, kolejne sięgają wstecz. */
export function useThread(conversationId: string | null) {
  return useInfiniteQuery({
    queryKey: [...chatKey, 'thread', conversationId],
    enabled: conversationId !== null,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      api<ChatThread>(
        `/chat/conversations/${conversationId}/messages${pageParam ? `?before=${encodeURIComponent(pageParam)}` : ''}`,
      ),
    getNextPageParam: (page) => (page.hasMore && page.messages.length > 0 ? page.messages[0].createdAt : null),
    refetchInterval: 60_000,
  })
}

export function usePeople(storeId: string | undefined) {
  return useQuery({
    queryKey: [...chatKey, 'people', storeId],
    enabled: !!storeId,
    queryFn: () => api<ChatPeople>(`/stores/${storeId}/chat/people`),
    // Odbicia kart i zmiany grafiku: co minutę wystarczy.
    refetchInterval: 60_000,
  })
}

export function useCandidates(storeId: string | undefined, company: boolean, enabled = true) {
  return useQuery({
    queryKey: [...chatKey, 'candidates', storeId, company],
    enabled: enabled && !!storeId,
    queryFn: () => api<ChatPerson[]>(`/stores/${storeId}/chat/candidates?company=${company}`),
    staleTime: 5 * 60_000,
  })
}

export const attachmentUrl = (id: string) => `/chat/attachments/${id}`

export function useChatActions() {
  const queryClient = useQueryClient()
  const refresh = () => void queryClient.invalidateQueries({ queryKey: chatKey })
  const options = { meta: NO_GLOBAL_INVALIDATION, onSuccess: refresh }

  const send = useMutation({
    ...options,
    mutationFn: ({
      conversationId,
      body,
      files,
      mentions = [],
    }: {
      conversationId: string
      body: string
      files: File[]
      /** `user:<id>`, `department:<id>`, `all` */
      mentions?: string[]
    }) => {
      const formData = new FormData()
      formData.append('body', body)
      files.forEach((file) => formData.append('files', file, file.name))
      mentions.forEach((token) => formData.append('mentions', token))
      return api<ChatMessage>(`/chat/conversations/${conversationId}/messages`, { method: 'POST', formData })
    },
  })
  const retract = useMutation({
    ...options,
    mutationFn: (messageId: string) => api<ChatMessage>(`/chat/messages/${messageId}/retract`, { method: 'POST' }),
  })
  const restore = useMutation({
    ...options,
    mutationFn: (messageId: string) => api<ChatMessage>(`/chat/messages/${messageId}/restore`, { method: 'POST' }),
  })
  const createGroup = useMutation({
    ...options,
    mutationFn: (input: { name: string; storeId: string | null; memberIds: string[] }) =>
      api<ChatConversation>('/chat/groups', { method: 'POST', json: input }),
  })
  const rename = useMutation({
    ...options,
    mutationFn: ({ conversationId, name }: { conversationId: string; name: string }) =>
      api<ChatConversation>(`/chat/conversations/${conversationId}/name`, { method: 'PUT', json: { name } }),
  })
  const setLogo = useMutation({
    ...options,
    mutationFn: ({ conversationId, file }: { conversationId: string; file: Blob }) => {
      const formData = new FormData()
      formData.append('file', file, 'logo.webp')
      return api<ChatLogoChange>(`/chat/conversations/${conversationId}/logo`, { method: 'PUT', formData })
    },
  })
  const removeLogo = useMutation({
    ...options,
    mutationFn: (conversationId: string) =>
      api<ChatLogoChange>(`/chat/conversations/${conversationId}/logo`, { method: 'DELETE' }),
  })
  const restoreLogo = useMutation({
    ...options,
    mutationFn: ({ conversationId, logoId }: { conversationId: string; logoId: string }) =>
      api<ChatConversation>(`/chat/conversations/${conversationId}/logo/${logoId}/restore`, { method: 'POST' }),
  })
  const setMembers = useMutation({
    ...options,
    mutationFn: ({ conversationId, userIds }: { conversationId: string; userIds: string[] }) =>
      api<ChatConversation>(`/chat/conversations/${conversationId}/members`, { method: 'PUT', json: { userIds } }),
  })
  const direct = useMutation({
    ...options,
    mutationFn: ({ userId, storeId }: { userId: string; storeId: string | undefined }) =>
      api<ChatConversation>(`/chat/direct${storeId ? `?storeId=${storeId}` : ''}`, {
        method: 'POST',
        json: { userId },
      }),
  })
  const read = useMutation({
    ...options,
    mutationFn: (conversationId: string) => api(`/chat/conversations/${conversationId}/read`, { method: 'POST' }),
  })
  return { send, retract, restore, createGroup, rename, setLogo, removeLogo, restoreLogo, setMembers, direct, read }
}

/** „Pisze…”: bez mutacji i odświeżania, samo zgłoszenie (przeglądarka wysyła je najwyżej co kilka sekund). */
export function sendTyping(conversationId: string) {
  void api(`/chat/conversations/${conversationId}/typing`, { method: 'POST' }).catch(() => undefined)
}
