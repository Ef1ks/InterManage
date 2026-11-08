import { useState } from 'react'
import { ShieldIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, SelectField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useEmployeeActions, useStaffStructure } from '@/features/staff/api'
import { ShiftPicker } from '@/features/staff/ShiftPicker'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { StaffEmployee, StaffShift, StoreSummary } from '@/lib/types'

type Props = {
  storeId: string
  stores: StoreSummary[]
  /** Brak = okno zamknięte. */
  employee?: StaffEmployee
  onClose: () => void
}

/** Przeniesienie pracownika do innego sklepu firmy: konto i login zostają. Stanowiska są wspólne, więc może zostać. */
export function TransferDialog({ storeId, stores, employee, onClose }: Props) {
  return (
    <Dialog open={employee !== undefined} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        {employee && (
          <TransferForm
            key={employee.id}
            storeId={storeId}
            stores={stores.filter((store) => store.id !== employee.storeId)}
            employee={employee}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function TransferForm({
  storeId,
  stores,
  employee,
  onDone,
}: {
  storeId: string
  stores: StoreSummary[]
  employee: StaffEmployee
  onDone: () => void
}) {
  const { transfer } = useEmployeeActions(storeId)
  const [targetId, setTargetId] = useState(stores.length === 1 ? stores[0].id : '')
  const [positionId, setPositionId] = useState(employee.positionId)
  const [shifts, setShifts] = useState<StaffShift[]>(employee.shifts)
  const [error, setError] = useState<string | null>(null)

  // Stanowiska są wspólne, ale godziny do wyboru ma każdy sklep swoje.
  const { data: departments = [] } = useStaffStructure(targetId || storeId)
  const positions = departments.flatMap((department) =>
    department.positions.map((position) => ({ ...position, departmentName: department.name })),
  )
  const position = positions.find((candidate) => candidate.id === positionId)
  const target = stores.find((store) => store.id === targetId)

  const failed = (cause: unknown) => (cause instanceof ApiError ? cause.message : 'Nie udało się przenieść pracownika')

  const submit = () => {
    if (!target) return
    setError(null)
    transfer.mutate(
      { id: employee.id, request: { storeId: target.id, positionId, shifts } },
      {
        onSuccess: () => {
          toast.success(`${employee.displayName} pracuje teraz w sklepie ${target.number}`, {
            action: {
              label: 'Cofnij',
              onClick: () =>
                transfer.mutate(
                  {
                    id: employee.id,
                    request: { storeId: employee.storeId, positionId: employee.positionId, shifts: employee.shifts },
                  },
                  { onError: (cause) => toast.error(failed(cause)) },
                ),
            },
          })
          onDone()
        },
        onError: (cause) => setError(failed(cause)),
      },
    )
  }

  return (
    <Form
      error={error}
      onSubmit={(event) => {
        event.preventDefault()
        submit()
      }}
    >
      <DialogHeader>
        <DialogTitle className="text-lg">Przenieś: {employee.displayName}</DialogTitle>
        <DialogDescription>Konto i login zostają. Stanowisko i godziny możesz od razu zmienić.</DialogDescription>
      </DialogHeader>

      <SelectField
        label="Sklep"
        options={stores.map((store) => ({ value: store.id, label: storeLabel(store) }))}
        value={targetId}
        onChange={setTargetId}
      />
      <SelectField
        label="Stanowisko"
        options={positions.map((candidate) => ({
          value: candidate.id,
          label: `${candidate.departmentName} · ${candidate.name}`,
        }))}
        value={positionId}
        onChange={setPositionId}
      />

      {position && (
        <FormNote icon={<ShieldIcon />}>
          {employee.ownerAccount
            ? 'Właściciel zostaje właścicielem, stanowisko nie zmienia jego uprawnień.'
            : `Uprawnienia w aplikacji: rola ${position.roleName}. Dostęp do obecnego sklepu zostanie zamieniony na nowy.`}
        </FormNote>
      )}

      <div className="grid gap-2">
        <p className="text-sm text-muted-foreground">Stałe godziny pracy</p>
        <ShiftPicker value={shifts} onChange={setShifts} options={position?.shifts ?? []} addLabel="Inne godziny" />
      </div>

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onDone}>
          Anuluj
        </Button>
        <Button type="submit" size="lg" disabled={!target || !position} loading={transfer.isPending}>
          Przenieś
        </Button>
      </FormActions>
    </Form>
  )
}
