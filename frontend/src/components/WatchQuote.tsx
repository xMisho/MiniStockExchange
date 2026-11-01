import { getMarketSnapshot } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import type { MarketSnapshot } from '../types/market'
import { formatChange } from '../utils/format'
import { PriceValue } from './PriceValue'

interface Props { symbol: string; selected: boolean; snapshot: MarketSnapshot | null }
export function WatchQuote({ symbol, selected, snapshot }: Props) {
  const { resource, refreshError } = useSymbolResource(selected ? null : symbol, getMarketSnapshot, 5000)
  const data = selected ? snapshot : resource.status === 'ready' ? resource.data : null
  const tone = data?.priceChange == null || data.priceChange === 0 ? 'neutral' : data.priceChange > 0 ? 'positive' : 'negative'
  return <span className="watch-quote"><strong><PriceValue value={data?.lastTradedPrice ?? null} /></strong>
    <span className={tone}>{!data ? 'Awaiting quote' : data.priceChangePercent === null ? 'No execution yet' : `${formatChange(data.priceChangePercent)}%`}</span>
    {!selected && (refreshError || resource.status === 'error') && <span className="watch-stale">Quote unavailable</span>}
  </span>
}
