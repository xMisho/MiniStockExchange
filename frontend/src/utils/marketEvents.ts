export const marketRefreshEvent = 'exchange:market-refresh'
export function refreshMarket(symbol: string) { window.dispatchEvent(new CustomEvent(marketRefreshEvent, { detail: symbol })) }
