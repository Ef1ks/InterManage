import { AssignTaskButton } from '@/features/tasks/AssignTaskButton'
import { useTaskSource } from '@/features/tasks/task-helpers'

/** „Zleć” w nagłówku dokumentu (np. przyjęcie PZ do wyjaśnienia z dostawcą): zadanie z linkiem do tego dokumentu. */
export function DocumentTaskButton({ document }: { document: { typeSingular: string; number: string } }) {
  const label = `${document.typeSingular} ${document.number}`
  const source = useTaskSource('DOCUMENT', label)
  return <AssignTaskButton title={`Wyjaśnij: ${label}`} source={source} label="Zleć" />
}
