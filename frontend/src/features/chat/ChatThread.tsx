import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  ArrowLeftIcon,
  CheckCheckIcon,
  CheckIcon,
  FileTextIcon,
  ImageIcon,
  PaperclipIcon,
  SendHorizonalIcon,
  SettingsIcon,
  Undo2Icon,
  UsersIcon,
  XIcon,
} from 'lucide-react'
import { toast } from 'sonner'

import { LoadingPanel } from '@/components/LoadingPanel'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/UserAvatar'
import { ApiError, downloadFile } from '@/lib/api'
import type { ChatConversation, ChatMessage, ChatPerson, ChatThread as Thread } from '@/lib/types'
import { cn } from '@/lib/utils'

import { attachmentUrl, MAX_CHAT_FILE_BYTES, MAX_CHAT_FILES, sendTyping, useChatActions, useThread } from './api'
import { conversationTitle, dayLabel, fileSize, isSameDay, messageTime } from './format'
import { ChatSettingsDialog } from './ChatSettingsDialog'
import { MentionText, mentionOptions, useMentionPicker, type MentionOption } from './mentions'

/** Kolejne wiadomości tej samej osoby w tym czasie stoją razem, bez powtarzania imienia. */
const RUN_GAP_MS = 5 * 60_000
const TYPING_EVERY_MS = 3_000

type Props = {
  conversationId: string
  meId: string
  /** Kto teraz pisze (z wiadomości na żywo), poza tym, co podała odpowiedź. */
  typingNow: ChatPerson[]
  onBack: () => void
  onManage: (conversation: ChatConversation, members: ChatPerson[]) => void
  onTogglePeople?: () => void
}

export function ChatThread({ conversationId, meId, typingNow, onBack, onManage, onTogglePeople }: Props) {
  const thread = useThread(conversationId)
  const { read } = useChatActions()
  const pages = thread.data?.pages
  const first: Thread | undefined = pages?.[0]
  const messages = useMemo(() => (pages ? [...pages].reverse().flatMap((page) => page.messages) : []), [pages])
  const newest = messages.at(-1)
  const [editing, setEditing] = useState(false)

  // Odczyt: gdy rozmowa jest na ekranie i przyszło coś nowego od kogoś innego.
  const markRead = read.mutate
  useEffect(() => {
    if (!newest || document.visibilityState !== 'visible') return
    const myRead = first?.reads.find((entry) => entry.userId === meId)?.lastReadAt
    if (newest.authorId === meId || (myRead && myRead >= newest.createdAt)) return
    markRead(conversationId)
  }, [conversationId, newest, first?.reads, meId, markRead])

  if (thread.isPending || !first) {
    return <LoadingPanel className="h-full rounded-none" />
  }
  if (thread.isError) {
    return <p className="p-6 text-sm text-destructive">Nie udało się wczytać rozmowy.</p>
  }

  const conversation = first.conversation
  const typing = mergeTyping(first.typing, typingNow, meId)
  const othersCount = Math.max(0, first.members.filter((member) => member.id !== meId).length)

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2.5">
        <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Wróć do rozmów" onClick={onBack}>
          <ArrowLeftIcon />
        </Button>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-[0.95rem] font-semibold">{conversationTitle(conversation)}</h2>
          <p className="truncate text-xs text-muted-foreground">
            {typing.length > 0 ? (
              <span className="text-emerald-700 dark:text-emerald-400">{typingLabel(typing)}</span>
            ) : (
              subtitle(conversation, first.members.length)
            )}
          </p>
        </div>
        {conversation.canEdit && (
          <Button variant="ghost" size="sm" onClick={() => setEditing(true)}>
            <ImageIcon data-icon="inline-start" />
            Nazwa i logo
          </Button>
        )}
        {conversation.canManage && (
          <Button variant="outline" size="sm" onClick={() => onManage(conversation, first.members)}>
            <SettingsIcon data-icon="inline-start" />
            Członkowie
          </Button>
        )}
        {onTogglePeople && (
          <Button variant="ghost" size="icon" aria-label="Osoby w rozmowie" onClick={onTogglePeople}>
            <UsersIcon />
          </Button>
        )}
      </header>
      {/* Klucze rodzeństwa muszą być różne: ten sam klucz zostawiał stare listy przy każdym przejściu. */}
      <MessageList
        key={`list:${conversationId}`}
        messages={messages}
        meId={meId}
        thread={first}
        othersCount={othersCount}
        showAuthors={conversation.kind !== 'DIRECT'}
        hasMore={thread.hasNextPage}
        loadingMore={thread.isFetchingNextPage}
        onLoadMore={() => void thread.fetchNextPage()}
        typing={typing}
      />
      {editing && <ChatSettingsDialog conversation={conversation} open={editing} onOpenChange={setEditing} />}
      <Composer
        key={`composer:${conversationId}`}
        conversationId={conversationId}
        mentionable={mentionOptions(first.members, first.departments, first.canMentionAll, meId)}
      />
    </div>
  )
}

