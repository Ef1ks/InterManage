import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { DownloadIcon, FolderIcon, PencilIcon, PlusIcon, Trash2Icon, UploadIcon } from 'lucide-react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  shortcutFileUrl,
  shortcutsZipUrl,
  useCreateShortcut,
  useRemoveShortcut,
  useRenameShortcut,
  useRestoreShortcut,
  useShortcuts,
  type FolderShortcut,
} from '@/features/downloads/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'

/** Znaki, których Windows nie przyjmie w nazwie pliku (sprawdza też serwer). */
const FORBIDDEN = /[\\/:*?"<>|]/

/**
 * Skróty do folderów na dysku sieciowym: pracownik pobiera je na pulpit i przez nie wybiera folder w oknie wyboru
 * (np. przy Imporcie danych na nowym komputerze). Dodaje je i nazywa właściciel.
 */
export function ShortcutsSection({ owner }: { owner: boolean }) {
  const { data: shortcuts, isPending, isError } = useShortcuts()
  const [editor, setEditor] = useState<{ open: boolean; shortcut?: FolderShortcut }>({ open: false })
  const remove = useRemoveShortcut()
  const restore = useRestoreShortcut()

  if (isPending || isError) return null
  if (shortcuts.length === 0 && !owner) return null

  const onRemove = (shortcut: FolderShortcut) =>
    remove.mutate(shortcut.id, {
      onSuccess: () =>
        toast.success(`Usunięto skrót „${shortcut.name}”`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(shortcut.id, {
                onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić'),
              }),
          },
        }),
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć'),
    })

  return (
    <section className="mt-8 flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-lg font-semibold">Skróty do folderów</h2>
          <p className="text-sm text-muted-foreground">
            Pobierz, otwórz pobrany plik ZIP i przeciągnij skrót na pulpit. W oknie wyboru folderu wejdź w skrót zamiast
            szukać folderu na dysku.
          </p>
        </div>
        {shortcuts.length > 1 && (
          <Button variant="outline" nativeButton={false} render={<a href={shortcutsZipUrl} download />}>
            <DownloadIcon />
            Pobierz wszystkie
          </Button>
        )}
        {owner && (
          <Button onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Dodaj skrót
          </Button>
        )}
      </div>

      {shortcuts.length === 0 ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">Nie ma jeszcze skrótów</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Zrób w Windows skrót do folderu (prawy przycisk na folderze → Utwórz skrót), nazwij go i wgraj tutaj.
          </p>
        </Card>
      ) : (
        <Card className="gap-0 p-0">
          <ul className="divide-y">
            {shortcuts.map((shortcut) => (
              <ShortcutRow
                key={shortcut.id}
                shortcut={shortcut}
                owner={owner}
                onEdit={() => setEditor({ open: true, shortcut })}
                onRemove={() => onRemove(shortcut)}
              />
            ))}
          </ul>
        </Card>
      )}

      {owner && (
        <ShortcutDialog
          open={editor.open}
          shortcut={editor.shortcut}
          onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
        />
      )}
    </section>
  )
}

function ShortcutRow({
  shortcut,
  owner,
  onEdit,
  onRemove,
}: {
  shortcut: FolderShortcut
  owner: boolean
  onEdit: () => void
  onRemove: () => void
}) {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <FolderIcon className="size-5 shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{shortcut.name}</div>
        {shortcut.target ? (
          <div className="flex min-w-0 items-center gap-1">
            <span className="truncate font-mono text-xs text-muted-foreground" title={shortcut.target}>
              {shortcut.target}
            </span>
            <CopyButton value={shortcut.target} label="Kopiuj ścieżkę folderu" />
          </div>
        ) : (
          <div className="text-xs text-muted-foreground">Nie udało się odczytać folderu ze skrótu</div>
        )}
      </div>
      {owner && (
        <div className="flex shrink-0">
          <Button variant="ghost" size="icon-sm" title="Zmień nazwę" aria-label="Zmień nazwę" onClick={onEdit}>
            <PencilIcon />
          </Button>
          <Button variant="ghost" size="icon-sm" title="Usuń" aria-label="Usuń" onClick={onRemove}>
            <Trash2Icon />
          </Button>
        </div>
      )}
      <Button
        variant="outline"
        className="shrink-0"
        nativeButton={false}
        render={<a href={shortcutFileUrl(shortcut.id)} download />}
      >
        <DownloadIcon />
        Pobierz
      </Button>
    </li>
  )
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Brak = nowy skrót. */
  shortcut?: FolderShortcut
}

