import type { SharedLibraryPlan } from '../types'

export default function SharedLibraryBadge({ plan }: { plan: SharedLibraryPlan }) {
  if (!plan.totalSharedClasses) return null
  return (
    <div className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-950 p-4">
      <div className="flex items-start gap-3">
        <span className="text-2xl">📦</span>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-amber-800 dark:text-amber-300">
            Shared Library Extracted
          </p>
          <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
            <span className="font-mono font-semibold">{plan.commonsModuleName}</span> ·{' '}
            {plan.totalSharedClasses} shared {plan.totalSharedClasses === 1 ? 'class' : 'classes'} ·{' '}
            {plan.duplicationsAvoided} copy-paste {plan.duplicationsAvoided === 1 ? 'instance' : 'instances'} avoided ·{' '}
            {(plan.duplicationRate * 100).toFixed(1)}% of codebase deduplicated
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {plan.sharedClasses.slice(0, 12).map(sc => (
              <span
                key={sc.fqn}
                title={`${sc.fqn} — ${sc.serviceCount} services`}
                className="font-mono text-xs bg-amber-100 dark:bg-amber-900 text-amber-800 dark:text-amber-300 rounded px-1.5 py-0.5"
              >
                {sc.shortName}
                <span className="ml-1 opacity-60 text-[10px]">×{sc.serviceCount}</span>
              </span>
            ))}
            {plan.sharedClasses.length > 12 && (
              <span className="text-xs text-amber-600 dark:text-amber-400">
                +{plan.sharedClasses.length - 12} more
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
