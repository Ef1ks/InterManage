import { toast } from 'sonner'

const SECONDS = 3

/**
 * Chwila na rozmyślenie się przed zapisem pliku z artykułami: plik w folderze sklepu od razu wczytuje makro, więc potem
 * jest już za późno. Pokazuje odliczanie z „Anuluj”; `true` = nikt nie anulował, można zapisywać.
 */
export function confirmArticleSave(count: number): Promise<boolean> {
  return new Promise((resolve) => {
    const id = `article-save:${crypto.randomUUID?.() ?? Math.random()}`
    let left = SECONDS
    const finish = (confirmed: boolean) => {
      window.clearInterval(timer)
      toast.dismiss(id)
      if (!confirmed) toast.message('Anulowano: artykuły nie zostały zapisane')
      resolve(confirmed)
    }
    const show = () =>
      toast.warning(`Artykuły (${count}) zapiszą się za ${left} s`, {
        id,
        description: 'Po zapisie komputer sklepu od razu wczyta je do programu.',
        duration: Infinity,
        dismissible: false,
        action: { label: 'Anuluj', onClick: () => finish(false) },
      })
    const timer = window.setInterval(() => {
      left -= 1
      if (left <= 0) finish(true)
      else show()
    }, 1000)
    show()
  })
}
