import { useId, useState } from 'react'
import { useForm } from 'react-hook-form'
import { CircleAlertIcon, CircleCheckIcon, FileKeyIcon, LinkIcon, PlusIcon, ShieldCheckIcon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { HowTo } from '@/components/HowTo'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import { useKsefSettings, useKsefSettingsMutations } from '@/features/ksef/api'
import { formatDateTime, formatDay, formatNip, invoicesWord } from '@/features/ksef/format'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { KsefCompany, KsefConnection, KsefSettings } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Ustawienia KSeF sklepu: połączenie (NIP firmy, certyfikat KSeF z kluczem i hasłem, test logowania), IDWew sklepu
 * (do tego, które faktury widać) i dostawcy pomijani na liście. Usunięcie IDWew albo dostawcy da się cofnąć.
 */
export function KsefSettingsPage({ storeId }: { storeId: string }) {
  const { data: settings, isPending, isError, refetch } = useKsefSettings(storeId)

  if (isPending) {
    return <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  if (isError) {
    return (
      <Card className="items-center px-6 py-10 text-center">
        <p className="font-medium">Nie udało się wczytać ustawień KSeF</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }
  return (
    <div className="grid max-w-4xl gap-6">
      <HowTo
        id="ksef-connection"
        title="Jak połączyć sklep z KSeF"
        steps={[
          <>
            W Aplikacji Podatnika KSeF osoba z prawem do przeglądania faktur firmy generuje{' '}
            <strong className="font-semibold text-foreground">certyfikat KSeF do uwierzytelniania</strong> i pobiera
            pliki .crt i .key.
          </>,
          <>
            Wgraj oba pliki, wpisz hasło do klucza i NIP firmy. Po zapisie od razu sprawdzimy logowanie. Faktury są
            wspólne dla firmy: gdy jest już połączona w innym sklepie, właściciel podłączy ten sklep jednym
            kliknięciem i faktury będą widoczne od razu.
          </>,
          <>
            Dodaj <strong className="font-semibold text-foreground">IDWew sklepu</strong>: sklep zobaczy faktury z
            tym IDWew i wszystkie bez IDWew. Faktury pobierają się same co pół godziny.
          </>,
        ]}
      />
      {!settings.connection && <CompanyLinkCard storeId={storeId} companies={settings.companies} />}
      <ConnectionCard storeId={storeId} connection={settings.connection} />
      <InternalIdsCard storeId={storeId} settings={settings} />
      <SkippedSuppliersCard storeId={storeId} settings={settings} />
    </div>
  )
}

/**
 * Firma połączona już w innym sklepie: właściciel podłącza do niej ten sklep bez certyfikatu (dane logowania są
 * kopiowane), a sklep od razu widzi faktury firmy. Pracownik widzi tylko podpowiedź.
 */
function CompanyLinkCard({ storeId, companies }: { storeId: string; companies: KsefCompany[] }) {
  const { owner } = useCurrentUser()
  const { linkConnection } = useKsefSettingsMutations(storeId)
  if (companies.length === 0) return null
  return (
    <Card className="gap-3 p-5">
      <div>
        <h2 className="text-base font-semibold">Firma połączona w innym sklepie</h2>
        <p className="text-sm text-muted-foreground">
          {owner
            ? 'Podłącz ten sklep do firmy bez wgrywania certyfikatu - zobaczy jej faktury od razu, bez czekania na pobranie.'
            : 'Właściciel może podłączyć ten sklep do firmy bez wgrywania certyfikatu.'}
        </p>
      </div>
      <ul className="divide-y rounded-lg border">
        {companies.map((company) => (
          <li key={company.nip} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <div className="min-w-0">
              <p className="font-medium tabular-nums">NIP {formatNip(company.nip)}</p>
              <p className="truncate text-sm text-muted-foreground">{company.stores.join(', ')}</p>
            </div>
            {owner && (
              <Button
                variant="outline"
                loading={linkConnection.isPending && linkConnection.variables === company.nip}
                disabled={linkConnection.isPending}
                onClick={() =>
                  linkConnection.mutate(company.nip, {
                    onSuccess: (result) =>
                      result.connection?.testOk
                        ? toast.success('Sklep podłączony do firmy - faktury są już widoczne')
                        : toast.error('Podłączono, ale logowanie do KSeF się nie udało', {
                            description: result.connection?.testMessage ?? undefined,
                          }),
                    onError: (error) => toast.error(errorMessage(error, 'Nie udało się podłączyć sklepu')),
                  })
                }
              >
                <LinkIcon />
                Podłącz
              </Button>
            )}
          </li>
        ))}
      </ul>
    </Card>
  )
}

type ConnectionForm = { nip: string; password: string }

function ConnectionCard({ storeId, connection }: { storeId: string; connection: KsefConnection | null }) {
  const { saveConnection, test } = useKsefSettingsMutations(storeId)
  const [certificate, setCertificate] = useState<File | undefined>()
  const [privateKey, setPrivateKey] = useState<File | undefined>()
  const [fileErrors, setFileErrors] = useState<{ certificate?: string; privateKey?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    resetField,
    formState: { errors },
  } = useForm<ConnectionForm>({ defaultValues: { nip: connection?.nip ?? '', password: '' } })

  const onSubmit = handleSubmit(({ nip, password }) => {
    setFormError(null)
    const missing = {
      certificate: !connection && !certificate ? 'Wgraj certyfikat (.crt)' : undefined,
      privateKey: !connection && !privateKey ? 'Wgraj klucz (.key)' : undefined,
    }
    setFileErrors(missing)
    if (missing.certificate || missing.privateKey) return
    saveConnection.mutate(
      { nip, password, certificate, privateKey },
      {
        onSuccess: (result) => {
          setCertificate(undefined)
          setPrivateKey(undefined)
          resetField('password')
          if (result.connection?.testOk) toast.success('Połączono z KSeF')
          else toast.error('Zapisano, ale logowanie do KSeF się nie udało', { description: result.connection?.testMessage ?? undefined })
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            setFileErrors({ certificate: error.fieldErrors.certificate, privateKey: error.fieldErrors.privateKey })
          }
          setFormError(applyServerErrors(error, setError, ['nip', 'password'], {}))
        },
      },
    )
  })

  return (
    <Card className="gap-4 p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">Połączenie z KSeF</h2>
          <p className="text-sm text-muted-foreground">
            Faktury zakupowe firmy (sklep jako nabywca) z produkcyjnego KSeF.
          </p>
        </div>
        {connection && (
          <Button
            variant="outline"
            loading={test.isPending}
            onClick={() =>
              test.mutate(undefined, {
                onSuccess: (result) =>
                  result.connection?.testOk
                    ? toast.success('Połączenie działa')
                    : toast.error('Logowanie do KSeF się nie udało'),
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się sprawdzić połączenia')),
              })
            }
          >
            <ShieldCheckIcon />
            Testuj połączenie
          </Button>
        )}
      </div>

      {connection && <ConnectionStatus connection={connection} />}

      <Form onSubmit={onSubmit} error={formError}>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="NIP firmy"
            inputMode="numeric"
            autoComplete="off"
            hint="Kontekst logowania - NIP, na który przychodzą faktury."
            error={errors.nip?.message}
            {...register('nip', { required: 'Podaj NIP firmy' })}
          />
          <TextField
            label="Hasło do klucza"
            type="password"
            autoComplete="new-password"
            hint={connection ? 'Zostaw puste, żeby zachować zapisane.' : 'Hasło podane przy generowaniu certyfikatu.'}
            error={errors.password?.message}
            {...register('password', { required: connection ? false : 'Podaj hasło do klucza' })}
          />
          <FilePicker
            label="Certyfikat (.crt)"
            accept=".crt,.cer,.pem"
            file={certificate}
            current={connection ? `Zapisany: ${connection.certificateSubject}` : undefined}
            error={fileErrors.certificate}
            onChange={(file) => {
              setCertificate(file)
              setFileErrors((current) => ({ ...current, certificate: undefined }))
            }}
          />
          <FilePicker
            label="Klucz prywatny (.key)"
            accept=".key,.pem"
            file={privateKey}
            current={connection ? 'Zapisany klucz' : undefined}
            error={fileErrors.privateKey}
            onChange={(file) => {
              setPrivateKey(file)
              setFileErrors((current) => ({ ...current, privateKey: undefined }))
            }}
          />
        </div>
        <p className="text-xs text-muted-foreground">
          Hasło jest zapisywane w bazie w postaci zaszyfrowanej i nigdy nie wraca do przeglądarki.
        </p>
        <FormActions>
          <Button type="submit" size="lg" loading={saveConnection.isPending}>
            {connection ? 'Zapisz i sprawdź' : 'Połącz z KSeF'}
          </Button>
        </FormActions>
      </Form>
    </Card>
  )
}

function ConnectionStatus({ connection }: { connection: KsefConnection }) {
  // Chwila z pierwszego renderu wystarczy - liczy się, czy do końca ważności zostało mniej niż 30 dni.
  const [now] = useState(() => Date.now())
  const expiresSoon = new Date(connection.certificateValidTo).getTime() - now < 30 * 24 * 3600 * 1000
  return (
    <dl className="grid gap-x-6 gap-y-3 rounded-xl bg-muted/60 px-4 py-3 text-sm sm:grid-cols-3">
      <div className="grid gap-0.5">
        <dt className="text-xs text-muted-foreground">Certyfikat</dt>
        <dd className="truncate" title={connection.certificateSubject}>
          {connection.certificateSubject}
        </dd>
      </div>
      <div className="grid gap-0.5">
        <dt className="text-xs text-muted-foreground">NIP · ważny do</dt>
        <dd className={cn('tabular-nums', expiresSoon && 'text-amber-700 dark:text-amber-400')}>
          {formatNip(connection.nip)} · {formatDay(connection.certificateValidTo)}
        </dd>
      </div>
      <div className="grid gap-0.5">
        <dt className="text-xs text-muted-foreground">Ostatni test</dt>
        <dd>
          {connection.testedAt == null ? (
            <span className="text-muted-foreground">Nie testowano</span>
          ) : (
            <span
              className={cn(
                'inline-flex items-center gap-1.5',
                connection.testOk ? 'text-emerald-700 dark:text-emerald-400' : 'text-destructive',
              )}
            >
              {connection.testOk ? (
                <CircleCheckIcon aria-hidden className="size-4" />
              ) : (
                <CircleAlertIcon aria-hidden className="size-4" />
              )}
              {connection.testOk ? 'Działa' : 'Błąd'} · {formatDateTime(connection.testedAt)}
            </span>
          )}
        </dd>
      </div>
      {connection.testOk === false && connection.testMessage && (
        <p className="text-sm text-destructive sm:col-span-3">{connection.testMessage}</p>
      )}
    </dl>
  )
}

/** Wybór pliku bez wgrywania - plik idzie na serwer dopiero z całym formularzem. */
function FilePicker({
  label,
  accept,
  file,
  current,
  error,
  onChange,
}: {
  label: string
  accept: string
  file?: File
  current?: string
  error?: string
  onChange: (file: File | undefined) => void
}) {
  const id = useId()
  return (
    <div className="grid gap-1.5">
      <label
        htmlFor={id}
        className={cn(
          'flex h-12 cursor-pointer items-center gap-3 rounded-xl border border-dashed border-input bg-background px-3 transition-[border-color,background-color] duration-150 hover:bg-muted/40 has-focus-visible:border-ring has-focus-visible:ring-3 has-focus-visible:ring-ring/30',
          error && 'border-destructive',
        )}
      >
        <FileKeyIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        <span className="grid min-w-0 flex-1">
          <span className="text-xs text-muted-foreground">{label}</span>
          <span className={cn('truncate text-sm', !file && 'text-muted-foreground')}>
            {file ? file.name : (current ?? 'Wybierz plik')}
          </span>
        </span>
        <input
          id={id}
          type="file"
          accept={accept}
          className="sr-only"
          aria-invalid={error ? true : undefined}
          onChange={(event) => onChange(event.target.files?.[0])}
        />
      </label>
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  )
}

type InternalIdForm = { internalId: string; name: string }

function InternalIdsCard({ storeId, settings }: { storeId: string; settings: KsefSettings }) {
  const { addInternalId, removeInternalId, restoreInternalId } = useKsefSettingsMutations(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<InternalIdForm>({ defaultValues: { internalId: '', name: '' } })
  const suggestions = settings.seenInternalIds.filter((seen) => !seen.assigned)

  const add = (internalId: string, name: string) =>
    addInternalId.mutate(
      { internalId, name },
      {
        onSuccess: () => {
          reset()
          setFormError(null)
          toast.success(`Dodano IDWew ${internalId}`)
        },
        onError: (error) => setFormError(applyServerErrors(error, setError, ['internalId', 'name'])),
      },
    )

  const remove = (id: string, internalId: string) =>
    removeInternalId.mutate(id, {
      onSuccess: () =>
        toast.success(`Usunięto IDWew ${internalId}`, {
          description: 'Faktury z nim znikną z listy sklepu.',
          action: {
            label: 'Przywróć',
            onClick: () =>
              restoreInternalId.mutate(id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić')),
              }),
          },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć')),
    })

  return (
    <Card className="gap-4 p-5">
      <div>
        <h2 className="text-base font-semibold">IDWew sklepu</h2>
        <p className="text-sm text-muted-foreground">
          Sklep widzi faktury z tymi identyfikatorami wewnętrznymi i wszystkie faktury bez IDWew. Faktury z innym IDWew
          należą do innych sklepów.
        </p>
      </div>

      {settings.internalIds.length > 0 && (
        <ul className="divide-y rounded-xl border">
          {settings.internalIds.map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
              <span className="min-w-0">
                <span className="block font-medium tabular-nums">{item.internalId}</span>
                {item.name && <span className="block truncate text-sm text-muted-foreground">{item.name}</span>}
              </span>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń IDWew ${item.internalId}`}
                onClick={() => remove(item.id, item.internalId)}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {suggestions.length > 0 && (
        <div className="grid gap-2">
          <h3 className="text-sm font-medium">IDWew z pobranych faktur</h3>
          <ul className="grid gap-1.5">
            {suggestions.map((seen) => (
              <li
                key={seen.internalId}
                className="flex items-center justify-between gap-3 rounded-xl bg-muted/60 px-3 py-2 text-sm"
              >
                <span className="min-w-0">
                  <span className="font-medium tabular-nums">{seen.internalId}</span>
                  <span className="text-muted-foreground">
                    {seen.name && ` · ${seen.name}`} · {seen.invoiceCount} {invoicesWord(seen.invoiceCount)}
                  </span>
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  loading={addInternalId.isPending && addInternalId.variables?.internalId === seen.internalId}
                  onClick={() => add(seen.internalId, seen.name ?? '')}
                >
                  <PlusIcon />
                  Dodaj
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <Form
        onSubmit={handleSubmit(({ internalId, name }) => add(internalId.trim(), name))}
        error={formError}
        className="sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:items-start"
      >
        <TextField
          label="IDWew"
          autoComplete="off"
          hint="NIP firmy, myślnik i 5 cyfr, np. 5260001234-00001."
          error={errors.internalId?.message}
          {...register('internalId', {
            required: 'Podaj IDWew',
            pattern: { value: /^\s*\d{10}-\d{5}\s*$/, message: 'NIP, myślnik i 5 cyfr' },
          })}
        />
        <TextField label="Opis (opcjonalnie)" autoComplete="off" error={errors.name?.message} {...register('name')} />
        <Button type="submit" variant="outline" size="lg" className="sm:mt-0.5 sm:h-11" loading={addInternalId.isPending}>
          <PlusIcon />
          Dodaj IDWew
        </Button>
      </Form>
    </Card>
  )
}

type SupplierForm = { nip: string; name: string }

function SkippedSuppliersCard({ storeId, settings }: { storeId: string; settings: KsefSettings }) {
  const { addSkippedSupplier, removeSkippedSupplier, restoreSkippedSupplier } = useKsefSettingsMutations(storeId)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<SupplierForm>({ defaultValues: { nip: '', name: '' } })

  const onSubmit = handleSubmit((values) =>
    addSkippedSupplier.mutate(values, {
      onSuccess: () => {
        reset()
        setFormError(null)
        toast.success('Dodano dostawcę do pomijanych')
      },
      onError: (error) => setFormError(applyServerErrors(error, setError, ['nip', 'name'])),
    }),
  )

  const remove = (id: string, label: string) =>
    removeSkippedSupplier.mutate(id, {
      onSuccess: () =>
        toast.success(`Faktury ${label} znów będą na liście`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restoreSkippedSupplier.mutate(id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić')),
              }),
          },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć')),
    })

  return (
    <Card className="gap-4 p-5">
      <div>
        <h2 className="text-base font-semibold">Pomijani dostawcy</h2>
        <p className="text-sm text-muted-foreground">
          Faktury tych dostawców (np. media, leasing) nie pokazują się na liście - widać je po włączeniu „Pokaż
          pominiętych dostawców”. Dostawcę dodasz też z menu faktury.
        </p>
      </div>

      {settings.skippedSuppliers.length > 0 && (
        <ul className="divide-y rounded-xl border">
          {settings.skippedSuppliers.map((supplier) => (
            <li key={supplier.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
              <span className="min-w-0">
                <span className="block truncate font-medium">{supplier.name ?? 'Bez nazwy'}</span>
                <span className="block text-sm text-muted-foreground tabular-nums">NIP {formatNip(supplier.nip)}</span>
              </span>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Przestań pomijać ${supplier.name ?? supplier.nip}`}
                onClick={() => remove(supplier.id, supplier.name ?? formatNip(supplier.nip))}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}

      <Form onSubmit={onSubmit} error={formError} className="sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] sm:items-start">
        <TextField
          label="NIP dostawcy"
          inputMode="numeric"
          autoComplete="off"
          error={errors.nip?.message}
          {...register('nip', { required: 'Podaj NIP dostawcy' })}
        />
        <TextField label="Nazwa (opcjonalnie)" autoComplete="off" error={errors.name?.message} {...register('name')} />
        <Button type="submit" variant="outline" size="lg" className="sm:mt-0.5 sm:h-11" loading={addSkippedSupplier.isPending}>
          <PlusIcon />
          Pomijaj
        </Button>
      </Form>
    </Card>
  )
}
