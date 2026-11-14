import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, TextareaField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCancelCashDocument, useRestoreCashDocument } from '@/features/cash/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { CashDocument } from '@/lib/types'

/** Anulowanie dokumentu z powodem; numer zostaje w historii, toast „Przywróć” cofa anulowanie. */
export function CancelDocumentDialog({
  storeId,
  document,
  onClose,
}: {
  storeId: string
  document: CashDocument | null
  onClose: () => void
}) {
  return (
    <Dialog open={document !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {document && <CancelForm key={document.id} storeId={storeId} document={document} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function CancelForm({ storeId, document, onDone }: { storeId: string; document: CashDocument; onDone: () => void }) {
  const cancel = useCancelCashDocument(storeId)
  const restore = useRestoreCashDocument(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<{ reason: string }>({ defaultValues: { reason: '' } })

  const onSubmit = handleSubmit(async ({ reason }) => {
    setFormError(null)
    try {
      await cancel.mutateAsync({ id: document.id, reason: reason.trim() })
      onDone()
      toast.success(`Anulowano ${document.number}`, {
        action: {
          label: 'Przywróć',
          onClick: () =>
            restore.mutate(document.id, {
              onError: (error) =>
                toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić dokumentu'),
            }),
        },
      })
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['reason']))
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Anulować {document.number}?</DialogTitle>
        <DialogDescription>
          Dokument zostanie w historii i w raporcie jako anulowany, bez wpływu na stan kasy. Numer nie wróci do puli.
        </DialogDescription>
      </DialogHeader>
      <TextareaField
        label="Powód anulowania"
        rows={2}
        autoFocus
        error={errors.reason?.message}
        {...register('reason', {
          validate: (value) => value.trim() !== '' || 'Podaj powód',
          maxLength: { value: 300, message: 'Najwyżej 300 znaków' },
        })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Wróć
        </Button>
        <Button type="submit" variant="destructive" size="lg" loading={isSubmitting}>
          Anuluj dokument
        </Button>
      </FormActions>
    </Form>
  )
}
