import { PrinterIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { usePrintInvoice } from '@/features/ksef/api'
import { ApiError } from '@/lib/api'

/**
 * „Drukuj” faktury (albo korekty) z KSeF: PDF otwiera się od razu w oknie drukowania przeglądarki, bez zapisu na
 * dysku. `icon` to mały przycisk z samą ikoną, np. w plakietce na liście.
 */
export function InvoicePrintButton({
  storeId,
  invoiceId,
  invoiceNumber,
  variant = 'button',
  label = 'Drukuj',
}: {
  storeId: string
  invoiceId: string
  invoiceNumber: string
  variant?: 'button' | 'icon'
  /** Napis na przycisku (domyślnie „Drukuj”). */
  label?: string
}) {
  const print = usePrintInvoice(storeId)
  const run = () =>
    print.mutate(
      { id: invoiceId },
      {
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się wydrukować faktury'),
      },
    )

  if (variant === 'icon') {
    return (
      <button
        type="button"
        aria-label={`Drukuj fakturę ${invoiceNumber}`}
        title="Drukuj"
        disabled={print.isPending}
        className="flex size-5 items-center justify-center rounded transition-colors hover:bg-sky-100 disabled:opacity-50 dark:hover:bg-sky-900"
        onClick={(event) => {
          event.stopPropagation()
          run()
        }}
        onKeyDown={(event) => event.stopPropagation()}
      >
        <PrinterIcon className="size-3" />
      </button>
    )
  }
  return (
    <Button
      variant="outline"
      size="sm"
      loading={print.isPending}
      onClick={run}
      title={`Drukuj fakturę ${invoiceNumber}`}
    >
      <PrinterIcon />
      {label}
    </Button>
  )
}
