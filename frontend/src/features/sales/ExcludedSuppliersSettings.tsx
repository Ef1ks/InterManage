import { useState } from 'react'
import { SettingsIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormNote, MultiComboboxField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { type ExcludedSuppliersReport, useSetExcludedSuppliers } from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import type { ReportSupplier } from '@/lib/types'

type Props = {
  storeId: string
  /** Ścieżka raportu w API, ta sama w kluczu zapytania. */
  path: ExcludedSuppliersReport
  report: { excludedSuppliers: ReportSupplier[]; suppliers: ReportSupplier[] }
  description: string
  label: string
  placeholder: string
  /** Początek zdania z listą dostawców dla osoby bez prawa zmian. */
  readOnlyLabel: string
}

const supplierLabel = (supplier: ReportSupplier) => `${supplier.code} ${supplier.name}`

/** Ustawienia raportu w oknie pod kołem zębatym, bo robi się je raz. Bez prawa zmian okno tylko pokazuje listę. */
export function ExcludedSuppliersSettings({
  storeId,
  path,
  report,
  description,
  label,
  placeholder,
  readOnlyLabel,
}: Props) {
  const canEdit = useCan('SALES', 'EDIT')
  const [open, setOpen] = useState(false)
  if (!canEdit && report.excludedSuppliers.length === 0) return null

  return (
    <>
      <Button
        variant="outline"
        size="icon-lg"
        aria-label={`Ustawienia raportu: ${label.toLowerCase()}`}
        title="Ustawienia raportu"
        onClick={() => setOpen(true)}
      >
        <SettingsIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="text-lg">Ustawienia raportu</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          {canEdit ? (
            <SupplierField storeId={storeId} path={path} report={report} label={label} placeholder={placeholder} />
          ) : (
            <FormNote>
              {readOnlyLabel}: {report.excludedSuppliers.map(supplierLabel).join(', ')}.
            </FormNote>
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Lista dla całego sklepu, zapisuje się od razu. */
function SupplierField({
  storeId,
  path,
  report,
  label,
  placeholder,
}: Pick<Props, 'storeId' | 'path' | 'report' | 'label' | 'placeholder'>) {
  const save = useSetExcludedSuppliers(storeId, path)
  // Do odpowiedzi serwera pole pokazuje to, co właśnie wybrano.
  const value =
    save.isPending && save.variables ? save.variables : report.excludedSuppliers.map((supplier) => supplier.code)

  // Pomijany dostawca, którego nie ma już na liście dostawców, też musi mieć swoją etykietę.
  const missing = report.excludedSuppliers.filter(
    (supplier) => !report.suppliers.some((option) => option.code === supplier.code),
  )
  return (
    <MultiComboboxField
      label={label}
      description="Lista zapisuje się od razu."
      options={[...missing, ...report.suppliers].map((supplier) => ({
        value: supplier.code,
        label: supplierLabel(supplier),
      }))}
      value={value}
      placeholder={placeholder}
      emptyText="Żaden dostawca nie pasuje"
      onChange={(codes) =>
        save.mutate(codes, {
          onError: (failure) =>
            toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zapisać listy dostawców'),
        })
      }
    />
  )
}
