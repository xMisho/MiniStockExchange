import { useEffect, useId, useRef, useState } from 'react'
import { searchInstruments } from '../../api/workspace'
import type { Instrument } from '../../types/externalMarket'
export function SymbolSearch({ onSelect, label = 'Find a stock', regionLabel = 'Search external stocks', disabled = false }: { onSelect: (item: Instrument) => void; label?: string; regionLabel?: string; disabled?: boolean }) {
  const resultsId = useId()
  const input = useRef<HTMLInputElement>(null)
  const results = useRef<HTMLUListElement>(null)
  const [query, setQuery] = useState(''), [items, setItems] = useState<Instrument[]>([]), [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(false)
  useEffect(() => {
    if (disabled) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      if (!query.trim()) { setItems([]); setError(null); setLoading(false); return }
      setLoading(true)
      searchInstruments(query.trim(), controller.signal).then(data => { if (!controller.signal.aborted) { setItems(data); setError(null) } }).catch(e => { if (!controller.signal.aborted) { setItems([]); setError(e instanceof Error ? e.message : 'Search failed.') } }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    }, 300)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [query, disabled])
  return <section className="symbol-search" aria-label={regionLabel}><label>{label}<input disabled={disabled} ref={input} aria-controls={resultsId} onKeyDown={event => {
    if (event.key === 'ArrowDown' && items.length) { event.preventDefault(); results.current?.querySelector('button')?.focus() }
    if (event.key === 'Escape') { setQuery(''); setItems([]) }
  }} type="search" maxLength={60} value={query} placeholder="Symbol or company, e.g. NVDA" onChange={event => { setQuery(event.target.value); setItems([]); setLoading(false); setError(null) }} /></label>
    {loading && <p role="status">Searching provider instruments…</p>}{error && <p role="alert">{error}</p>}
    {query.trim() && !loading && !error && items.length === 0 && <p className="panel-footnote">No results yet. Search uses Alpaca’s active US equity catalog.</p>}
    <p role="status" className="sr-only">{items.length ? `${items.length} results. Use Down Arrow to enter the results, then arrow keys or Tab to navigate.` : ''}</p>
    {items.length > 0 && <ul ref={results} id={resultsId} className="search-results" aria-label="Matching stocks">{items.map(item => <li key={item.symbol}><button type="button" disabled={disabled} onClick={() => { onSelect(item); setQuery(''); setItems([]); input.current?.focus() }} onKeyDown={event => {
      const buttons = Array.from(results.current?.querySelectorAll('button') ?? [])
      const index = buttons.indexOf(event.currentTarget)
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); buttons[(index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus() }
      if (event.key === 'Escape') { setQuery(''); setItems([]); input.current?.focus() }
    }}><strong>{item.symbol}</strong><span>{item.companyName}</span></button></li>)}</ul>}
  </section>
}
