import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { ApiError, fetchReader } from './api'
import { loadConfig, saveConfig, type KioskConfig } from './config'
import { loadQueue } from './punches'
import { readStored, writeStored } from './storage'
import type { ReaderInfo } from './types'

const READER_INFO_KEY = 'kiosk.readerInfo'

type KioskState = {
  /** `undefined` = jeszcze wczytujemy ustawienia. */
  config: KioskConfig | undefined
  /** Czytnik i sklep; zapamiętany, więc kiosk startuje też bez internetu. `null` = kiosk nieskonfigurowany. */
  reader: ReaderInfo | null
  /** Klucz czytnika usunięty w Ustawieniach Czasu pracy: odbicia nie przejdą, trzeba skonfigurować kiosk od nowa. */
  revoked: boolean
  configure: (next: KioskConfig, reader: ReaderInfo | null) => Promise<void>
}

const KioskContext = createContext<KioskState | null>(null)

export function KioskProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<KioskConfig | undefined>(undefined)
  const [reader, setReader] = useState<ReaderInfo | null>(null)
  const [revoked, setRevoked] = useState(false)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const [loaded, savedReader] = await Promise.all([loadConfig(), readStored(READER_INFO_KEY), loadQueue()])
      if (cancelled) return
      let known: ReaderInfo | null = null
      try {
        known = savedReader ? (JSON.parse(savedReader) as ReaderInfo) : null
      } catch {
        known = null
      }
      setReader(loaded.readerKey ? known : null)
      setConfig(loaded)
      if (!loaded.readerKey) return
      try {
        const fresh = await fetchReader()
        if (cancelled) return
        setReader(fresh)
        void writeStored(READER_INFO_KEY, JSON.stringify(fresh))
      } catch (error) {
        if (!cancelled && error instanceof ApiError && error.status === 403) setRevoked(true)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const configure = useCallback(async (next: KioskConfig, nextReader: ReaderInfo | null) => {
    await Promise.all([saveConfig(next), writeStored(READER_INFO_KEY, nextReader ? JSON.stringify(nextReader) : null)])
    setConfig(next)
    setReader(nextReader)
    setRevoked(false)
  }, [])

  const value = useMemo(() => ({ config, reader, revoked, configure }), [config, reader, revoked, configure])
  return <KioskContext.Provider value={value}>{children}</KioskContext.Provider>
}

export function useKiosk() {
  const value = useContext(KioskContext)
  if (!value) throw new Error('useKiosk poza KioskProvider')
  return value
}
