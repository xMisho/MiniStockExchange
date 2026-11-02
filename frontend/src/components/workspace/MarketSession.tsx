import { getSession } from '../../api/workspace'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import { formatUtcDateTime } from '../../utils/format'
export function MarketSession() {
  const state = useSymbolResource('market-session', getSession, 60000)
  const result = state.resource.status === 'ready' ? state.resource.data : null
  const data = result?.data
  return <div className="market-session" role="status">
    <strong>{data ? ({ REGULAR: 'Regular session open', PRE_MARKET: 'Pre-market', AFTER_HOURS: 'After-hours', CLOSED: 'Market closed' })[data.status] : state.resource.status === 'error' ? 'Session status unavailable' : 'Checking market session…'}</strong>
    {data && <span>{data.status === 'REGULAR' ? `Regular close ${formatUtcDateTime(data.nextClose)}` : `Next regular open ${formatUtcDateTime(data.nextOpen)}`} · US equity calendar</span>}
    {(result?.stale || state.refreshError) && <span>Session status could not refresh; last checked {result ? formatUtcDateTime(result.fetchedAt) : 'unknown'}.</span>}
    <small>Session hours do not guarantee IEX quotes or extended-hours coverage.</small>
  </div>
}
