export const sellSharesEvent = 'verdex:sell-shares'
export interface SellIntent { symbol: string; quantity: number; nonce: string }
export function sellShares(symbol: string, quantity: number) {
  window.location.hash = '/exchange'
  window.dispatchEvent(new CustomEvent<SellIntent>(sellSharesEvent, {
    detail: { symbol, quantity: Math.min(quantity, 2147483647), nonce: crypto.randomUUID() },
  }))
}
