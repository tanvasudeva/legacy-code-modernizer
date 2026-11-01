# Migration Dashboard

React + TypeScript + Tailwind dashboard for the Legacy Code Modernizer. It lists migration jobs and, per job, shows pipeline phase, generated services, shared-library classes, generated code, and evaluation metrics.

## Run

The orchestrator must be running on `localhost:8080` (see the root [README](../README.md)).

```bash
npm install
npm run dev      # http://localhost:5173 — /api is proxied to localhost:8080
```

Other scripts: `npm run build` (type-check + production build), `npm run lint`, `npm run preview`.

## Layout

- `src/pages/` — `JobList`, `JobDetail`
- `src/components/` — `ServiceCard`, `CodeViewer`, `MetricsPanel`, `SharedLibraryBadge`, `PhaseBar`, `StatusBadge`
- `src/api.ts` — REST client (axios + react-query)
- `src/types.ts` — API types
