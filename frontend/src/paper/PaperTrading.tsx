import { useCallback, useRef, useState } from 'react'
import { useAuth } from '../auth/authState'
import { AuthError } from '../api/auth'
import { getExternalQuote } from '../api/externalMarket'
import { useSymbolResource } from '../hooks/useSymbolResource'
import { ExternalPriceChart } from '../components/ExternalPriceChart'
import { formatMoney, formatUtcDateTime } from '../utils/format'
import { priceJson, orderQuantity } from '../api/orders'
import * as api from './api'
import type { PaperOrder, PaperRequest } from './api'
const starters = ['AAPL','AMD','AMZN','AVGO','GOOGL','META','MSFT','NVDA','PLTR','TSLA']
const message = (e: unknown) => e instanceof Error ? e.message : 'Paper trading is unavailable.'
export function PaperTrading({ view, active = true }: { view: 'trade' | 'orders' | 'portfolio'; active?: boolean }) {
 const auth=useAuth()
 return <><section className="dashboard-intro"><p className="eyebrow">ALPACA · IEX · SIMULATED EXECUTION</p><h1>{view==='trade'?'Trade the market.':view==='orders'?'Your paper orders.':'Your paper portfolio.'}</h1><p className="dashboard-description">Virtual money, external prices. This account is separate from Matching Sandbox. Orders are never sent to a broker.</p></section>{!auth.user?<p className="page-message">Sign in to use your $100,000 paper account.</p>:<PaperAccount key={auth.user.id} view={view} active={active} />}</>
}
function PaperAccount({view, active}: {view:'trade'|'orders'|'portfolio'; active:boolean}) {
 const auth=useAuth(); const [symbol,setSymbol]=useState('AAPL'); const [revision,setRevision]=useState(0)
 const [side,setSide]=useState<'BUY'|'SELL'>('BUY'),[type,setType]=useState<'MARKET'|'LIMIT'>('MARKET'),[quantity,setQuantity]=useState('1'),[price,setPrice]=useState('')
 const [busy,setBusy]=useState(false),[notice,setNotice]=useState<string|null>(null),[error,setError]=useState<string|null>(null)
 const storageKey=`verdex:paper-request:${auth.user!.id}`
 const [pending,setPending]=useState<PaperRequest|null>(()=>{try{const value=sessionStorage.getItem(storageKey);return value?JSON.parse(value) as PaperRequest:null}catch{return null}})
 const mutation=useRef(false)
 const balance=useSymbolResource(active ? `paper:${auth.user!.id}:${revision}` : null,api.portfolio,10000)
 const quote=useSymbolResource(active && view==='trade'?symbol:null,getExternalQuote,15000)
 const executions=useSymbolResource(active && view==='trade'?symbol:null,api.fills,5000)
 const [page,setPage]=useState(0)
 const [status,setStatus]=useState('')
 const loadOrders=useCallback((_key:string,signal:AbortSignal)=>api.orders(page,signal,status),[page,status])
 const history=useSymbolResource(active && view!=='portfolio' ? `paper-orders:${auth.user!.id}:${page}:${status}:${revision}` : null,loadOrders,5000)
 const data=balance.resource.status==='ready'?balance.resource.data:null
 const q=quote.resource.status==='ready'?quote.resource.data.data:null
 const current=executions.resource.status==='ready'?executions.resource.data:[]
 const refresh=()=>{setRevision(x=>x+1);executions.retry()}
 async function place(request:PaperRequest){
  if(mutation.current || auth.busy)return
  mutation.current=true;setBusy(true);setError(null);const release=auth.holdSession()
  try {
   sessionStorage.setItem(storageKey,JSON.stringify(request));setPending(request)
   const result=await api.submit(request)
   sessionStorage.removeItem(storageKey);setPending(null)
   setPage(0);setStatus('');setNotice(`${result.side} ${result.symbol} order #${result.id}: ${result.status}${result.executionPrice!==null?` at ${formatMoney(result.executionPrice)}`:'. Waiting for a fresh qualifying quote.'}`);refresh()
  }catch(e){
   const safe=e instanceof AuthError && (!e.mutationSent || (e.status>=400 && e.status<500))
   if(safe){sessionStorage.removeItem(storageKey);setPending(null)}
   setError(`${message(e)}${safe?'':' Check My Orders. Any retry below uses the same key and original order, never a new submission.'}`);refresh()
  }finally{release();mutation.current=false;setBusy(false)}
 }
 async function change(action:()=>Promise<void>){
  if(mutation.current || auth.busy)return;mutation.current=true;setBusy(true);setError(null);const release=auth.holdSession()
  try{await action();setNotice('Paper order updated.');refresh()}catch(e){setError(`${message(e)} Review My Orders before taking another action.`);refresh()}finally{release();mutation.current=false;setBusy(false)}
 }
 const [editing,setEditing]=useState<PaperOrder|null>(null),[editPrice,setEditPrice]=useState(''),[editQuantity,setEditQuantity]=useState('')
 const selected=data?.positions.find(p=>p.symbol===symbol)
 const list=history.resource.status==='ready'?history.resource.data:null
 const openOnPage=list?.content.filter(order=>order.status==='OPEN').length
 const money=(v:number|null)=>v===null?'—':formatMoney(v)
 return <>
  {notice&&<div className="trading-notice success" role="status">{notice}<button className="text-button" onClick={()=>setNotice(null)}>Dismiss</button></div>}
  {error&&<p className="trading-error" role="alert">{error}</p>}
  {pending&&<div className="trading-notice" role="alert"><p>Unconfirmed {pending.side} {pending.quantity} {pending.symbol} request. Review My Orders before retrying. The stored request key prevents a duplicate.</p><button className="text-button" disabled={busy||auth.busy} onClick={()=>void place(pending)}>Retry same request safely</button><a href="#/orders">Review My Orders</a></div>}
  {view==='trade'&&<>
   <section className="local-ticker" aria-label="External paper market"><label>Paper trading stock<select value={symbol} onChange={e=>{setSymbol(e.target.value);setPrice('')}}>{starters.map(s=><option key={s}>{s}</option>)}</select></label><div><span className="metric-label">IEX LAST</span><strong>{q?money(q.price):'—'}</strong></div><div><span className="metric-label">IEX BID / ASK</span><strong>{q?`${money(q.bid)} / ${money(q.ask)}`:'—'}</strong></div><a href="#/portfolio">View paper portfolio</a></section>
   {quote.errorMessage&&<p role="status">{quote.errorMessage} <button className="text-button" onClick={quote.retry}>Retry quote</button></p>}
   <div className="paper-trading-grid"><ExternalPriceChart key={symbol} symbol={symbol} executions={current}/><section className="data-panel terminal-ticket" aria-label="Place a paper order"><p className="eyebrow">PAPER TRADING · NO REAL MONEY</p><h2>Place an order</h2><p>{symbol} · IEX ask for BUY; bid for SELL.</p><p>Available cash: {data?money(data.availableCash):'—'} · Available {symbol}: {selected?.availableShares??0} shares</p>
   <form className={`trading-form side-${side.toLowerCase()}`} onSubmit={e=>{e.preventDefault();try{const qty=orderQuantity(quantity);if(qty>1000000)throw new Error('Maximum 1,000,000 shares.');void place({symbol,side,type,quantity:qty,...(type==='LIMIT'?{limitPrice:priceJson(price)}:{}),key:crypto.randomUUID()})}catch(f){setError(message(f))}}}>
    <fieldset disabled={busy||auth.busy||!!pending}><legend className="sr-only">Paper order fields</legend><div className="trading-fields"><label>Side<select value={side} onChange={e=>setSide(e.target.value as 'BUY'|'SELL')}><option>BUY</option><option>SELL</option></select></label><label>Order type<select value={type} onChange={e=>setType(e.target.value as 'MARKET'|'LIMIT')}><option>MARKET</option><option>LIMIT</option></select></label><label>Quantity<input required inputMode="numeric" value={quantity} onChange={e=>setQuantity(e.target.value)}/></label><label>Limit price<input inputMode="decimal" required={type==='LIMIT'} disabled={type==='MARKET'} value={type==='LIMIT'?price:''} onChange={e=>setPrice(e.target.value)} placeholder={type==='MARKET'?'Fresh IEX quote':'USD'}/></label></div>
    <p className="panel-footnote">{type==='MARKET'?'Executes only in the regular US session with a valid quote no older than 30 seconds. Missing quotes reject the order.':'Good until cancelled. Reserves cash or shares, then waits for a new qualifying quote in the regular session. Checks run about every 15 seconds while the server is awake; missed crossings are not replayed.'}</p>
    <p className="panel-footnote">Estimate: {(()=>{const p=type==='LIMIT'?Number(price):side==='BUY'?q?.ask:q?.bid;const qty=Number(quantity);return p&&qty>0?money(p*qty):'—'})()} · Final price is checked by the server.</p>
    <button className={`primary-button ${side==='SELL'?'sell-button':''}`} type="submit" disabled={busy||auth.busy||!!pending}>{busy?'Submitting…':`Submit paper ${side} order`}</button></fieldset>
   </form><p className="panel-footnote">Whole-share, full-size simulated fills at the quoted price. IEX is one exchange; displayed depth, slippage, fees and real broker execution are not modelled. No short selling or borrowing.</p></section></div>
   <section className="data-panel"><h2>Your executions · {symbol}</h2><p className="panel-footnote">BUY/SELL markers appear when the execution falls inside the displayed historical bars. Recent fills may be newer than the chart’s 15-minute cutoff.</p>{current.length===0?<p>No paper fills yet.</p>:<ul>{current.slice(0,10).map(o=><li key={o.id}>{o.side} {o.quantity} at {money(o.executionPrice)} · {formatUtcDateTime(o.executedAt!)} · Quote {formatUtcDateTime(o.quoteAt!)}</li>)}</ul>}</section>
  </>}
  {view==='portfolio'&&<section className="data-panel" aria-label="External paper portfolio"><h2>Paper portfolio · USD</h2><p>Separate $100,000 account · valued at external reported trade prices</p>{data?<><dl className="portfolio-metrics">{[['Cash',data.cash],['Reserved cash',data.reservedCash],['Available cash',data.availableCash],['Equity',data.equity],['Realized P/L',data.realizedPnl],['Unrealized P/L',data.unrealizedPnl]].map(([label,v])=><div key={String(label)}><dt>{label}</dt><dd>{money(v as number|null)}</dd></div>)}</dl>{data.positions.length===0?<p>No paper holdings yet. Buy a starter stock in Paper Trading.</p>:<div className="table-scroll"><table className="market-table"><thead><tr>{['Stock','Shares','Reserved','Average cost','External mark','Mark time (UTC)','Market value','Unrealized P/L'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{data.positions.map(p=><tr key={p.symbol}><td>{p.symbol}</td><td>{p.quantity}</td><td>{p.reservedShares}</td><td>{money(p.averagePrice)}</td><td>{money(p.markPrice)}</td><td>{p.markAsOf?formatUtcDateTime(p.markAsOf):'Unavailable'}</td><td>{money(p.marketValue)}</td><td>{money(p.unrealizedPnl)}</td></tr>)}</tbody></table></div>}<p className="panel-footnote">Marks use the latest reported external trade, which may be from a previous session. Check its timestamp. Missing valuation stays —; cash and holdings are preserved. Execution requires fresh bid/ask quotes.</p></>:<p>{balance.errorMessage??'Loading paper account…'}</p>}{balance.refreshError&&<p role="status">Refresh failed; displayed balances may be stale.</p>}<button className="text-button" onClick={balance.retry}>Refresh paper portfolio</button></section>}
  {view!=='portfolio'&&<section className="data-panel" aria-label="My paper orders"><div className="data-heading"><h2>My paper orders</h2><button className="text-button" onClick={history.retry}>Refresh paper orders</button></div><div className="order-summary" aria-label="Paper order summary"><strong>{list ? status==='OPEN' ? `${list.totalElements} open paper orders` : `${openOnPage} open paper orders on this page` : 'Checking open paper orders…'}</strong><button className="text-button" onClick={()=>{setStatus('OPEN');setPage(0)}}>View all open paper orders</button><a href="#/sandbox-orders">Looking for sandbox orders?</a></div><div className="order-history-tools"><label>Paper order status<select value={status} onChange={e=>{setStatus(e.target.value);setPage(0)}}><option value="">All statuses</option><option value="OPEN">OPEN · Waiting for a quote</option><option value="FILLED">FILLED</option><option value="CANCELLED">CANCELLED</option></select></label><span>Paper Trading orders only · Refreshes every 5 seconds</span></div>{!list?<p>{history.errorMessage??'Loading orders…'}</p>:<><div className="table-scroll"><table className="market-table paper-order-table"><thead><tr>{['ID / stock','Side / type','Quantity','Limit','Status','Fill / quote time','Actions'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{list.content.map(o=><tr key={o.id}><td data-label="ID / stock">#{o.id} {o.symbol}</td><td data-label="Side / type">{o.side} / {o.type}</td><td data-label="Quantity">{o.quantity}</td><td data-label="Limit">{money(o.limitPrice)}</td><td data-label="Status"><span className={`order-status status-${o.status.toLowerCase()}`}>{o.status}</span>{o.status==='OPEN'&&<small className="cell-secondary">Waiting for a qualifying IEX quote</small>}</td><td data-label="Fill / quote time">{money(o.executionPrice)}{o.quoteAt&&<small> · {formatUtcDateTime(o.quoteAt)}</small>}</td><td data-label="Actions">{o.status==='OPEN'&&<><button className="text-button" disabled={busy||auth.busy} onClick={()=>{setEditing(o);setEditPrice(String(o.limitPrice));setEditQuantity(String(o.quantity))}}>Amend</button><button className="text-button" disabled={busy||auth.busy} onClick={()=>void change(()=>api.cancel(o.id))}>Cancel</button></>}</td></tr>)}</tbody></table></div>{list.content.length===0&&<p>{status ? 'No paper orders match this filter.' : 'No paper orders yet.'}</p>}<div className="form-actions"><button className="text-button" disabled={page===0} onClick={()=>setPage(x=>x-1)}>Previous</button><span>Page {page+1} · {list.totalElements} orders</span><button className="text-button" disabled={page+1>=list.totalPages} onClick={()=>setPage(x=>x+1)}>Next</button></div></>}{history.refreshError&&<p role="status">Order refresh failed. Check again before submitting another order.</p>}</section>}
  {editing&&<section className="data-panel" aria-label="Amend paper limit order"><h2>Amend paper order #{editing.id}</h2><form className="amend-form" onSubmit={e=>{e.preventDefault();try{const qty=orderQuantity(editQuantity),p=priceJson(editPrice);void change(async()=>{await api.amend(editing.id,p,qty);setEditing(null)})}catch(f){setError(message(f))}}}><label>New limit price<input required inputMode="decimal" value={editPrice} onChange={e=>setEditPrice(e.target.value)}/></label><label>New quantity<input required inputMode="numeric" value={editQuantity} onChange={e=>setEditQuantity(e.target.value)}/></label><button className="primary-button" disabled={busy||auth.busy}>Save amendment</button><button className="text-button" type="button" onClick={()=>setEditing(null)}>Close</button></form></section>}
 </>
}
