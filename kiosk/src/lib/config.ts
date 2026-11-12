import Constants from 'expo-constants'

import { readStored, writeStored } from './storage'
import type { UsbFormat } from './uid'

const SERVER_KEY = 'kiosk.server'
const READER_KEY = 'kiosk.readerKey'
const USB_FORMAT_KEY = 'kiosk.usbFormat'

export const DEFAULT_SERVER: string =
  (Constants.expoConfig?.extra as { apiUrl?: string } | undefined)?.apiUrl ?? 'https://intermanage.pl'

/** Ustawienia urządzenia: serwer, klucz czytnika z Ustawień Czasu pracy i format czytnika USB. */
export type KioskConfig = {
  server: string
  readerKey: string | null
  usbFormat: UsbFormat
}

let current: KioskConfig = { server: DEFAULT_SERVER, readerKey: null, usbFormat: 'AUTO' }

export const config = () => current

export async function loadConfig() {
  const [server, readerKey, usbFormat] = await Promise.all([
    readStored(SERVER_KEY),
    readStored(READER_KEY),
    readStored(USB_FORMAT_KEY),
  ])
  current = {
    server: server || DEFAULT_SERVER,
    readerKey: readerKey || null,
    usbFormat: (usbFormat as UsbFormat | null) ?? 'AUTO',
  }
  return current
}

export async function saveConfig(next: KioskConfig) {
  current = next
  await Promise.all([
    writeStored(SERVER_KEY, next.server),
    writeStored(READER_KEY, next.readerKey),
    writeStored(USB_FORMAT_KEY, next.usbFormat),
  ])
}

export function normalizeServer(url: string) {
  let value = url.trim().replace(/\/+$/, '')
  if (value && !/^https?:\/\//i.test(value)) {
    // Adres w sieci lokalnej to zwykle backend bez HTTPS.
    const local = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/i.test(value)
    value = `${local ? 'http' : 'https'}://${value}`
  }
  return value
}
