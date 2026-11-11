import * as NativeSplash from 'expo-splash-screen'
import { useEffect, useRef, useState } from 'react'
import { AccessibilityInfo, Animated, Easing, Pressable, StyleSheet, useWindowDimensions } from 'react-native'

import { LOGO_PIECES, LOGO_RATIO, pieceStyle, type LogoPiece } from '@/components/Logo'
import { useTheme } from '@/lib/theme'

// Te same czasy i krzywe co animacja w przeglądarce (frontend/src/index.css, klatki splash-*).
const STAGGER = 55
const BAR_DELAY = 480
const ACCENT_DELAY = 820
const SLIDE = 760
const BAR = 620
const ACCENT = 420
/** Od startu do zniknięcia: wjazd ok. 1,2 s, chwila na przeczytanie, potem wyjście. */
const HOLD_UNTIL = 1650
const EXIT = 420
/** Najdłużej tyle czekamy na wczytanie kawałków, zanim ruszy animacja. */
const LOAD_TIMEOUT = 1500

const SLIDE_EASING = Easing.bezier(0.16, 1, 0.3, 1)
const ACCENT_EASING = Easing.bezier(0.34, 1.56, 0.64, 1)
const EXIT_EASING = Easing.bezier(0.4, 0, 1, 1)

function delayOf(piece: LogoPiece) {
  if (piece.motion === 'bar') return BAR_DELAY
  if (piece.motion === 'accent') return ACCENT_DELAY
  return piece.order * STAGGER
}

function durationOf(piece: LogoPiece) {
  if (piece.motion === 'bar') return BAR
  if (piece.motion === 'accent') return ACCENT
  return SLIDE
}

/**
 * Animacja startowa jak na stronie: logo na pełny ekran składa się z kawałków („Inter” wjeżdża z lewej, „manage”
 * z prawej, potem pas i kreska nad „e”), ok. 2 s przy każdym uruchomieniu aplikacji. Aplikacja ładuje się pod spodem,
 * dotknięcie pomija, przy ograniczonym ruchu animacji nie ma. Systemowy ekran startowy to samo tło (przezroczysty
 * obraz w app.json), więc przejście do tej animacji jest niewidoczne.
 */
export function SplashAnimation() {
  const t = useTheme()
  const { width: screenWidth } = useWindowDimensions()
  const [phase, setPhase] = useState<'loading' | 'play' | 'exit' | 'done'>('loading')
  const [progress] = useState(() => LOGO_PIECES.map(() => new Animated.Value(0)))
  const [exit] = useState(() => new Animated.Value(0))
  const loaded = useRef(0)

  const logoWidth = Math.min(screenWidth * 0.82, 480)
  const logoHeight = logoWidth / LOGO_RATIO

  // Systemowy ekran startowy chowamy dopiero, gdy ta warstwa (w tym samym kolorze) już stoi.
  useEffect(() => {
    void NativeSplash.hideAsync().catch(() => undefined)
    let cancelled = false
    void AccessibilityInfo.isReduceMotionEnabled()
      .then((reduce) => {
        if (!cancelled && reduce) setPhase('done')
      })
      .catch(() => undefined)
    const timer = setTimeout(() => setPhase((current) => (current === 'loading' ? 'play' : current)), LOAD_TIMEOUT)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [])

  useEffect(() => {
    if (phase === 'play') {
      Animated.parallel(
        LOGO_PIECES.map((piece, index) =>
          Animated.timing(progress[index], {
            toValue: 1,
            duration: durationOf(piece),
            delay: delayOf(piece),
            easing: piece.motion === 'accent' ? ACCENT_EASING : SLIDE_EASING,
            useNativeDriver: true,
          }),
        ),
      ).start()
      const timer = setTimeout(() => setPhase('exit'), HOLD_UNTIL)
      return () => clearTimeout(timer)
    }
    if (phase === 'exit') {
      Animated.timing(exit, { toValue: 1, duration: EXIT, easing: EXIT_EASING, useNativeDriver: true }).start(() =>
        setPhase('done'),
      )
    }
  }, [phase, progress, exit])

  if (phase === 'done') return null

  const onLoad = () => {
    loaded.current += 1
    if (loaded.current >= LOGO_PIECES.length) setPhase((current) => (current === 'loading' ? 'play' : current))
  }

  return (
    <Animated.View
      style={[
        StyleSheet.absoluteFill,
        {
          backgroundColor: t.background,
          zIndex: 1000,
          opacity: exit.interpolate({ inputRange: [0, 1], outputRange: [1, 0] }),
        },
      ]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Pressable
        style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}
        onPressIn={() => setPhase((current) => (current === 'exit' || current === 'done' ? current : 'exit'))}
      >
        <Animated.View
          style={{
            width: logoWidth,
            height: logoHeight,
            transform: [{ scale: exit.interpolate({ inputRange: [0, 1], outputRange: [1, 1.04] }) }],
          }}
        >
          {LOGO_PIECES.map((piece, index) => {
            const value = progress[index]
            const tint = piece.dark && t.logoInk ? { tintColor: t.logoInk } : null
            return (
              <Animated.Image
                key={index}
                source={piece.source}
                resizeMode="stretch"
                onLoad={onLoad}
                style={[pieceStyle(piece), tint, motionStyle(piece, value, screenWidth, logoHeight)]}
              />
            )
          })}
        </Animated.View>
      </Pressable>
    </Animated.View>
  )
}

/** Odpowiedniki klatek splash-from-left / -right / -bar / -accent z przeglądarki. */
function motionStyle(piece: LogoPiece, value: Animated.Value, screenWidth: number, logoHeight: number) {
  switch (piece.motion) {
    case 'left':
    case 'right': {
      const side = piece.motion === 'left' ? -1 : 1
      return {
        // Litery wjeżdżają z boku, pochylone w stronę jazdy, i prostują się przy hamowaniu.
        opacity: value.interpolate({ inputRange: [0, 0.25, 1], outputRange: [0, 1, 1] }),
        transform: [
          { translateX: value.interpolate({ inputRange: [0, 1], outputRange: [side * screenWidth * 0.7, 0] }) },
          { skewX: value.interpolate({ inputRange: [0, 1], outputRange: [`${side * 14}deg`, '0deg'] }) },
        ],
      }
    }
    case 'bar':
      return {
        opacity: value.interpolate({ inputRange: [0, 0.001, 1], outputRange: [0, 1, 1] }),
        transformOrigin: 'left center',
        transform: [{ scaleX: value.interpolate({ inputRange: [0, 1], outputRange: [0.0001, 1] }) }],
      }
    case 'accent': {
      const height = (logoHeight * piece.height) / 100
      return {
        opacity: value.interpolate({ inputRange: [0, 1], outputRange: [0, 1], extrapolate: 'clamp' as const }),
        transform: [{ translateY: value.interpolate({ inputRange: [0, 1], outputRange: [-1.6 * height, 0] }) }],
      }
    }
  }
}
