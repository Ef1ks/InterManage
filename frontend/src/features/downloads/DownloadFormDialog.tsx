import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { FileIcon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, SwitchField, TextareaField, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  useCreateDownload,
  useUpdateDownload,
  type Download,
  type DownloadChangesForm,
  type DownloadForm,
} from '@/features/downloads/api'
import { CHANGE_KINDS, changesText, formatSize } from '@/features/downloads/format'
import { applyServerErrors } from '@/lib/form-errors'

/** Tyle przyjmuje serwer (`DownloadService.MAX_FILE_BYTES`). */
const MAX_FILE_MB = 50

const CHANGE_HINTS: Record<keyof DownloadChangesForm, string> = {
  added: 'Np. Makro korekty z faktury.',
  changed: 'Np. Szybsze wpisywanie EAN-ów.',
  fixed: 'Np. Esc zatrzymuje makro zamiast zamykać program.',
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowa pozycja. */
  download?: Download
}

type Values = Omit<DownloadForm, 'file' | 'announce' | 'changesEdited'>

export function DownloadFormDialog({ open, onOpenChange, download }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
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
  const [announce, setAnnounce] = useState(true)
  const input = useRef<HTMLInputElement>(null)
  const isNew = download === undefined
  const current = download?.file ?? null
  const currentChanges = (kind: (typeof CHANGE_KINDS)[number]['kind']) =>
    current ? changesText(current.changes, kind) : ''

  const {
    register,
    handleSubmit,
    setError,
    reset,
    getValues,
    formState: { errors, dirtyFields },
  } = useForm<Values>({
    defaultValues: {
      title: download?.title ?? '',
      description: download?.description ?? '',
      version: '',
      added: currentChanges('ADDED'),
      changed: currentChanges('CHANGED'),
      fixed: currentChanges('FIXED'),
    },
  })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const changesEdited = CHANGE_KINDS.some(({ field }) => dirtyFields[field])
    const form: DownloadForm = { ...values, file, announce, changesEdited }
    const done = {
      onSuccess: () => {
        toast.success(
          isNew
            ? `Dodano „${values.title.trim()}”`
            : file
              ? announce
                ? 'Wgrano nową wersję. Pracownicy zobaczą powiadomienie.'
                : 'Wgrano nową wersję bez powiadomienia'
              : 'Zapisano zmiany',
        )
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
    // Nowa wersja ma własną listę zmian: pola startują puste (lista obecnej wersji zostaje przy niej).
    if (!file && current) {
      reset({ ...getValues(), added: '', changed: '', fixed: '' }, { keepDirty: false })
    }
    setFile(picked)
  }

  const showsChanges = file !== null || current !== null

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{isNew ? 'Nowy plik do pobrania' : download.title}</DialogTitle>
        <DialogDescription>
          Pobrać go może każdy zalogowany pracownik. Nowa wersja zastępuje poprzednią, a stara zostaje w historii zmian.
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
        rows={3}
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
            ) : current ? (
              <>
                <div className="truncate">
                  {current.fileName}
                  {current.version && <span className="text-muted-foreground"> · {current.version}</span>}
                </div>
                <div className="text-xs text-muted-foreground">Obecna wersja; wybierz plik, żeby wgrać nową.</div>
              </>
            ) : (
              <div className="text-muted-foreground">Bez pliku pracownicy nie zobaczą tej pozycji.</div>
            )}
          </div>
          <Button type="button" variant="outline" onClick={() => input.current?.click()}>
            <UploadIcon />
            {file || current ? 'Zmień plik' : 'Wybierz plik'}
          </Button>
        </div>
        {file && (
          <>
            <TextField
              label="Wersja (opcjonalnie)"
              autoComplete="off"
              hint="Np. v37 - widać ją w powiadomieniu i przy przycisku pobierania."
              {...register('version', { maxLength: { value: 40, message: 'Maksymalnie 40 znaków' } })}
              error={errors.version?.message}
            />
            <SwitchField
              label="Powiadom pracowników"
              description={
                announce
                  ? 'Każdy zobaczy u góry aplikacji „Nowa wersja”, dopóki jej nie pobierze albo nie potwierdzi, że ją ma.'
                  : 'Wersja podmieni się po cichu, np. przy drobnej poprawce.'
              }
              checked={announce}
              onCheckedChange={setAnnounce}
            />
          </>
        )}
      </div>

      {showsChanges && (
        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-sm font-medium">
            {file ? 'Co nowego w tej wersji' : 'Lista zmian obecnej wersji'}
            <span className="block text-xs font-normal text-muted-foreground">
              Każda linia to osobny punkt. Pracownicy zobaczą to w „Co nowego” i w historii zmian.
            </span>
          </legend>
          {CHANGE_KINDS.map(({ field, label }) => (
            <TextareaField
              key={field}
              label={`${label} (opcjonalnie)`}
              rows={2}
              hint={CHANGE_HINTS[field]}
              {...register(field)}
            />
          ))}
        </fieldset>
      )}

      <FormActions>
        <Button type="button" variant="ghost" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={create.isPending || update.isPending}>
          {isNew ? 'Dodaj' : file ? 'Wgraj nową wersję' : 'Zapisz'}
        </Button>
      </FormActions>
    </Form>
  )
}
