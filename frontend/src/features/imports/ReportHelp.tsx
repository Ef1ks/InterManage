import { useState } from 'react'
import { Popover } from '@base-ui/react/popover'
import { CircleHelpIcon, FolderIcon, PencilIcon } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { Form, FormActions, TextareaField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useSaveImportGuide } from '@/features/imports/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { ImportGuide, ImportReportKind } from '@/lib/types'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/**
 * Znak zapytania przy tytule raportu: po najechaniu albo kliknięciu okienko z opisem raportu i instrukcją właściciela
 * (jak wygenerować plik, gdzie go szukać). Właściciel ma obok ołówek do zmiany instrukcji.
 */
export function ReportHelp({
  kind,
  title,
  description,
  guide,
  canEdit,
}: {
  kind: ImportReportKind
  title: string
  description: string
  guide: ImportGuide | undefined
  canEdit: boolean
}) {
  const [editing, setEditing] = useState(false)

  return (
    <>
      <Popover.Root>
        <Popover.Trigger
          openOnHover
          delay={150}
          aria-label={`Co to za raport: ${title}`}
          className="grid size-7 place-items-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring data-popup-open:bg-muted data-popup-open:text-foreground"
        >
          <CircleHelpIcon aria-hidden className="size-4" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Positioner side="bottom" align="start" sideOffset={6} collisionPadding={12} className="z-50">
            <Popover.Popup className="grid w-80 max-w-[calc(100vw-2rem)] origin-(--transform-origin) gap-3 rounded-xl border bg-popover p-4 text-sm text-popover-foreground shadow-lg outline-none transition-[opacity,scale] duration-150 data-ending-style:scale-95 data-ending-style:opacity-0 data-starting-style:scale-95 data-starting-style:opacity-0 motion-reduce:transition-none">
              <Popover.Title className="font-semibold">{title}</Popover.Title>
              <p className="text-muted-foreground">{description}</p>
              <div className="grid gap-1.5 border-t pt-3">
                <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                  Jak wygenerować plik
                </p>
                {guide?.content ? (
                  <p className="whitespace-pre-line">{guide.content}</p>
                ) : (
                  <p className="text-muted-foreground">
                    {canEdit
                      ? 'Brak instrukcji. Dopisz ją ołówkiem obok tytułu.'
                      : 'Właściciel nie dopisał jeszcze instrukcji.'}
                  </p>
                )}
                {guide?.folderPath && (
                  <div className="mt-1 flex items-center gap-2 rounded-lg bg-muted px-2.5 py-1.5">
                    <FolderIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate font-mono text-xs" title={guide.folderPath}>
                      {guide.folderPath}
                    </span>
                    <CopyButton value={guide.folderPath} label="Kopiuj ścieżkę" />
                  </div>
                )}
                {guide && (
                  <p className="text-xs text-muted-foreground">
                    {guide.createdBy ?? 'Usunięty użytkownik'} · {dateFormat.format(new Date(guide.createdAt))}
                  </p>
                )}
              </div>
            </Popover.Popup>
          </Popover.Positioner>
        </Popover.Portal>
      </Popover.Root>

      {canEdit && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="text-muted-foreground"
          aria-label={`Zmień instrukcję: ${title}`}
          title="Zmień instrukcję"
          onClick={() => setEditing(true)}
        >
          <PencilIcon />
        </Button>
      )}

      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {editing && <GuideForm kind={kind} title={title} guide={guide} onDone={() => setEditing(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

type GuideFormValues = { content: string; folderPath: string }

function GuideForm({
  kind,
  title,
  guide,
  onDone,
}: {
  kind: ImportReportKind
  title: string
  guide: ImportGuide | undefined
  onDone: () => void
}) {
  const save = useSaveImportGuide()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<GuideFormValues>({
    defaultValues: { content: guide?.content ?? '', folderPath: guide?.folderPath ?? '' },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await save.mutateAsync({ kind, ...values })
      toast.success('Zapisano instrukcję')
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['content', 'folderPath']))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Instrukcja: {title}</DialogTitle>
        <DialogDescription>
          Widzą ją wszyscy pracownicy wszystkich sklepów pod znakiem zapytania przy raporcie.
        </DialogDescription>
      </DialogHeader>
      <TextareaField
        label="Jak wygenerować plik"
        rows={7}
        hint="Np. Mercalys → Raporty → Statystyki → Wejścia/Wyjścia, zakres: ostatnie 7 dni, Eksport do Excela"
        error={errors.content?.message}
        {...register('content', { maxLength: { value: 4000, message: 'Maksymalnie 4000 znaków' } })}
      />
      <TextField
        label="Folder z plikiem"
        autoComplete="off"
        hint="Podpowiedź przy wyborze folderu w ustawieniach importu, np. N:\RAPORTY"
        error={errors.folderPath?.message}
        {...register('folderPath', { maxLength: { value: 500, message: 'Maksymalnie 500 znaków' } })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
