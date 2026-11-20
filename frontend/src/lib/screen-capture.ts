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

/**
 * Czeka, aż strona przestanie zmieniać rozmiar: po rozpoczęciu udostępniania Chrome dokłada nad kartą pasek
 * „Udostępniasz tę kartę”, okno robi się niższe i strona układa się od nowa. Zrzut w trakcie łapał stary układ.
 */
function settledSize(quietMs: number, maxMs: number) {
  return new Promise<void>((resolve) => {
    let quiet = setTimeout(done, quietMs)
    const limit = setTimeout(done, maxMs)
    function onResize() {
      clearTimeout(quiet)
      quiet = setTimeout(done, quietMs)
    }
    function done() {
      clearTimeout(quiet)
      clearTimeout(limit)
      window.removeEventListener('resize', onResize)
      resolve()
    }
    window.addEventListener('resize', onResize)
  })
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
    // Natywny rozmiar karty, bez skalowania i przycinania do proporcji (żądany 16:9 ucinał dół strony).
    video: { displaySurface: 'browser', resizeMode: 'none', frameRate: { ideal: 10 } },
    audio: false,
    preferCurrentTab: true,
    selfBrowserSurface: 'include',
    surfaceSwitching: 'exclude',
    monitorTypeSurfaces: 'exclude',
  } as DisplayMediaStreamOptions
  // Wysokość okna przed paskiem „Udostępniasz tę kartę”: zrzut ma objąć tyle, ile było widać przed kliknięciem.
  const heightBefore = window.innerHeight
  const stream = await navigator.mediaDevices.getDisplayMedia(options)
  const root = document.documentElement
  const zoomBefore = root.style.zoom
  root.classList.add(CAPTURE_CLASS)
  try {
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.srcObject = stream
    await video.play()
    await settledSize(400, 2000)
    // Pasek Chrome zabiera wysokość okna, więc dół strony by się nie zmieścił. Na czas zrzutu strona jest
    // pomniejszona dokładnie o tyle: układ ma znowu dawną wysokość, a wchodzi w niższe okno.
    const heightNow = window.innerHeight
    if (heightNow < heightBefore - 1) {
      root.style.zoom = String(heightNow / heightBefore)
    }
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
    root.style.zoom = zoomBefore
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
