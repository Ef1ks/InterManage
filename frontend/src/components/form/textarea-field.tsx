import { useId, type ComponentProps, type ReactNode } from 'react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatingLabelClass } from '@/components/form/field-styles'
import { cn } from '@/lib/utils'

type TextareaFieldProps = Omit<ComponentProps<'textarea'>, 'placeholder'> & {
  label: string
  error?: string
  hint?: ReactNode
}

/** Pole wielowierszowe (notatki) z pływającą etykietą jak {@link TextField}; działa z `register()`. */
export function TextareaField({ label, error, hint, id, rows = 3, className, ...props }: TextareaFieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId

  return (
    <FieldShell id={fieldId} error={error} hint={hint} className={className}>
      <textarea
        id={fieldId}
        rows={rows}
        placeholder=" "
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, error, hint)}
        className={cn(fieldControlClass, 'h-auto resize-y pt-6 leading-relaxed')}
        {...props}
      />
      <label
        htmlFor={fieldId}
        className={cn(
          floatingLabelClass,
          'peer-focus:-translate-y-2.5 peer-focus:scale-[0.8] peer-focus:text-foreground',
          'peer-[:not(:placeholder-shown)]:-translate-y-2.5 peer-[:not(:placeholder-shown)]:scale-[0.8]',
          'peer-aria-invalid:text-destructive peer-aria-invalid:peer-focus:text-destructive',
        )}
      >
        {label}
      </label>
    </FieldShell>
  )
}
