import axios from 'axios'
import type {
  Job,
  ServiceBoundary,
  Artifact,
  RepairStats,
  SharedLibraryPlan,
  EvalMetric,
} from './types'

const http = axios.create({ baseURL: '/api' })

// ─── Jobs ─────────────────────────────────────────────────────────────────────

export const fetchJobs = (): Promise<Job[]> =>
  http.get<Job[]>('/jobs').then(r => r.data)

export const fetchJob = (id: number): Promise<Job> =>
  http.get<Job>(`/jobs/${id}/status`).then(r => r.data)

export const createJob = (name: string, sourceDirectory: string): Promise<Job> =>
  http.post<Job>('/jobs', { name, sourceDirectory }).then(r => r.data)

export const retryJob = (id: number): Promise<Job> =>
  http.post<Job>(`/jobs/${id}/retry`).then(r => r.data)

// ─── Boundaries & Artifacts ───────────────────────────────────────────────────

export const fetchBoundaries = (jobId: number): Promise<ServiceBoundary[]> =>
  http.get<ServiceBoundary[]>(`/jobs/${jobId}/boundaries`).then(r => r.data)

export const fetchArtifacts = (jobId: number, serviceName: string): Promise<Artifact[]> =>
  http
    .get<Artifact[]>(`/jobs/${jobId}/artifacts/${encodeURIComponent(serviceName)}`)
    .then(r => r.data)

// ─── Repair stats ─────────────────────────────────────────────────────────────

export const fetchRepairStats = (jobId: number): Promise<RepairStats | null> =>
  http
    .get<RepairStats>(`/jobs/${jobId}/repair-stats`)
    .then(r => r.data)
    .catch(() => null)

// ─── Shared library plan ─────────────────────────────────────────────────────

export const fetchSharedClasses = (jobId: number): Promise<SharedLibraryPlan | null> =>
  http
    .get<SharedLibraryPlan>(`/jobs/${jobId}/shared-classes`)
    .then(r => r.data)
    .catch(() => null)

// ─── Eval metrics ─────────────────────────────────────────────────────────────

export const fetchMetrics = (jobId: number): Promise<EvalMetric[]> =>
  http.get<EvalMetric[]>(`/eval/${jobId}`).then(r => r.data).catch(() => [])

// ─── Actions ──────────────────────────────────────────────────────────────────

export const triggerAnalysis = (jobId: number) =>
  http.post(`/jobs/${jobId}/analyze`)

export const triggerGenerate = (jobId: number, boundaryId: number) =>
  http.post(`/jobs/${jobId}/generate-service/${boundaryId}`)

// ─── Bundle download ──────────────────────────────────────────────────────────

export const downloadBundle = (jobId: number): void => {
  const a = document.createElement('a')
  a.href = `/api/jobs/${jobId}/bundle`
  a.download = `job-${jobId}-bundle.zip`
  a.click()
}
