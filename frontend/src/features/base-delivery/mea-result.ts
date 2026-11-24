import type { AgentMacroJob } from '@/lib/types'

/**
 * Wynik makra kodów MEA z podsumowania odtwarzacza („52817: obsłużone pliki: 2 (…)”, linia powstaje po ostatnim pliku
 * kodu). `missing` to kody bez pliku do przetworzenia w Mercalys. Przy udanym makrze kod bez linii jest wpisany (skończył
 * się na limicie plików albo podsumowanie było za długie), a przy przerwanym nie wiadomo, więc nie jest.
 */
export function meaResult(codes: string[], job: Pick<AgentMacroJob, 'status' | 'message'>) {
  const files = new Map<string, number>()
  for (const [, code, count] of (job.message ?? '').matchAll(/^\s*(\w+): obsłużone pliki: (\d+)/gm)) {
    files.set(code, Number(count))
  }
  const missing = codes.filter((code) => files.get(code) === 0)
  const entered = codes.filter((code) => {
    const count = files.get(code)
    return count === undefined ? job.status === 'DONE' : count > 0
  })
  return { entered, missing }
}
