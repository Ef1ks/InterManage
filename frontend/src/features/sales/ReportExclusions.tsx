import { type ReactNode, useState } from 'react'
import { SettingsIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormNote, MultiComboboxField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import {
  type ExclusionReport,
  useReportExclusions,
  useSalesNomenclatures,
  useSetReportExclusions,
} from '@/features/sales/api'
import { ApiError } from '@/lib/api'
import type { ReportSupplier } from '@/lib/types'

const LEVEL_INDENT = { REGION: '', FAMILY: '  ', SUBFAMILY: '    ' } as const

const supplierLabel = (supplier: ReportSupplier) => `${supplier.code} ${supplier.name}`

const onError = (message: string) => (failure: Error) =>
  toast.error(failure instanceof ApiError ? failure.message : message)

/**
 * Koło zębate raportu weryfikującego zakupy: okno z nomenklaturami i dostawcami, których artykułów raport nie
 * pokazuje (osobne listy dla raportu, wspólne dla sklepu, zapis od razu), oraz z dodatkowymi ustawieniami raportu
 * (`children`). Liczba przy kole to liczba pozycji na obu listach. Bez prawa zmian okno tylko pokazuje listy.
 */
export function ReportExclusions({
  storeId,
  report,
  description,
  supplierHint = 'Liczy się dostawca z linii przyjęcia.',
  skipped,
  note,
  children,
}: {
  storeId: string
  report: ExclusionReport
  description: string
  supplierHint?: string
  /** Ile artykułów listy pominęły teraz (gdy raport to liczy). */
  skipped?: { nomenclatures: number; suppliers: number }
  /** Uwaga pod listami, np. o dostawcach pomijanych zawsze. */
  note?: ReactNode
  children?: ReactNode
}) {
  const canEdit = useCan('SALES', 'EDIT')
  const [open, setOpen] = useState(false)
  const exclusions = useReportExclusions(storeId, report).data
  const nomenclatures = useSalesNomenclatures(storeId, open).data ?? []
  const saveNomenclatures = useSetReportExclusions(storeId, report, 'nomenclatures')
  const saveSuppliers = useSetReportExclusions(storeId, report, 'suppliers')

  const excludedNomenclatures = exclusions?.nomenclatures ?? []
  const excludedSuppliers = exclusions?.suppliers ?? []
  const nomenclatureCodes =
    saveNomenclatures.isPending && saveNomenclatures.variables ? saveNomenclatures.variables : excludedNomenclatures
  const supplierCodes =
    saveSuppliers.isPending && saveSuppliers.variables
      ? saveSuppliers.variables
      : excludedSuppliers.map((supplier) => supplier.code)
  const active = excludedNomenclatures.length + excludedSuppliers.length
  const nameOf = (code: string) => nomenclatures.find((item) => item.code === code)?.name
  const options = exclusions?.supplierOptions ?? []

  // Pomijany dostawca, którego nie ma już na liście dostawców, też musi mieć swoją etykietę.
  const supplierOptions = [
    ...excludedSuppliers.filter((skippedSupplier) => !options.some((item) => item.code === skippedSupplier.code)),
    ...options,
  ].map((supplier) => ({ value: supplier.code, label: supplierLabel(supplier) }))

  const count = (value: number | undefined) => (value ? ` Pominięte teraz: ${value}.` : '')

  return (
    <>
      <Button
        variant="outline"
        size="icon-lg"
        className="relative"
        aria-label={active > 0 ? `Ustawienia raportu, pomijane: ${active}` : 'Ustawienia raportu'}
        title="Ustawienia raportu: pomijane nomenklatury i dostawcy"
        onClick={() => setOpen(true)}
      >
        <SettingsIcon />
        {active > 0 && (
          <span className="absolute -top-1.5 -right-1.5 min-w-5 rounded-full bg-foreground px-1.5 text-xs leading-5 font-medium text-background tabular-nums">
            {active}
          </span>
        )}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="text-lg">Ustawienia raportu</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          {children}
          {canEdit ? (
            <>
              <MultiComboboxField
                label="Pomijane nomenklatury"
                description={`Rejon pomija też swoje rodziny i podrodziny.${count(skipped?.nomenclatures)}`}
                options={nomenclatures.map((item) => ({
                  value: item.code,
                  label: `${LEVEL_INDENT[item.level]}${item.code} ${item.name}`,
                }))}
                value={nomenclatureCodes}
                placeholder="Wybierz rejony, rodziny albo podrodziny"
                emptyText="Żadna nomenklatura nie pasuje"
                onChange={(codes) =>
                  saveNomenclatures.mutate(codes, { onError: onError('Nie udało się zapisać nomenklatur') })
                }
              />
              <MultiComboboxField
                label="Pomijani dostawcy"
                description={`${supplierHint}${count(skipped?.suppliers)}`}
                options={supplierOptions}
                value={supplierCodes}
                placeholder="Wybierz dostawców"
                emptyText="Żaden dostawca nie pasuje"
                onChange={(codes) =>
                  saveSuppliers.mutate(codes, { onError: onError('Nie udało się zapisać dostawców') })
                }
              />
            </>
          ) : (
            <FormNote>
              {active === 0
                ? 'Raport niczego nie pomija.'
                : [
                    excludedNomenclatures.length > 0 &&
                      `Nomenklatury: ${excludedNomenclatures
                        .map((code) => [code, nameOf(code)].filter(Boolean).join(' '))
                        .join(', ')}.`,
                    excludedSuppliers.length > 0 && `Dostawcy: ${excludedSuppliers.map(supplierLabel).join(', ')}.`,
                  ]
                    .filter(Boolean)
                    .join(' ')}
            </FormNote>
          )}
          {note}
        </DialogContent>
      </Dialog>
    </>
  )
}
