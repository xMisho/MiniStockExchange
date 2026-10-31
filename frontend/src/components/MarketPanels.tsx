import { MarketDepth } from './MarketDepth'
import { RecentTrades } from './RecentTrades'
import { CandleChart } from './CandleChart'

export function MarketPanels({ symbol }: { symbol: string }) {
  return <>
    <div className="lower-panels"><MarketDepth symbol={symbol} /><RecentTrades symbol={symbol} /></div>
    <CandleChart symbol={symbol} />
  </>
}
