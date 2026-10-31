import { formatUtcTime } from '../utils/format'

interface Props { updatedAt: number | null; refreshError: boolean; refreshing: boolean; onRetry: () => void }

export function RefreshStatus({ updatedAt, refreshError, refreshing, onRetry }: Props) {
  if (updatedAt === null) return null
  return <div className="refresh-status">
    <span>Last updated {formatUtcTime(new Date(updatedAt).toISOString())}{refreshing ? ' · Refreshing…' : ''}</span>
    {refreshError && <span role="status">Refresh failed; showing previous data. <button type="button" onClick={onRetry}>Retry refresh</button></span>}
  </div>
}
