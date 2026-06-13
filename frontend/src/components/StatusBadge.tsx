import type { JobStatus } from '../types'

const cfg: Record<JobStatus, { label: string; cls: string }> = {
  PENDING:    { label: 'Pending',    cls: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300' },
  ANALYZING:  { label: 'Analyzing', cls: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-200 animate-pulse' },
  PLANNING:   { label: 'Planning',  cls: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-200 animate-pulse' },
  GENERATING: { label: 'Generating',cls: 'bg-purple-100 text-purple-700 dark:bg-purple-900 dark:text-purple-200 animate-pulse' },
  DONE:       { label: 'Done',      cls: 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-200' },
  FAILED:     { label: 'Failed',    cls: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-200' },
}

export default function StatusBadge({ status }: { status: JobStatus }) {
  const { label, cls } = cfg[status] ?? cfg.PENDING
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold ${cls}`}>
      {['ANALYZING', 'PLANNING', 'GENERATING'].includes(status) && (
        <span className="w-1.5 h-1.5 rounded-full bg-current" />
      )}
      {label}
    </span>
  )
}
