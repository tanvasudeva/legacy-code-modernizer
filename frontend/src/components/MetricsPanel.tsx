import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis,
  ResponsiveContainer, Tooltip,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Legend,
} from 'recharts'
import type { EvalMetric, RepairStats } from '../types'

const METRIC_LABELS: Record<string, string> = {
  COMPILATION_SUCCESS:          'Compile',
  COMPILATION_FIRST_ATTEMPT:    'First-Try',
  COMPILATION_POST_REPAIR:      'Post-Repair',
  COVERAGE:                     'Coverage',
  API_COMPLETENESS:             'API',
  LLM_JUDGE_SCORE:              'Judge',
  SHARED_CLASS_DUPLICATION_RATE:'Shared',
}

const SYSTEM_COLORS: Record<string, string> = {
  'multi-agent':         '#6366f1',
  'single-prompt-claude':'#10b981',
  'single-prompt-gpt4o': '#f59e0b',
}

interface Props {
  metrics: EvalMetric[]
  repairStats: RepairStats | null
}

export default function MetricsPanel({ metrics, repairStats }: Props) {
  if (!metrics.length && !repairStats) return null

  // Build radar data: one entry per metric, values per system
  const byMetric: Record<string, Record<string, number>> = {}
  for (const m of metrics) {
    if (!byMetric[m.metricName]) byMetric[m.metricName] = {}
    byMetric[m.metricName][m.systemId] = Number(m.metricValue)
  }

  const radarData = Object.entries(byMetric)
    .filter(([k]) => !['COMPILATION_POST_REPAIR', 'SHARED_CLASS_DUPLICATION_RATE'].includes(k))
    .map(([name, vals]) => ({
      metric: METRIC_LABELS[name] ?? name,
      ...Object.fromEntries(
        Object.entries(vals).map(([sys, v]) => [sys, +(v * (name === 'LLM_JUDGE_SCORE' ? 10 : 100)).toFixed(1)])
      ),
    }))

  const systems = [...new Set(metrics.map(m => m.systemId))]

  // Compilation repair bar data
  const repairBar = repairStats ? [
    { name: 'First Try', value: +(repairStats.firstAttemptRate * 100).toFixed(1) },
    { name: 'After Repair', value: +(repairStats.finalRate * 100).toFixed(1) },
  ] : []

  return (
    <div className="space-y-6">
      {/* Radar chart */}
      {radarData.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-3">
            Quality Metrics Comparison
          </h3>
          <ResponsiveContainer width="100%" height={280}>
            <RadarChart data={radarData}>
              <PolarGrid stroke="#e2e8f0" />
              <PolarAngleAxis dataKey="metric" tick={{ fontSize: 11, fill: '#94a3b8' }} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: 'none', borderRadius: 8, fontSize: 12 }}
                itemStyle={{ color: '#e2e8f0' }}
              />
              {systems.map(sys => (
                <Radar
                  key={sys}
                  name={sys}
                  dataKey={sys}
                  stroke={SYSTEM_COLORS[sys] ?? '#94a3b8'}
                  fill={SYSTEM_COLORS[sys] ?? '#94a3b8'}
                  fillOpacity={0.15}
                  strokeWidth={2}
                />
              ))}
              <Legend
                iconType="circle"
                iconSize={8}
                wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
              />
            </RadarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Repair stats bar */}
      {repairStats && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-1">
            Compilation Repair Loop
          </h3>
          <p className="text-xs text-slate-500 mb-3">
            {repairStats.totalServices} services · avg {repairStats.avgAttemptsToCompile.toFixed(1)} attempts ·{' '}
            {repairStats.unrepairedCount} unrepaired
          </p>
          <ResponsiveContainer width="100%" height={100}>
            <BarChart data={repairBar} layout="vertical" barSize={20}>
              <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#334155" />
              <XAxis type="number" domain={[0, 100]} tick={{ fontSize: 10, fill: '#94a3b8' }} unit="%" />
              <YAxis type="category" dataKey="name" tick={{ fontSize: 11, fill: '#94a3b8' }} width={80} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: 'none', borderRadius: 8, fontSize: 12 }}
                formatter={(v: number) => [`${v}%`, '']}
              />
              <Bar dataKey="value" fill="#6366f1" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
