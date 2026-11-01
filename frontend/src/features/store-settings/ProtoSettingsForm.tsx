import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SwitchField, TextField } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import { PROTOCOLS, useProtoFolder, useProtoSettings, useSaveProtoSettings } from '@/lib/proto-files'
import type { ProtocolKind, ProtoSettings } from '@/lib/types'

/** Folder domyślny z backendu (StoreProtoSettingsService.DEFAULT_FOLDER). */
const DEFAULT_FOLDER = 'N:\\avisoconfig'
const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** Folder plików PROTO (wspólny z PROTO6 garmażu i przyjęć MM) i protokoły widoczne we wprowadzaniu danych. */
export function ProtoSettingsForm({ storeId }: { storeId: string }) {
  const { data, isError, refetch } = useProtoSettings(storeId)

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać ustawień</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }
  if (!data) {
    return <div aria-hidden className="h-72 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  return <SettingsForm key={data.updatedAt ?? 'new'} storeId={storeId} settings={data} />
}

type Values = { folderPath: string; hidden: ProtocolKind[] }

function SettingsForm({ storeId, settings }: { storeId: string; settings: ProtoSettings }) {
  const save = useSaveProtoSettings(storeId)
  const folder = useProtoFolder(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    control,
    setValue,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<Values>({
    defaultValues: {
      folderPath: settings.defaultFolder ? '' : settings.folderPath,
      hidden: settings.hiddenProtocols,
    },
  })
  const hidden = useWatch({ control, name: 'hidden' })

  const submit = handleSubmit(async ({ folderPath, hidden }) => {
    setFormError(null)
    try {
      await save.mutateAsync({ folderPath: folderPath.trim() || null, hiddenProtocols: hidden })
      toast.success('Zapisano ustawienia plików PROTO')
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(applyServerErrors(error, setError, ['folderPath']))
      } else {
        setFormError('Nie udało się zapisać ustawień')
      }
    }
  })

  const toggle = (kind: ProtocolKind, visible: boolean) =>
    setValue('hidden', visible ? hidden.filter((item) => item !== kind) : [...hidden, kind], { shouldDirty: true })

  return (
    <Card className="p-4">
      <Form onSubmit={submit} error={formError}>
        <TextField
          label="Folder plików PROTO"
          autoComplete="off"
          error={errors.folderPath?.message}
          hint={`Folder, z którego program sklepu wczytuje pliki: PROTO6 garmażu i przyjęć MM oraz protokoły z wprowadzania danych. Puste pole = ${DEFAULT_FOLDER}.`}
          {...register('folderPath', { maxLength: { value: 300, message: 'Najwyżej 300 znaków' } })}
        />
        <div className="grid gap-2">
          <p className="text-sm font-medium">Protokoły we wprowadzaniu danych</p>
          {PROTOCOLS.map((protocol) => (
            <SwitchField
              key={protocol.kind}
              label={protocol.label}
              description={`Plik PROTO${protocol.number}`}
              checked={!hidden.includes(protocol.kind)}
              onCheckedChange={(visible) => toggle(protocol.kind, visible)}
            />
          ))}
        </div>
        {settings.updatedAt && (
          <FormNote>
            Zmienione {dateFormat.format(new Date(settings.updatedAt))}
            {settings.updatedBy && ` przez ${settings.updatedBy}`}.
          </FormNote>
        )}
        <FormActions>
          <Button type="submit" size="xl" loading={isSubmitting} disabled={!isDirty}>
            Zapisz
          </Button>
        </FormActions>
      </Form>
      <div className="mt-4 grid gap-1.5 border-t pt-4">
        <p className="text-sm font-medium">Folder w tej przeglądarce</p>
        <p className="text-xs text-muted-foreground">
          Przeglądarka nie zapisze pliku pod ścieżką podaną w aplikacji: folder trzeba wskazać raz na każdym komputerze.
        </p>
        <SavedFolder folder={folder} />
      </div>
    </Card>
  )
}
