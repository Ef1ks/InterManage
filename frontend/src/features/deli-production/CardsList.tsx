import { useState } from 'react'
import { PlusIcon } from 'lucide-react'
import { Link } from 'react-router'

import { DateField } from '@/components/form'
import { HowTo } from '@/components/HowTo'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  cardPdfPath,
  formPdfPath,
  productionPaths,
  useProductionCards,
  type CardScope,
} from '@/features/deli-production/api'
import { quantityFormat } from '@/features/deli-production/format'
import { PdfButtons } from '@/features/deli-production/PdfButtons'
import { addDays, formatShortDate, todayIso } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

/**
 * Karty sklepu (`OWN`) albo sklepów zaprzyjaźnionych (`PARTNERS`, tylko podgląd i wydruk) z okresu, od najnowszej.
 * Nad własnymi: formularz do wydruku i nowa karta.
 */
export function CardsList({ store, scope }: { store: StoreSummary; scope: CardScope }) {
  const canEdit = useCan('DELI_PRODUCTION', 'EDIT')
  const own = scope === 'OWN'
  const [from, setFrom] = useState(() => addDays(todayIso(), -30))
  const [to, setTo] = useState(todayIso)
  const { data: cards, isPending, isError, refetch } = useProductionCards(store.id, from, to, scope)
  const columns = own ? 5 : 6

  return (
    <div className="space-y-5">
      {own && (
        <>
          <HowTo
            id="production-cards"
            title="Jak zrobić kartę produkcji"
            steps={[
              'Wydrukuj formularz: artykuły z bazy alfabetycznie, z kodami do zeskanowania.',
              'W ciągu dnia wpisuj długopisem ilość przy tym, co zostało wyprodukowane.',
              'Kliknij „Nowa karta”, zeskanuj kody zaznaczonych artykułów, a potem wpisz ilości: liczba, Enter, liczba, Enter.',
              'Zapisz kartę i wydrukuj. Kartę można przypiąć do ruchu międzymagazynowego w Dokumentach.',
            ]}
          />
          <section className="flex flex-wrap items-center justify-between gap-x-6 gap-y-4 rounded-xl border bg-card p-5">
            <div className="min-w-0">
              <h2 className="text-base font-semibold">Formularz do uzupełnienia</h2>
              <p className="mt-0.5 text-sm text-muted-foreground">
                Kartka A4 z artykułami bazy: nazwa, puste pole na ilość i kod kreskowy.
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <PdfButtons path={formPdfPath(store.id)} fileName="formularz-karty-produkcji.pdf" printLabel="Drukuj formularz" />
              {canEdit && (
                <Button size="xl" nativeButton={false} render={<Link to={productionPaths.create} />}>
                  <PlusIcon />
                  Nowa karta
                </Button>
              )}
            </div>
          </section>
        </>
      )}

      <section className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold">
              {own ? 'Zapisane karty' : 'Karty sklepów zaprzyjaźnionych'}{' '}
              {cards && <span className="font-normal text-muted-foreground tabular-nums">{cards.length}</span>}
            </h2>
            {!own && (
              <p className="mt-0.5 text-sm text-muted-foreground">
                Do podglądu i wydruku, np. gdy wyroby przyjechały ruchem międzymagazynowym.
              </p>
            )}
          </div>
          <div className="grid w-full gap-2 sm:flex sm:w-auto">
            <DateField label="Od" value={from} onChange={setFrom} max={to} className="sm:w-64" />
            <DateField label="Do" value={to} onChange={setTo} min={from} className="sm:w-64" />
          </div>
        </div>

        <div className="overflow-hidden rounded-xl border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-36">Data produkcji</TableHead>
                {!own && <TableHead>Sklep</TableHead>}
                <TableHead className="text-right">Pozycje</TableHead>
                <TableHead className="text-right">Suma ilości</TableHead>
                <TableHead>Zapisał(a)</TableHead>
                <TableHead className="w-24">
                  <span className="sr-only">Wydruk</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending && <TableSkeletonRows columns={columns} />}
              {isError && (
                <TableRow>
                  <TableCell colSpan={columns} className="py-8 text-center text-sm text-muted-foreground">
                    Nie udało się wczytać kart.{' '}
                    <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
                      Spróbuj ponownie
                    </Button>
                  </TableCell>
                </TableRow>
              )}
              {cards?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns} className="py-10 text-center text-sm text-muted-foreground">
                    {own
                      ? 'W tym okresie nie zapisano kart produkcji.'
                      : 'Sklepy zaprzyjaźnione nie mają kart z tego okresu (albo sklep nie ma jeszcze zaprzyjaźnionych).'}
                  </TableCell>
                </TableRow>
              )}
              {cards?.map((card) => (
                <TableRow key={card.id} className="relative cursor-pointer hover:bg-muted/50">
                  <TableCell className="font-medium tabular-nums">
                    <Link
                      to={productionPaths.card(card.id)}
                      className="outline-none after:absolute after:inset-0 focus-visible:underline"
                    >
                      {formatShortDate(card.productionDate)}
                    </Link>
                  </TableCell>
                  {!own && (
                    <TableCell>
                      {card.storeNumber} · {card.storeName}
                    </TableCell>
                  )}
                  <TableCell className="text-right tabular-nums">{card.lineCount}</TableCell>
                  <TableCell className="text-right tabular-nums">{quantityFormat.format(card.totalQuantity)}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {card.createdBy ?? '–'}
                    <span className="ml-1.5 tabular-nums">{dateTime.format(new Date(card.createdAt))}</span>
                  </TableCell>
                  <TableCell>
                    <PdfButtons
                      compact
                      path={cardPdfPath(store.id, card.id)}
                      fileName={`karta-produkcji-${card.productionDate}.pdf`}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>
    </div>
  )
}
