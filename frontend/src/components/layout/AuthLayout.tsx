import type { ReactNode } from 'react'

import { BrandMark } from '@/components/BrandMark'

type Props = {
  title: string
  description?: ReactNode
  children: ReactNode
  /** Treść pod panelem, np. podpowiedź albo link. */
  footer?: ReactNode
}

/** Ekrany poza aplikacją (logowanie, aktywacja konta, ustawienie hasła): jeden panel na spokojnym tle. */
export function AuthLayout({ title, description, children, footer }: Props) {
  return (
    <div className="flex min-h-dvh flex-col bg-muted/50 px-4 py-6 sm:py-10">
      <header className="mx-auto w-full max-w-sm sm:max-w-md">
        <BrandMark />
      </header>

      <main className="flex flex-1 items-center justify-center py-10">
        <div className="w-full max-w-sm animate-in fade-in-0 slide-in-from-bottom-2 duration-300 sm:max-w-md motion-reduce:animate-none">
          <div className="rounded-2xl bg-background p-6 shadow-[0_1px_2px_rgb(0_0_0/0.04),0_12px_32px_-12px_rgb(0_0_0/0.12)] ring-1 ring-foreground/6 sm:p-8">
            <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
            {description && <p className="mt-1.5 text-sm text-muted-foreground">{description}</p>}
            <div className="mt-6">{children}</div>
          </div>
          {footer && <div className="mt-5 px-2 text-center text-sm text-balance text-muted-foreground">{footer}</div>}
        </div>
      </main>
    </div>
  )
}
