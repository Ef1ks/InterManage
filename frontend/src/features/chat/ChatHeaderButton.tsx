import { MessageCircleIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import { useCurrentStore } from '@/features/stores/current-store'
import { api } from '@/lib/api'
import type { ChatConversation } from '@/lib/types'

import { chatKey, useChatUnread } from './api'
import { CHAT_PATH, CONVERSATION_PARAM } from './notifications'

/**
 * Czat w nagłówku (zawsze widoczny, w przeciwieństwie do zwijanego menu) z licznikiem nieprzeczytanych. Klik otwiera
 * najświeższą rozmowę z nieprzeczytanymi, bez nich listę rozmów.
 */
export function ChatHeaderButton() {
  const { data } = useChatUnread()
  const count = data?.total ?? 0
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const storeId = useCurrentStore()?.id

  const open = async () => {
    if (count === 0) {
      navigate(CHAT_PATH)
      return
    }
    try {
      const list = await queryClient.fetchQuery({
        queryKey: [...chatKey, 'conversations', storeId ?? null],
        queryFn: () => api<ChatConversation[]>(`/chat/conversations${storeId ? `?storeId=${storeId}` : ''}`),
        staleTime: 10_000,
      })
      const first = list.find((conversation) => conversation.unread > 0)
      navigate(first ? `${CHAT_PATH}?${CONVERSATION_PARAM}=${encodeURIComponent(first.id)}` : CHAT_PATH)
    } catch {
      navigate(CHAT_PATH)
    }
  }

  const label = count > 0 ? `Czat: ${count} nieprzeczytanych` : 'Czat'
  return (
    <Button
      variant="ghost"
      size="icon"
      className="relative"
      aria-label={label}
      title={label}
      onClick={() => void open()}
    >
      <MessageCircleIcon />
      {count > 0 && (
        <span className="pointer-events-none absolute -top-0.5 -right-0.5 min-w-[1.125rem] rounded-full bg-red-600 px-1 text-center text-[0.65rem] leading-[1.125rem] font-bold text-white tabular-nums ring-2 ring-background animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Button>
  )
}
