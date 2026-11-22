import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { LinkIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SegmentedField, TextareaField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FEEDBACK_KINDS, MAX_FEEDBACK_SHOTS, useCreateFeedback } from '@/features/feedback/api'
import { DraftAttachments } from '@/features/tasks/TaskAttachments'
import { localShot, type LocalShot } from '@/features/tasks/task-helpers'
import { applyServerErrors } from '@/lib/form-errors'
import type { FeedbackKind } from '@/lib/types'

type Values = { kind: FeedbackKind; description: string }

const KINDS = (Object.keys(FEEDBACK_KINDS) as FeedbackKind[]).map((value) => ({
  value,
  label: FEEDBACK_KINDS[value],
}))

/** Okno zgłoszenia: rodzaj, opis i zrzuty (zrobiony przed otwarciem, wklejone, z pliku). */
export function FeedbackDialog({
  open,
  onOpenChange,
  shots,
  pageUrl,
  pageLabel,
  storeId,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  shots: Blob[]
  pageUrl: string
  pageLabel: string
  storeId: string | null
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-xl">
        {open && (
          <FeedbackForm
            initialShots={shots}
            pageUrl={pageUrl}
            pageLabel={pageLabel}
            storeId={storeId}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function FeedbackForm({
  initialShots,
  pageUrl,
  pageLabel,
  storeId,
  onDone,
}: {
  initialShots: Blob[]
  pageUrl: string
  pageLabel: string
  storeId: string | null
  onDone: () => void
}) {
  const create = useCreateFeedback()
  const [shots, setShots] = useState<LocalShot[]>(() => initialShots.map(localShot))
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ defaultValues: { kind: 'BUG', description: '' } })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await create.mutateAsync({
        kind: values.kind,
        description: values.description.trim(),
        pageUrl,
        pageLabel,
        storeId,
        shots: shots.map((shot) => shot.blob),
      })
      toast.success('Dziękujemy, zgłoszenie wysłane', { description: 'Właściciel zobaczy je razem ze zrzutami.' })
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['description']))
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Zgłoś poprawkę</DialogTitle>
        <DialogDescription>
          Co nie działa albo co można zrobić lepiej? Zaznacz to na zrzucie, a opisz poniżej.
        </DialogDescription>
      </DialogHeader>

      <Controller
        control={control}
        name="kind"
        render={({ field }) => (
          <SegmentedField label="Rodzaj zgłoszenia" options={KINDS} value={field.value} onChange={field.onChange} />
        )}
      />
      <TextareaField
        label="Opis"
        rows={4}
        autoFocus
        error={errors.description?.message}
        {...register('description', {
          validate: (value) => value.trim() !== '' || 'Opisz, co poprawić',
          maxLength: { value: 5000, message: 'Najwyżej 5000 znaków' },
        })}
      />
      <DraftAttachments
        shots={shots}
        onChange={(next) => {
          if (next.length > MAX_FEEDBACK_SHOTS) {
            toast.error(`Najwyżej ${MAX_FEEDBACK_SHOTS} zrzutów przy jednym zgłoszeniu`)
            return
          }
          setShots(next)
        }}
      />
      {pageUrl && <FormNote icon={<LinkIcon />}>Zgłoszenie zapamięta stronę: {pageLabel}</FormNote>}
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Wyślij zgłoszenie
        </Button>
      </FormActions>
    </Form>
  )
}
