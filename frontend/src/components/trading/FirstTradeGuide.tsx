export function FirstTradeGuide({ symbol }: { symbol: string }) {
  return <details className="first-trade-guide data-panel">
    <summary>New to the local exchange? Try your first trade</summary>
    <p>This is a simulation. External market · IEX prices do not fill these orders. Use a small quantity and choose your own example limit.</p>
    <p>Before selling, ask an admin to allocate demo shares. Allocations use your virtual cash at the local reference price; short selling is disabled.</p><ol>
      <li>Select {symbol}, SELL, LIMIT and 1 share. Enter an example price and submit. With no matching buyer, the sell rests in the local book.</li>
      <li>Click its ask price in Market depth to copy the exact limit into the ticket. Select BUY, LIMIT and 1 share, then submit.</li>
      <li>Check Recent trades, the local chart and My Orders → Order activity to see the fill. If another participant takes the quote first, inspect the current book before trying again.</li>
    </ol>
    <p className="panel-footnote">Using one account for both sides demonstrates matching but nets to zero in your portfolio. Use two accounts to explore holdings. No demo orders are created automatically.</p>
  </details>
}