function subtitle(conversation: ChatConversation, members: number): string {
  if (conversation.kind === 'DIRECT') return conversation.storeName ?? 'Rozmowa'
  if (conversation.kind === 'STORE') return `${conversation.storeName ?? 'Sklep'} · ${members} osób`
  const where = conversation.storeNumber ? `Sklep ${conversation.storeNumber}` : 'Niezależna od sklepu'
  return `${where} · ${members} osób`
}

function mergeTyping(fromServer: ChatPerson[], live: ChatPerson[], meId: string): ChatPerson[] {
  const byId = new Map<string, ChatPerson>()
  ;[...fromServer, ...live].filter((person) => person.id !== meId).forEach((person) => byId.set(person.id, person))
  return [...byId.values()]
}

function typingLabel(people: ChatPerson[]): string {
  const names = people.map((person) => person.name.split(' ')[0])
  if (names.length === 1) return `${names[0]} pisze…`
  if (names.length === 2) return `${names[0]} i ${names[1]} piszą…`
  return `${names.length} osoby piszą…`
}

// ---------------------------------------------------------------- wiadomości

type ListProps = {
  messages: ChatMessage[]
  meId: string
  thread: Thread
  othersCount: number
  showAuthors: boolean
  hasMore: boolean
  loadingMore: boolean
  onLoadMore: () => void
  typing: ChatPerson[]
}

function MessageList({
  messages,
  meId,
  thread,
  othersCount,
  showAuthors,
  hasMore,
  loadingMore,
  onLoadMore,
  typing,
}: ListProps) {
  const scroller = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)
  const previousFirst = useRef<string | undefined>(undefined)
  const previousHeight = useRef(0)

  // Nowe wiadomości: na dół, o ile ktoś był na dole. Starsze doczytane u góry: widok zostaje w tym samym miejscu.
  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    const firstId = messages[0]?.id
    if (previousFirst.current && firstId !== previousFirst.current) {
      element.scrollTop += element.scrollHeight - previousHeight.current
    } else if (stickToBottom.current) {
      element.scrollTop = element.scrollHeight
    }
    previousFirst.current = firstId
    previousHeight.current = element.scrollHeight
  }, [messages, typing.length])

  const readers = (message: ChatMessage) =>
    thread.reads.filter((entry) => entry.userId !== meId && entry.lastReadAt >= message.createdAt)
  const lastMine = [...messages].reverse().find((message) => message.authorId === meId)
  const names = new Map(thread.members.map((member) => [member.id, member.name]))

  return (
    <div
      ref={scroller}
      onScroll={(event) => {
        const element = event.currentTarget
        stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80
        previousHeight.current = element.scrollHeight
      }}
      className="min-h-0 flex-1 overflow-y-auto px-3 py-4 sm:px-5"
    >
      {hasMore && (
        <div className="mb-4 flex justify-center">
          <Button variant="outline" size="sm" loading={loadingMore} onClick={onLoadMore}>
            Starsze wiadomości
          </Button>
        </div>
      )}
      {messages.length === 0 && (
        <p className="mt-16 text-center text-sm text-muted-foreground">Nie ma jeszcze wiadomości. Napisz pierwszą.</p>
      )}
      <ol className="space-y-0.5">
        {messages.map((message, index) => {
          const previous = messages[index - 1]
          const newDay = !previous || !isSameDay(previous.createdAt, message.createdAt)
          const startsRun =
            newDay ||
            previous.authorId !== message.authorId ||
            new Date(message.createdAt).getTime() - new Date(previous.createdAt).getTime() > RUN_GAP_MS
          const mine = message.authorId === meId
          const seenBy = mine ? readers(message) : []
          return (
            <li key={message.id}>
              {newDay && (
                <div className="my-4 flex justify-center">
                  <span className="rounded-full bg-muted px-3 py-0.5 text-xs font-medium text-muted-foreground first-letter:uppercase">
                    {dayLabel(message.createdAt)}
                  </span>
                </div>
              )}
              <Bubble
                message={message}
                mine={mine}
                startsRun={startsRun}
                showAuthor={showAuthors && !mine && startsRun}
                read={mine && othersCount > 0 && seenBy.length >= othersCount}
              />
              {message.id === lastMine?.id && othersCount > 0 && (
                <SeenLine seenBy={seenBy.map((entry) => names.get(entry.userId) ?? '')} total={othersCount} />
              )}
            </li>
          )
        })}
      </ol>
      {typing.length > 0 && <TypingDots />}
    </div>
  )
}

