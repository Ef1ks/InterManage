import { useAppDispatch, useAppSelector } from '@/store'
import { persistHiddenHints, setHintHidden } from '@/store/uiSlice'

/** Czy użytkownik schował podpowiedź albo legendę; wybór zostaje w tej przeglądarce. */
export function useHiddenHint(id: string) {
  const hiddenHints = useAppSelector((state) => state.ui.hiddenHints)
  const dispatch = useAppDispatch()

  const setHidden = (next: boolean) => {
    dispatch(setHintHidden({ hint: id, hidden: next }))
    const others = hiddenHints.filter((hint) => hint !== id)
    persistHiddenHints(next ? [...others, id] : others)
  }

  return [hiddenHints.includes(id), setHidden] as const
}
