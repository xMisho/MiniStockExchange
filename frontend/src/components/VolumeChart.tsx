import type { ChartCandle } from '../types/market'
import { formatCount, formatUtcDateTime } from '../utils/format'

export function VolumeChart({ candles, symbol }: { candles: ChartCandle[]; symbol: string }) {
  const visible = candles.slice(-30)
  if (visible.length === 0) return null
  const peak = Math.max(...visible.map((candle) => candle.volume))
  return <div className="volume-chart">
    <div className="volume-heading"><h3>Executed volume</h3><span>Latest {visible.length} bars · shares</span></div>
    <div className="volume-bars" role="img" aria-label={`${symbol} executed volume across ${visible.length} candles. Peak ${formatCount(peak)} shares. Exact volumes are in the candle data table.`}>
      {visible.map((candle) => <div key={candle.startTime} className={candle.close >= candle.open ? 'volume-up' : 'volume-down'} style={{ height: `${(peak > 0 ? Math.max(2, candle.volume / peak * 100) : 0)}%` }} title={`${formatUtcDateTime(candle.startTime)} · ${formatCount(candle.volume)} shares`} />)}
    </div>
    <div className="volume-axis" aria-hidden="true"><span>{formatUtcDateTime(visible[0].startTime)}</span><span>{formatUtcDateTime(visible.at(-1)!.startTime)}</span></div>
  </div>
}