function Bubble({
  message,
  mine,
  startsRun,
  showAuthor,
  read,
}: {
  message: ChatMessage
  mine: boolean
  startsRun: boolean
  showAuthor: boolean
  read: boolean
}) {
  const { retract, restore } = useChatActions()
  const hidden = message.retracted && message.body === null

  const onRetract = () =>
    retract.mutate(message.id, {
      onSuccess: () =>
        toast.success('Wiadomość cofnięta dla wszystkich', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(message.id) },
        }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się cofnąć wiadomości'),
    })

  return (
    <div className={cn('group flex items-end gap-2', mine && 'flex-row-reverse', startsRun && 'mt-3')}>
      {!mine && (
        <div className="w-8 shrink-0">
          {startsRun && (
            <UserAvatar name={message.authorName ?? '?'} url={message.authorAvatarUrl} className="size-8 text-xs" />
          )}
        </div>
      )}
      <div className={cn('flex max-w-[min(36rem,78%)] flex-col', mine ? 'items-end' : 'items-start')}>
        {showAuthor && (
          <span className="mb-0.5 px-1 text-xs font-medium text-muted-foreground">{message.authorName}</span>
        )}
        <div
          className={cn(
            'chat-bubble rounded-2xl px-3 py-1.5 text-[0.9rem] leading-snug break-words whitespace-pre-wrap',
            mine ? 'bg-primary text-primary-foreground' : 'bg-muted text-foreground',
            // Wiadomość, która mnie oznacza, odróżnia się od reszty jak w Slacku.
            message.mentionsMe &&
              !mine &&
              'bg-amber-200 text-amber-950 ring-2 ring-amber-400 dark:bg-amber-900/70 dark:text-amber-50',
            mine && startsRun && 'rounded-tr-md',
            !mine && startsRun && 'rounded-tl-md',
            hidden && 'border border-dashed bg-transparent text-muted-foreground italic',
            message.retracted && !hidden && 'bg-muted/60 text-muted-foreground line-through decoration-1',
          )}
        >
          {hidden ? (
            'Wiadomość cofnięta'
          ) : (
            <>
              {message.body && (
                <span>
                  <MentionText body={message.body} mentions={message.mentions} mine={mine} />
                </span>
              )}
              {message.attachments.length > 0 && (
                <span className={cn('flex flex-col gap-1', message.body && 'mt-1.5')}>
                  {message.attachments.map((file) => (
                    <button
                      key={file.id}
                      type="button"
                      onClick={() =>
                        void downloadFile(attachmentUrl(file.id), file.fileName).catch(() =>
                          toast.error('Nie udało się pobrać pliku'),
                        )
                      }
                      className={cn(
                        'flex items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm no-underline not-italic',
                        mine
                          ? 'bg-primary-foreground/15 hover:bg-primary-foreground/25'
                          : 'bg-background/70 hover:bg-background',
                      )}
                    >
                      <FileTextIcon className="size-4 shrink-0" />
                      <span className="min-w-0 flex-1 truncate">{file.fileName}</span>
                      <span className="shrink-0 text-xs opacity-75 tabular-nums">{fileSize(file.sizeBytes)}</span>
                    </button>
                  ))}
                </span>
              )}
            </>
          )}
          <span
            className={cn(
              'float-right mt-1 ml-2 inline-flex translate-y-0.5 items-center gap-0.5 text-[0.68rem] tabular-nums no-underline not-italic',
              mine && !message.retracted ? 'text-primary-foreground/75' : 'text-muted-foreground',
            )}
          >
            {messageTime(message.createdAt)}
            {mine &&
              !message.retracted &&
              (read ? (
                <CheckCheckIcon aria-label="Wyświetlona" className="size-3.5 text-sky-300 dark:text-sky-600" />
              ) : (
                <CheckIcon aria-label="Wysłana" className="size-3.5" />
              ))}
          </span>
        </div>
        {message.retracted && !hidden && (
          <span className="mt-0.5 px-1 text-[0.7rem] text-muted-foreground">
            Cofnięta{message.retractedByName ? ` przez ${message.retractedByName}` : ''} · inni widzą „Wiadomość
            cofnięta”
          </span>
        )}
      </div>
      <div className="flex shrink-0 self-center opacity-0 transition-opacity duration-150 group-hover:opacity-100 focus-within:opacity-100">
        {message.canRetract && (
          <MessageAction label="Cofnij dla wszystkich" onClick={onRetract}>
            <Undo2Icon />
          </MessageAction>
        )}
        {message.canRestore && (
          <MessageAction label="Przywróć wiadomość" onClick={() => restore.mutate(message.id)}>
            <Undo2Icon className="-scale-x-100" />
          </MessageAction>
        )}
      </div>
    </div>
  )
}

