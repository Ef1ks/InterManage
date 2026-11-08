import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { FileIcon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextareaField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCreateDownload, useUpdateDownload, type Download, type DownloadForm } from '@/features/downloads/api'
import { formatSize } from '@/features/downloads/format'
import { applyServerErrors } from '@/lib/form-errors'

/** Tyle przyjmuje serwer (`DownloadService.MAX_FILE_BYTES`). */
const MAX_FILE_MB = 50

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowa pozycja. */
  download?: Download
}

export function DownloadFormDialog({ open, onOpenChange, download }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {open && <Body key={download?.id ?? 'new'} download={download} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function Body({ download, onDone }: { download?: Download; onDone: () => void }) {
  const create = useCreateDownload()
  const update = useUpdateDownload()
  const [formError, setFormError] = useState<string | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const isNew = download === undefined

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Omit<DownloadForm, 'file'>>({
    defaultValues: {
      title: download?.title ?? '',
      description: download?.description ?? '',
      version: '',
    },
  })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const form = { ...values, file }
    const done = {
      onSuccess: () => {
        toast.success(isNew ? `Dodano „${values.title.trim()}”` : file ? 'Wgrano nową wersję' : 'Zapisano zmiany')
        onDone()
      },
      onError: (error: unknown) => setFormError(applyServerErrors(error, setError, ['title', 'description'])),
    }
    if (isNew) create.mutate(form, done)
    else update.mutate({ id: download.id, form }, done)
  })

  const pick = (picked: File | undefined) => {
    if (!picked) return
    if (picked.size > MAX_FILE_MB * 1024 * 1024) {
      toast.error(`Plik jest za duży (najwyżej ${MAX_FILE_MB} MB)`)
      return
    }
    setFile(picked)
  }

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{isNew ? 'Nowy plik do pobrania' : download.title}</DialogTitle>
        <DialogDescription>
          Pobrać go może każdy zalogowany pracownik. Nowa wersja zastępuje poprzednią, a stara zostaje w historii.
        </DialogDescription>
      </DialogHeader>

      <TextField
        label="Nazwa"
        autoComplete="off"
        autoFocus={isNew}
        hint="Np. AutoJob - odtwarzacz makr."
        error={errors.title?.message}
        {...register('title', {
          required: 'Podaj nazwę',
          maxLength: { value: 120, message: 'Maksymalnie 120 znaków' },
        })}
      />

      <TextareaField
        label="Opis i instrukcja (opcjonalnie)"
        rows={4}
        hint="Do czego służy i jak zainstalować. Każda linia to osobny akapit."
        error={errors.description?.message}
        {...register('description', { maxLength: { value: 2000, message: 'Maksymalnie 2000 znaków' } })}
      />

      <div className="flex flex-col gap-3 rounded-lg border border-dashed p-3">
        <input
          ref={input}
          type="file"
          className="hidden"
          onChange={(event) => {
            pick(event.target.files?.[0])
            event.target.value = ''
          }}
        />
        <div className="flex items-center gap-3">
          <FileIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="min-w-0 flex-1 text-sm">
            {file ? (
              <>
                <div className="truncate font-medium">{file.name}</div>
                <div className="text-xs text-muted-foreground">{formatSize(file.size)}</div>
              </>
            ) : download?.file ? (
              <>
                <div className="truncate">{download.file.fileName}</div>
                <div className="text-xs text-muted-foreground">Obecna wersja; wybierz plik, żeby ją wymienić.</div>
              </>
            ) : (
              <div className="text-muted-foreground">Bez pliku pracownicy nie zobaczą tej pozycji.</div>
            )}
          </div>
          <Button type="button" variant="outline" onClick={() => input.current?.click()}>
            <UploadIcon />
            {file || download?.file ? 'Zmień plik' : 'Wybierz plik'}
          </Button>
        </div>
        {file && (
          <TextField
            label="Wersja (opcjonalnie)"
            autoComplete="off"
            hint="Np. v19 - widać ją przy przycisku pobierania."
            {...register('version', { maxLength: { value: 40, message: 'Maksymalnie 40 znaków' } })}
            error={errors.version?.message}
          />
        )}
      </div>

      <FormActions>
        <Button type="button" variant="ghost" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={create.isPending || update.isPending}>
          {isNew ? 'Dodaj' : 'Zapisz'}
        </Button>
      </FormActions>
    </Form>
  )
}
