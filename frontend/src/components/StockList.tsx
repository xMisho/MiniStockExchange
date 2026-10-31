import type { StockMetadata } from '../types/market'

interface Props {
  stocks: StockMetadata[]
  selectedSymbol: string | null
  onSelect: (symbol: string) => void
}

export function StockList({ stocks, selectedSymbol, onSelect }: Props) {
  return (
    <aside className="stock-panel" aria-labelledby="stocks-title">
      <div className="panel-heading"><h2 id="stocks-title">Stocks</h2><span>{stocks.length} listed</span></div>
      <div className="stock-list" role="group" aria-label="Select a stock">
        {stocks.map((stock) => (
          <button key={stock.symbol} className={`stock-option ${selectedSymbol === stock.symbol ? 'selected' : ''}`}
            type="button" aria-label={`${stock.symbol} ${stock.companyName}`} aria-pressed={selectedSymbol === stock.symbol} onClick={() => onSelect(stock.symbol)}>
            <span className="stock-symbol">{stock.symbol}</span>
            <span className="stock-company">{stock.companyName}</span>
            <span className="stock-arrow" aria-hidden="true">↗</span>
          </button>
        ))}
      </div>
    </aside>
  )
}
