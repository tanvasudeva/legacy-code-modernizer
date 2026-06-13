import type { JobStatus } from '../types'

const PHASES: { key: JobStatus; label: string }[] = [
  { key: 'PENDING',    label: '① Created'   },
  { key: 'ANALYZING',  label: '② Analysis'  },
  { key: 'PLANNING',   label: '③ Planning'  },
  { key: 'GENERATING', label: '④ Generation'},
  { key: 'DONE',       label: '⑤ Done'      },
]

const ORDER: Record<JobStatus, number> = {
  PENDING: 0, ANALYZING: 1, PLANNING: 2, GENERATING: 3, DONE: 4, FAILED: 4,
}

export default function PhaseBar({ status }: { status: JobStatus }) {
  const cur = ORDER[status] ?? 0
  const failed = status === 'FAILED'

  return (
    <div className="flex gap-0 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700 text-xs font-medium">
      {PHASES.map((p, i) => {
        const done    = i <  cur
        const active  = i === cur && !failed
        const isFail  = failed && i === cur

        let cls = 'flex-1 py-2 text-center transition-colors '
        if (isFail)       cls += 'bg-red-500 text-white'
        else if (active)  cls += 'bg-indigo-500 text-white animate-pulse'
        else if (done)    cls += 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-300'
        else              cls += 'bg-slate-50 text-slate-400 dark:bg-slate-800 dark:text-slate-500'

        return <div key={p.key} className={cls}>{isFail ? '✗ Failed' : p.label}</div>
      })}
    </div>
  )
}
