import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { MessagesSquareIcon, PlusIcon, StoreIcon, UsersRoundIcon } from 'lucide-react'
import { toast } from 'sonner'

import { LoadingPanel } from '@/components/LoadingPanel'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { UserAvatar } from '@/components/UserAvatar'
import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { onLiveMessage } from '@/lib/live-events'
import type { ChatConversation, ChatPerson } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useChatActions, useChatUnread, useConversations, useThread } from './api'
import { ChatTasksBar } from './ChatTasksBar'
import { ChatThread } from './ChatThread'
import { conversationTitle, filesLabel, listTime } from './format'
import { GroupDialog } from './GroupDialog'
import { PeoplePanel } from './PeoplePanel'

const CONVERSATION_PARAM = 'rozmowa'
const TYPING_MS = 6_000

/**
 * Czat firmowy: po lewej rozmowy (najpierw bieżącego sklepu, niżej niezależne od sklepu i z innych sklepów, w każdej
 * części grupy przed rozmowami z osobami), w środku rozmowa, po prawej osoby sklepu (w pracy i pozostali).
 */
export function ChatPage() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const storeId = store?.id
  const [params, setParams] = useSearchParams()
  const selected = params.get(CONVERSATION_PARAM)
  const { data: conversations, isPending } = useConversations(storeId)
  const [query, setQuery] = useState('')
  const [groupDialog, setGroupDialog] = useState<{ group?: ChatConversation; members?: ChatPerson[] } | null>(null)
  const [peopleOpen, setPeopleOpen] = useState(false)
  const typing = useLiveTyping()
  const { direct } = useChatActions()
  // Panel osób idzie za otwartą rozmową: jej sklep i (poza czatem całego sklepu) tylko jej członkowie.
  const open = useThread(selected).data?.pages[0]
  const panelStoreId = open?.conversation.storeId ?? storeId
  const panelMembers = open && open.conversation.kind !== 'STORE' ? open.members : undefined
  const peopleTitle = open ? 'Osoby w rozmowie' : 'Osoby w sklepie'

  const select = (id: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (id) next.set(CONVERSATION_PARAM, id)
        else next.delete(CONVERSATION_PARAM)
        return next
      },
      { replace: id === null },
    )

  const openDirect = (userId: string) =>
    direct.mutate(
      { userId, storeId },
      {
        onSuccess: (conversation) => {
          select(conversation.id)
          setPeopleOpen(false)
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się otworzyć rozmowy'),
      },
    )

  const canStoreGroup = hasPermission(me, 'CHAT', 'EDIT')
  const canCompanyGroup =
    me.owner || Object.values(me.storePermissions).some((permissions) => permissions.CHAT === 'EDIT')

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase()
    return (conversations ?? []).filter((conversation) => conversationTitle(conversation).toLowerCase().includes(text))
  }, [conversations, query])

  if (!storeId) return null

  return (
    <div data-wide-page data-fit-page className="flex min-h-0 flex-1 flex-col">
      <ChatTasksBar storeId={storeId} />
      <div className="grid h-[calc(100svh-5.5rem)] min-h-0 flex-1 overflow-hidden rounded-xl border bg-card lg:h-auto lg:grid-cols-[19rem_minmax(0,1fr)] xl:grid-cols-[19rem_minmax(0,1fr)_17rem]">
        <aside className={cn('flex min-h-0 flex-col border-r', selected && 'hidden lg:flex')}>
          <div className="flex items-center gap-2 px-3 pt-3 pb-2">
            <h1 className="flex-1 text-lg font-semibold">Czat</h1>
            {(canStoreGroup || canCompanyGroup) && (
              <Button variant="outline" size="sm" onClick={() => setGroupDialog({})}>
                <PlusIcon data-icon="inline-start" />
                Grupa
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon-sm"
              className="xl:hidden"
              aria-label="Osoby w sklepie"
              onClick={() => setPeopleOpen(true)}
            >
              <UsersRoundIcon />
            </Button>
          </div>
          <SearchInput value={query} onChange={setQuery} placeholder="Szukaj rozmowy" className="mx-3 mb-2" />
          <div className="min-h-0 flex-1 overflow-y-auto pb-3">
            {isPending ? (
              <LoadingPanel className="mx-3 h-64 rounded-xl" />
            ) : (
              <ConversationSections
                conversations={filtered}
                storeNumber={store?.number}
                meId={me.id}
                selected={selected}
                typing={typing}
                onSelect={select}
              />
            )}
          </div>
        </aside>
        <section className={cn('min-h-0', !selected && 'hidden lg:block')}>
          {selected ? (
            <ChatThread
              conversationId={selected}
              meId={me.id}
              typingNow={typing.get(selected) ?? []}
              onBack={() => select(null)}
              onManage={(group, members) => setGroupDialog({ group, members })}
              onTogglePeople={() => setPeopleOpen(true)}
            />
          ) : (
            <div className="flex h-full flex-col items-center justify-center gap-2 p-6 text-center text-muted-foreground">
              <MessagesSquareIcon className="size-10 opacity-40" />
              <p className="text-sm">Wybierz rozmowę albo kliknij osobę po prawej, żeby do niej napisać.</p>
            </div>
          )}
        </section>
        <aside className="hidden min-h-0 border-l xl:block">
          <PeoplePanel
            storeId={panelStoreId ?? storeId}
            meId={me.id}
            onOpen={openDirect}
            opening={direct.isPending ? (direct.variables?.userId ?? null) : null}
            members={panelMembers}
          />
        </aside>
      </div>

      <Dialog open={peopleOpen} onOpenChange={setPeopleOpen}>
        <DialogContent className="max-h-[85svh] p-0 sm:max-w-sm">
          <DialogHeader className="px-4 pt-4">
            <DialogTitle>{peopleTitle}</DialogTitle>
          </DialogHeader>
          <div className="max-h-[70svh]">
            <PeoplePanel
              storeId={panelStoreId ?? storeId}
              meId={me.id}
              onOpen={openDirect}
              opening={direct.isPending ? (direct.variables?.userId ?? null) : null}
              members={panelMembers}
            />
          </div>
        </DialogContent>
      </Dialog>

      <GroupDialog
        open={groupDialog !== null}
        onOpenChange={(open) => !open && setGroupDialog(null)}
        storeId={storeId}
        meId={me.id}
        group={groupDialog?.group}
        members={groupDialog?.members}
        canStoreGroup={canStoreGroup}
        canCompanyGroup={canCompanyGroup}
        onSaved={(conversation) => select(conversation.id)}
      />
    </div>
  )
}

/** „Pisze…” z wiadomości na żywo: rozmowa → osoby, każda gaśnie sama po kilku sekundach. */
function useLiveTyping(): Map<string, ChatPerson[]> {
  const [entries, setEntries] = useState<{ conversationId: string; person: ChatPerson; until: number }[]>([])
  useEffect(
    () =>
      onLiveMessage((_store, message) => {
        if (message.topic !== 'chat') return
        const conversationId = String(message.conversationId)
        if (message.type === 'typing') {
          const person = { id: String(message.userId), name: String(message.name ?? ''), avatarUrl: null }
          setEntries((current) => [
            ...current.filter((entry) => !(entry.conversationId === conversationId && entry.person.id === person.id)),
            { conversationId, person, until: Date.now() + TYPING_MS },
          ])
        } else if (message.type === 'message') {
          setEntries((current) =>
            current.filter(
              (entry) => !(entry.conversationId === conversationId && entry.person.id === message.authorId),
            ),
          )
        }
      }),
    [],
  )
  useEffect(() => {
    if (entries.length === 0) return
    const timer = window.setInterval(() => {
      setEntries((current) => {
        const now = Date.now()
        const next = current.filter((entry) => entry.until > now)
        return next.length === current.length ? current : next
      })
    }, 1_000)
    return () => window.clearInterval(timer)
  }, [entries.length])
  return useMemo(() => {
    const map = new Map<string, ChatPerson[]>()
    entries.forEach((entry) => map.set(entry.conversationId, [...(map.get(entry.conversationId) ?? []), entry.person]))
    return map
  }, [entries])
}

// ---------------------------------------------------------------- lista rozmów

type SectionsProps = {
  conversations: ChatConversation[]
  storeNumber: string | undefined
  meId: string
  selected: string | null
  typing: Map<string, ChatPerson[]>
  onSelect: (id: string) => void
}

function ConversationSections({ conversations, storeNumber, meId, selected, typing, onSelect }: SectionsProps) {
  const local = conversations.filter((conversation) => conversation.local)
  const other = conversations.filter((conversation) => !conversation.local)
  const row = (conversation: ChatConversation) => (
    <ConversationRow
      key={conversation.id}
      conversation={conversation}
      meId={meId}
      active={conversation.id === selected}
      typing={typing.get(conversation.id) ?? []}
      onSelect={onSelect}
    />
  )
  if (conversations.length === 0) {
    return <p className="px-4 py-6 text-center text-sm text-muted-foreground">Nie ma takich rozmów.</p>
  }
  return (
    <>
      <Part title={storeNumber ? `Sklep ${storeNumber}` : 'Ten sklep'} conversations={local} render={row} />
      <Part title="Niezależne od sklepu" conversations={other} render={row} />
    </>
  )
}

/** Część listy: najpierw grupy (czat sklepu na samej górze), potem rozmowy z osobami. */
function Part({
  title,
  conversations,
  render,
}: {
  title: string
  conversations: ChatConversation[]
  render: (conversation: ChatConversation) => ReactNode
}) {
  if (conversations.length === 0) return null
  const groups = conversations
    .filter((conversation) => conversation.kind !== 'DIRECT')
    .sort((a, b) => Number(b.kind === 'STORE') - Number(a.kind === 'STORE'))
  const people = conversations.filter((conversation) => conversation.kind === 'DIRECT')
  return (
    <section className="mt-2">
      <h2 className="px-4 pt-2 pb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h2>
      {groups.length > 0 && <ul>{groups.map(render)}</ul>}
      {groups.length > 0 && people.length > 0 && <div className="mx-4 my-1 border-t" />}
      {people.length > 0 && <ul>{people.map(render)}</ul>}
    </section>
  )
}

function ConversationRow({
  conversation,
  meId,
  active,
  typing,
  onSelect,
}: {
  conversation: ChatConversation
  meId: string
  active: boolean
  typing: ChatPerson[]
  onSelect: (id: string) => void
}) {
  const title = conversationTitle(conversation)
  const unread = conversation.unread > 0
  return (
    <li>
      <button
        type="button"
        onClick={() => onSelect(conversation.id)}
        aria-current={active || undefined}
        className={cn(
          'flex w-full items-center gap-3 px-3 py-2 text-left transition-colors duration-100',
          active ? 'bg-primary/10' : 'hover:bg-muted',
        )}
      >
        <ConversationIcon conversation={conversation} title={title} />
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline gap-2">
            <span className={cn('min-w-0 flex-1 truncate text-sm', unread ? 'font-semibold' : 'font-medium')}>
              {title}
            </span>
            {conversation.lastMessageAt && (
              <span
                className={cn('shrink-0 text-[0.7rem] tabular-nums', unread ? 'text-primary' : 'text-muted-foreground')}
              >
                {listTime(conversation.lastMessageAt)}
              </span>
            )}
          </span>
          <span className="flex items-center gap-2">
            <span
              className={cn('min-w-0 flex-1 truncate text-xs', unread ? 'text-foreground' : 'text-muted-foreground')}
            >
              {typing.length > 0 ? (
                <span className="text-emerald-700 dark:text-emerald-400">pisze…</span>
              ) : (
                preview(conversation, meId)
              )}
            </span>
            {conversation.unreadMentions > 0 && (
              <span
                title="Ktoś Cię tu oznaczył"
                aria-label={`Oznaczenia: ${conversation.unreadMentions}`}
                className="shrink-0 rounded-full bg-amber-300 px-1.5 text-[0.7rem] leading-[1.125rem] font-bold text-amber-950 dark:bg-amber-700 dark:text-amber-50"
              >
                @
              </span>
            )}
            {unread && (
              <span className="shrink-0 rounded-full bg-primary px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-primary-foreground tabular-nums">
                {conversation.unread > 99 ? '99+' : conversation.unread}
              </span>
            )}
          </span>
        </span>
      </button>
    </li>
  )
}

function preview(conversation: ChatConversation, meId: string): string {
  const last = conversation.lastMessage
  if (!last) {
    if (conversation.kind === 'GROUP') return `${conversation.memberCount} osób`
    return conversation.kind === 'STORE' ? 'Wszyscy z tego sklepu' : 'Napisz pierwszą wiadomość'
  }
  const who =
    last.authorId === meId ? 'Ty: ' : conversation.kind === 'DIRECT' ? '' : `${(last.authorName ?? '').split(' ')[0]}: `
  if (last.retracted) return `${who}wiadomość cofnięta`
  if (last.preview) return who + last.preview
  return who + filesLabel(last.attachmentCount)
}

function ConversationIcon({ conversation, title }: { conversation: ChatConversation; title: string }) {
  if (conversation.kind === 'DIRECT') {
    return <UserAvatar name={title} url={conversation.peer?.avatarUrl} className="size-10 shrink-0 text-sm" />
  }
  if (conversation.logoUrl) {
    return <img src={conversation.logoUrl} alt="" className="size-10 shrink-0 rounded-full bg-muted object-cover" />
  }
  const Icon = conversation.kind === 'STORE' ? StoreIcon : UsersRoundIcon
  return (
    <span
      className={cn(
        'flex size-10 shrink-0 items-center justify-center rounded-full',
        conversation.kind === 'STORE'
          ? 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100'
          : 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
      )}
    >
      <Icon className="size-5" />
    </span>
  )
}

/** Licznik nieprzeczytanych przy Czacie w menu. */
export function ChatUnreadBadge() {
  const { data } = useChatUnread()
  const count = data?.total ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} nieprzeczytanych wiadomości`}
      className="rounded-full bg-primary px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-primary-foreground tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}
