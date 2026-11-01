import { useDeferredValue, useMemo, useState, type ReactNode } from 'react'
import { ChevronRightIcon, GlobeIcon, MailIcon, PhoneIcon, PlusIcon } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { SegmentedField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { AddSupplierForm } from '@/features/documents/SupplierPicker'
import { storeLabel } from '@/features/stores/current-store'
import { useSuppliers } from '@/features/suppliers/api'
import { phoneHref, supplierPaths, websiteLabel } from '@/features/suppliers/format'
import { TOOL_ROUTES } from '@/features/tools/categories'
import { matchesSearch } from '@/lib/search'
import type { StoreSummary, Supplier } from '@/lib/types'

type Origin = 'all' | 'list' | 'manual'

const ORIGINS: { value: Origin; label: string }[] = [
  { value: 'all', label: 'Wszyscy' },
  { value: 'list', label: 'Z listy sklepu' },
  { value: 'manual', label: 'Dodani ręcznie' },
]

/** Parametry adresu: wyszukiwanie i rodzaj zostają po wejściu w kartę dostawcy i powrocie. */
const ORIGIN_PARAM: Record<Origin, string | null> = { all: null, list: 'lista', manual: 'reczni' }

/**
 * Wszyscy dostawcy sklepu naraz (jest ich kilkuset), z wyszukiwarką po nazwie, kodzie, NIP-ie, mieście
 * i przedstawicielu. Wiersz otwiera kartę dostawcy; telefon, e-mail i strona działają od razu z listy.
 */
export function SuppliersList({ store }: { store: StoreSummary }) {
  const canEdit = useCan('SUPPLIERS', 'EDIT')
  const canImport = useCan('IMPORTS')
  const { data: suppliers, isPending, isError, refetch } = useSuppliers(store.id)
  const [params, setParams] = useSearchParams()
  const query = params.get('szukaj') ?? ''
  const deferredQuery = useDeferredValue(query)
  const origin: Origin = params.get('rodzaj') === 'lista' ? 'list' : params.get('rodzaj') === 'reczni' ? 'manual' : 'all'
  const [adding, setAdding] = useState(false)
  const navigate = useNavigate()

  const setParam = (key: string, value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )

  const visible = useMemo(
    () =>
      (suppliers ?? []).filter(
        (supplier) =>
          (origin === 'all' || supplier.manual === (origin === 'manual')) &&
          matchesSearch(
            [
              supplier.name,
              supplier.keyword,
              supplier.code,
              supplier.nip,
              supplier.city,
              supplier.representative?.name,
              supplier.website,
            ],
            deferredQuery,
          ),
      ),
    [suppliers, origin, deferredQuery],
  )
  const open = (supplier: Supplier) => navigate(supplierPaths.supplier(supplier.id))

  return (
    <>
      <PageHeader
        title="Dostawcy"
        description={`Dostawcy sklepu ${storeLabel(store)}: dane z listy z systemu sklepu, strony internetowe, przedstawiciele i kontakty.`}
        actions={
          canEdit && (
            <Button onClick={() => setAdding(true)}>
              <PlusIcon />
              Dodaj dostawcę
            </Button>
          )
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać dostawców"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : !isPending && suppliers.length === 0 ? (
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <p className="font-medium">Nie ma jeszcze dostawców</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wgraj raport „Lista dostawców” z systemu sklepu
            {canImport && (
              <>
                {' '}w{' '}
                <Link
                  to={TOOL_ROUTES.IMPORTS.path}
                  className="font-medium text-foreground underline-offset-4 hover:underline"
                >
                  Imporcie danych
                </Link>
              </>
            )}
            {canEdit ? ' albo dodaj dostawcę ręcznie.' : '.'}
          </p>
        </Card>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2">
            <SearchInput
              className="w-full sm:w-96"
              value={query}
              onChange={(value) => setParam('szukaj', value || null)}
              placeholder="Szukaj: nazwa, kod, NIP, miasto, przedstawiciel"
            />
            <SegmentedField
              label="Pochodzenie dostawcy"
              options={ORIGINS}
              value={origin}
              onChange={(value) => setParam('rodzaj', ORIGIN_PARAM[value])}
            />
            {!isPending && (
              <span className="text-sm text-muted-foreground tabular-nums">
                {visible.length === suppliers.length ? suppliers.length : `${visible.length} z ${suppliers.length}`}
              </span>
            )}
          </div>

          <Card className="py-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="pl-4">Dostawca</TableHead>
                  <TableHead>Przedstawiciel</TableHead>
                  <TableHead>Kontakt</TableHead>
                  <TableHead className="text-right">Kontakty</TableHead>
                  <TableHead className="w-10 pr-4">
                    <span className="sr-only">Otwórz</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {isPending ? (
                  <TableSkeletonRows columns={5} rows={6} />
                ) : visible.length === 0 ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={5} className="py-8 text-center text-sm text-muted-foreground">
                      {deferredQuery ? `Żaden dostawca nie pasuje do „${deferredQuery}”.` : 'Brak dostawców w tej grupie.'}
                    </TableCell>
                  </TableRow>
                ) : (
                  visible.map((supplier) => (
                    <SupplierRow key={supplier.id} supplier={supplier} onOpen={() => open(supplier)} />
                  ))
                )}
              </TableBody>
            </Table>
          </Card>
        </>
      )}

      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent className="gap-5 p-6 sm:max-w-lg">
          {adding && (
            <AddSupplierForm
              storeId={store.id}
              initialName={query.trim()}
              onAdded={(supplier) => {
                setAdding(false)
                navigate(supplierPaths.supplier(supplier.id))
              }}
              onBack={() => setAdding(false)}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

function SupplierRow({ supplier, onOpen }: { supplier: Supplier; onOpen: () => void }) {
  const details = [supplier.code ? `kod ${supplier.code}` : 'dodany ręcznie', supplier.city, supplier.nip && `NIP ${supplier.nip}`]
  const representative = supplier.representative
  return (
    <TableRow
      tabIndex={0}
      role="link"
      aria-label={`Dostawca ${supplier.name}`}
      onClick={onOpen}
      onKeyDown={(event) => event.key === 'Enter' && event.target === event.currentTarget && onOpen()}
      className="cursor-pointer outline-none focus-visible:bg-muted"
    >
      <TableCell className="max-w-96 py-3 pl-4 whitespace-normal">
        <div className="font-medium">{supplier.name}</div>
        <div className="text-xs text-muted-foreground tabular-nums">{details.filter(Boolean).join(' · ')}</div>
      </TableCell>
      <TableCell className="whitespace-normal">
        {representative ? (
          <>
            <div>{representative.name ?? '—'}</div>
            {representative.phone && (
              <ContactLink href={phoneHref(representative.phone)} className="text-xs tabular-nums">
                {representative.phone}
              </ContactLink>
            )}
          </>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      <TableCell>
        <div className="flex items-center gap-1">
          {supplier.phone && (
            <IconLink href={phoneHref(supplier.phone)} label={`Zadzwoń: ${supplier.phone}`} icon={<PhoneIcon />} />
          )}
          {supplier.email && (
            <IconLink href={`mailto:${supplier.email}`} label={`Napisz: ${supplier.email}`} icon={<MailIcon />} />
          )}
          {supplier.website && (
            <IconLink
              href={supplier.website}
              label={`Strona: ${websiteLabel(supplier.website)}`}
              icon={<GlobeIcon />}
              external
            />
          )}
          {!supplier.phone && !supplier.email && !supplier.website && (
            <span className="text-muted-foreground">—</span>
          )}
        </div>
      </TableCell>
      <TableCell className="text-right tabular-nums text-muted-foreground">
        {supplier.contactCount > 0 ? supplier.contactCount : '—'}
      </TableCell>
      <TableCell className="pr-4 text-right">
        <ChevronRightIcon aria-hidden className="ml-auto size-4 text-muted-foreground" />
      </TableCell>
    </TableRow>
  )
}

/** Link w wierszu, który nie otwiera karty dostawcy. */
function ContactLink({ href, className, children }: { href: string; className?: string; children: string }) {
  return (
    <a
      href={href}
      onClick={(event) => event.stopPropagation()}
      className={`text-muted-foreground underline-offset-4 hover:text-foreground hover:underline ${className ?? ''}`}
    >
      {children}
    </a>
  )
}

function IconLink({
  href,
  label,
  icon,
  external,
}: {
  href: string
  label: string
  icon: ReactNode
  external?: boolean
}) {
  return (
    <a
      href={href}
      title={label}
      aria-label={label}
      onClick={(event) => event.stopPropagation()}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      className="flex size-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/40 focus-visible:outline-none [&_svg]:size-4"
    >
      {icon}
    </a>
  )
}
