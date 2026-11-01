import { useEffect, useRef, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useSearchParams } from 'react-router'
import { CloudIcon, DatabaseBackupIcon, LogOutIcon, RefreshCwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SelectField, SwitchField, TextField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useBackup, useConnectGoogle, useDisconnectGoogle, useRunBackup, useSaveBackup } from '@/features/settings/api'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { BackupRun, BackupSettings, BackupStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateTime = new Intl.DateTimeFormat('pl-PL', {
  dateStyle: 'short',
  timeStyle: 'short',
})
const date = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

const HOURS = Array.from({ length: 24 }, (_, hour) => ({
  value: String(hour),
  label: `${String(hour).padStart(2, '0')}:00`,
}))

const STATUS: Record<BackupStatus, { label: string; className: string }> = {
  RUNNING: {
    label: 'W trakcie',
    className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  SUCCEEDED: {
    label: 'Na Dysku',
    className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  FAILED: {
    label: 'Nieudana',
    className: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

/** Kopia zapasowa całej bazy na Dysk Google właściciela: połączenie, harmonogram, hasło szyfrowania, historia. */
export function BackupPage() {
  const { data, isError, refetch } = useBackup()
  const run = useRunBackup()
  useGoogleResult()
  useFinishedToast(data)

  const start = async () => {
    try {
      await run.mutateAsync()
      toast.success('Kopia ruszyła', {
        description: 'Robi się w tle, możesz zamknąć tę stronę.',
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się zacząć kopii'))
    }
  }

  return (
    <>
      <PageHeader
        title="Kopia zapasowa"
        description="Kopia całej bazy, zaszyfrowana Twoim hasłem, codziennie na Twoim Dysku Google."
        actions={
          data && (
            <Button
              size="lg"
              onClick={start}
              loading={run.isPending || data.running}
              disabled={!data.connected || !data.passwordSet}
            >
              <DatabaseBackupIcon />
              {data.running ? 'Kopia w trakcie' : 'Zrób kopię teraz'}
            </Button>
          )
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać ustawień kopii"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : !data ? (
        <div aria-hidden className="h-72 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className="grid gap-6">
          <div className="grid items-start gap-6 lg:grid-cols-2">
            <GoogleCard settings={data} />
            <ScheduleForm key={`${data.passwordSetAt}-${data.enabled}-${data.connected}`} settings={data} />
          </div>
          <History runs={data.runs} />
          <RestoreHelp />
        </div>
      )}
    </>
  )
}

/** Wynik powrotu z okna zgody Google (`?google=polaczono|blad&komunikat=`): toast i czysty adres. */
function useGoogleResult() {
  const [params, setParams] = useSearchParams()
  const result = params.get('google')
  const message = params.get('komunikat')
  useEffect(() => {
    if (!result) return
    if (result === 'polaczono') {
      toast.success('Połączono z Dyskiem Google')
    } else {
      toast.error('Nie połączono z Dyskiem Google', {
        description: message ?? undefined,
      })
    }
    setParams({}, { replace: true })
  }, [result, message, setParams])
}

/** Toast po końcu kopii, którą widać było w trakcie. */
function useFinishedToast(data: BackupSettings | undefined) {
  const wasRunning = useRef(false)
  useEffect(() => {
    if (!data) return
    if (wasRunning.current && !data.running) {
      const last = data.runs[0]
      if (last?.status === 'SUCCEEDED') {
        toast.success('Kopia jest na Dysku Google')
      } else if (last?.status === 'FAILED') {
        toast.error('Kopia się nie udała', {
          description: last.error ?? undefined,
        })
      }
    }
    wasRunning.current = data.running
  }, [data])
}

function GoogleCard({ settings }: { settings: BackupSettings }) {
  const connect = useConnectGoogle()
  const disconnect = useDisconnectGoogle()

  const startConnect = () =>
    connect.mutate(undefined, {
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się połączyć')),
    })

  const stop = async () => {
    try {
      await disconnect.mutateAsync()
      toast.success('Odłączono Dysk Google', {
        description: 'Kopie automatyczne są wyłączone.',
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się odłączyć'))
    }
  }

  return (
    <Card className="gap-3 p-4">
      <div className="flex items-center gap-2">
        <CloudIcon className="size-5 text-muted-foreground" aria-hidden />
        <h2 className="font-medium">Dysk Google</h2>
      </div>
      {!settings.googleConfigured ? (
        <p className="text-sm text-muted-foreground">
          Serwer nie ma jeszcze danych aplikacji Google (<code>GOOGLE_CLIENT_ID</code>,{' '}
          <code>GOOGLE_CLIENT_SECRET</code>). Jak je przygotować, opisuje <code>deploy/README.md</code>, sekcja 4a.
        </p>
      ) : settings.connected ? (
        <>
          <div>
            <p className="font-medium break-all">{settings.googleEmail}</p>
            {settings.googleConnectedAt && (
              <p className="text-xs text-muted-foreground">
                Połączone {date.format(new Date(settings.googleConnectedAt))}
                {settings.googleConnectedBy && ` przez ${settings.googleConnectedBy}`}. Kopie trafiają do folderu
                „Intermanage - kopie zapasowe”, a aplikacja widzi tylko pliki, które sama tam wgrała.
              </p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={startConnect} loading={connect.isPending}>
              <RefreshCwIcon />
              Połącz inne konto
            </Button>
            <Button variant="ghost" onClick={stop} loading={disconnect.isPending}>
              <LogOutIcon />
              Odłącz
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-sm text-muted-foreground">
            Zaloguj się kontem Google, na którego Dysk mają trafiać kopie. Aplikacja dostanie dostęp tylko do plików,
            które sama utworzy, nie do reszty Dysku.
          </p>
          <div>
            <Button size="lg" onClick={startConnect} loading={connect.isPending}>
              <CloudIcon />
              Połącz z Google
            </Button>
          </div>
        </>
      )}
    </Card>
  )
}

type Values = {
  enabled: boolean
  runHour: string
  keepCount: string
  password: string
}

function ScheduleForm({ settings }: { settings: BackupSettings }) {
  const save = useSaveBackup()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    control,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<Values>({
    defaultValues: {
      enabled: settings.enabled,
      runHour: String(settings.runHour),
      keepCount: String(settings.keepCount),
      password: '',
    },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await save.mutateAsync({
        enabled: values.enabled,
        runHour: Number(values.runHour),
        keepCount: Number(values.keepCount),
        password: values.password || null,
      })
      reset({ ...values, password: '' })
      toast.success('Zapisano ustawienia kopii')
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(applyServerErrors(error, setError, ['runHour', 'keepCount', 'password']))
      } else {
        setFormError('Nie udało się zapisać ustawień')
      }
    }
  })

  return (
    <Card className="p-4">
      <Form onSubmit={submit} error={formError}>
        <Controller
          control={control}
          name="enabled"
          render={({ field }) => (
            <SwitchField
              label="Codzienna kopia automatyczna"
              description={
                settings.connected
                  ? 'Nieudana kopia ponawia się co godzinę, najwyżej 3 razy, a właściciele dostają maila.'
                  : 'Najpierw połącz Dysk Google.'
              }
              checked={field.value}
              onCheckedChange={field.onChange}
              disabled={!settings.connected}
            />
          )}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <Controller
            control={control}
            name="runHour"
            render={({ field }) => (
              <SelectField
                label="Godzina kopii"
                options={HOURS}
                value={field.value}
                onChange={field.onChange}
                onBlur={field.onBlur}
                error={errors.runHour?.message}
                hint="Najlepiej w nocy, gdy nikt nie pracuje."
              />
            )}
          />
          <TextField
            label="Ile kopii trzymać"
            type="number"
            inputMode="numeric"
            min={1}
            max={365}
            error={errors.keepCount?.message}
            hint="Starsze trafiają do kosza Dysku."
            {...register('keepCount', {
              required: 'Podaj liczbę kopii',
              min: { value: 1, message: 'Co najmniej 1 kopia' },
              max: { value: 365, message: 'Najwyżej 365 kopii' },
            })}
          />
        </div>
        <TextField
          label={settings.passwordSet ? 'Nowe hasło szyfrowania' : 'Hasło szyfrowania'}
          type="password"
          autoComplete="new-password"
          error={errors.password?.message}
          hint={
            settings.passwordSet
              ? `Ustawione ${settings.passwordSetAt ? date.format(new Date(settings.passwordSetAt)) : ''}${
                  settings.passwordSetBy ? ` przez ${settings.passwordSetBy}` : ''
                }. Puste pole = bez zmian. Nowe hasło dotyczy kolejnych kopii, starsze odtworzysz starym.`
              : 'Co najmniej 12 znaków. Zapisz je poza aplikacją: bez niego kopii nie da się odtworzyć.'
          }
          {...register('password', {
            validate: (value) => !value || value.length >= 12 || 'Hasło musi mieć co najmniej 12 znaków',
          })}
        />
        {settings.nextRunAt && (
          <FormNote>Następna kopia automatyczna: {dateTime.format(new Date(settings.nextRunAt))}.</FormNote>
        )}
        <FormActions>
          <Button type="submit" size="xl" loading={isSubmitting} disabled={!isDirty}>
            Zapisz
          </Button>
        </FormActions>
      </Form>
    </Card>
  )
}

function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} kB`
  if (bytes < 1024 * 1024 * 1024)
    return `${(bytes / 1024 / 1024).toLocaleString('pl-PL', { maximumFractionDigits: 1 })} MB`
  return `${(bytes / 1024 / 1024 / 1024).toLocaleString('pl-PL', { maximumFractionDigits: 2 })} GB`
}

function History({ runs }: { runs: BackupRun[] | undefined }) {
  return (
    <section className="grid gap-2">
      <h2 className="font-medium">Historia kopii</h2>
      {runs && runs.length === 0 ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="text-sm text-muted-foreground">Nie było jeszcze żadnej kopii.</p>
        </Card>
      ) : (
        <Card className="py-0">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Początek</TableHead>
                <TableHead>Rodzaj</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Rozmiar</TableHead>
                <TableHead className="pr-4">Plik</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {!runs ? (
                <TableSkeletonRows columns={5} />
              ) : (
                runs.map((run) => (
                  <TableRow key={run.id}>
                    <TableCell className="py-3 pl-4 tabular-nums">{dateTime.format(new Date(run.startedAt))}</TableCell>
                    <TableCell>
                      {run.manual ? `Ręczna${run.startedBy ? ` · ${run.startedBy}` : ''}` : 'Automatyczna'}
                    </TableCell>
                    <TableCell>
                      <span
                        className={cn(
                          'inline-flex rounded-md px-2 py-0.5 text-xs font-medium',
                          STATUS[run.status].className,
                        )}
                      >
                        {run.trashedAt ? 'W koszu Dysku' : STATUS[run.status].label}
                      </span>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {run.sizeBytes != null ? formatSize(run.sizeBytes) : '–'}
                    </TableCell>
                    <TableCell className="max-w-md pr-4 whitespace-normal">
                      {run.error ? (
                        <span className="text-sm text-red-700 dark:text-red-300">{run.error}</span>
                      ) : (
                        <span className="text-sm break-all">
                          {run.fileName ?? '–'}
                          {run.googleEmail && (
                            <span className="block text-xs text-muted-foreground">{run.googleEmail}</span>
                          )}
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}
    </section>
  )
}

function RestoreHelp() {
  return (
    <Card className="gap-2 p-4">
      <h2 className="font-medium">Jak odtworzyć bazę z kopii</h2>
      <p className="text-sm text-muted-foreground">
        Pobierz plik z folderu „Intermanage - kopie zapasowe” na Dysku i na komputerze z programami <code>gpg</code> i
        PostgreSQL 18 odszyfruj go hasłem kopii, a potem wgraj do nowej bazy:
      </p>
      <pre className="overflow-x-auto rounded-lg bg-muted p-3 text-xs leading-relaxed">
        {`gpg --decrypt intermanage_RRRR-MM-DD_GGMM.dump.gpg > baza.dump
createdb -h <serwer bazy> -U postgres intermanage_restore
pg_restore -h <serwer bazy> -U postgres --no-owner -d intermanage_restore baza.dump`}
      </pre>
    </Card>
  )
}
