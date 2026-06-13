import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchArtifacts } from '../api'
import type { ServiceBoundary } from '../types'
import CodeViewer from './CodeViewer'

interface Props {
  jobId: number
  boundary: ServiceBoundary
}

const SERVICE_COLORS = [
  'border-l-blue-500',
  'border-l-violet-500',
  'border-l-emerald-500',
  'border-l-amber-500',
  'border-l-rose-500',
  'border-l-cyan-500',
  'border-l-fuchsia-500',
  'border-l-teal-500',
]

export default function ServiceCard({ jobId, boundary }: Props) {
  const [open, setOpen] = useState(false)
  const [showCode, setShowCode] = useState(false)

  const { data: artifacts = [], isFetching } = useQuery({
    queryKey: ['artifacts', jobId, boundary.serviceName],
    queryFn: () => fetchArtifacts(jobId, boundary.serviceName),
    enabled: open,
    staleTime: 30_000,
  })

  const colorIdx = Math.abs(
    boundary.serviceName.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0)
  ) % SERVICE_COLORS.length
  const color = SERVICE_COLORS[colorIdx]

  return (
    <div
      className={`rounded-xl border border-slate-200 dark:border-slate-700 border-l-4 ${color} bg-white dark:bg-slate-900 shadow-sm`}
    >
      {/* Header row */}
      <div
        className="flex items-center justify-between px-4 py-3 cursor-pointer select-none"
        onClick={() => setOpen(v => !v)}
      >
        <div className="flex items-center gap-3 min-w-0">
          <span className="text-slate-400 text-sm">{open ? '▾' : '▸'}</span>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="font-mono text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
                {boundary.serviceName}
              </span>
              {boundary.isCommons && (
                <span className="text-xs px-1.5 py-0.5 rounded bg-amber-100 dark:bg-amber-900 text-amber-700 dark:text-amber-300 font-medium">
                  commons
                </span>
              )}
            </div>
            {boundary.description && (
              <p className="text-xs text-slate-500 dark:text-slate-400 truncate mt-0.5">
                {boundary.description}
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3 shrink-0 ml-4">
          <span className="text-xs text-slate-400">
            {boundary.classCount} {boundary.classCount === 1 ? 'class' : 'classes'}
          </span>
          {artifacts.length > 0 && (
            <span className="text-xs text-slate-400">
              {artifacts.length} files
            </span>
          )}
        </div>
      </div>

      {/* Expanded body */}
      {open && (
        <div className="border-t border-slate-100 dark:border-slate-800 px-4 pt-3 pb-4 space-y-4">
          {/* Class list */}
          <div>
            <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-2">
              Assigned classes
            </p>
            <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto">
              {boundary.classFqns.map(fqn => {
                const short = fqn.includes('.') ? fqn.split('.').pop()! : fqn
                return (
                  <span
                    key={fqn}
                    title={fqn}
                    className="font-mono text-xs bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded px-1.5 py-0.5"
                  >
                    {short}
                  </span>
                )
              })}
            </div>
          </div>

          {/* Rationale */}
          {boundary.rationale && (
            <div>
              <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-1">
                Rationale
              </p>
              <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
                {boundary.rationale}
              </p>
            </div>
          )}

          {/* Generated files tree + CodeViewer toggle */}
          {isFetching && (
            <p className="text-xs text-slate-400 animate-pulse">Loading artifacts…</p>
          )}
          {!isFetching && artifacts.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-2">
                <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
                  Generated files
                </p>
                <button
                  onClick={() => setShowCode(v => !v)}
                  className="text-xs text-indigo-600 dark:text-indigo-400 hover:underline"
                >
                  {showCode ? 'Hide code' : 'View code'}
                </button>
              </div>

              {/* File tree */}
              <div className="font-mono text-xs space-y-0.5 bg-slate-50 dark:bg-slate-800 rounded-lg p-2 mb-3">
                <div className="text-slate-500">📁 {boundary.serviceName}/</div>
                {artifacts.map(a => {
                  const icon = a.filePath.endsWith('.xml') ? '📄'
                    : a.artifactType === 'TEST_CODE' ? '🧪'
                    : '☕'
                  return (
                    <div key={a.id} className="pl-4 text-slate-600 dark:text-slate-400 flex justify-between">
                      <span>{icon} {a.filePath}</span>
                      <span className="text-slate-400 ml-4">{a.lineCount}L</span>
                    </div>
                  )
                })}
              </div>

              {showCode && <CodeViewer artifacts={artifacts} />}
            </div>
          )}
          {!isFetching && artifacts.length === 0 && (
            <p className="text-xs text-slate-400">No generated artifacts yet — run generation first.</p>
          )}
        </div>
      )}
    </div>
  )
}
