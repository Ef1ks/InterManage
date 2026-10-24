import { useId, useState, type ReactNode } from 'react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'

export type SelectOption = { value: string; label: string }

type SelectFieldProps = {
  label: string
  options: SelectOption[]
  /** Pusty string = nic nie wybrano (etykieta zostaje w środku pola). */
  value: string
  onChange: (value: string) => void
  onBlur?: () => void
  error?: string
  hint?: ReactNode
  id?: string
  disabled?: boolean
  className?: string
}

/** Lista wyboru z pływającą etykietą, spójna z {@link TextField}. Z react-hook-form używaj przez `Controller`. */
export function SelectField({
  label,
  options,
  value,
  onChange,
  onBlur,
  error,
  hint,
  id,
  disabled,
  className,
}: SelectFieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId
  const [open, setOpen] = useState(false)
  const floated = open || value !== ''

  return (
    <FieldShell id={fieldId} error={error} hint={hint} className={className}>
      <Select
        items={options}
        value={value || null}
        onValueChange={(next) => onChange((next as string | null) ?? '')}
        open={open}
        onOpenChange={(next) => {
          setOpen(next)
          if (!next) onBlur?.()
        }}
        disabled={disabled}
      >
        <SelectTrigger
          id={fieldId}
          aria-labelledby={`${fieldId}-label`}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(fieldId, error, hint)}
          className={cn(
            fieldControlClass,
            // Pole bez pionowego paddingu (strzałka na środku), tekst wartości schodzi pod etykietę.
            'flex h-12 items-center justify-between gap-2 py-0 pr-3 text-left data-[size=default]:h-12 data-popup-open:border-ring data-popup-open:ring-3 data-popup-open:ring-ring/30',
          )}
        >
          <SelectValue className="truncate pt-3.5" />
        </SelectTrigger>
        <SelectContent alignItemWithTrigger={false} sideOffset={6}>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value} className="py-2">
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <label
        id={`${fieldId}-label`}
        htmlFor={fieldId}
        className={cn(
          floatingLabelClass,
          floated && floatedLabelClass,
          open && 'text-foreground',
          error && 'text-destructive',
        )}
      >
        {label}
      </label>
    </FieldShell>
  )
}
