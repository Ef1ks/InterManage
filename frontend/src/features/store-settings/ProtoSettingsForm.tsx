import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SwitchField, TextField } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { LST_FOLDER } from '@/features/data-entry/lst-file'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import {
  PROTOCOLS,
  useProductFileFolder,
  useProtoFolder,
  useProtoSettings,
  useSaveProtoSettings,
} from '@/lib/proto-files'
import type { ProtocolKind, ProtoSettings } from '@/lib/types'

/** Folder domyślny z backendu (StoreProtoSettingsService.DEFAULT_FOLDER). */
const DEFAULT_FOLDER = 'N:\\AVISOCONFIG\\BACKUP'
const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/**
 * Folder plików PROTO (wspólny z PROTO6 garmażu i przyjęć MM), protokoły widoczne we wprowadzaniu danych i folder
 * pliku nowych i zmienionych artykułów (CADBEZP), który powstaje przy zapisie pliku XML.
 */
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

type Values = { folderPath: string; productFileFolderPath: string; hidden: ProtocolKind[] }

function SettingsForm({ storeId, settings }: { storeId: string; settings: ProtoSettings }) {
  const save = useSaveProtoSettings(storeId)
  const folder = useProtoFolder(storeId)
  const productFolder = useProductFileFolder(storeId)
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
      productFileFolderPath: settings.productFileFolderPath ?? '',
      hidden: settings.hiddenProtocols,
    },
  })
  const hidden = useWatch({ control, name: 'hidden' })

  const submit = handleSubmit(async ({ folderPath, productFileFolderPath, hidden }) => {
    setFormError(null)
    try {
      await save.mutateAsync({
        folderPath: folderPath.trim() || null,
        hiddenProtocols: hidden,
        productFileFolderPath: productFileFolderPath.trim() || null,
      })
      toast.success('Zapisano ustawienia protokołów i zmiany cen')
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(applyServerErrors(error, setError, ['folderPath', 'productFileFolderPath']))
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
          label="Folder protokołów i zmiany cen"
          autoComplete="off"
          error={errors.folderPath?.message}
          hint={`Folder, z którego program sklepu wczytuje pliki: zamówienia garmażu, przyjęcia MM, protokoły i zmiany cen. Podpowiadany przy wyborze folderu w przeglądarce. Puste pole = ${DEFAULT_FOLDER}.`}
          {...register('folderPath', { maxLength: { value: 300, message: 'Najwyżej 300 znaków' } })}
        />
        <div className="grid gap-2">
          <p className="text-sm font-medium">Protokoły we wprowadzaniu danych</p>
          {PROTOCOLS.map((protocol) => (
            <SwitchField
              key={protocol.kind}
              label={protocol.label}
              checked={!hidden.includes(protocol.kind)}
              onCheckedChange={(visible) => toggle(protocol.kind, visible)}
            />
          ))}
        </div>
        <TextField
          label="Folder pliku nowych artykułów"
          autoComplete="off"
          error={errors.productFileFolderPath?.message}
          hint="Przy zapisie pliku XML we wprowadzaniu danych nowe i zmienione artykuły trafiają tu do pliku (.xls) dla programu sklepu."
          {...register('productFileFolderPath', { maxLength: { value: 300, message: 'Najwyżej 300 znaków' } })}
        />
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
        <p className="text-sm font-medium">Foldery w tej przeglądarce</p>
        <p className="text-xs text-muted-foreground">
          Przeglądarka nie zapisze pliku pod ścieżką podaną w aplikacji: folder trzeba wskazać raz na każdym komputerze.
        </p>
        <SavedFolder folder={folder} />
        <SavedFolder folder={productFolder} />
        <SavedFolder folder={LST_FOLDER} />
      </div>
    </Card>
  )
}
