import { useMutation, useQuery } from '@tanstack/react-query'
import { ClipboardCopyIcon, TriangleAlertIcon } from 'lucide-react'
import { type ReactNode, useState } from 'react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatDocumentDate } from '@/features/documents/format'
import { api, ApiError } from '@/lib/api'
import type { MercalysSupplierCreation, MercalysSupplierDraft } from '@/lib/types'

import { buildSupplierMacro, SUPPLIER_CODE_TRIES, type SupplierMacro } from './mercalys-macro'

const suppliersPath = (storeId: string) => `/stores/${storeId}/suppliers`

/** Pola Mercalys: tyle znaków przyjmuje program. */
const LIMITS = { code: 8, keyword: 8, nip: 10, name1: 30, name2: 30, street: 30, postalCode: 5, city: 30 } as const

type Props = {
  storeId: string
  nip: string
  /** Nazwa sprzedawcy z faktury: zapas, gdy GUS nie zna firmy albo nie odpowiada. */
  invoiceName: string | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Sprzedawcy z faktury nie ma na liście dostawców: dane z GUS przycięte do pól Mercalys (do poprawienia) i pierwszy
 * wolny kod sklepu. „Skopiuj makro” rezerwuje kod (do wgrania nowej listy dostawców) i kładzie makro do schowka;
 * toast „Cofnij” zwalnia kod.
 */
export function MercalysSupplierDialog({ storeId, nip, invoiceName, open, onOpenChange }: Props) {
  const draft = useQuery({
    queryKey: ['stores', storeId, 'suppliers', 'mercalys-draft', nip],
    queryFn: () =>
      api<MercalysSupplierDraft>(
        `${suppliersPath(storeId)}/mercalys-draft?${new URLSearchParams({ nip, name: invoiceName ?? '' })}`,
      ),
    enabled: open,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Nowy dostawca w Mercalys</DialogTitle>
          <DialogDescription>
            Makro założy dostawcę w Obsługa → Dostawcy i wyeksportuje listę dostawców do Excela. Sprawdź dane, skopiuj
            makro, przejdź do Mercalys i naciśnij Alt+E.
          </DialogDescription>
        </DialogHeader>
        {draft.isPending ? (
          <p className="py-6 text-center text-sm text-muted-foreground">Pobieranie danych z GUS…</p>
        ) : draft.isError ? (
          <p className="rounded-md bg-red-200 px-3 py-2 text-sm text-red-950 dark:bg-red-900/70 dark:text-red-100">
            {draft.error instanceof ApiError ? draft.error.message : 'Nie udało się przygotować danych dostawcy.'}
          </p>
        ) : (
          <SupplierForm
            key={`${draft.data.nip}:${draft.data.code}`}
            storeId={storeId}
            draft={draft.data}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SupplierForm({
  storeId,
  draft,
  onDone,
}: {
  storeId: string
  draft: MercalysSupplierDraft
  onDone: () => void
}) {
  const [values, setValues] = useState<SupplierMacro>({
    code: draft.code,
    keyword: draft.keyword,
    nip: draft.nip,
    name1: draft.name1,
    name2: draft.name2,
    street: draft.street,
    postalCode: draft.postalCode,
    city: draft.city,
  })
  const create = useMutation({
    mutationFn: () =>
      api<MercalysSupplierCreation>(`${suppliersPath(storeId)}/mercalys-creations`, {
        method: 'POST',
        json: { nip: values.nip, code: values.code, name: [values.name1, values.name2].join(' ').trim() },
      }),
    meta: { invalidates: false },
  })
  const release = (id: string) =>
    api(`${suppliersPath(storeId)}/mercalys-creations/${id}`, { method: 'DELETE' }).catch(() =>
      toast.error('Nie udało się zwolnić kodu'),
    )

  const set = (field: keyof SupplierMacro, value: string) =>
    setValues((current) => ({ ...current, [field]: value.toUpperCase().slice(0, LIMITS[field]) }))

  const errors: Partial<Record<keyof SupplierMacro, string>> = {
    code: /^\d{1,8}$/.test(values.code) ? undefined : 'Od 1 do 8 cyfr',
    keyword: values.keyword.trim() ? undefined : 'Wpisz hasło',
    nip: /^\d{10}$/.test(values.nip) ? undefined : 'Dziesięć cyfr',
    name1: values.name1.trim() ? undefined : 'Wpisz nazwę',
    postalCode: values.postalCode === '' || /^\d{5}$/.test(values.postalCode) ? undefined : 'Pięć cyfr, bez myślnika',
  }
  const invalid = Object.values(errors).some(Boolean)

  const copy = async () => {
    let creation: MercalysSupplierCreation
    try {
      creation = await create.mutateAsync()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zarezerwować kodu')
      return
    }
    try {
      await navigator.clipboard.writeText(buildSupplierMacro(values))
    } catch {
      await release(creation.id)
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    onDone()
    toast.success(`Skopiowano makro: dostawca ${creation.code} albo kolejny wolny kod`, {
      description:
        'Przejdź do Mercalys i naciśnij Alt+E. Potem wgraj w Imporcie danych listę dostawców, żeby numer pojawił się tutaj.',
      duration: 15000,
      action: {
        label: 'Cofnij',
        onClick: () => void release(creation.id).then(() => toast.success(`Zwolniono kod ${creation.code}`)),
      },
    })
  }

  const field = (name: keyof SupplierMacro, label: string, className?: string) => (
    <TextField
      label={label}
      value={values[name]}
      onChange={(event) => set(name, event.target.value)}
      error={errors[name]}
      maxLength={LIMITS[name]}
      className={className}
    />
  )

  return (
    <>
      {(draft.gusProblem || draft.previous) && (
        <div className="grid gap-2">
          {draft.previous && (
            <Notice>
              Makro dla tego NIP-u skopiowano już {formatDocumentDate(draft.previous.createdAt)} (kod{' '}
              {draft.previous.code}
              {draft.previous.createdBy ? `, ${draft.previous.createdBy}` : ''}). Jeśli dostawca jest już w Mercalys,
              wgraj listę dostawców w Imporcie danych zamiast zakładać go drugi raz.
            </Notice>
          )}
          {draft.gusProblem && (
            <Notice>{draft.gusProblem}. Nazwa jest z faktury, a adres trzeba wpisać ręcznie.</Notice>
          )}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-[8rem_minmax(0,1fr)]">
        {field('code', 'Kod dostawcy')}
        {field('keyword', 'Hasło (8 znaków)')}
        {field('nip', 'Kod NIP', 'sm:col-span-2')}
        {field('name1', 'Nazwa spółki', 'sm:col-span-2')}
        {field('name2', 'Nazwa, ciąg dalszy', 'sm:col-span-2')}
        {field('street', 'Adres: ulica i numer', 'sm:col-span-2')}
        {field('postalCode', 'Kod pocztowy')}
        {field('city', 'Biuro dystr. (miasto)')}
      </div>
      <p className="text-xs text-muted-foreground">
        NIP {draft.nip}
        {draft.fromGus ? ' · dane z GUS' : ''} · Pochodz. 2, telefon 11, zaznaczone Aktu. stanów przy zamknięciu dostawy
        i Pełna realiz. zamów. Gdy kod jest w Mercalys zajęty, makro bierze kolejny (do {SUPPLIER_CODE_TRIES} prób) i
        niczego nie nadpisuje.
      </p>
      <DialogFooter>
        <Button size="lg" loading={create.isPending} disabled={invalid} onClick={() => void copy()}>
          <ClipboardCopyIcon />
          Skopiuj makro
        </Button>
      </DialogFooter>
    </>
  )
}

function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="flex gap-2 rounded-md bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900/60 dark:text-amber-100">
      <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </p>
  )
}
