import { Platform } from 'react-native'
import NfcManager, { NfcAdapter, NfcEvents, NfcTech, type TagEvent } from 'react-native-nfc-manager'

import { normalizeUid } from './uid'

/**
 * NFC urządzenia (telefon, tablet z NFC). Odbicia czytają tylko UID karty, a programowanie zapisuje na karcie
 * MIFARE Classic 1K numer RCP pracownika. Pracownika i tak wskazuje UID (tak liczy backend), więc zapis to
 * oznaczenie karty, które da się sprawdzić bez serwera, a nie klucz dostępu.
 *
 * Zapis MIFARE Classic działa tylko na urządzeniach z układem NFC od NXP; inne (część Samsungów) czytają sam UID.
 * Wtedy karta jest przypisana tylko numerem, a aplikacja mówi, dlaczego nic nie zapisała.
 */

export type NfcState = 'unsupported' | 'disabled' | 'ready'

/** Wszystkie operacje na NFC idą po kolei: odbicia i programowanie nie mogą naraz trzymać czytnika. */
let chain: Promise<unknown> = Promise.resolve()
function exclusive<T>(task: () => Promise<T>): Promise<T> {
  const next = chain.then(task, task)
  chain = next.catch(() => undefined)
  return next
}

let started: Promise<boolean> | null = null

export async function nfcState(): Promise<NfcState> {
  if (Platform.OS !== 'android') return 'unsupported'
  started ??= NfcManager.isSupported()
    .then(async (supported) => {
      if (supported) await NfcManager.start()
      return supported
    })
    .catch(() => false)
  if (!(await started)) return 'unsupported'
  return (await NfcManager.isEnabled().catch(() => false)) ? 'ready' : 'disabled'
}

export const openNfcSettings = () => NfcManager.goToNfcSetting().catch(() => false)

/** Tryb czytnika: bez dźwięku systemu i bez szukania NDEF (MIFARE Classic go nie ma), karty ISO 14443A. */
const READER_OPTIONS = {
  isReaderModeEnabled: true,
  readerModeFlags:
    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
}

/** Ciągłe czytanie kart do odbić. Zwraca funkcję, która kończy nasłuch. */
export function listenForCards(onCard: (uid: string) => void): () => void {
  let active = true
  NfcManager.setEventListener(NfcEvents.DiscoverTag, (tag: TagEvent) => {
    const uid = tag.id ? normalizeUid(tag.id) : ''
    if (active && uid) onCard(uid)
  })
  void exclusive(() => NfcManager.registerTagEvent(READER_OPTIONS)).catch(() => undefined)
  return () => {
    active = false
    NfcManager.setEventListener(NfcEvents.DiscoverTag, null)
    void exclusive(() => NfcManager.unregisterTagEvent()).catch(() => undefined)
  }
}

// --- MIFARE Classic 1K -------------------------------------------------------------------------------------------

/** Sektor 1, blok 0 (blok 4 karty): pierwszy blok danych poza sektorem 0 z numerem producenta. */
const DATA_SECTOR = 1
/** Klucz fabryczny kart MIFARE Classic. */
const DEFAULT_KEY = [0xff, 0xff, 0xff, 0xff, 0xff, 0xff]
/** „IMRC” + wersja: po tym aplikacja poznaje swoją kartę. */
const MAGIC = [0x49, 0x4d, 0x52, 0x43, 0x01]

export type CardRead = {
  uid: string
  /** Numer RCP zapisany na karcie; `null` = karta bez naszego zapisu albo nieczytelna. */
  rcpNumber: number | null
  /** Dlaczego nie da się czytać ani zapisywać pamięci karty (UID i tak jest). */
  memoryProblem: string | null
}

export type CardWrite = { uid: string; written: boolean; problem: string | null }

function encode(rcpNumber: number) {
  const block = new Array<number>(16).fill(0)
  MAGIC.forEach((byte, i) => (block[i] = byte))
  block[5] = (rcpNumber >>> 24) & 0xff
  block[6] = (rcpNumber >>> 16) & 0xff
  block[7] = (rcpNumber >>> 8) & 0xff
  block[8] = rcpNumber & 0xff
  return block
}

function decode(block: number[]) {
  if (block.length < 9 || MAGIC.some((byte, i) => block[i] !== byte)) return null
  return ((block[5] << 24) | (block[6] << 16) | (block[7] << 8) | block[8]) >>> 0
}

type Session = { uid: string; mifare: boolean }

/** Czeka na kartę i trzyma ją na czas `work`; `cancel()` przerywa czekanie (np. wyjście z ekranu). */
function withCard<T>(work: (session: Session) => Promise<T>): Promise<T> {
  return exclusive(async () => {
    try {
      const tech = await NfcManager.requestTechnology([NfcTech.MifareClassic, NfcTech.NfcA], READER_OPTIONS)
      const tag = await NfcManager.getTag({ readNdef: false })
      const uid = tag?.id ? normalizeUid(tag.id) : ''
      if (!uid) throw new Error('Nie udało się odczytać numeru karty. Przyłóż ją jeszcze raz.')
      return await work({ uid, mifare: tech === NfcTech.MifareClassic })
    } finally {
      await NfcManager.cancelTechnologyRequest({ throwOnError: false }).catch(() => undefined)
    }
  })
}

export const cancelCardRequest = () =>
  NfcManager.cancelTechnologyRequest({ throwOnError: false }).catch(() => undefined)

const NO_MIFARE =
  'To urządzenie czyta tylko numer karty, a nie jej pamięć (zapis MIFARE Classic wymaga układu NFC od NXP).'

async function authenticate() {
  const mifare = NfcManager.mifareClassicHandlerAndroid
  const ok = await mifare.mifareClassicAuthenticateA(DATA_SECTOR, DEFAULT_KEY).catch(() => false)
  if (!ok) throw new Error('Karta ma zmieniony klucz pamięci, nie da się jej zaprogramować.')
  return mifare.mifareClassicSectorToBlock(DATA_SECTOR)
}

/** Odczyt karty: UID i numer RCP z pamięci, gdy urządzenie i karta na to pozwalają. */
export function readCard(): Promise<CardRead> {
  return withCard(async ({ uid, mifare }) => {
    if (!mifare) return { uid, rcpNumber: null, memoryProblem: NO_MIFARE }
    try {
      const block = await authenticate()
      const data = await NfcManager.mifareClassicHandlerAndroid.mifareClassicReadBlock(block)
      return { uid, rcpNumber: decode(data), memoryProblem: null }
    } catch (error) {
      return { uid, rcpNumber: null, memoryProblem: error instanceof Error ? error.message : String(error) }
    }
  })
}

/**
 * Programowanie: zapisuje numer RCP w bloku danych i czyta go z powrotem. `canWrite` sprawdza UID przed zapisem
 * (np. czy karta nie należy już do kogoś innego); rzucony błąd przerywa bez zapisu.
 */
export function programCard(rcpNumber: number, canWrite: (uid: string) => void): Promise<CardWrite> {
  return withCard(async ({ uid, mifare }) => {
    canWrite(uid)
    if (!mifare) return { uid, written: false, problem: NO_MIFARE }
    const block = await authenticate()
    const data = encode(rcpNumber)
    await NfcManager.mifareClassicHandlerAndroid.mifareClassicWriteBlock(block, data)
    const check = await NfcManager.mifareClassicHandlerAndroid.mifareClassicReadBlock(block)
    if (decode(check) !== rcpNumber) throw new Error('Zapis się nie powiódł (karta odsunięta za wcześnie?).')
    return { uid, written: true, problem: null }
  })
}
