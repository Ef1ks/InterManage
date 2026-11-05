import { PlusIcon, XIcon } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { Form, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { parseNumber } from '@/features/data-entry/grid-values'

type Props = {
  levels: number[]
  onChange: (levels: number[]) => void
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Stopnie automatycznych przecen do wyboru przy artykule: dowolne, od 1 do 99%. */
export function DiscountLevelsDialog({ levels, onChange, open, onOpenChange }: Props) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<{ level: string }>({ defaultValues: { level: '' } })

  const add = handleSubmit(({ level }) => {
    onChange([...levels, parseNumber(level) as number].sort((a, b) => a - b))
    reset()
  })

  const remove = (level: number) => {
    onChange(levels.filter((candidate) => candidate !== level))
    toast.success(`Usunięto przecenę -${level}%`, {
      action: { label: 'Przywróć', onClick: () => onChange(levels) },
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Automatyczne przeceny</DialogTitle>
          <DialogDescription>
            Obniżka od ceny z kartoteki do wyboru przy każdym artykule. Usunięcie stopnia nie zmienia cen już wpisanych.
          </DialogDescription>
        </DialogHeader>

        {levels.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nie ma jeszcze żadnej przeceny.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {levels.map((level) => (
              <li
                key={level}
                className="inline-flex h-9 items-center gap-1 rounded-lg border bg-muted pr-1 pl-3 text-sm font-medium tabular-nums"
              >
                -{String(level).replace('.', ',')}%
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Usuń przecenę -${level}%`}
                  onClick={() => remove(level)}
                >
                  <XIcon />
                </Button>
              </li>
            ))}
          </ul>
        )}

        <Form onSubmit={add}>
          <div className="flex items-start gap-2">
            <TextField
              label="Nowa przecena (%)"
              inputMode="decimal"
              autoComplete="off"
              autoFocus
              className="flex-1"
              error={errors.level?.message}
              {...register('level', {
                validate: (value) => {
                  const parsed = parseNumber(value)
                  if (parsed === null) return 'Podaj obniżkę w %'
                  if (parsed === undefined || parsed <= 0 || parsed >= 100) return 'Obniżka to liczba od 1 do 99'
                  return !levels.includes(parsed) || 'Taka przecena już jest'
                },
              })}
            />
            <Button type="submit" size="xl">
              <PlusIcon />
              Dodaj
            </Button>
          </div>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
