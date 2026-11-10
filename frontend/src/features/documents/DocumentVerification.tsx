import { CheckIcon, ChevronDownIcon, CircleCheckIcon, ClockIcon, ShieldCheckIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { useCan } from '@/features/auth/api'
import { useDocumentVerification } from '@/features/documents/api'
import { bonNumber } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { Document, DocumentVerificationStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const changedAtFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

export const VERIFICATION_LABELS: Record<DocumentVerificationStatus, string> = {
  IN_PROGRESS: 'W trakcie weryfikacji',
  VERIFIED: 'Zweryfikowany',
}

export const VERIFICATION_STYLES: Record<DocumentVerificationStatus, string> = {
  IN_PROGRESS: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  VERIFIED: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
}

const OPTIONS: { status: DocumentVerificationStatus; label: string; hint: string; icon: typeof ClockIcon }[] = [
  {
    status: 'IN_PROGRESS',
    label: 'W trakcie weryfikacji',
    hint: 'Czeka na sprawdzenie przez kierownika',
    icon: ClockIcon,
  },
  { status: 'VERIFIED', label: 'Zweryfikowany', hint: 'Przyjęcie sprawdzone', icon: CircleCheckIcon },
]

/** Plakietka statusu weryfikacji przyjęcia (lista i nagłówek dokumentu). */
export function VerificationBadge({
  status,
  at,
  by,
  className,
}: {
  status: DocumentVerificationStatus | null
  at?: string | null
  by?: string | null
  className?: string
}) {
  if (!status) return null
  const title = [VERIFICATION_LABELS[status], at && changedAtFormat.format(new Date(at)), by]
    .filter(Boolean)
    .join(' · ')
  return (
    <Badge className={cn(VERIFICATION_STYLES[status], className)} title={title}>
      {status === 'VERIFIED' ? <CircleCheckIcon /> : <ClockIcon />}
      {status === 'VERIFIED' ? 'zweryfikowany' : 'w trakcie weryfikacji'}
    </Badge>
  )
}

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Status weryfikacji w nagłówku przyjęcia PZ: „W trakcie weryfikacji” (dla kierownika) albo „Zweryfikowany”, z
 * możliwością zdjęcia statusu. Toast „Cofnij” przywraca poprzedni stan. Bez prawa edycji widać tylko plakietkę.
 */
export function VerificationMenu({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { set, clear } = useDocumentVerification(storeId, document.type, document.id)
  if (document.type !== 'PZ') return null
  const current = document.verificationStatus
  if (!canEdit) return <VerificationBadge status={current} at={document.verificationAt} by={document.verificationBy} />

  const restore = (previous: DocumentVerificationStatus | null) => {
    const options = { onError: (error: unknown) => toast.error(errorMessage(error, 'Nie udało się cofnąć zmiany')) }
    if (previous) set.mutate(previous, options)
    else clear.mutate(undefined, options)
  }
  const change = (next: DocumentVerificationStatus | null) => {
    if (next === current) return
    const options = {
      onSuccess: () =>
        toast.success(
          next
            ? `Bon ${bonNumber(document.number)}: ${VERIFICATION_LABELS[next].toLowerCase()}`
            : `Bon ${bonNumber(document.number)}: zdjęto status weryfikacji`,
          { action: { label: 'Cofnij', onClick: () => restore(current) } },
        ),
      onError: (error: unknown) => toast.error(errorMessage(error, 'Nie udało się zmienić statusu weryfikacji')),
    }
    if (next) set.mutate(next, options)
    else clear.mutate(undefined, options)
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="outline"
            loading={set.isPending || clear.isPending}
            className={cn(current && VERIFICATION_STYLES[current], current && 'border-transparent')}
            title={
              current
                ? [
                    VERIFICATION_LABELS[current],
                    document.verificationAt && changedAtFormat.format(new Date(document.verificationAt)),
                    document.verificationBy,
                  ]
                    .filter(Boolean)
                    .join(' · ')
                : undefined
            }
          />
        }
      >
        {current === 'VERIFIED' ? <CircleCheckIcon /> : current === 'IN_PROGRESS' ? <ClockIcon /> : <ShieldCheckIcon />}
        {current ? VERIFICATION_LABELS[current] : 'Weryfikacja'}
        <ChevronDownIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-72">
        {OPTIONS.map((option) => (
          <DropdownMenuItem
            key={option.status}
            onClick={() => change(option.status)}
            className="items-start gap-2.5 py-2"
          >
            <option.icon className="mt-0.5" />
            <span className="grid flex-1">
              <span className="font-medium">{option.label}</span>
              <span className="text-xs text-muted-foreground">{option.hint}</span>
            </span>
            {current === option.status && <CheckIcon className="mt-0.5" />}
          </DropdownMenuItem>
        ))}
        {current && (
          <DropdownMenuItem onClick={() => change(null)} className="gap-2.5 py-2">
            <XIcon />
            Zdejmij status
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
