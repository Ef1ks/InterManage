import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { PencilIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { useSupplierDocumentNumber } from '@/features/documents/api'
import { formatDocumentDate } from '@/features/documents/format'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { Document } from '@/lib/types'

/**
 * Numer dokumentu dostawcy (zwykle numer faktury). W przyjęciu PZ da się go poprawić: po nim faktura KSeF wiąże się
 * z dostawą („wprowadzona”), a literówka z systemu sklepu to blokuje. Poprawka nie znika przy kolejnym imporcie.
 */
export function SupplierDocumentNumber({ storeId, document }: { storeId: string; document: Document }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT') && document.supplierDocumentNumberEditable
  const { save, restore } = useSupplierDocumentNumber(storeId, document.type, document.id)
  const [editing, setEditing] = useState(false)
  const edited = document.supplierDocumentNumberEditedAt !== null

  const onRestore = () =>
    restore.mutate(undefined, {
      onSuccess: () => toast.success('Przywrócono numer z raportu'),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić numeru'),
    })

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <span className="tabular-nums">{document.supplierDocumentNumber ?? '-'}</span>
        {canEdit && (
          <Button
            variant="ghost"
            size="icon-xs"
            aria-label="Popraw numer dokumentu dostawcy"
            title="Popraw numer"
            onClick={() => setEditing(true)}
          >
            <PencilIcon />
          </Button>
        )}
        {edited && (
          <span className="text-xs text-muted-foreground">
            poprawiony
            {document.supplierDocumentNumberEditedBy && ` przez ${document.supplierDocumentNumberEditedBy}`} · w raporcie{' '}
            <span className="tabular-nums">{document.reportSupplierDocumentNumber ?? 'brak'}</span>
            {canEdit && (
              <>
                {' · '}
                <Button variant="link" className="h-auto p-0 text-xs" disabled={restore.isPending} onClick={onRestore}>
                  Przywróć z raportu
                </Button>
              </>
            )}
          </span>
        )}
      </div>

      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent className="gap-5 p-6 sm:max-w-md">
          {editing && (
            <NumberForm
              document={document}
              onSubmit={(number) => save.mutateAsync(number)}
              onDone={(previous) => {
                setEditing(false)
                toast.success('Zapisano numer dokumentu dostawcy', {
                  description: 'Faktury KSeF z tym numerem są teraz powiązane z przyjęciem.',
                  action: {
                    label: 'Cofnij',
                    onClick: () => (previous.edited ? save.mutate(previous.number ?? '') : restore.mutate()),
                  },
                })
              }}
              onCancel={() => setEditing(false)}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

function NumberForm({
  document,
  onSubmit,
  onDone,
  onCancel,
}: {
  document: Document
  onSubmit: (number: string) => Promise<unknown>
  onDone: (previous: { number: string | null; edited: boolean }) => void
  onCancel: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<{ supplierDocumentNumber: string }>({
    defaultValues: { supplierDocumentNumber: document.supplierDocumentNumber ?? '' },
  })

  const submit = handleSubmit(async ({ supplierDocumentNumber }) => {
    setFormError(null)
    const previous = {
      number: document.supplierDocumentNumber,
      edited: document.supplierDocumentNumberEditedAt !== null,
    }
    try {
      await onSubmit(supplierDocumentNumber.trim())
      onDone(previous)
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['supplierDocumentNumber']))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Numer dokumentu dostawcy</DialogTitle>
        <DialogDescription>
          Przyjęcie {document.number} z {formatDocumentDate(document.documentDate)}. Wpisz numer dokładnie tak, jak na
          fakturze - po nim faktura z KSeF połączy się z tym przyjęciem.
        </DialogDescription>
      </DialogHeader>
      <TextField
        label="Numer faktury dostawcy"
        autoComplete="off"
        autoFocus
        error={errors.supplierDocumentNumber?.message}
        {...register('supplierDocumentNumber', { maxLength: { value: 60, message: 'Maksymalnie 60 znaków' } })}
      />
      <FormNote>
        W raporcie: <span className="tabular-nums">{document.reportSupplierDocumentNumber ?? 'brak numeru'}</span>.
        Kolejne importy nie nadpiszą poprawki.
      </FormNote>
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
