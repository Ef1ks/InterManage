import { ChevronDownIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'

/** Kolor mówi, do czego służy przycisk paska: wczytanie, tworzenie plików, AI, uwaga, zapis. */
export const ACTION_TONES = {
  load: 'border-sky-300 bg-sky-200 text-sky-950 hover:bg-sky-300 hover:text-sky-950 aria-expanded:bg-sky-300 aria-expanded:text-sky-950 dark:border-sky-700 dark:bg-sky-900 dark:text-sky-50 dark:hover:bg-sky-800 dark:hover:text-sky-50 dark:aria-expanded:bg-sky-800 dark:aria-expanded:text-sky-50',
  make: 'border-violet-300 bg-violet-200 text-violet-950 hover:bg-violet-300 hover:text-violet-950 aria-expanded:bg-violet-300 aria-expanded:text-violet-950 dark:border-violet-700 dark:bg-violet-900 dark:text-violet-50 dark:hover:bg-violet-800 dark:hover:text-violet-50 dark:aria-expanded:bg-violet-800 dark:aria-expanded:text-violet-50',
  ai: 'border-fuchsia-300 bg-fuchsia-200 text-fuchsia-950 hover:bg-fuchsia-300 hover:text-fuchsia-950 dark:border-fuchsia-700 dark:bg-fuchsia-900 dark:text-fuchsia-50 dark:hover:bg-fuchsia-800 dark:hover:text-fuchsia-50',
  warn: 'border-amber-400 bg-amber-200 text-amber-950 hover:bg-amber-300 hover:text-amber-950 aria-expanded:bg-amber-300 aria-expanded:text-amber-950 dark:border-amber-700 dark:bg-amber-900 dark:text-amber-50 dark:hover:bg-amber-800 dark:hover:text-amber-50 dark:aria-expanded:bg-amber-800 dark:aria-expanded:text-amber-50',
  save: 'border-emerald-800 bg-emerald-700 text-white hover:bg-emerald-800 hover:text-white aria-expanded:bg-emerald-800 aria-expanded:text-white dark:border-emerald-600 dark:bg-emerald-700 dark:text-white dark:hover:bg-emerald-600 dark:hover:text-white dark:aria-expanded:bg-emerald-600 dark:aria-expanded:text-white',
} as const

/** Przycisk paska z listą działań; bez {@code tone} zwykły obrysowany. */
export function ActionMenu({
  label,
  icon,
  tone,
  loading,
  disabled,
  title,
  className,
  children,
}: {
  label: React.ReactNode
  icon: React.ReactNode
  tone?: keyof typeof ACTION_TONES
  loading?: boolean
  disabled?: boolean
  title?: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="outline"
            size="sm"
            className={cn(tone && ACTION_TONES[tone], className)}
            loading={loading}
            disabled={disabled}
            title={title}
          />
        }
      >
        {icon}
        {label}
        <ChevronDownIcon className="ml-auto" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-80">{children}</DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * Pozycja menu paska. Podpowiedź przy pozycji nieaktywnej jest widoczna pod nazwą, bo na wyłączonej pozycji dymek
 * się nie pokazuje, a pracownik musi wiedzieć, co ją blokuje.
 */
export function ActionItem({
  icon,
  label,
  count,
  hint,
  disabled,
  onClick,
}: {
  icon: React.ReactNode
  label: string
  count?: number
  hint?: string
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <DropdownMenuItem
      disabled={disabled}
      onClick={onClick}
      title={disabled ? undefined : hint}
      className="items-start data-disabled:text-muted-foreground data-disabled:opacity-100 [&_svg]:mt-0.5"
    >
      {icon}
      <span className="grid min-w-0 flex-1">
        <span>{label}</span>
        {disabled && hint && <span className="text-xs text-pretty">{hint}</span>}
      </span>
      {count !== undefined && count > 0 && <span className="font-medium tabular-nums">{count}</span>}
    </DropdownMenuItem>
  )
}
