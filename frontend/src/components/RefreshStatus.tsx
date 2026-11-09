import { formatUtcTime } from '../utils/format'

interface Props { updatedAt: number | null; refreshError: boolean; refreshing: boolean; onRetry: () => void; showActivity?: boolean }

export function RefreshStatus({ updatedAt, refreshError, refreshing, onRetry, showActivity = false }: Props) {
  if (updatedAt === null || (!showActivity && !refreshError)) return null
  return <div className="refresh-status">
    {showActivity && <span>Last updated {formatUtcTime(new Date(updatedAt).toISOString())}{refreshing ? ' · Refreshing…' : ''}</span>}
    {refreshError && <span role="status">Stale · Connection unavailable; showing previous data. <button type="button" onClick={onRetry}>Retry refresh</button></span>}
  </div>
}
