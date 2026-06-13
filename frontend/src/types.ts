export type JobStatus =
  | 'PENDING'
  | 'ANALYZING'
  | 'PLANNING'
  | 'GENERATING'
  | 'DONE'
  | 'FAILED'

export interface Job {
  id: number
  name: string
  status: JobStatus
  sourceDirectory: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

export interface ServiceBoundary {
  id: number
  serviceName: string
  classCount: number
  classFqns: string[]
  description: string
  rationale: string
  isCommons: boolean
}

export interface Artifact {
  id: number
  filePath: string
  artifactType: 'SERVICE_CODE' | 'TEST_CODE'
  content: string
  lineCount: number
}

export interface RepairStats {
  firstAttemptRate: number
  finalRate: number
  avgAttemptsToCompile: number
  unrepairedCount: number
  totalServices: number
}

export interface SharedClassResult {
  fqn: string
  shortName: string
  serviceCount: number
  referencingServices: string[]
}

export interface SharedLibraryPlan {
  jobId: number
  commonsModuleName: string
  sharedClasses: SharedClassResult[]
  totalSharedClasses: number
  duplicationsAvoided: number
  duplicationRate: number
}

export interface EvalMetric {
  id: number
  jobId: number
  metricName: string
  metricValue: number
  systemId: string
  judgeModel: string | null
  measuredAt: string
}
