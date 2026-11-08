import { useEffect, useState, type CSSProperties } from 'react'

/** Klucz w sessionStorage: każda karta i okno ma własny, więc animacja jest raz na nowe okno, a nie przy odświeżeniu. */
const SHOWN_KEY = 'intermanage.splashShown'

type Motion = 'left' | 'right' | 'bar' | 'accent'

/**
 * Kawałki logo (hero.webp rozbite na spójne fragmenty, `public/splash`), położenie w % całego logo 4640×928.
 * „Inter” wjeżdża z lewej, „manage” z prawej (litery bliżej środka pierwsze), potem pas pod napisem i kreska nad „e”.
 */
const PIECES: {
  src: string
  left: number
  top: number
  width: number
  height: number
  motion: Motion
  order: number
}[] = [
  { src: '/splash/00.webp', left: 0, top: 0, width: 5.668, height: 68.534, motion: 'left', order: 4 },
  { src: '/splash/01.webp', left: 5.151, top: 16.272, width: 11.466, height: 52.371, motion: 'left', order: 3 },
  { src: '/splash/02.webp', left: 17.543, top: 0, width: 6.746, height: 68.642, motion: 'left', order: 2 },
  { src: '/splash/03.webp', left: 24.073, top: 16.272, width: 9.203, height: 51.94, motion: 'left', order: 1 },
  { src: '/splash/04.webp', left: 32.651, top: 16.272, width: 12.091, height: 83.728, motion: 'left', order: 0 },
  { src: '/splash/05.webp', left: 42.069, top: 16.487, width: 12.284, height: 51.724, motion: 'right', order: 0 },
  { src: '/splash/07.webp', left: 54.526, top: 16.272, width: 9.116, height: 52.586, motion: 'right', order: 1 },
  { src: '/splash/08.webp', left: 63.75, top: 16.487, width: 9.31, height: 51.724, motion: 'right', order: 2 },
  { src: '/splash/09.webp', left: 73.297, top: 16.272, width: 9.418, height: 52.694, motion: 'right', order: 3 },
  { src: '/splash/10.webp', left: 82.974, top: 16.595, width: 8.513, height: 66.056, motion: 'right', order: 4 },
  { src: '/splash/11.webp', left: 91.228, top: 16.487, width: 8.772, height: 51.724, motion: 'right', order: 5 },
  { src: '/splash/06.webp', left: 43.75, top: 73.707, width: 54.72, height: 26.293, motion: 'bar', order: 0 },
  { src: '/splash/12.webp', left: 94.267, top: 4.741, width: 4.784, height: 7.651, motion: 'accent', order: 0 },
]

const STAGGER = 55
const BAR_DELAY = 480
const ACCENT_DELAY = 820
/** Od startu do zniknięcia: wjazd ok. 1,2 s, chwila na przeczytanie, potem wyjście. */
const HOLD_UNTIL = 1650
const EXIT = 420

function shouldShow() {
  try {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return false
    if (sessionStorage.getItem(SHOWN_KEY)) return false
    sessionStorage.setItem(SHOWN_KEY, '1')
    return true
  } catch {
    return false
  }
}

function delayOf(motion: Motion, order: number) {
  if (motion === 'bar') return BAR_DELAY
  if (motion === 'accent') return ACCENT_DELAY
  return order * STAGGER
}

/** Animacja logo na pełny ekran przy pierwszym wejściu w nowym oknie. Aplikacja ładuje się pod spodem; klik albo klawisz pomija. */
export function SplashScreen() {
  const [phase, setPhase] = useState<'loading' | 'play' | 'exit' | 'done'>(() => (shouldShow() ? 'loading' : 'done'))

  // Start dopiero po wczytaniu wszystkich kawałków, żeby żadna litera nie doskoczyła w połowie (najdłużej 1,5 s czekania).
  useEffect(() => {
    if (phase !== 'loading') return
    let cancelled = false
    const decoded = PIECES.map(({ src }) => {
      const img = new Image()
      img.src = src
      return img.decode().catch(() => undefined)
    })
    const timeout = new Promise((resolve) => setTimeout(resolve, 1500))
    void Promise.race([Promise.all(decoded), timeout]).then(() => {
      if (!cancelled) setPhase('play')
    })
    return () => {
      cancelled = true
    }
  }, [phase])

  useEffect(() => {
    if (phase === 'play') {
      const timer = setTimeout(() => setPhase('exit'), HOLD_UNTIL)
      return () => clearTimeout(timer)
    }
    if (phase === 'exit') {
      const timer = setTimeout(() => setPhase('done'), EXIT)
      return () => clearTimeout(timer)
    }
  }, [phase])

  useEffect(() => {
    if (phase === 'done' || phase === 'exit') return
    const skip = () => setPhase('exit')
    window.addEventListener('keydown', skip)
    return () => window.removeEventListener('keydown', skip)
  }, [phase])

  if (phase === 'done') return null

  return (
    <div
      aria-hidden
      onPointerDown={() => phase !== 'exit' && setPhase('exit')}
      data-phase={phase}
      className="splash fixed inset-0 z-[1000] grid place-items-center overflow-hidden bg-background"
    >
      <div className="splash-logo relative aspect-[5/1] w-[min(82vw,48rem)] dark:[filter:invert(1)_hue-rotate(180deg)_saturate(2.4)_brightness(0.92)]">
        {PIECES.map((piece) => (
          <img
            key={piece.src}
            src={piece.src}
            alt=""
            draggable={false}
            data-motion={piece.motion}
            className="splash-piece absolute max-w-none select-none"
            style={
              {
                left: `${piece.left}%`,
                top: `${piece.top}%`,
                width: `${piece.width}%`,
                height: `${piece.height}%`,
                '--splash-delay': `${delayOf(piece.motion, piece.order)}ms`,
              } as CSSProperties
            }
          />
        ))}
      </div>
    </div>
  )
}
