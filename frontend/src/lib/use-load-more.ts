import { useEffect, useRef } from 'react'

/**
 * Doczytywanie listy przy przewijaniu: element z tym ref-em ustaw pod ostatnim wierszem; gdy zbliży się do dołu
 * ekranu (z zapasem 400 px), wywołuje {@code onLoadMore}. Nic nie robi, gdy {@code enabled} jest fałszywe.
 * {@code loaded} (np. liczba pokazanych wierszy) po zmianie każe sprawdzić to jeszcze raz - gdy po doczytaniu
 * koniec listy wciąż jest na ekranie, dociągamy dalej.
 */
export function useLoadMore<T extends Element>(onLoadMore: () => void, enabled: boolean, loaded: number) {
  const ref = useRef<T>(null)
  const callback = useRef(onLoadMore)

  useEffect(() => {
    callback.current = onLoadMore
  })

  useEffect(() => {
    const element = ref.current
    if (!element || !enabled) return
    const observer = new IntersectionObserver((entries) => entries[0]?.isIntersecting && callback.current(), {
      rootMargin: '0px 0px 400px 0px',
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [enabled, loaded])

  return ref
}
