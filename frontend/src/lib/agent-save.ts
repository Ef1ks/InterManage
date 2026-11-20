import { toast } from 'sonner'

import { api, ApiError } from '@/lib/api'
import { onLiveMessage, type LiveMessage } from '@/lib/live-events'

/*
 * Zapis pliku przez agenta sklepu. Plik idzie do kolejki sklepu, agent czeka na nią stałym połączeniem i zapisuje go
 * w folderze w 1-2 s, a serwer daje znać na żywo (temat `file-queue`, `saved`). Ekran pokazuje „Czeka na agenta…”,
 * potem potwierdzenie z godziną. Gdy w 15 s nie ma potwierdzenia albo agentowi się nie udało, toast proponuje
 * „Zapisz z tej przeglądarki”: przydział pliku (`claim`, więc agent już go nie zapisze), zapis jak dotąd i `saved`.
 * Plik nigdy nie zapisze się dwa razy: zapisuje tylko ten, kto ma przydział.
 */

const FALLBACK_AFTER_MS = 15_000
/** Zapas na wypadek zerwanego strumienia na żywo: stan pliku sprawdzamy też sami. */
const POLL_MS = 4_000

type FileStatus = {
  id: string
  fileName: string
  state: 'WAITING' | 'CLAIMED' | 'SAVED' | 'FAILED' | 'REMOVED'
  savedAt: string | null
  savedByAgent: boolean
  claimedBy: string | null
  error: string | null
}

const time = (value?: string | null) =>
  (value ? new Date(value) : new Date()).toLocaleTimeString('pl-PL', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })

/**
 * Wysyła plik do agenta i czeka na zapis. Kończy się, gdy plik leży w folderze (agent albo zapas z przeglądarki);
 * błąd, gdy użytkownik zostawi plik w kolejce albo zapas się nie uda.
 */
export async function saveViaAgent(
  storeId: string,
  fileId: string,
  fileName: string,
  writeHere: () => Promise<void>,
): Promise<void> {
  const toastId = `agent-save:${fileId}`
  toast.loading(`Czeka na agenta sklepu… ${fileName}`, { id: toastId, duration: Infinity })

  return new Promise<void>((resolve, reject) => {
    let done = false
    let fallbackShown = false
    const cleanup: Array<() => void> = []
    const finish = (error?: Error) => {
      if (done) return
      done = true
      cleanup.forEach((fn) => fn())
      if (error) reject(error)
      else resolve()
    }

    const saved = (at?: string | null, byAgent = true) => {
      toast.success(`Zapisano w folderze: ${fileName} · ${time(at)}`, {
        id: toastId,
        duration: 5_000,
        description: byAgent ? 'Zapisał agent sklepu.' : 'Zapisano z tej przeglądarki.',
      })
      finish()
    }

    const leaveInQueue = () => {
      toast.message(`Plik ${fileName} czeka w kolejce sklepu`, {
        id: toastId,
        duration: 6_000,
        description: 'Zapisze go agent sklepu albo „Zapisz wszystkie” w kolejce plików.',
      })
      finish(new ApiError(0, `Plik ${fileName} czeka w kolejce sklepu na zapis.`))
    }

    const fallback = async () => {
      toast.loading(`Zapisuję z tej przeglądarki… ${fileName}`, { id: toastId, duration: Infinity })
      try {
        await api(`/stores/${storeId}/file-queue/${fileId}/claim`, { method: 'POST' })
      } catch (error) {
        offerFallback(error instanceof ApiError ? error.message : 'Nie udało się przydzielić pliku.')
        return
      }
      try {
        await writeHere()
      } catch (error) {
        const message = error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku w folderze.'
        await api(`/stores/${storeId}/file-queue/${fileId}/failed`, { method: 'POST', json: { error: message } }).catch(
          () => {},
        )
        toast.error(message, { id: toastId, duration: 8_000 })
        finish(error instanceof Error ? error : new ApiError(0, message))
        return
      }
      try {
        await api(`/stores/${storeId}/file-queue/${fileId}/saved`, { method: 'POST' })
      } catch {
        // Plik już leży w folderze; potwierdzenie dojdzie przy kolejnym zapisie z kolejki albo zostanie do usunięcia.
      }
      saved(null, false)
    }

    const offerFallback = (reason: string) => {
      fallbackShown = true
      toast.warning(reason, {
        id: toastId,
        duration: Infinity,
        action: { label: 'Zapisz z tej przeglądarki', onClick: () => void fallback() },
        cancel: { label: 'Zostaw w kolejce', onClick: leaveInQueue },
      })
    }

    const apply = (status: Pick<FileStatus, 'state' | 'savedAt' | 'savedByAgent' | 'error'>) => {
      if (done) return
      if (status.state === 'SAVED') saved(status.savedAt, status.savedByAgent)
      else if (status.state === 'REMOVED') leaveInQueue()
      else if (status.state === 'FAILED' && !fallbackShown) {
        offerFallback(`Agent nie zapisał pliku: ${status.error ?? 'błąd zapisu'}`)
      }
    }

    cleanup.push(
      onLiveMessage((messageStore, message: LiveMessage) => {
        if (messageStore !== storeId || message.topic !== 'file-queue' || message.id !== fileId) return
        if (message.action === 'saved') {
          apply({
            state: 'SAVED',
            savedAt: (message.at as string) ?? null,
            savedByAgent: message.by === 'agent',
            error: null,
          })
        } else if (message.action === 'failed' && message.by === 'agent') {
          apply({ state: 'FAILED', savedAt: null, savedByAgent: true, error: (message.error as string) ?? null })
        }
      }),
    )
    const poll = window.setInterval(() => {
      api<FileStatus>(`/stores/${storeId}/file-queue/${fileId}`)
        .then(apply)
        .catch(() => {})
    }, POLL_MS)
    cleanup.push(() => window.clearInterval(poll))
    const timer = window.setTimeout(() => {
      if (!done && !fallbackShown) offerFallback('Agent sklepu nie potwierdził zapisu w 15 s.')
    }, FALLBACK_AFTER_MS)
    cleanup.push(() => window.clearTimeout(timer))
  })
}
