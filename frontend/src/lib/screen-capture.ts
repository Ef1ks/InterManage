/**
 * Zrzut ekranu bieżącej karty przez Screen Capture API (Chrome, Edge: `preferCurrentTab` od razu proponuje tę kartę).
 * Rysowanie strony we własnym zakresie (html2canvas) gubi szkło, rozmycia i zdjęcie w tle, a obraz z przeglądarki
 * wygląda dokładnie jak ekran. Na czas zrzutu `<html>` dostaje klasę `screen-capture`, która chowa okna, listy
 * i powiadomienia (index.css), więc na zrzucie jest sam widok spod okna zadania.
 */

const CAPTURE_CLASS = 'screen-capture'

export function canCaptureScreen() {
  return typeof navigator !== 'undefined' && typeof navigator.mediaDevices?.getDisplayMedia === 'function'
}

/** Odmowa zgody w oknie przeglądarki: nie jest błędem, po prostu nie ma zrzutu. */
export function isCaptureCancelled(error: unknown) {
  return error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError')
}

const nextFrame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** Czeka na klatkę wideo nowszą niż chwila wywołania (karta wysyła klatki tylko po zmianie obrazu). */
function freshFrame(video: HTMLVideoElement, timeoutMs: number) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, timeoutMs)
    if (typeof video.requestVideoFrameCallback === 'function') {
      video.requestVideoFrameCallback(() => {
        clearTimeout(timer)
        resolve()
      })
    }
  })
}

/** Zrzut bieżącej karty jako PNG. Wołać prosto z kliknięcia (okno zgody wymaga gestu użytkownika). */
export async function captureCurrentTab(): Promise<Blob> {
  const options = {
    video: { displaySurface: 'browser', width: { ideal: 3840 }, height: { ideal: 2160 }, frameRate: { ideal: 10 } },
    audio: false,
    preferCurrentTab: true,
    selfBrowserSurface: 'include',
    surfaceSwitching: 'exclude',
    monitorTypeSurfaces: 'exclude',
  } as DisplayMediaStreamOptions
  const stream = await navigator.mediaDevices.getDisplayMedia(options)
  const root = document.documentElement
  root.classList.add(CAPTURE_CLASS)
  try {
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.srcObject = stream
    await video.play()
    // Okna znikają w następnej klatce strony, a obraz karty dochodzi chwilę później.
    await nextFrame()
    await nextFrame()
    await sleep(150)
    await freshFrame(video, 700)
    const canvas = document.createElement('canvas')
    canvas.width = video.videoWidth
    canvas.height = video.videoHeight
    const context = canvas.getContext('2d')
    if (!context || canvas.width === 0) throw new Error('Przeglądarka nie oddała obrazu karty')
    context.drawImage(video, 0, 0)
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('Nie udało się zapisać zrzutu'))), 'image/png'),
    )
  } finally {
    stream.getTracks().forEach((track) => track.stop())
    root.classList.remove(CAPTURE_CLASS)
  }
}

/** Zdjęcie ze schowka (np. po Win+Shift+S); `null`, gdy w schowku nie ma obrazu albo przeglądarka nie pozwala. */
export async function readClipboardImage(): Promise<Blob | null> {
  if (typeof navigator.clipboard?.read !== 'function') return null
  const items = await navigator.clipboard.read()
  for (const item of items) {
    const type = item.types.find((candidate) => candidate.startsWith('image/'))
    if (type) return item.getType(type)
  }
  return null
}

/** Obraz z wklejenia (Ctrl+V); `null`, gdy wklejono tekst. */
export function pastedImage(event: ClipboardEvent): File | null {
  for (const item of Array.from(event.clipboardData?.items ?? [])) {
    if (item.kind === 'file' && item.type.startsWith('image/')) return item.getAsFile()
  }
  return null
}
