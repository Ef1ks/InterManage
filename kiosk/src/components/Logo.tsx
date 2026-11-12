import { Image, View, type ImageSourcePropType, type ImageStyle } from 'react-native'

import { useTheme } from '@/lib/theme'

export type PieceMotion = 'left' | 'right' | 'bar' | 'accent'

export type LogoPiece = {
  source: ImageSourcePropType
  left: number
  top: number
  width: number
  height: number
  motion: PieceMotion
  order: number
  /** Czarny kawałek („Inter”, pas): w ciemnym motywie dostaje kolor tekstu. Czerwone zostają. */
  dark: boolean
}

/**
 * Logo z kawałków, jak `frontend/src/components/SplashScreen.tsx` (te same pliki, zmniejszone do 30% w `assets/logo`),
 * położenie w % całego logo 4640×928. Z kawałków, a nie z jednego hero.webp, bo React Native nie ma filtra CSS:
 * czarne części przebarwia `tintColor` w ciemnym motywie.
 */
export const LOGO_PIECES: LogoPiece[] = [
  {
    source: require('../../assets/logo/00.webp'),
    left: 0,
    top: 0,
    width: 5.668,
    height: 68.534,
    motion: 'left',
    order: 4,
    dark: true,
  },
  {
    source: require('../../assets/logo/01.webp'),
    left: 5.151,
    top: 16.272,
    width: 11.466,
    height: 52.371,
    motion: 'left',
    order: 3,
    dark: true,
  },
  {
    source: require('../../assets/logo/02.webp'),
    left: 17.543,
    top: 0,
    width: 6.746,
    height: 68.642,
    motion: 'left',
    order: 2,
    dark: true,
  },
  {
    source: require('../../assets/logo/03.webp'),
    left: 24.073,
    top: 16.272,
    width: 9.203,
    height: 51.94,
    motion: 'left',
    order: 1,
    dark: true,
  },
  {
    source: require('../../assets/logo/04.webp'),
    left: 32.651,
    top: 16.272,
    width: 12.091,
    height: 83.728,
    motion: 'left',
    order: 0,
    dark: true,
  },
  {
    source: require('../../assets/logo/05.webp'),
    left: 42.069,
    top: 16.487,
    width: 12.284,
    height: 51.724,
    motion: 'right',
    order: 0,
    dark: false,
  },
  {
    source: require('../../assets/logo/07.webp'),
    left: 54.526,
    top: 16.272,
    width: 9.116,
    height: 52.586,
    motion: 'right',
    order: 1,
    dark: false,
  },
  {
    source: require('../../assets/logo/08.webp'),
    left: 63.75,
    top: 16.487,
    width: 9.31,
    height: 51.724,
    motion: 'right',
    order: 2,
    dark: false,
  },
  {
    source: require('../../assets/logo/09.webp'),
    left: 73.297,
    top: 16.272,
    width: 9.418,
    height: 52.694,
    motion: 'right',
    order: 3,
    dark: false,
  },
  {
    source: require('../../assets/logo/10.webp'),
    left: 82.974,
    top: 16.595,
    width: 8.513,
    height: 66.056,
    motion: 'right',
    order: 4,
    dark: false,
  },
  {
    source: require('../../assets/logo/11.webp'),
    left: 91.228,
    top: 16.487,
    width: 8.772,
    height: 51.724,
    motion: 'right',
    order: 5,
    dark: false,
  },
  {
    source: require('../../assets/logo/06.webp'),
    left: 43.75,
    top: 73.707,
    width: 54.72,
    height: 26.293,
    motion: 'bar',
    order: 0,
    dark: true,
  },
  {
    source: require('../../assets/logo/12.webp'),
    left: 94.267,
    top: 4.741,
    width: 4.784,
    height: 7.651,
    motion: 'accent',
    order: 0,
    dark: false,
  },
]

/** Proporcje całego logo (4640×928). */
export const LOGO_RATIO = 5

export function pieceStyle(piece: LogoPiece): ImageStyle {
  return {
    position: 'absolute',
    left: `${piece.left}%`,
    top: `${piece.top}%`,
    width: `${piece.width}%`,
    height: `${piece.height}%`,
  }
}

/** Napis Intermanage (nasza grafika zamiast zwykłej czcionki). `width` w punktach, wysokość wynika z proporcji. */
export function Logo({ width }: { width: number }) {
  const t = useTheme()
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel="Intermanage"
      style={{ width, height: width / LOGO_RATIO }}
    >
      {LOGO_PIECES.map((piece, index) => (
        <Image
          key={index}
          source={piece.source}
          resizeMode="stretch"
          style={[pieceStyle(piece), piece.dark && t.logoInk ? { tintColor: t.logoInk } : null]}
        />
      ))}
    </View>
  )
}
