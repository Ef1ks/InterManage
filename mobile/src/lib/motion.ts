import { useEffect, useState } from 'react'
import { AccessibilityInfo } from 'react-native'

/** Czy użytkownik ma w systemie ograniczony ruch (wtedy bez animacji, od razu stan końcowy). */
export function useReduceMotion() {
  const [reduce, setReduce] = useState(false)
  useEffect(() => {
    let alive = true
    void AccessibilityInfo.isReduceMotionEnabled().then((value) => alive && setReduce(value))
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduce)
    return () => {
      alive = false
      subscription.remove()
    }
  }, [])
  return reduce
}
