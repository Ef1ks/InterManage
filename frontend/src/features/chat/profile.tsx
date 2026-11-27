import { useState, useSyncExternalStore } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { CopyIcon, MessageCircleIcon, PencilIcon, PhoneIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextareaField, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { UserAvatar } from '@/components/UserAvatar'
import { useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { ChatProfile } from '@/lib/types'

import { useChatActions, useChatProfile, useSaveChatProfile } from './api'
import { lastSeen } from './format'
import { CHAT_PATH, CONVERSATION_PARAM } from './notifications'

/*
 * Profil osoby w czacie: klik w imię albo zdjęcie otwiera okno z tym, co osoba o sobie napisała (nick, opis,
 * telefon z dzwonieniem jednym kliknięciem), rangą i stanowiskiem oraz „Napisz”. Okno jest jedno na stronę
 * (ProfileDialogHost), a otwiera je openProfile z dowolnego miejsca czatu.
 */

let openUserId: string | null = null
const listeners = new Set<() => void>()

export function openProfile(userId: string) {
  openUserId = userId
  listeners.forEach((listener) => listener())
}

function closeProfile() {
  openUserId = null
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Przycisk wokół imienia albo zdjęcia, który otwiera profil. */
export function ProfileTrigger({
  userId,
  className,
  children,
}: {
  userId: string | null
  className?: string
  children: React.ReactNode
}) {
  if (!userId) return <>{children}</>
  return (
    <button
      type="button"
      onClick={(event) => {
        event.stopPropagation()
        openProfile(userId)
      }}
      className={
        className ?? 'rounded-full text-left hover:underline focus-visible:outline-2 focus-visible:outline-ring'
      }
      title="Pokaż profil"
    >
      {children}
    </button>
  )
}

export function ProfileDialogHost() {
  const userId = useSyncExternalStore(subscribe, () => openUserId)
  return (
    <Dialog open={userId !== null} onOpenChange={(open) => !open && closeProfile()}>
      <DialogContent className="sm:max-w-sm">{userId && <ProfileContent key={userId} userId={userId} />}</DialogContent>
    </Dialog>
  )
}

/** „+48 600 100 200” → „tel:+48600100200”. */
const telHref = (phone: string) => `tel:${phone.replace(/[^\d+]/g, '')}`

function ProfileContent({ userId }: { userId: string }) {
  const storeId = useCurrentStore()?.id
  const { data: profile, isPending, isError } = useChatProfile(userId, storeId)
  const [editing, setEditing] = useState(false)
  const { direct } = useChatActions()
  const navigate = useNavigate()

  if (isPending) return <LoadingPanel className="h-56 rounded-xl" />
  if (isError || !profile) {
    return (
      <DialogHeader>
        <DialogTitle>Profil</DialogTitle>
        <DialogDescription>Nie udało się wczytać profilu.</DialogDescription>
      </DialogHeader>
    )
  }
  if (editing) return <ProfileForm profile={profile} storeId={storeId} onDone={() => setEditing(false)} />

  const write = () =>
    direct.mutate(
      { userId: profile.id, storeId },
      {
        onSuccess: (conversation) => {
          closeProfile()
          navigate(`${CHAT_PATH}?${CONVERSATION_PARAM}=${encodeURIComponent(conversation.id)}`)
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się otworzyć rozmowy'),
      },
    )
  const role = [profile.rankName, profile.position].filter(Boolean).join(' · ')

  return (
    <>
      <DialogHeader className="items-center text-center">
        <div className="relative">
          <UserAvatar name={profile.name} url={profile.avatarUrl} className="size-20 text-2xl" />
          {profile.online && (
            <span
              aria-hidden
              className="absolute right-1 bottom-1 size-4 rounded-full bg-emerald-500 ring-3 ring-popover"
            />
          )}
        </div>
        <DialogTitle className="mt-2 text-lg">{profile.name}</DialogTitle>
        {profile.nickname && <p className="-mt-1 text-sm font-medium text-primary">„{profile.nickname}”</p>}
        <DialogDescription>
          {role && <span className="block">{role}</span>}
          <span className="block text-xs">{lastSeen(profile.lastSeenAt, profile.online)}</span>
        </DialogDescription>
      </DialogHeader>

      {profile.about ? (
        <p className="rounded-xl bg-muted px-3 py-2.5 text-sm leading-relaxed whitespace-pre-wrap">{profile.about}</p>
      ) : (
        profile.me && (
          <p className="text-center text-sm text-muted-foreground">
            Napisz parę słów o sobie i dodaj telefon, żeby inni wiedzieli, jak Cię złapać.
          </p>
        )
      )}

      {profile.phone && (
        <div className="flex items-center gap-2">
          <Button
            render={<a href={telHref(profile.phone)} />}
            nativeButton={false}
            variant="outline"
            size="lg"
            className="flex-1 justify-start"
          >
            <PhoneIcon data-icon="inline-start" className="text-emerald-600 dark:text-emerald-400" />
            <span className="tabular-nums">{profile.phone}</span>
          </Button>
          <Button
            variant="ghost"
            size="icon-lg"
            aria-label="Kopiuj numer"
            title="Kopiuj numer"
            onClick={() =>
              void navigator.clipboard
                .writeText(profile.phone ?? '')
                .then(() => toast.success('Skopiowano numer'))
                .catch(() => toast.error('Nie udało się skopiować'))
            }
          >
            <CopyIcon />
          </Button>
        </div>
      )}

      {profile.me ? (
        <Button size="lg" variant="outline" onClick={() => setEditing(true)}>
          <PencilIcon data-icon="inline-start" />
          Edytuj profil
        </Button>
      ) : (
        <Button size="lg" loading={direct.isPending} onClick={write}>
          <MessageCircleIcon data-icon="inline-start" />
          Napisz
        </Button>
      )}
    </>
  )
}

type ProfileValues = { nickname: string; about: string; phone: string }

function ProfileForm({
  profile,
  storeId,
  onDone,
}: {
  profile: ChatProfile
  storeId: string | undefined
  onDone: () => void
}) {
  const save = useSaveChatProfile(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, formState } = useForm<ProfileValues>({
    defaultValues: { nickname: profile.nickname ?? '', about: profile.about ?? '', phone: profile.phone ?? '' },
  })

  const submit = handleSubmit((values) => {
    setFormError(null)
    save.mutate(values, {
      onSuccess: () => {
        toast.success('Profil zapisany')
        onDone()
      },
      onError: (error) => setFormError(applyServerErrors(error, setError, ['nickname', 'about', 'phone'])),
    })
  })

  return (
    <>
      <DialogHeader>
        <DialogTitle>Mój profil</DialogTitle>
        <DialogDescription>Widzą go osoby z Twoich sklepów w czacie.</DialogDescription>
      </DialogHeader>
      <Form onSubmit={submit} error={formError}>
        <TextField
          label="Nick"
          maxLength={40}
          error={formState.errors.nickname?.message}
          {...register('nickname', { maxLength: { value: 40, message: 'Najwyżej 40 znaków' } })}
        />
        <TextareaField
          label="O mnie"
          rows={4}
          maxLength={500}
          hint="Np. dział, zmiany, w czym możesz pomóc."
          error={formState.errors.about?.message}
          {...register('about', { maxLength: { value: 500, message: 'Najwyżej 500 znaków' } })}
        />
        <TextField
          label="Telefon"
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          maxLength={30}
          hint="Inni zadzwonią do Ciebie jednym kliknięciem."
          error={formState.errors.phone?.message}
          {...register('phone', {
            validate: (value) =>
              !value.trim() ||
              (/^[0-9+()\-. ]+$/.test(value) && value.replace(/\D/g, '').length >= 6) ||
              'Same cyfry, spacje, „+” i „-”, co najmniej 6 cyfr',
          })}
        />
        <FormActions>
          <Button type="button" variant="ghost" onClick={onDone}>
            Anuluj
          </Button>
          <Button type="submit" loading={save.isPending}>
            Zapisz
          </Button>
        </FormActions>
      </Form>
    </>
  )
}
