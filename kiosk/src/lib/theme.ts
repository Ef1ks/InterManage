import { useColorScheme } from 'react-native'

// Te same role co tokeny aplikacji w przeglądarce: tło < karta < okno; czerwień marki tylko dla znaku firmowego.
const light = {
  background: '#f5f5f4',
  card: '#ffffff',
  muted: '#f0efed',
  border: '#e4e2df',
  text: '#1c1917',
  mutedText: '#6b6661',
  primary: '#1c1917',
  primaryText: '#ffffff',
  brand: '#e30613',
  danger: '#b91c1c',
  dangerBg: '#fecaca',
  dangerText: '#7f1d1d',
  promoBg: '#fed7aa',
  promoText: '#7c2d12',
  infoBg: '#bfdbfe',
  infoText: '#1e3a8a',
  okBg: '#bbf7d0',
  okText: '#14532d',
  warnBg: '#fde68a',
  warnText: '#78350f',
  bar: '#57534e',
  barMuted: '#d6d3d1',
  /**
   * Wykres wyników sklepu: ten tydzień (pełne, wąskie słupki z przodu) i rok temu (kontur z delikatnym wypełnieniem
   * z tyłu). Para sprawdzona walidatorem palety skilla dataviz w obu motywach (kontrast z tłem, rozróżnienie przy
   * zaburzeniach widzenia barw).
   */
  chartNow: '#2563eb',
  chartYearAgo: '#d97706',
  chartYearAgoWash: 'rgba(217, 119, 6, 0.15)',
  chartMuted: '#d6d3d1',
  up: '#15803d',
  /** Kolor czarnych części logo; `null` = oryginalna grafika. */
  logoInk: null as string | null,
}

const dark: typeof light = {
  background: '#0c0a09',
  card: '#1c1917',
  muted: '#292524',
  border: '#3a3532',
  text: '#f5f5f4',
  mutedText: '#a8a29e',
  primary: '#f5f5f4',
  primaryText: '#1c1917',
  brand: '#ff3b47',
  danger: '#f87171',
  dangerBg: '#7f1d1d',
  dangerText: '#fee2e2',
  promoBg: '#7c2d12',
  promoText: '#ffedd5',
  infoBg: '#1e3a8a',
  infoText: '#dbeafe',
  okBg: '#14532d',
  okText: '#dcfce7',
  warnBg: '#78350f',
  warnText: '#fef3c7',
  bar: '#d6d3d1',
  barMuted: '#44403c',
  chartNow: '#3b82f6',
  chartYearAgo: '#b45309',
  chartYearAgoWash: 'rgba(180, 83, 9, 0.22)',
  chartMuted: '#57534e',
  up: '#4ade80',
  logoInk: '#f5f5f4',
}

export type Theme = typeof light

export function useTheme(): Theme {
  return useColorScheme() === 'dark' ? dark : light
}
