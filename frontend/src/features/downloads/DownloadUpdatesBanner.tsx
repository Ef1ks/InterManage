import { useState } from 'react'
import { SparklesIcon } from 'lucide-react'
import { useLocation } from 'react-router'

import { Button } from '@/components/ui/button'
import { useDownloads, useMarkDownloadSeen, type Download } from '@/features/downloads/api'
import { DownloadButton, WhatsNewDialog } from '@/features/downloads/changes'
import { versionName } from '@/features/downloads/format'
import { downloadsPath } from '@/features/downloads/paths'

/**
 * Pasek pod nagłówkiem aplikacji, na każdej stronie: nowa wersja programu z „Do pobrania”, której to konto jeszcze
 * nie pobrało. Znika po pobraniu albo „Mam już tę wersję” (zapisane na serwerze, więc też na innych komputerach).
 */
export function DownloadUpdatesBanner() {
  const { pathname } = useLocation()
  const { data: downloads } = useDownloads()
  const pending = downloads?.filter((download) => download.newVersion && download.file) ?? []
  // Na stronie „Do pobrania” nowe wersje są wyróżnione na kartach.
  if (pending.length === 0 || pathname === downloadsPath) return null
  return (
    <div className="flex flex-col border-b border-sky-300 bg-sky-100 text-sky-950 dark:border-sky-800 dark:bg-sky-950/70 dark:text-sky-50">
      {pending.map((download) => (
        <BannerRow key={download.id} download={download} />
      ))}
    </div>
  )
}

function BannerRow({ download }: { download: Download }) {
  const [whatsNew, setWhatsNew] = useState(false)
  const seen = useMarkDownloadSeen()
  const changes = download.file?.changes.length ?? 0
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 not-last:border-b not-last:border-sky-200 md:px-6 dark:not-last:border-sky-900">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <SparklesIcon className="size-5 shrink-0 text-sky-700 dark:text-sky-300" aria-hidden />
        <p className="min-w-0 text-sm">
          <span className="font-semibold">Nowa wersja: {versionName(download)}.</span>{' '}
          <span className="text-sky-900 dark:text-sky-200">
            Pobierz ją i zainstaluj na komputerze, na którym pracujesz.
          </span>
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {changes > 0 && (
          <Button variant="outline" className="bg-background/70" onClick={() => setWhatsNew(true)}>
            Co nowego ({changes})
          </Button>
        )}
        <Button variant="ghost" onClick={() => seen.mutate(download.id)}>
          Mam już tę wersję
        </Button>
        <DownloadButton download={download} label="Pobierz" size="default" />
      </div>
      <WhatsNewDialog download={download} open={whatsNew} onOpenChange={setWhatsNew} />
    </div>
  )
}

/** Licznik w menu przy „Do pobrania”: nowe wersje, których to konto nie pobrało. */
export function NewDownloadsBadge() {
  const { data: downloads } = useDownloads()
  const count = downloads?.filter((download) => download.newVersion && download.file).length ?? 0
  if (count === 0) return null
  return (
    <span
      aria-label={`${count} nowe wersje do pobrania`}
      className="rounded-full bg-sky-600 px-1.5 text-[0.7rem] leading-[1.125rem] font-semibold text-white tabular-nums animate-in zoom-in-50 fade-in-0 duration-200 motion-reduce:animate-none"
    >
      {count}
    </span>
  )
}
