import { useEffect, useRef, useState } from 'react'
import Prism from 'prismjs'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-xml-doc'
import type { Artifact } from '../types'

const TABS = [
  { label: 'pom.xml',     match: (p: string) => p === 'pom.xml' },
  { label: 'Application', match: (p: string) => p.endsWith('Application.java') },
  { label: 'Entity',      match: (p: string) => p.includes('/entity/') },
  { label: 'DTO',         match: (p: string) => p.includes('/dto/') },
  { label: 'Repository',  match: (p: string) => p.includes('Repository.java') },
  { label: 'Service',     match: (p: string) => p.includes('Service.java') && !p.includes('Application') },
  { label: 'Controller',  match: (p: string) => p.includes('Controller.java') },
  { label: 'Tests',       match: (p: string) => p.includes('Test') },
]

function langFor(path: string): string {
  if (path.endsWith('.xml')) return 'xml'
  if (path.endsWith('.java')) return 'java'
  return 'text'
}

interface Props {
  artifacts: Artifact[]
}

export default function CodeViewer({ artifacts }: Props) {
  const [tab, setTab] = useState(0)
  const codeRef = useRef<HTMLElement>(null)

  // Find artifact for the active tab
  const tabDef = TABS[tab]
  const hit = artifacts.find(a => tabDef.match(a.filePath))

  // If nothing found on this tab, fall back to first artifact
  const artifact = hit ?? artifacts[0]

  useEffect(() => {
    if (codeRef.current) Prism.highlightElement(codeRef.current)
  }, [artifact])

  if (!artifacts.length) {
    return (
      <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-8 text-center text-slate-400 text-sm">
        No generated files yet
      </div>
    )
  }

  const hasTabs = TABS.filter(t => artifacts.some(a => t.match(a.filePath)))

  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
      {/* Tab bar */}
      <div className="flex overflow-x-auto bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
        {hasTabs.map((t, i) => {
          const origIdx = TABS.indexOf(t)
          return (
            <button
              key={t.label}
              onClick={() => setTab(origIdx)}
              className={`px-3 py-2 text-xs font-medium whitespace-nowrap transition-colors ${
                origIdx === tab
                  ? 'bg-white dark:bg-slate-900 text-indigo-600 dark:text-indigo-400 border-b-2 border-indigo-500'
                  : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'
              }`}
            >
              {t.label}
            </button>
          )
        })}
      </div>

      {/* File path header */}
      {artifact && (
        <div className="px-4 py-2 bg-slate-800 text-slate-400 text-xs font-mono flex justify-between items-center">
          <span>{artifact.filePath}</span>
          <span className="text-slate-500">{artifact.lineCount} lines</span>
        </div>
      )}

      {/* Code */}
      <div className="max-h-[480px] overflow-auto">
        {artifact ? (
          <pre className={`language-${langFor(artifact.filePath)} !m-0 !rounded-none`}>
            <code ref={codeRef} className={`language-${langFor(artifact.filePath)}`}>
              {artifact.content}
            </code>
          </pre>
        ) : (
          <div className="p-6 text-slate-400 text-sm text-center">No file for this tab</div>
        )}
      </div>
    </div>
  )
}
