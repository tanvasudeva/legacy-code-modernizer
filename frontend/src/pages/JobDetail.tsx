import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchJob, fetchBoundaries, fetchRepairStats,
  fetchSharedClasses, fetchMetrics, retryJob, downloadBundle,
} from '../api'
import StatusBadge from '../components/StatusBadge'
import PhaseBar from '../components/PhaseBar'
import ServiceCard from '../components/ServiceCard'
import MetricsPanel from '../components/MetricsPanel'
import SharedLibraryBadge from '../components/SharedLibraryBadge'

const POLLING_STATUSES = new Set(['ANALYZING', 'PLANNING', 'GENERATING'])

export default function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const jobId = Number(id)
  const navigate = useNavigate()
  const qc = useQueryClient()

  const { data: job, isLoading: jobLoading } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => fetchJob(jobId),
    refetchInterval: d => POLLING_STATUSES.has(d.state.data?.status ?? '') ? 3_000 : false,
  })

  const isActive = POLLING_STATUSES.has(job?.status ?? '')

  const { data: boundaries = [] } = useQuery({
    queryKey: ['boundaries', jobId],
    queryFn: () => fetchBoundaries(jobId),
    enabled: !!job,
    refetchInterval: isActive ? 5_000 : false,
  })

  const { data: repairStats = null } = useQuery({
    queryKey: ['repairStats', jobId],
    queryFn: () => fetchRepairStats(jobId),
    enabled: !!job && job.status === 'DONE',
  })

  const { data: sharedPlan = null } = useQuery({
    queryKey: ['sharedClasses', jobId],
    queryFn: () => fetchSharedClasses(jobId),
    enabled: !!job && job.status === 'DONE',
  })

  const { data: metrics = [] } = useQuery({
    queryKey: ['metrics', jobId],
    queryFn: () => fetchMetrics(jobId),
    enabled: !!job && job.status === 'DONE',
  })

  const retry = useMutation({
    mutationFn: () => retryJob(jobId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['job', jobId] }),
  })

  if (jobLoading) {
    return (
      <div className="text-center py-24 text-slate-400 animate-pulse">
        Loading job…
      </div>
    )
  }

  if (!job) {
    return (
      <div className="text-center py-24">
        <p className="text-slate-400 text-lg">Job #{jobId} not found.</p>
        <button
          onClick={() => navigate('/')}
          className="mt-4 text-indigo-600 dark:text-indigo-400 text-sm hover:underline"
        >
          ← Back to jobs
        </button>
      </div>
    )
  }

  const commons = boundaries.find(b => b.isCommons)
  const services = boundaries.filter(b => !b.isCommons)

  return (
    <div className="space-y-8">
      {/* Breadcrumb */}
      <div>
        <button
          onClick={() => navigate('/')}
          className="text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"
        >
          ← Jobs
        </button>
      </div>

      {/* Job header */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm p-5">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3 flex-wrap">
              <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100 truncate">
                {job.name}
              </h1>
              <StatusBadge status={job.status} />
            </div>
            <p className="text-xs text-slate-400 font-mono mt-1 truncate">
              #{job.id} · {job.sourceDirectory}
            </p>
            {job.errorMessage && (
              <p className="mt-2 text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-950 rounded-lg px-3 py-2">
                {job.errorMessage}
              </p>
            )}
          </div>

          {/* Action buttons */}
          <div className="flex items-center gap-2 shrink-0">
            {job.status === 'FAILED' && (
              <button
                onClick={() => retry.mutate()}
                disabled={retry.isPending}
                className="px-3 py-1.5 text-sm font-medium bg-amber-500 hover:bg-amber-600 disabled:opacity-50 text-white rounded-lg transition-colors"
              >
                {retry.isPending ? 'Retrying…' : 'Retry'}
              </button>
            )}
            {job.status === 'DONE' && (
              <button
                onClick={() => downloadBundle(jobId)}
                className="px-3 py-1.5 text-sm font-semibold bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg shadow transition-colors flex items-center gap-1.5"
              >
                ↓ Download Bundle
              </button>
            )}
          </div>
        </div>

        {/* Phase bar */}
        <div className="mt-5">
          <PhaseBar status={job.status} />
        </div>

        {/* Stats row */}
        <div className="mt-4 flex flex-wrap gap-4 text-xs text-slate-500">
          <span>{boundaries.length} service{boundaries.length !== 1 ? 's' : ''} planned</span>
          {boundaries.length > 0 && (
            <span>{boundaries.reduce((a, b) => a + b.classCount, 0)} total classes</span>
          )}
          {job.createdAt && (
            <span>Created {new Date(job.createdAt).toLocaleString()}</span>
          )}
        </div>
      </div>

      {/* Shared library badge */}
      {sharedPlan && sharedPlan.totalSharedClasses > 0 && (
        <SharedLibraryBadge plan={sharedPlan} />
      )}

      {/* Commons module first */}
      {commons && (
        <div className="space-y-3">
          <h2 className="text-xs font-semibold text-amber-700 dark:text-amber-400 uppercase tracking-wide">
            Commons Library
          </h2>
          <ServiceCard jobId={jobId} boundary={commons} />
        </div>
      )}

      {/* Service boundaries */}
      {services.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
            Service Boundaries ({services.length})
          </h2>
          <div className="space-y-3">
            {services.map(b => (
              <ServiceCard key={b.id} jobId={jobId} boundary={b} />
            ))}
          </div>
        </div>
      )}

      {boundaries.length === 0 && isActive && (
        <div className="text-center py-12 text-slate-400 text-sm animate-pulse">
          Waiting for boundaries to be planned…
        </div>
      )}

      {boundaries.length === 0 && !isActive && job.status !== 'DONE' && (
        <div className="text-center py-12 text-slate-400 text-sm">
          No service boundaries yet.
        </div>
      )}

      {/* Metrics */}
      {(metrics.length > 0 || repairStats) && (
        <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm p-5">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-4">
            Evaluation Results
          </h2>
          <MetricsPanel metrics={metrics} repairStats={repairStats} />
        </div>
      )}
    </div>
  )
}
