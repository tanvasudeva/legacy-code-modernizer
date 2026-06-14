import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { fetchJobs, createJob } from '../api'
import StatusBadge from '../components/StatusBadge'

export default function JobList() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [name, setName] = useState('')
  const [srcDir, setSrcDir] = useState('')

  const { data: jobs = [], isLoading } = useQuery({
    queryKey: ['jobs'],
    queryFn: fetchJobs,
    refetchInterval: 3_000,
  })

  const create = useMutation({
    mutationFn: () => createJob(name.trim(), srcDir.trim()),
    onSuccess: job => {
      qc.invalidateQueries({ queryKey: ['jobs'] })
      setShowForm(false)
      setName('')
      setSrcDir('')
      navigate(`/jobs/${job.id}`)
    },
  })

  const active = jobs.filter(j =>
    ['ANALYZING', 'PLANNING', 'GENERATING'].includes(j.status)
  )

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">
            Migration Jobs
          </h1>
          <p className="text-sm text-slate-500 mt-0.5">
            {jobs.length} total · {active.length} in progress
          </p>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-lg shadow transition-colors"
        >
          + New Job
        </button>
      </div>

      {/* Create form */}
      {showForm && (
        <div className="rounded-xl border border-indigo-200 dark:border-indigo-800 bg-indigo-50 dark:bg-indigo-950 p-5 space-y-4">
          <h2 className="text-sm font-semibold text-indigo-900 dark:text-indigo-200">
            Create migration job
          </h2>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                Job name
              </label>
              <input
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="benchmark-petclinic"
                className="w-full px-3 py-2 text-sm border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
                Source directory
              </label>
              <input
                value={srcDir}
                onChange={e => setSrcDir(e.target.value)}
                placeholder="/abs/path/to/benchmarks/spring-petclinic/src"
                className="w-full px-3 py-2 text-sm border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => create.mutate()}
              disabled={!name.trim() || !srcDir.trim() || create.isPending}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-colors"
            >
              {create.isPending ? 'Creating…' : 'Create'}
            </button>
            <button
              onClick={() => setShowForm(false)}
              className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:underline"
            >
              Cancel
            </button>
          </div>
          {create.isError && (
            <p className="text-xs text-red-600 dark:text-red-400">
              {String((create.error as Error).message)}
            </p>
          )}
        </div>
      )}

      {/* Table */}
      {isLoading ? (
        <div className="text-center py-12 text-slate-400 text-sm animate-pulse">
          Loading jobs…
        </div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-16 text-slate-400">
          <div className="text-5xl mb-4">🏗️</div>
          <p className="text-base font-medium">No migration jobs yet</p>
          <p className="text-sm mt-1">Click "New Job" to get started</p>
        </div>
      ) : (
        <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
                <th className="text-left px-4 py-3 font-semibold text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wide">ID</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wide">Name</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wide">Status</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wide hidden sm:table-cell">Source</th>
                <th className="text-left px-4 py-3 font-semibold text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wide hidden md:table-cell">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {jobs
                .slice()
                .sort((a, b) => b.id - a.id)
                .map(job => (
                  <tr
                    key={job.id}
                    onClick={() => navigate(`/jobs/${job.id}`)}
                    className="cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60 transition-colors"
                  >
                    <td className="px-4 py-3 font-mono text-slate-500 dark:text-slate-400">
                      #{job.id}
                    </td>
                    <td className="px-4 py-3 font-medium text-slate-800 dark:text-slate-200 max-w-[220px] truncate">
                      {job.name}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={job.status} />
                    </td>
                    <td className="px-4 py-3 text-slate-500 dark:text-slate-400 font-mono text-xs hidden sm:table-cell max-w-[260px] truncate">
                      {job.sourceDirectory}
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-xs hidden md:table-cell">
                      {new Date(job.createdAt).toLocaleDateString()}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
