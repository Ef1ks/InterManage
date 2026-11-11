import * as Haptics from 'expo-haptics'
import { router, usePathname } from 'expo-router'
import { BlurView } from 'expo-blur'
import { GlassView, isLiquidGlassAvailable } from 'expo-glass-effect'
import { useEffect, useMemo, useState, type ComponentRef, type ReactNode, type RefObject } from 'react'
import { Animated, Easing, Keyboard, Platform, Pressable, StyleSheet, Text, useColorScheme, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { CalendarIcon, HomeIcon, ScanIcon } from '@/components/icons'
import { addDays } from '@/lib/format'
import { useAuth } from '@/lib/auth'
import { useReduceMotion } from '@/lib/motion'
import { useMySchedule } from '@/lib/queries'
import { useTheme } from '@/lib/theme'
import { todayIso } from '@/lib/week'

const ITEM_WIDTH = 72
const ITEM_HEIGHT = 52
const PADDING = 6
const DOCK_HEIGHT = ITEM_HEIGHT + PADDING * 2
const GAP_BELOW = 10
const EASE_OUT = Easing.bezier(0.23, 1, 0.32, 1)
/** Liquid Glass z iOS 26; starszy iOS i Android dostają rozmycie z expo-blur. */
const LIQUID_GLASS = Platform.OS === 'ios' && isLiquidGlassAvailable()

/** Ekrany, nad którymi unosi się pasek; skaner, panel konta i logowanie mają cały ekran dla siebie. */
function showsDock(pathname: string) {
  return pathname === '/' || pathname === '/schedule' || pathname.startsWith('/article')
}

/** Ile miejsca zostawić pod treścią przewijaną, żeby ostatnia karta nie chowała się pod paskiem. */
export function useDockInset() {
  const insets = useSafeAreaInsets()
  return insets.bottom + GAP_BELOW + DOCK_HEIGHT + 20
}

type Item = {
  key: string
  label: string
  icon: (color: string) => ReactNode
  active: boolean
  onPress: () => void
}

/**
 * Pływający pasek jak w iOS: szklana kapsuła nad treścią z szybkim przejściem do Startu, skanera i grafiku
 * (konto jest tylko w prawym górnym rogu). Podświetlenie bieżącego ekranu przesuwa się sprężyście między ikonami
 * (tylko transform, natywny sterownik). `blurTarget` to widok z ekranami, który Android rozmywa pod paskiem.
 */
export function FloatingDock({ blurTarget }: { blurTarget: RefObject<ComponentRef<typeof View> | null> }) {
  const t = useTheme()
  const dark = useColorScheme() === 'dark'
  const insets = useSafeAreaInsets()
  const pathname = usePathname()
  const reduce = useReduceMotion()
  const { me, canSeeArticles } = useAuth()
  const keyboardOpen = useKeyboardOpen()

  // Ten sam zakres co karta „Mój grafik”, więc dane są już w pamięci zapytań.
  const range = useMemo(() => {
    const today = todayIso()
    return { from: today, to: addDays(today, 13) }
  }, [])
  const schedule = useMySchedule(range)

  const visible = !!me && showsDock(pathname) && !keyboardOpen

  const items: Item[] = []
  items.push({
    key: 'home',
    label: 'Start',
    icon: (color) => <HomeIcon color={color} size={22} />,
    active: pathname === '/',
    onPress: () => {
      if (pathname !== '/') router.dismissTo('/')
    },
  })
  if (canSeeArticles) {
    items.push({
      key: 'scan',
      label: 'Skanuj',
      icon: (color) => <ScanIcon color={color} size={22} />,
      active: false,
      onPress: () => router.push('/scan'),
    })
  }
  if (schedule.data?.employee !== false) {
    items.push({
      key: 'schedule',
      label: 'Grafik',
      icon: (color) => <CalendarIcon color={color} size={22} />,
      active: pathname === '/schedule',
      onPress: () => {
        if (pathname !== '/schedule') router.navigate('/schedule')
      },
    })
  }

  const activeIndex = items.findIndex((item) => item.active)

  const [shown] = useState(() => new Animated.Value(visible ? 1 : 0))
  const [indicatorX] = useState(() => new Animated.Value(Math.max(activeIndex, 0) * ITEM_WIDTH))
  const [indicatorOpacity] = useState(() => new Animated.Value(activeIndex >= 0 ? 1 : 0))

  useEffect(() => {
    if (reduce) {
      shown.setValue(visible ? 1 : 0)
      return
    }
    Animated.timing(shown, {
      toValue: visible ? 1 : 0,
      duration: visible ? 320 : 200,
      easing: visible ? EASE_OUT : Easing.in(Easing.quad),
      useNativeDriver: true,
    }).start()
  }, [visible, reduce, shown])

  useEffect(() => {
    const hasActive = activeIndex >= 0
    if (reduce) {
      if (hasActive) indicatorX.setValue(activeIndex * ITEM_WIDTH)
      indicatorOpacity.setValue(hasActive ? 1 : 0)
      return
    }
    const animations = [
      Animated.timing(indicatorOpacity, {
        toValue: hasActive ? 1 : 0,
        duration: 160,
        useNativeDriver: true,
      }),
    ]
    if (hasActive) {
      animations.push(
        Animated.spring(indicatorX, {
          toValue: activeIndex * ITEM_WIDTH,
          damping: 18,
          stiffness: 220,
          mass: 0.8,
          useNativeDriver: true,
        }),
      )
    }
    Animated.parallel(animations).start()
  }, [activeIndex, reduce, indicatorX, indicatorOpacity])

  if (!me) return null

  const translateY = shown.interpolate({
    inputRange: [0, 1],
    outputRange: [DOCK_HEIGHT + insets.bottom + 24, 0],
  })

  return (
    <View pointerEvents="box-none" style={[StyleSheet.absoluteFill, styles.layer]}>
      <Animated.View
        pointerEvents={visible ? 'auto' : 'none'}
        accessibilityRole="tablist"
        // Bez animacji przezroczystości: rozmycie pod półprzezroczystym rodzicem znika na czas animacji.
        style={[
          styles.dock,
          {
            marginBottom: insets.bottom + GAP_BELOW,
            transform: [{ translateY }],
          },
        ]}
      >
        <GlassBackground dark={dark} blurTarget={blurTarget} />
        <Animated.View
          pointerEvents="none"
          style={[
            styles.indicator,
            {
              backgroundColor: dark ? 'rgba(255, 255, 255, 0.16)' : 'rgba(28, 25, 23, 0.08)',
              opacity: indicatorOpacity,
              transform: [{ translateX: indicatorX }],
            },
          ]}
        />
        {items.map((item) => {
          const color = item.active ? t.text : t.mutedText
          return (
            <Pressable
              key={item.key}
              onPress={() => {
                void Haptics.selectionAsync()
                item.onPress()
              }}
              accessibilityRole="tab"
              accessibilityLabel={item.label}
              accessibilityState={{ selected: item.active }}
              style={({ pressed }) => [styles.item, pressed && { transform: [{ scale: 0.9 }] }]}
            >
              {item.icon(color)}
              <Text style={[styles.label, { color }]}>{item.label}</Text>
            </Pressable>
          )
        })}
      </Animated.View>
    </View>
  )
}

/** Szkło pod ikonami: Liquid Glass na iOS 26, gdzie indziej rozmycie z lekkim zabarwieniem i jasną krawędzią. */
function GlassBackground({
  dark,
  blurTarget,
}: {
  dark: boolean
  blurTarget: RefObject<ComponentRef<typeof View> | null>
}) {
  if (LIQUID_GLASS) {
    return <GlassView style={styles.glass} glassEffectStyle="regular" colorScheme={dark ? 'dark' : 'light'} />
  }
  return (
    <View
      pointerEvents="none"
      style={[
        styles.glass,
        {
          borderColor: dark ? 'rgba(255, 255, 255, 0.14)' : 'rgba(255, 255, 255, 0.7)',
        },
      ]}
    >
      <BlurView
        style={StyleSheet.absoluteFill}
        intensity={Platform.OS === 'android' ? 80 : 60}
        tint={dark ? 'systemChromeMaterialDark' : 'systemChromeMaterialLight'}
        blurMethod="dimezisBlurViewSdk31Plus"
        blurTarget={blurTarget}
      />
      <View
        style={[
          StyleSheet.absoluteFill,
          // Android poniżej 12 nie rozmywa, więc zabarwienie musi samo dać czytelne tło.
          {
            backgroundColor: dark ? 'rgba(28, 25, 23, 0.55)' : 'rgba(255, 255, 255, 0.55)',
          },
        ]}
      />
    </View>
  )
}

/** Przy otwartej klawiaturze pasek się chowa, żeby nie wjechał nad nią (Android zmniejsza okno). */
function useKeyboardOpen() {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    const showEvent = Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow'
    const hideEvent = Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide'
    const show = Keyboard.addListener(showEvent, () => setOpen(true))
    const hide = Keyboard.addListener(hideEvent, () => setOpen(false))
    return () => {
      show.remove()
      hide.remove()
    }
  }, [])
  return open
}

const styles = StyleSheet.create({
  layer: { justifyContent: 'flex-end', alignItems: 'center' },
  dock: {
    flexDirection: 'row',
    padding: PADDING,
    borderRadius: DOCK_HEIGHT / 2,
    shadowColor: '#000',
    shadowOpacity: 0.16,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 10 },
  },
  glass: {
    ...StyleSheet.absoluteFill,
    borderRadius: DOCK_HEIGHT / 2,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  indicator: {
    position: 'absolute',
    top: PADDING,
    left: PADDING,
    width: ITEM_WIDTH,
    height: ITEM_HEIGHT,
    borderRadius: ITEM_HEIGHT / 2,
  },
  item: {
    width: ITEM_WIDTH,
    height: ITEM_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
  },
  label: { fontSize: 11, fontWeight: '600' },
})
