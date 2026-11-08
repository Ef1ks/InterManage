import { useState, type ReactNode } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { GlobeIcon, MailIcon, PencilIcon, PhoneIcon, PlusIcon, StoreIcon, Trash2Icon } from 'lucide-react'
import { useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { CopyButton } from '@/components/CopyButton'
import { Form, FormActions, FormNote, SelectField, TextareaField, TextField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { GusButton } from '@/components/GusButton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import {
  type SupplierContactForm,
  type SupplierDetailsForm,
  useSupplier,
  useSupplierActions,
} from '@/features/suppliers/api'
import {
  CONTACT_KINDS,
  contactKindLabel,
  formatDate,
  formatDateTime,
  phoneHref,
  supplierPaths,
  websiteLabel,
} from '@/features/suppliers/format'
import { ApiError } from '@/lib/api'
import { applyServerErrors } from '@/lib/form-errors'
import { fillFromGus } from '@/lib/gus'
import type { SupplierContact, SupplierDetails } from '@/lib/types'

/**
 * Karta dostawcy: dane z listy z systemu sklepu (u dodanego ręcznie wpisane w aplikacji), strona, telefon, e-mail,
 * notatki i kontakty. Wszystko poza danymi z listy jest wspólne dla sklepów firmy (ten sam NIP).
 */
export function SupplierCard({ storeId }: { storeId: string }) {
  const { supplierId } = useParams()
  const { data: supplier, isPending, isError } = useSupplier(storeId, supplierId)
  const navigate = useNavigate()

  if (isError) {
    return (
      <FullPageMessage
        title="Nie znaleziono dostawcy"
        description="Może należeć do innego sklepu."
        action={{ label: 'Wróć do listy', onClick: () => navigate(supplierPaths.list) }}
        inline
      />
    )
  }
  if (isPending) {
    return <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  return <SupplierView storeId={storeId} supplier={supplier} />
}

function SupplierView({ storeId, supplier }: { storeId: string; supplier: SupplierDetails }) {
  const canEdit = useCan('SUPPLIERS', 'EDIT')
  const { update, saveContact, removeContact, restoreContact } = useSupplierActions(storeId, supplier.id)
  const [editingDetails, setEditingDetails] = useState(false)
  const [editingContact, setEditingContact] = useState<SupplierContact | 'new' | null>(null)
  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const removeContactWithUndo = (contact: SupplierContact) =>
    removeContact.mutate(contact.id, {
      onSuccess: () =>
        toast.success(`Usunięto kontakt ${contact.name ?? contactKindLabel(contact.kind).toLowerCase()}`, {
          action: { label: 'Przywróć', onClick: () => restoreContact.mutate(contact.id, { onError }) },
        }),
      onError,
    })

  const address = [supplier.address, [supplier.postalCode, supplier.city].filter(Boolean).join(' ')]
    .filter(Boolean)
    .join(', ')
  const hasContactData = supplier.website || supplier.phone || supplier.email || supplier.notes

  return (
    <>
      <BackLink to={supplierPaths.list}>Dostawcy</BackLink>
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight">{supplier.name}</h1>
          <p className="mt-1 text-sm text-muted-foreground tabular-nums">
            {supplier.manual
              ? `Dodany ręcznie${supplier.createdBy ? ` · ${supplier.createdBy}` : ''} · ${formatDate(supplier.createdAt)}`
              : [
                  `Kod ${supplier.code}`,
                  supplier.keyword,
                  supplier.listReportGeneratedAt && `z listy dostawców z ${formatDate(supplier.listReportGeneratedAt)}`,
                ]
                  .filter(Boolean)
                  .join(' · ')}
          </p>
        </div>
        {canEdit && (
          <Button variant="outline" onClick={() => setEditingDetails(true)}>
            <PencilIcon />
            Zmień dane
          </Button>
        )}
      </div>

      {supplier.sharedWithStores.length > 0 && (
        <div className="mb-4">
          <FormNote icon={<StoreIcon />}>
            Strona, telefon, e-mail, notatki i kontakty są wspólne ze sklepami tej samej firmy:{' '}
            {supplier.sharedWithStores.join(', ')}. Zmiana tutaj od razu jest widoczna także tam.
          </FormNote>
        </div>
      )}

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="grid gap-4">
          <Card className="gap-3 p-5">
            <h2 className="font-medium">Dane dostawcy</h2>
            <dl className="grid gap-2.5 text-sm">
              <Detail label="NIP">
                {supplier.nip ? (
                  <span className="inline-flex items-center gap-1 tabular-nums">
                    {supplier.nip}
                    <CopyButton value={supplier.nip} label="Kopiuj NIP" />
                  </span>
                ) : null}
              </Detail>
              <Detail label="Adres">{address || null}</Detail>
              {!supplier.manual && <Detail label="Pochodzenie">{supplier.origin}</Detail>}
            </dl>
            {!supplier.manual && (
              <p className="text-xs text-muted-foreground">
                Te dane przychodzą z listy dostawców z systemu sklepu i zmieniają się przy jej imporcie.
              </p>
            )}
          </Card>

          <Card className="gap-3 p-5">
            <h2 className="font-medium">Kontakt do firmy</h2>
            {hasContactData ? (
              <dl className="grid gap-2.5 text-sm">
                <Detail label="Strona">
                  {supplier.website && (
                    <a
                      href={supplier.website}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline"
                    >
                      <GlobeIcon aria-hidden className="size-4 text-muted-foreground" />
                      {websiteLabel(supplier.website)}
                    </a>
                  )}
                </Detail>
                <Detail label="Telefon">{supplier.phone && <PhoneLink phone={supplier.phone} />}</Detail>
                <Detail label="E-mail">{supplier.email && <EmailLink email={supplier.email} />}</Detail>
                {supplier.notes && (
                  <div className="grid gap-1 pt-1">
                    <dt className="text-muted-foreground">Notatki</dt>
                    <dd className="whitespace-pre-line">{supplier.notes}</dd>
                  </div>
                )}
              </dl>
            ) : (
              <p className="text-sm text-muted-foreground">
                Brak strony, telefonu i e-maila.{canEdit && ' Uzupełnij je przyciskiem „Zmień dane”.'}
              </p>
            )}
            {supplier.detailsUpdatedAt && (
              <p className="text-xs text-muted-foreground">
                Zmienione {formatDateTime(supplier.detailsUpdatedAt)}
                {supplier.detailsUpdatedBy && ` · ${supplier.detailsUpdatedBy}`}
              </p>
            )}
          </Card>
        </div>

        <Card className="gap-3 p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-medium">
              Kontakty
              {supplier.contacts.length > 0 && (
                <span className="ml-1.5 text-muted-foreground tabular-nums">{supplier.contacts.length}</span>
              )}
            </h2>
            {canEdit && (
              <Button variant="outline" onClick={() => setEditingContact('new')}>
                <PlusIcon />
                Dodaj kontakt
              </Button>
            )}
          </div>
          {supplier.contacts.length === 0 ? (
            <p className="rounded-lg border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
              Nie ma jeszcze kontaktów. Dodaj przedstawiciela handlowego, biuro zamówień albo księgowość.
            </p>
          ) : (
            <ul className="divide-y rounded-lg border">
              {supplier.contacts.map((contact) => (
                <ContactItem
                  key={contact.id}
                  contact={contact}
                  canEdit={canEdit}
                  removing={removeContact.isPending}
                  onEdit={() => setEditingContact(contact)}
                  onRemove={() => removeContactWithUndo(contact)}
                />
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Dialog open={editingDetails} onOpenChange={setEditingDetails}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {editingDetails && (
            <DetailsForm
              supplier={supplier}
              onSubmit={(values) => update.mutateAsync(values)}
              onDone={() => {
                setEditingDetails(false)
                toast.success('Zapisano dane dostawcy')
              }}
              onCancel={() => setEditingDetails(false)}
            />
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={editingContact !== null} onOpenChange={(open) => !open && setEditingContact(null)}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {editingContact && (
            <ContactForm
              contact={editingContact === 'new' ? null : editingContact}
              onSubmit={(values) =>
                saveContact.mutateAsync({ id: editingContact === 'new' ? undefined : editingContact.id, ...values })
              }
              onDone={() => {
                setEditingContact(null)
                toast.success(editingContact === 'new' ? 'Dodano kontakt' : 'Zapisano kontakt')
              }}
              onCancel={() => setEditingContact(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children ?? <span className="text-muted-foreground">—</span>}</dd>
    </div>
  )
}

function PhoneLink({ phone }: { phone: string }) {
  return (
    <a
      href={phoneHref(phone)}
      className="inline-flex items-center gap-1.5 tabular-nums underline-offset-4 hover:underline"
    >
      <PhoneIcon aria-hidden className="size-4 text-muted-foreground" />
      {phone}
    </a>
  )
}

function EmailLink({ email }: { email: string }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1">
      <a
        href={`mailto:${email}`}
        className="inline-flex min-w-0 items-center gap-1.5 underline-offset-4 hover:underline"
      >
        <MailIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate">{email}</span>
      </a>
      <CopyButton value={email} label="Kopiuj e-mail" />
    </span>
  )
}

function ContactItem({
  contact,
  canEdit,
  removing,
  onEdit,
  onRemove,
}: {
  contact: SupplierContact
  canEdit: boolean
  removing: boolean
  onEdit: () => void
  onRemove: () => void
}) {
  const title = contact.name ?? contactKindLabel(contact.kind)
  return (
    <li className="flex flex-wrap items-start gap-x-3 gap-y-2 px-4 py-3">
      <div className="grid min-w-0 flex-1 gap-1 text-sm">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="font-medium">{title}</span>
          <span className="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {contactKindLabel(contact.kind)}
          </span>
        </div>
        {contact.jobTitle && <p className="text-muted-foreground">{contact.jobTitle}</p>}
        {(contact.phone || contact.email) && (
          <div className="flex flex-wrap gap-x-4 gap-y-1">
            {contact.phone && <PhoneLink phone={contact.phone} />}
            {contact.email && <EmailLink email={contact.email} />}
          </div>
        )}
        {contact.notes && <p className="whitespace-pre-line text-muted-foreground">{contact.notes}</p>}
      </div>
      {canEdit && (
        <div className="flex gap-1">
          <Button variant="ghost" size="icon" aria-label={`Zmień kontakt ${title}`} onClick={onEdit}>
            <PencilIcon />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Usuń kontakt ${title}`}
            disabled={removing}
            onClick={onRemove}
          >
            <Trash2Icon />
          </Button>
        </div>
      )}
    </li>
  )
}

const emailPattern = { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: 'To nie wygląda na adres e-mail' }

function DetailsForm({
  supplier,
  onSubmit,
  onDone,
  onCancel,
}: {
  supplier: SupplierDetails
  onSubmit: (values: SupplierDetailsForm) => Promise<SupplierDetails>
  onDone: () => void
  onCancel: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<SupplierDetailsForm>({
    defaultValues: {
      name: supplier.name,
      nip: supplier.nip ?? '',
      address: supplier.address ?? '',
      postalCode: supplier.postalCode ?? '',
      city: supplier.city ?? '',
      website: supplier.website ?? '',
      email: supplier.email ?? '',
      phone: supplier.phone ?? '',
      notes: supplier.notes ?? '',
    },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await onSubmit(values)
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, Object.keys(values) as (keyof SupplierDetailsForm)[]))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">Dane dostawcy</DialogTitle>
        <DialogDescription>
          {supplier.manual
            ? 'Dostawca dodany ręcznie: wszystkie dane wpisujesz tutaj.'
            : 'Nazwa, NIP i adres przychodzą z listy dostawców z systemu sklepu. Tu uzupełniasz resztę.'}
        </DialogDescription>
      </DialogHeader>

      {supplier.manual && (
        <>
          <div className="flex items-start gap-2">
            <TextField
              label="NIP"
              inputMode="numeric"
              autoComplete="off"
              className="min-w-0 flex-1"
              error={errors.nip?.message}
              {...register('nip', {
                pattern: { value: /^[\d\s-]{0,20}$/, message: 'NIP to same cyfry, np. 9291243024' },
              })}
            />
            <GusButton nip={watch('nip')} onFound={(company) => fillFromGus(setValue, company)} />
          </div>
          <TextField
            label="Nazwa dostawcy"
            autoComplete="off"
            error={errors.name?.message}
            {...register('name', {
              validate: (value) => value.trim() !== '' || 'Podaj nazwę dostawcy',
              maxLength: { value: 200, message: 'Maksymalnie 200 znaków' },
            })}
          />
          <TextField label="Adres" autoComplete="off" error={errors.address?.message} {...register('address')} />
          <div className="grid gap-4 sm:grid-cols-[8rem_minmax(0,1fr)]">
            <TextField
              label="Kod pocztowy"
              autoComplete="off"
              error={errors.postalCode?.message}
              {...register('postalCode', { maxLength: { value: 10, message: 'Maksymalnie 10 znaków' } })}
            />
            <TextField label="Miejscowość" autoComplete="off" error={errors.city?.message} {...register('city')} />
          </div>
        </>
      )}

      <TextField
        label="Strona internetowa"
        inputMode="url"
        autoComplete="off"
        hint="Np. sokolow.pl"
        error={errors.website?.message}
        {...register('website', { maxLength: { value: 490, message: 'Maksymalnie 490 znaków' } })}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Telefon"
          type="tel"
          autoComplete="off"
          error={errors.phone?.message}
          {...register('phone', { maxLength: { value: 50, message: 'Maksymalnie 50 znaków' } })}
        />
        <TextField
          label="E-mail"
          type="email"
          autoComplete="off"
          error={errors.email?.message}
          {...register('email', { pattern: emailPattern })}
        />
      </div>
      <TextareaField
        label="Notatki"
        hint="Np. dni zamówień, minimum logistyczne, godziny dostaw"
        error={errors.notes?.message}
        {...register('notes', { maxLength: { value: 5000, message: 'Maksymalnie 5000 znaków' } })}
      />

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}

function ContactForm({
  contact,
  onSubmit,
  onDone,
  onCancel,
}: {
  contact: SupplierContact | null
  onSubmit: (values: SupplierContactForm) => Promise<SupplierDetails>
  onDone: () => void
  onCancel: () => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SupplierContactForm>({
    defaultValues: {
      kind: contact?.kind ?? 'REPRESENTATIVE',
      name: contact?.name ?? '',
      jobTitle: contact?.jobTitle ?? '',
      phone: contact?.phone ?? '',
      email: contact?.email ?? '',
      notes: contact?.notes ?? '',
    },
  })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    if (!values.name.trim() && !values.phone.trim() && !values.email.trim()) {
      setFormError('Podaj imię i nazwisko, telefon albo e-mail')
      return
    }
    try {
      await onSubmit(values)
      onDone()
    } catch (error) {
      setFormError(applyServerErrors(error, setError, Object.keys(values) as (keyof SupplierContactForm)[]))
    }
  })

  return (
    <Form onSubmit={submit} error={formError}>
      <DialogHeader>
        <DialogTitle className="text-lg">{contact ? 'Zmień kontakt' : 'Nowy kontakt'}</DialogTitle>
        <DialogDescription>Wystarczy jedno z: imię i nazwisko, telefon albo e-mail.</DialogDescription>
      </DialogHeader>
      <Controller
        control={control}
        name="kind"
        render={({ field }) => (
          <SelectField
            label="Kto to jest"
            options={CONTACT_KINDS}
            value={field.value}
            onChange={(value) => field.onChange(value || 'OTHER')}
            onBlur={field.onBlur}
          />
        )}
      />
      <TextField
        label="Imię i nazwisko"
        autoComplete="off"
        autoFocus
        error={errors.name?.message}
        {...register('name', { maxLength: { value: 150, message: 'Maksymalnie 150 znaków' } })}
      />
      <TextField
        label="Stanowisko (opcjonalnie)"
        autoComplete="off"
        hint="Np. kierownik regionu, handlowiec na woj. warmińsko-mazurskie"
        error={errors.jobTitle?.message}
        {...register('jobTitle', { maxLength: { value: 100, message: 'Maksymalnie 100 znaków' } })}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Telefon"
          type="tel"
          autoComplete="off"
          error={errors.phone?.message}
          {...register('phone', { maxLength: { value: 50, message: 'Maksymalnie 50 znaków' } })}
        />
        <TextField
          label="E-mail"
          type="email"
          autoComplete="off"
          error={errors.email?.message}
          {...register('email', { pattern: emailPattern })}
        />
      </div>
      <TextareaField
        label="Notatki (opcjonalnie)"
        error={errors.notes?.message}
        {...register('notes', { maxLength: { value: 2000, message: 'Maksymalnie 2000 znaków' } })}
      />
      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onCancel}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting}>
          Zapisz
        </Button>
      </FormActions>
    </Form>
  )
}