function ShortcutDialog({ open, onOpenChange, shortcut }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        {open && <Body key={shortcut?.id ?? 'new'} shortcut={shortcut} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function Body({ shortcut, onDone }: { shortcut?: FolderShortcut; onDone: () => void }) {
  const create = useCreateShortcut()
  const rename = useRenameShortcut()
  const [file, setFile] = useState<File | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const isNew = shortcut === undefined
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<{ name: string }>({ defaultValues: { name: shortcut?.name ?? '' } })

  const pick = (picked: File | undefined) => {
    if (!picked) return
    if (!picked.name.toLowerCase().endsWith('.lnk')) {
      toast.error('Wybierz skrót Windows (plik .lnk)')
      return
    }
    setFile(picked)
    // Bez wpisanej nazwy podpowiadamy nazwę skrótu z dysku.
    if (!getValues('name').trim()) setValue('name', picked.name.replace(/\.lnk$/i, ''), { shouldValidate: true })
  }

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    const done = {
      onSuccess: () => {
        toast.success(isNew ? `Dodano skrót „${values.name.trim()}”` : 'Zmieniono nazwę')
        onDone()
      },
      onError: (error: unknown) => setFormError(applyServerErrors(error, setError, ['name'])),
    }
    if (isNew) {
      if (!file) {
        setFormError('Wybierz skrót')
        return
      }
      create.mutate({ name: values.name, file }, done)
    } else {
      rename.mutate({ id: shortcut.id, name: values.name }, done)
    }
  })

  return (
    <Form onSubmit={onSubmit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{isNew ? 'Nowy skrót do folderu' : 'Zmień nazwę skrótu'}</DialogTitle>
        <DialogDescription>
          {isNew
            ? 'Pracownik pobierze go pod tą nazwą. Folder, do którego prowadzi, odczytamy ze skrótu.'
            : 'Pracownicy pobiorą skrót pod nową nazwą.'}
        </DialogDescription>
      </DialogHeader>

      {isNew && (
        <div className="flex items-center gap-3 rounded-lg border border-dashed p-3">
          <input
            ref={input}
            type="file"
            accept=".lnk"
            className="hidden"
            onChange={(event) => {
              pick(event.target.files?.[0])
              event.target.value = ''
            }}
          />
          <FolderIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="min-w-0 flex-1 truncate text-sm">
            {file ? (
              <span className="font-medium">{file.name}</span>
            ) : (
              <span className="text-muted-foreground">Nie wybrano skrótu</span>
            )}
          </div>
          <Button type="button" variant="outline" onClick={() => input.current?.click()}>
            <UploadIcon />
            {file ? 'Zmień' : 'Wybierz skrót'}
          </Button>
        </div>
      )}

      <TextField
        label="Nazwa"
        autoComplete="off"
        autoFocus={!isNew}
        hint="Np. Raporty z Mercalys. Tak będzie się nazywał skrót na pulpicie."
        error={errors.name?.message}
        {...register('name', {
          required: 'Podaj nazwę skrótu',
          maxLength: { value: 100, message: 'Maksymalnie 100 znaków' },
          validate: (value) => (FORBIDDEN.test(value) ? 'Nazwa nie może mieć znaków \\ / : * ? " < > |' : true),
        })}
      />

      <FormActions>
        <Button type="button" variant="ghost" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={create.isPending || rename.isPending}>
          {isNew ? 'Dodaj' : 'Zapisz'}
        </Button>
      </FormActions>
    </Form>
  )
}