function MessageAction({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <Button variant="ghost" size="icon-sm" title={label} aria-label={label} onClick={onClick}>
      {children}
    </Button>
  )
}

function SeenLine({ seenBy, total }: { seenBy: string[]; total: number }) {
  if (seenBy.length === 0) return null
  const text =
    total === 1
      ? 'Wyświetlona'
      : seenBy.length >= total
        ? 'Wyświetlili wszyscy'
        : `Wyświetlili: ${seenBy.length} z ${total}`
  return (
    <p className="mt-0.5 pr-1 text-right text-[0.7rem] text-muted-foreground" title={seenBy.join(', ')}>
      {text}
    </p>
  )
}

function TypingDots() {
  return (
    <div
      className="mt-3 ml-10 inline-flex items-center gap-1 rounded-2xl rounded-tl-md bg-muted px-3 py-2.5"
      aria-label="Ktoś pisze"
    >
      {[0, 1, 2].map((dot) => (
        <span
          key={dot}
          className="chat-typing-dot size-1.5 rounded-full bg-muted-foreground"
          style={{ animationDelay: `${dot * 160}ms` }}
        />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------- pisanie

const COMPOSER_MAX_HEIGHT = 160

function Composer({ conversationId, mentionable }: { conversationId: string; mentionable: MentionOption[] }) {
  const [text, setText] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)
  const filePicker = useRef<HTMLInputElement>(null)
  const lastTyping = useRef(0)
  const { send } = useChatActions()
  const mentions = useMentionPicker(input, text, setText, mentionable)

  // Pole rośnie z treścią do kilku linii; pasek przewijania dopiero ponad nimi (inaczej stoją strzałki przy jednej linii).
  useLayoutEffect(() => {
    const element = input.current
    if (!element) return
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, COMPOSER_MAX_HEIGHT)}px`
    element.style.overflowY = element.scrollHeight > COMPOSER_MAX_HEIGHT ? 'auto' : 'hidden'
  }, [text])

  const addFiles = (list: FileList | File[]) => {
    const next = [...files, ...Array.from(list)]
    if (next.length > MAX_CHAT_FILES) {
      toast.error(`Do jednej wiadomości dołączysz najwyżej ${MAX_CHAT_FILES} plików`)
      return
    }
    if (next.reduce((sum, file) => sum + file.size, 0) > MAX_CHAT_FILE_BYTES) {
      toast.error('Pliki jednej wiadomości mogą mieć razem najwyżej 10 MB', {
        description: 'Większe wyślij w kilku wiadomościach.',
      })
      return
    }
    setFiles(next)
  }

  const submit = () => {
    const body = text.trim()
    if ((!body && files.length === 0) || send.isPending) return
    send.mutate(
      { conversationId, body, files, mentions: mentions.tokens(body) },
      {
        onSuccess: () => {
          setText('')
          setFiles([])
          mentions.reset()
          lastTyping.current = 0
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się wysłać wiadomości'),
      },
    )
  }

  return (
    <div
      className={cn('border-t p-2 sm:p-3', dragging && 'bg-primary/5')}
      onDragOver={(event) => {
        if (!event.dataTransfer.types.includes('Files')) return
        event.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        if (event.dataTransfer.files.length === 0) return
        event.preventDefault()
        setDragging(false)
        addFiles(event.dataTransfer.files)
      }}
    >
      {files.length > 0 && (
        <ul className="mb-2 flex flex-wrap gap-1.5">
          {files.map((file, index) => (
            <li
              key={`${file.name}-${index}`}
              className="flex max-w-60 items-center gap-1.5 rounded-lg bg-muted py-1 pr-1 pl-2 text-xs"
            >
              <FileTextIcon className="size-3.5 shrink-0" />
              <span className="min-w-0 truncate">{file.name}</span>
              <span className="shrink-0 text-muted-foreground tabular-nums">{fileSize(file.size)}</span>
              <button
                type="button"
                aria-label={`Usuń ${file.name}`}
                className="rounded p-0.5 hover:bg-background"
                onClick={() => setFiles(files.filter((_, i) => i !== index))}
              >
                <XIcon className="size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="relative flex items-end gap-1.5">
        {mentions.list}
        <input
          ref={filePicker}
          type="file"
          multiple
          className="hidden"
          onChange={(event) => {
            if (event.target.files) addFiles(event.target.files)
            event.target.value = ''
          }}
        />
        <Button
          variant="ghost"
          size="icon-lg"
          aria-label="Dołącz plik"
          title="Dołącz plik (PDF, Excel…)"
          onClick={() => filePicker.current?.click()}
        >
          <PaperclipIcon />
        </Button>
        <textarea
          ref={input}
          rows={1}
          autoFocus
          value={text}
          placeholder="Napisz wiadomość"
          aria-label="Wiadomość"
          onChange={(event) => {
            setText(event.target.value)
            mentions.update(event.target.value, event.target.selectionStart)
            const now = Date.now()
            if (event.target.value.trim() && now - lastTyping.current > TYPING_EVERY_MS) {
              lastTyping.current = now
              sendTyping(conversationId)
            }
          }}
          onSelect={(event) => mentions.update(event.currentTarget.value, event.currentTarget.selectionStart)}
          onBlur={() => mentions.update('', 0)}
          aria-expanded={mentions.open}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing || mentions.onKeyDown(event)) return
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              submit()
            }
          }}
          onPaste={(event) => {
            if (event.clipboardData.files.length > 0) {
              event.preventDefault()
              addFiles(event.clipboardData.files)
            }
          }}
          className="max-h-40 min-h-9 flex-1 resize-none rounded-2xl border border-input bg-background px-3.5 py-2 text-[0.9rem] leading-snug outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
        />
        <Button
          size="icon-lg"
          className="rounded-full active:scale-95"
          aria-label="Wyślij"
          loading={send.isPending}
          disabled={!text.trim() && files.length === 0}
          onClick={submit}
        >
          <SendHorizonalIcon />
        </Button>
      </div>
    </div>
  )
}
