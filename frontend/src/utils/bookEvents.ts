export const bookPriceEvent = 'verdex:book-price'
export function chooseBookPrice(symbol: string, price: number) {
  window.dispatchEvent(new CustomEvent(bookPriceEvent, { detail: { symbol, price } }))
}
