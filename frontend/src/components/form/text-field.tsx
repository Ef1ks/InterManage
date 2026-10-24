import { useId, useState, type ComponentProps, type ReactNode } from 'react'
import { Input as InputPrimitive } from '@base-ui/react/input'
import { EyeIcon, EyeOffIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatingLabelClass } from '@/components/form/field-styles'
import { cn } from '@/lib/utils'

type TextFieldProps = Omit<ComponentProps<'input'>, 'placeholder'> & {
  label: string
  error?: string
  hint?: ReactNode
}

/**
 * Pole tekstowe z pływającą etykietą. Działa z `register()` z react-hook-form (ref przekazywany dalej).
 * Dla `type="password"` dokłada przycisk pokazywania hasła.
 */
export function TextField({ label, error, hint, id, type = 'text', className, ...props }: TextFieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId
  const [revealed, setRevealed] = useState(false)
  const isPassword = type === 'password'

  return (
    <FieldShell id={fieldId} error={error} hint={hint} className={className}>
      <InputPrimitive
        id={fieldId}
        type={isPassword && revealed ? 'text' : type}
        // Spacja jako placeholder: dzięki :placeholder-shown CSS wie, czy pole jest puste (także przy register()).
        placeholder=" "
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, error, hint)}
        className={cn(fieldControlClass, isPassword && 'pr-11')}
        {...props}
      />
      <label
        htmlFor={fieldId}
        className={cn(
          floatingLabelClass,
          'peer-focus:-translate-y-2.5 peer-focus:scale-[0.8] peer-focus:text-foreground',
          'peer-[:not(:placeholder-shown)]:-translate-y-2.5 peer-[:not(:placeholder-shown)]:scale-[0.8]',
          'peer-autofill:-translate-y-2.5 peer-autofill:scale-[0.8]',
          'peer-aria-invalid:text-destructive peer-aria-invalid:peer-focus:text-destructive',
        )}
      >
        {label}
      </label>
      {isPassword && (
        <button
          type="button"
          onClick={() => setRevealed((value) => !value)}
          aria-label={revealed ? 'Ukryj hasło' : 'Pokaż hasło'}
          aria-pressed={revealed}
          aria-controls={fieldId}
          className="absolute top-1/2 right-1.5 flex size-9 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground transition-[color,background-color,transform] duration-150 ease-snappy outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/30 active:scale-[0.94] [&_svg]:size-4"
        >
          {revealed ? <EyeOffIcon /> : <EyeIcon />}
        </button>
      )}
    </FieldShell>
  )
}
