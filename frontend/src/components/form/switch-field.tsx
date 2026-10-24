import { useId } from 'react'

import { Switch } from '@/components/ui/switch'
import { cn } from '@/lib/utils'

type SwitchFieldProps = {
  label: string
  description?: string
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  disabled?: boolean
  className?: string
}

/** Wiersz z przełącznikiem: cała powierzchnia jest klikalna, opis tłumaczy skutki zmiany. */
export function SwitchField({ label, description, checked, onCheckedChange, disabled, className }: SwitchFieldProps) {
  const id = useId()
  return (
    <label
      htmlFor={id}
      className={cn(
        'flex cursor-pointer items-center justify-between gap-4 rounded-xl border bg-background px-3 py-3 transition-colors duration-150 hover:bg-muted/50 has-disabled:cursor-not-allowed has-disabled:opacity-60',
        className,
      )}
    >
      <span className="grid gap-0.5">
        <span className="text-sm font-medium">{label}</span>
        {description && <span className="text-xs text-muted-foreground">{description}</span>}
      </span>
      <Switch id={id} checked={checked} onCheckedChange={onCheckedChange} disabled={disabled} />
    </label>
  )
}
