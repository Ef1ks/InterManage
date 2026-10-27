import { CheckIcon, TriangleAlertIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/api'
import { useAmountConfirmation } from '@/features/documents/api'
import { formatMoney } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import type { InvoiceAmountCheck as Check } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/** Różnica ze znakiem: "+1,00", "−12,34". */
const signed = (value: number) => (value > 0 ? `+${formatMoney(value)}` : value < 0 ? `−${formatMoney(-value)}` : '0,00')

const confirmedTitle = (check: Check) =>
  `Faktury netto ${formatMoney(check.invoiceNet)} zł, przyjęcie ${formatMoney(check.documentValue)} zł. Potwierdził(a) ${
    check.confirmedBy ?? 'nieznany użytkownik'
  }${check.confirmedAt ? `, ${new Date(check.confirmedAt).toLocaleString('pl-PL')}` : ''}.`

/**
 * Zgodność kwoty netto przyjęcia (po korektach) z jego fakturami KSeF; różnica do 1 zł w obie strony to zgodność.
 * Większą różnicę osoba z Edycją potwierdza
 * (toast „Cofnij”); po zmianie faktur albo przyjęcia różnica wraca do sprawdzenia. W wierszu listy {@code compact}:
 * zgodna kwota nic nie pokazuje, kliknięcia nie otwierają wiersza.
 */
export function InvoiceAmountCheck({
  storeId,
  documentId,
  check,
  compact,
}: {
  storeId: string
  documentId: string
  check: Check | null
  compact?: boolean
}) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { confirm, cancel } = useAmountConfirmation(storeId, 'pz', documentId)
  if (!check) return null

  if (check.matches) {
    if (compact) return null
    return (
      <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-muted-foreground tabular-nums">
        <CheckIcon aria-hidden className="size-4 text-emerald-600 dark:text-emerald-400" />
        Kwota netto zgodna z fakturą ({formatMoney(check.invoiceNet)} zł
        {check.difference !== 0 && `, różnica ${signed(check.difference)} zł w granicy 1 zł`})
      </p>
    )
  }

  const onConfirm = () =>
    confirm.mutate(undefined, {
      onSuccess: () =>
        toast.success(`Potwierdzono różnicę ${signed(check.difference)} zł`, {
          action: { label: 'Cofnij', onClick: () => cancel.mutate() },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się potwierdzić różnicy')),
    })

  if (check.confirmedAt) {
    return (
      <span
        className={cn('inline-flex flex-wrap items-center gap-2 tabular-nums', compact ? 'mt-1' : 'mt-2')}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => event.stopPropagation()}
      >
        <span
          className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-[0.7rem] font-medium text-muted-foreground"
          title={confirmedTitle(check)}
        >
          <CheckIcon aria-hidden className="size-3" />
          Różnica {signed(check.difference)} zł potwierdzona
        </span>
        {!compact && canEdit && (
          <Button
            variant="ghost"
            size="xs"
            loading={cancel.isPending}
            onClick={() =>
              cancel.mutate(undefined, {
                onSuccess: () =>
                  toast.success('Cofnięto potwierdzenie różnicy', {
                    action: { label: 'Przywróć', onClick: () => confirm.mutate() },
                  }),
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się cofnąć potwierdzenia')),
              })
            }
          >
            Cofnij potwierdzenie
          </Button>
        )}
      </span>
    )
  }

  return (
    <span
      className={cn('inline-flex flex-wrap items-center gap-1 tabular-nums', compact ? 'mt-1' : 'mt-2')}
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <span
        className="inline-flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-amber-900 ring-1 ring-amber-200 dark:bg-amber-950/40 dark:text-amber-200 dark:ring-amber-800"
        title={`Faktury netto ${formatMoney(check.invoiceNet)} zł, przyjęcie netto ${formatMoney(check.documentValue)} zł`}
      >
        <TriangleAlertIcon aria-hidden className="size-3" />
        {compact
          ? `Faktura ${formatMoney(check.invoiceNet)} · różnica ${signed(check.difference)} zł`
          : `Kwota netto faktury ${formatMoney(check.invoiceNet)} zł różni się od przyjęcia (${formatMoney(
              check.documentValue,
            )} zł) o ${signed(check.difference)} zł`}
      </span>
      {canEdit && (
        <Button variant="ghost" size="xs" loading={confirm.isPending} onClick={onConfirm}>
          Potwierdź
        </Button>
      )}
    </span>
  )
}
