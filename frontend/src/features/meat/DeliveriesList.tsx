import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { CameraIcon, FileUpIcon, ImageIcon, PencilLineIcon, ScanTextIcon } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { DateField } from '@/components/form'
import { HowTo } from '@/components/HowTo'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { meatPaths, scanKey, useMeatDeliveries, useScanMeatDocument } from '@/features/meat/api'
import { prepareScan } from '@/features/meat/format'
import { ApiError } from '@/lib/api'
import { addDays, formatShortDate, todayIso } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'

/**
 * Dokumenty WZ sklepu z okresu i wczytywanie nowego: zdjęcie telefonem albo skan (PDF, JPG, PNG). AI odczytuje
 * dostawcę, numer, datę wysyłki i ceny netto za kg, a edytor pokazuje je do sprawdzenia przed zapisem.
 */
export function DeliveriesList({ store }: { store: StoreSummary }) {
  const canEdit = useCan('MEAT_PURCHASES', 'EDIT')
  const [from, setFrom] = useState(() => addDays(todayIso(), -30))
  const [to, setTo] = useState(todayIso)
  const { data: deliveries, isPending, isError, refetch } = useMeatDeliveries(store.id, from, to)

  return (
    <div className="space-y-5">
      <HowTo
        id="meat-deliveries"
        title="Jak wprowadzić dokument WZ"
        steps={[
          'Zrób zdjęcie dokumentu WZ telefonem albo wgraj skan.',
          'AI odczyta dostawcę, numer, datę wysyłki i ceny netto za kg, a pozycje dopasuje do mięsa z kartoteki.',
          'Sprawdź pozycje ze skanem obok i zapisz. Poprawione dopasowania zapamiętamy dla tego dostawcy.',
        ]}
      />

      {canEdit && <ScanButtons storeId={store.id} />}

      <section className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 className="text-base font-semibold">
            Wprowadzone dokumenty{' '}
            {deliveries && <span className="font-normal text-muted-foreground tabular-nums">{deliveries.length}</span>}
          </h2>
          <div className="grid w-full gap-2 sm:flex sm:w-auto">
            <DateField label="Od" value={from} onChange={setFrom} max={to} className="sm:w-64" />
            <DateField label="Do" value={to} onChange={setTo} min={from} className="sm:w-64" />
          </div>
        </div>

        <div className="overflow-hidden rounded-xl border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-32">Data dostawy</TableHead>
                <TableHead>Dostawca</TableHead>
                <TableHead>Numer dokumentu</TableHead>
                <TableHead className="text-right">Pozycje</TableHead>
                <TableHead className="w-10">
                  <span className="sr-only">Skan</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending && <TableSkeletonRows columns={5} />}
              {isError && (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-sm text-muted-foreground">
                    Nie udało się wczytać dokumentów.{' '}
                    <Button variant="link" className="h-auto p-0" onClick={() => refetch()}>
                      Spróbuj ponownie
                    </Button>
                  </TableCell>
                </TableRow>
              )}
              {deliveries?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                    W tym okresie nie wprowadzono dokumentów WZ.
                  </TableCell>
                </TableRow>
              )}
              {deliveries?.map((delivery) => (
                <TableRow key={delivery.id} className="relative cursor-pointer hover:bg-muted/50">
                  <TableCell className="tabular-nums">{formatShortDate(delivery.deliveryDate)}</TableCell>
                  <TableCell className="font-medium">
                    <Link
                      to={meatPaths.delivery(delivery.id)}
                      className="outline-none after:absolute after:inset-0 focus-visible:underline"
                    >
                      {delivery.supplier.name}
                    </Link>
                  </TableCell>
                  <TableCell className="tabular-nums">{delivery.documentNumber}</TableCell>
                  <TableCell className="text-right tabular-nums">{delivery.lineCount}</TableCell>
                  <TableCell>
                    {delivery.hasScan && (
                      <ImageIcon aria-label="Ze skanem" className="size-4 text-muted-foreground" />
                    )}
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

/** Zdjęcie (na telefonie od razu aparat) albo plik ze skanera; po odczycie przejście do edytora. */
function ScanButtons({ storeId }: { storeId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const scan = useScanMeatDocument(storeId)
  const cameraInput = useRef<HTMLInputElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [preparing, setPreparing] = useState(false)

  const read = async (file: File | undefined) => {
    if (!file) return
    setPreparing(true)
    const prepared = await prepareScan(file)
    setPreparing(false)
    scan.mutate(prepared, {
      onSuccess: (result) => {
        queryClient.setQueryData(scanKey(storeId, result.scanId), result)
        navigate(meatPaths.fromScan(result.scanId))
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się odczytać dokumentu'),
    })
  }
  const busy = preparing || scan.isPending

  return (
    <section className="rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-4">
        <div className="min-w-0">
          <h2 className="text-base font-semibold">Nowy dokument WZ</h2>
          <p className="mt-0.5 text-sm text-muted-foreground">Zdjęcie albo skan: AI odczyta dostawcę i ceny netto za kg.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button size="xl" onClick={() => cameraInput.current?.click()} disabled={busy}>
            <CameraIcon />
            Zrób zdjęcie WZ
          </Button>
          <Button size="xl" variant="outline" onClick={() => fileInput.current?.click()} disabled={busy}>
            <FileUpIcon />
            Wgraj skan
          </Button>
          <Button size="xl" variant="ghost" nativeButton={false} render={<Link to={meatPaths.create} />} disabled={busy}>
            <PencilLineIcon />
            Wpisz ręcznie
          </Button>
        </div>
      </div>
      {busy && (
        <p role="status" className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
          <ScanTextIcon className="size-4 animate-pulse motion-reduce:animate-none" />
          Odczytuję dokument, to zajmuje kilka sekund…
        </p>
      )}
      <input
        ref={cameraInput}
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        tabIndex={-1}
        onChange={(event) => {
          void read(event.target.files?.[0])
          event.target.value = ''
        }}
      />
      <input
        ref={fileInput}
        type="file"
        accept="application/pdf,image/jpeg,image/png,image/webp"
        className="sr-only"
        tabIndex={-1}
        onChange={(event) => {
          void read(event.target.files?.[0])
          event.target.value = ''
        }}
      />
    </section>
  )
}
