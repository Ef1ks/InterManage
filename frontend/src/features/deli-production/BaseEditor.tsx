import { useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { Form, FormActions, SelectField, TextareaField, TextField } from '@/components/form'
import { ImportError } from '@/components/ImportError'
import { SearchInput } from '@/components/SearchInput'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { useBaseMutations, useProductionBase, type ArticleForm, type SchemaForm } from '@/features/deli-production/api'
import { matchesName, schemaSummary } from '@/features/deli-production/format'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import type { ProductionArticle, ProductionBase, ProductionSchema, StoreSummary } from '@/lib/types'

const ALL = 'all'

const errorToast = (fallback: string) => (error: unknown) =>
  toast.error(error instanceof ApiError ? error.message : fallback)

/**
 * Baza kart produkcji: schematy parametrów procesu (artykuły mają kilka powtarzających się zestawów, więc wybierają
 * schemat), artykuły z kodem do skanowania, pieczątka na kartach i wgranie bazy z dawnego arkusza.
 */
export function BaseEditor({ store }: { store: StoreSummary }) {
  const canEdit = useCan('DELI_PRODUCTION', 'EDIT')
  const { data: base, isPending, isError, refetch } = useProductionBase(store.id)
  const mutations = useBaseMutations(store.id)
  const [schemaDialog, setSchemaDialog] = useState<{ schema?: ProductionSchema } | null>(null)
  const [articleDialog, setArticleDialog] = useState<{ article?: ProductionArticle } | null>(null)

  if (isError) {
    return (
      <p className="py-10 text-center text-sm text-muted-foreground">
        Nie udało się wczytać bazy.{' '}
        <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </p>
    )
  }

  return (
    <div className="space-y-8">
      {canEdit && <ImportPanel storeId={store.id} empty={base?.articles.length === 0} />}

      <SchemasSection
        base={base}
        canEdit={canEdit}
        onEdit={(schema) => setSchemaDialog({ schema })}
        onCreate={() => setSchemaDialog({})}
        onDelete={(schema) =>
          mutations.deleteSchema.mutate(schema.id, {
            onSuccess: () =>
              toast.success(`Usunięto schemat ${schema.name}`, {
                action: {
                  label: 'Przywróć',
                  onClick: () =>
                    mutations.restoreSchema.mutate(schema.id, { onError: errorToast('Nie udało się przywrócić') }),
                },
              }),
            onError: errorToast('Nie udało się usunąć schematu'),
          })
        }
      />

      <ArticlesSection
        base={base}
        loading={isPending}
        canEdit={canEdit}
        mutations={mutations}
        onEdit={(article) => setArticleDialog({ article })}
        onCreate={() => setArticleDialog({})}
      />

      {base && <StampSection key={base.stamp} base={base} canEdit={canEdit} mutations={mutations} />}

      <Dialog open={schemaDialog !== null} onOpenChange={(open) => !open && setSchemaDialog(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {schemaDialog && (
            <SchemaFormBody
              schema={schemaDialog.schema}
              onSubmit={(form) => mutations.saveSchema.mutateAsync({ id: schemaDialog.schema?.id, form })}
              onDone={() => setSchemaDialog(null)}
            />
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={articleDialog !== null} onOpenChange={(open) => !open && setArticleDialog(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {articleDialog && base && (
            <ArticleFormBody
              article={articleDialog.article}
              schemas={base.schemas}
              onSubmit={(form) => mutations.saveArticle.mutateAsync({ id: articleDialog.article?.id, form })}
              onDone={() => setArticleDialog(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function ImportPanel({ storeId, empty }: { storeId: string; empty: boolean }) {
  const { importBase } = useBaseMutations(storeId)
  const [file, setFile] = useState<File>()

  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-base font-semibold">Wgraj bazę z Excela</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Arkusz „Baza” z dawnego pliku: Ean, Nazwa, Nomenklatura, Temperatura pieczenia, Czas schładzania, Temperatura
          schładzania, Czas (ważność w dniach), Wewnętrzna. Artykuły o tym samym kodzie są nadpisywane, a parametry
          trafiają do schematów o tych samych wartościach.
        </p>
      </div>
      <FileDropZone
        accept={['.xlsx', '.xlsm']}
        pendingFile={importBase.isPending ? file : undefined}
        title={empty ? 'Przeciągnij plik z bazą albo kliknij' : 'Wgraj bazę ponownie'}
        hint=".xlsx albo .xlsm"
        className={empty ? undefined : 'py-5'}
        onReject={(name) => toast.error(`${name}: obsługujemy pliki .xlsx i .xlsm`)}
        onFile={(chosen) => {
          setFile(chosen)
          importBase.mutate(chosen, {
            onSuccess: (result) =>
              toast.success('Wgrano bazę', {
                description: `Nowe: ${result.added}, zmienione: ${result.updated}, bez zmian: ${result.unchanged}, nowe schematy: ${result.schemasCreated}`,
              }),
          })
        }}
      />
      {importBase.error && <ImportError error={importBase.error} />}
    </section>
  )
}

function SchemasSection({
  base,
  canEdit,
  onEdit,
  onCreate,
  onDelete,
}: {
  base: ProductionBase | undefined
  canEdit: boolean
  onEdit: (schema: ProductionSchema) => void
  onCreate: () => void
  onDelete: (schema: ProductionSchema) => void
}) {
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">
            Schematy{' '}
            {base && <span className="font-normal text-muted-foreground tabular-nums">{base.schemas.length}</span>}
          </h2>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Parametry procesu na karcie. Zmiana schematu dotyczy nowych kart; zapisane karty zostają bez zmian.
          </p>
        </div>
        {canEdit && (
          <Button variant="outline" onClick={onCreate}>
            <PlusIcon />
            Nowy schemat
          </Button>
        )}
      </div>
      <ul className="divide-y rounded-xl border bg-card">
        {base?.schemas.length === 0 && (
          <li className="px-4 py-8 text-center text-sm text-muted-foreground">
            Nie ma jeszcze schematów. Dodaj pierwszy albo wgraj bazę z Excela.
          </li>
        )}
        {base?.schemas.map((schema) => (
          <li key={schema.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="font-medium">{schema.name}</div>
              <div className="text-sm text-muted-foreground">{schemaSummary(schema)}</div>
            </div>
            <span className="text-sm text-muted-foreground tabular-nums">
              {schema.articleCount === 1 ? '1 artykuł' : `${schema.articleCount} art.`}
            </span>
            {canEdit && (
              <div className="flex gap-1">
                <Button variant="ghost" size="icon" aria-label={`Zmień schemat ${schema.name}`} title="Zmień" onClick={() => onEdit(schema)}>
                  <PencilIcon />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Usuń schemat ${schema.name}`}
                  title={schema.articleCount > 0 ? 'Najpierw wybierz artykułom inny schemat' : 'Usuń'}
                  disabled={schema.articleCount > 0}
                  onClick={() => onDelete(schema)}
                >
                  <Trash2Icon />
                </Button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

function ArticlesSection({
  base,
  loading,
  canEdit,
  mutations,
  onEdit,
  onCreate,
}: {
  base: ProductionBase | undefined
  loading: boolean
  canEdit: boolean
  mutations: ReturnType<typeof useBaseMutations>
  onEdit: (article: ProductionArticle) => void
  onCreate: () => void
}) {
  const [query, setQuery] = useState('')
  const [schemaFilter, setSchemaFilter] = useState(ALL)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkSchema, setBulkSchema] = useState('')

  const schemaItems = useMemo(
    () => (base?.schemas ?? []).map((schema) => ({ value: schema.id, label: schema.name })),
    [base],
  )
  const schemaName = useMemo(() => new Map(schemaItems.map((item) => [item.value, item.label])), [schemaItems])

  const visible = useMemo(
    () =>
      (base?.articles ?? []).filter(
        (article) =>
          (schemaFilter === ALL || article.schemaId === schemaFilter) &&
          (query.trim() === '' || matchesName(article, query) || article.code?.includes(query.trim())),
      ),
    [base, query, schemaFilter],
  )
  const selectedVisible = visible.filter((article) => selected.has(article.id))
  const allSelected = visible.length > 0 && selectedVisible.length === visible.length

  const toggle = (id: string) =>
    setSelected((current) => {
      const copy = new Set(current)
      if (copy.has(id)) copy.delete(id)
      else copy.add(id)
      return copy
    })

  const changeSchema = (articleIds: string[], schemaId: string, onDone?: () => void) =>
    mutations.changeSchema.mutate(
      { articleIds, schemaId },
      {
        onSuccess: () => {
          toast.success(
            articleIds.length === 1 ? 'Zmieniono schemat' : `Ustawiono schemat ${articleIds.length} artykułom`,
          )
          onDone?.()
        },
        onError: errorToast('Nie udało się zmienić schematu'),
      },
    )

  const remove = (article: ProductionArticle) =>
    mutations.deleteArticle.mutate(article.id, {
      onSuccess: () =>
        toast.success(`Usunięto ${article.name}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              mutations.restoreArticle.mutate(article.id, { onError: errorToast('Nie udało się przywrócić') }),
          },
        }),
      onError: errorToast('Nie udało się usunąć artykułu'),
    })

  const filterItems = [{ value: ALL, label: 'Wszystkie schematy' }, ...schemaItems]
  const columns = canEdit ? 6 : 4

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">
            Artykuły{' '}
            {base && <span className="font-normal text-muted-foreground tabular-nums">{base.articles.length}</span>}
          </h2>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Kod trafia na formularz jako kod kreskowy, więc to on jest skanowany.
          </p>
        </div>
        {canEdit && (
          <Button variant="outline" onClick={onCreate} disabled={!base || base.schemas.length === 0}>
            <PlusIcon />
            Nowy artykuł
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <SearchInput className="w-full sm:w-80" value={query} onChange={setQuery} placeholder="Szukaj: nazwa albo kod" />
        <Select items={filterItems} value={schemaFilter} onValueChange={(value) => value && setSchemaFilter(value)}>
          <SelectTrigger aria-label="Schemat" className="h-9 max-w-full sm:max-w-96">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {filterItems.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {canEdit && selectedVisible.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-sky-300 bg-sky-100 px-4 py-2.5 text-sm text-sky-950 dark:border-sky-500/40 dark:bg-sky-950/60 dark:text-sky-100">
          <span className="font-medium tabular-nums">Zaznaczone: {selectedVisible.length}</span>
          <Select items={schemaItems} value={bulkSchema} onValueChange={(value) => value && setBulkSchema(value)}>
            <SelectTrigger aria-label="Schemat dla zaznaczonych" className="h-9 max-w-full bg-background sm:max-w-96">
              <SelectValue placeholder="Wybierz schemat" />
            </SelectTrigger>
            <SelectContent>
              {schemaItems.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            disabled={!bulkSchema}
            loading={mutations.changeSchema.isPending}
            onClick={() =>
              changeSchema(
                selectedVisible.map((article) => article.id),
                bulkSchema,
                () => setSelected(new Set()),
              )
            }
          >
            Ustaw schemat
          </Button>
          <Button variant="ghost" onClick={() => setSelected(new Set())}>
            Odznacz
          </Button>
        </div>
      )}

      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              {canEdit && (
                <TableHead className="w-10 pl-4">
                  <input
                    type="checkbox"
                    aria-label="Zaznacz wszystkie widoczne"
                    className="size-4 cursor-pointer accent-foreground"
                    checked={allSelected}
                    ref={(input) => {
                      if (input) input.indeterminate = selectedVisible.length > 0 && !allSelected
                    }}
                    onChange={() =>
                      setSelected((current) => {
                        const copy = new Set(current)
                        visible.forEach((article) => (allSelected ? copy.delete(article.id) : copy.add(article.id)))
                        return copy
                      })
                    }
                  />
                </TableHead>
              )}
              <TableHead>Nazwa</TableHead>
              <TableHead>Kod</TableHead>
              <TableHead>Nomenklatura</TableHead>
              <TableHead>Schemat</TableHead>
              {canEdit && (
                <TableHead className="w-24">
                  <span className="sr-only">Działania</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading && <TableSkeletonRows columns={columns} />}
            {base && visible.length === 0 && (
              <TableRow>
                <TableCell colSpan={columns} className="py-10 text-center text-sm text-muted-foreground">
                  {base.articles.length === 0 ? 'Baza nie ma artykułów.' : 'Żaden artykuł nie pasuje do wyszukiwania.'}
                </TableCell>
              </TableRow>
            )}
            {visible.map((article) => (
              <TableRow key={article.id} data-state={selected.has(article.id) ? 'selected' : undefined}>
                {canEdit && (
                  <TableCell className="pl-4">
                    <input
                      type="checkbox"
                      aria-label={`Zaznacz ${article.name}`}
                      className="size-4 cursor-pointer accent-foreground"
                      checked={selected.has(article.id)}
                      onChange={() => toggle(article.id)}
                    />
                  </TableCell>
                )}
                <TableCell className="font-medium">{article.name}</TableCell>
                <TableCell className="tabular-nums">
                  {article.code ?? <span className="text-muted-foreground">bez kodu</span>}
                </TableCell>
                <TableCell className="text-muted-foreground tabular-nums">{article.nomenclature ?? '–'}</TableCell>
                <TableCell className="max-w-96">
                  {canEdit ? (
                    <Select
                      items={schemaItems}
                      value={article.schemaId}
                      onValueChange={(value) => value && value !== article.schemaId && changeSchema([article.id], value)}
                    >
                      <SelectTrigger aria-label={`Schemat: ${article.name}`} className="h-8 w-full max-w-96 border-transparent bg-transparent hover:bg-muted">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {schemaItems.map((item) => (
                          <SelectItem key={item.value} value={item.value}>
                            {item.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  ) : (
                    <span className="text-sm">{schemaName.get(article.schemaId)}</span>
                  )}
                </TableCell>
                {canEdit && (
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" aria-label={`Zmień ${article.name}`} title="Zmień" onClick={() => onEdit(article)}>
                        <PencilIcon />
                      </Button>
                      <Button variant="ghost" size="icon" aria-label={`Usuń ${article.name}`} title="Usuń" onClick={() => remove(article)}>
                        <Trash2Icon />
                      </Button>
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </section>
  )
}

function StampSection({
  base,
  canEdit,
  mutations,
}: {
  base: ProductionBase
  canEdit: boolean
  mutations: ReturnType<typeof useBaseMutations>
}) {
  const [stamp, setStamp] = useState(base.customStamp ? base.stamp : '')

  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-base font-semibold">Pieczątka na karcie</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Lewy górny róg karty, pod „Pieczątka zakładu pracy:”. Puste pole = nazwa sklepu i NIP.
        </p>
      </div>
      <div className="grid gap-3 rounded-xl border bg-card p-5 sm:grid-cols-[1fr_auto] sm:items-end">
        <TextareaField
          label="Pieczątka (każda linia osobno)"
          rows={4}
          value={stamp}
          disabled={!canEdit}
          onChange={(event) => setStamp(event.target.value)}
          hint={base.customStamp ? undefined : `Teraz: ${base.stamp.replaceAll('\n', ' · ')}`}
        />
        {canEdit && (
          <Button
            loading={mutations.saveStamp.isPending}
            disabled={stamp.trim() === (base.customStamp ? base.stamp : '')}
            onClick={() =>
              mutations.saveStamp.mutate(stamp, {
                onSuccess: () => toast.success('Zapisano pieczątkę'),
                onError: errorToast('Nie udało się zapisać pieczątki'),
              })
            }
          >
            Zapisz pieczątkę
          </Button>
        )}
      </div>
    </section>
  )
}

type SchemaFields = Record<
  'name' | 'bakingTemperature' | 'internalTemperature' | 'coolingTime' | 'coolingTemperature' | 'shelfLifeDays',
  string
>

const SCHEMA_NUMBERS: { field: Exclude<keyof SchemaFields, 'name'>; label: string; min: number; max: number }[] = [
  { field: 'bakingTemperature', label: 'Temperatura pieczenia (°C)', min: 0, max: 400 },
  { field: 'internalTemperature', label: 'Temperatura wewnętrzna po wypieku (°C)', min: 0, max: 200 },
  { field: 'coolingTime', label: 'Czas schładzania', min: 0, max: 1440 },
  { field: 'coolingTemperature', label: 'Temperatura schładzania (°C)', min: -40, max: 100 },
  { field: 'shelfLifeDays', label: 'Ważność (dni)', min: 0, max: 365 },
]

function SchemaFormBody({
  schema,
  onSubmit,
  onDone,
}: {
  schema?: ProductionSchema
  onSubmit: (form: SchemaForm) => Promise<unknown>
  onDone: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const text = (value: number | null | undefined) => (value == null ? '' : String(value))
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SchemaFields>({
    defaultValues: {
      name: schema?.name ?? '',
      bakingTemperature: text(schema?.bakingTemperature),
      internalTemperature: text(schema?.internalTemperature),
      coolingTime: text(schema?.coolingTime),
      coolingTemperature: text(schema?.coolingTemperature),
      shelfLifeDays: text(schema?.shelfLifeDays),
    },
  })
  const number = (value: string) => (value.trim() === '' ? null : Number(value))

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await onSubmit({
        name: values.name.trim(),
        bakingTemperature: number(values.bakingTemperature),
        internalTemperature: number(values.internalTemperature),
        coolingTime: number(values.coolingTime),
        coolingTemperature: number(values.coolingTemperature),
        shelfLifeDays: number(values.shelfLifeDays),
      })
      toast.success(schema ? 'Zapisano schemat' : 'Dodano schemat')
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['name', ...SCHEMA_NUMBERS.map((item) => item.field)]))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{schema ? 'Zmień schemat' : 'Nowy schemat'}</DialogTitle>
        <DialogDescription>Puste pole = na karcie „-”. Zapisane karty zostają bez zmian.</DialogDescription>
      </DialogHeader>
      <TextField
        label="Nazwa"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          validate: (value) => value.trim() !== '' || 'Wpisz nazwę',
          maxLength: { value: 100, message: 'Najwyżej 100 znaków' },
        })}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        {SCHEMA_NUMBERS.map(({ field, label, min, max }) => (
          <TextField
            key={field}
            label={label}
            inputMode="numeric"
            autoComplete="off"
            error={errors[field]?.message}
            {...register(field, {
              validate: (value) => {
                if (value.trim() === '') return true
                if (!/^-?\d+$/.test(value.trim())) return 'Liczba całkowita'
                const parsed = Number(value)
                return (parsed >= min && parsed <= max) || `Od ${min} do ${max}`
              },
            })}
          />
        ))}
      </div>
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          {schema ? 'Zapisz' : 'Dodaj schemat'}
        </Button>
      </FormActions>
    </Form>
  )
}

function ArticleFormBody({
  article,
  schemas,
  onSubmit,
  onDone,
}: {
  article?: ProductionArticle
  schemas: ProductionSchema[]
  onSubmit: (form: ArticleForm) => Promise<unknown>
  onDone: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<{ code: string; name: string; nomenclature: string; schemaId: string }>({
    defaultValues: {
      code: article?.code ?? '',
      name: article?.name ?? '',
      nomenclature: article?.nomenclature ?? '',
      schemaId: article?.schemaId ?? '',
    },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await onSubmit({
        code: values.code.trim() || null,
        name: values.name.trim(),
        nomenclature: values.nomenclature.trim() || null,
        schemaId: values.schemaId,
      })
      toast.success(article ? 'Zapisano artykuł' : 'Dodano artykuł')
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, ['code', 'name', 'nomenclature', 'schemaId']))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{article ? 'Zmień artykuł' : 'Nowy artykuł'}</DialogTitle>
        <DialogDescription>Kod drukuje się na formularzu i jest skanowany przy wpisywaniu karty.</DialogDescription>
      </DialogHeader>
      <TextField
        label="Nazwa na karcie"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', {
          validate: (value) => value.trim() !== '' || 'Wpisz nazwę',
          maxLength: { value: 200, message: 'Najwyżej 200 znaków' },
        })}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="Kod (EAN)"
          inputMode="numeric"
          autoComplete="off"
          hint="Pusty = wybór tylko po nazwie"
          error={errors.code?.message}
          {...register('code', {
            validate: (value) => /^[0-9A-Za-z]{0,20}$/.test(value.trim()) || 'Cyfry albo litery, do 20 znaków',
          })}
        />
        <TextField
          label="Nomenklatura"
          autoComplete="off"
          error={errors.nomenclature?.message}
          {...register('nomenclature', { maxLength: { value: 20, message: 'Najwyżej 20 znaków' } })}
        />
      </div>
      <Controller
        control={control}
        name="schemaId"
        rules={{ required: 'Wybierz schemat' }}
        render={({ field }) => (
          <SelectField
            label="Schemat"
            options={schemas.map((schema) => ({ value: schema.id, label: schema.name }))}
            value={field.value}
            onChange={field.onChange}
            onBlur={field.onBlur}
            error={errors.schemaId?.message}
          />
        )}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          {article ? 'Zapisz' : 'Dodaj artykuł'}
        </Button>
      </FormActions>
    </Form>
  )
}
