import type { Download, DownloadChange, DownloadChangeKind, DownloadChangesForm } from '@/features/downloads/api'

export function formatSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} kB`
    : `${(bytes / 1024 / 1024).toLocaleString('pl-PL', { maximumFractionDigits: 1 })} MB`
}

/** Rodzaje wpisów listy zmian w kolejności wyświetlania, z polem formularza. */
export const CHANGE_KINDS: { kind: DownloadChangeKind; field: keyof DownloadChangesForm; label: string }[] = [
  { kind: 'ADDED', field: 'added', label: 'Dodano' },
  { kind: 'CHANGED', field: 'changed', label: 'Zmieniono' },
  { kind: 'FIXED', field: 'fixed', label: 'Naprawiono' },
]

/** Wpisy jednego rodzaju jako tekst do pola formularza (wpis w linii). */
export function changesText(changes: DownloadChange[], kind: DownloadChangeKind) {
  return changes
    .filter((change) => change.kind === kind)
    .map((change) => change.text)
    .join('\n')
}

/** „AutoJob v37” albo sama nazwa, gdy wersja nie ma numeru. */
export function versionName(download: Download) {
  return [download.title, download.file?.version].filter(Boolean).join(' ')
}
