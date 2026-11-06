import { useEffect, useState } from 'react'
import { AuthProvider } from './auth/AuthContext'
import { AccountMenu } from './components/AccountMenu'
import { ExternalMarketDashboard } from './pages/ExternalMarketDashboard'
import { LocalExchange } from './pages/LocalExchange'
import { PaperPortfolio } from './components/workspace/PaperPortfolio'
import { useAuth } from './auth/authState'
import { RecoveryLink } from './components/RecoveryLink'
import { PaperTrading } from './paper/PaperTrading'
import './App.css'

type View = 'markets' | 'exchange' | 'orders' | 'portfolio' | 'paper' | 'sandbox-orders' | 'sandbox-portfolio'
const currentView = (): View => { const route=window.location.hash.slice(2); return ['paper','exchange','orders','portfolio','sandbox-orders','sandbox-portfolio'].includes(route) ? route as View : 'markets' }
export function VerdexWorkspace() {
  const auth = useAuth()
  const [view, setView] = useState<View>(currentView)
  useEffect(() => {
    const changed = () => { setView(currentView()); window.scrollTo?.({ top: 0 }) }
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  useEffect(() => { document.title = `Verdex | ${view === 'markets' ? 'Markets' : view === 'exchange' ? 'Matching Sandbox' : view === 'paper' ? 'Paper Trading' : view.includes('orders') ? 'My Orders' : 'Portfolio'}` }, [view])
  return <><a className="skip-link" href="#main-content">Skip to workspace</a><div className="app-shell">
    <header className="site-header app-header">
      <a className="brand" href="#/markets" aria-label="Verdex home"><span className="brand-mark" aria-hidden="true">V<span>↗</span></span><span>Verdex</span></a>
      <nav className="primary-nav" aria-label="Primary navigation">{([['markets', 'Markets'], ['paper', 'Paper Trading'], ['orders', 'My Orders'], ['portfolio', 'Portfolio'], ['exchange', 'Matching Sandbox']] as const).map(([key, label]) => <a key={key} href={`#/${key}`} aria-current={view === key ? 'page' : undefined}>{label}</a>)}</nav>
      <AccountMenu /><RecoveryLink />
    </header>
    <div id="trading-feedback" />
    <main id="main-content" tabIndex={-1}>
      {view === 'markets' && <ExternalMarketDashboard />}
      {view === 'sandbox-portfolio' && <><section className="dashboard-intro"><p className="eyebrow">LOCAL PAPER ACCOUNT</p><h1>Your paper portfolio.</h1></section><PaperPortfolio key={auth.user?.id ?? 'anonymous'} active /></>}
      {['paper','orders','portfolio'].includes(view) && <PaperTrading key={view} view={view === 'paper' ? 'trade' : view === 'orders' ? 'orders' : 'portfolio'} />}
      {view === 'exchange' && <p className="panel-footnote">Independent matching-engine sandbox · <a href="#/sandbox-orders">Sandbox orders</a> · <a href="#/sandbox-portfolio">Sandbox portfolio</a>. For external-price executions, use <a href="#/paper">Paper Trading</a>.</p>}
      <LocalExchange view={view === 'exchange' ? 'local' : view === 'sandbox-orders' ? 'orders' : 'hidden'} onShowOrders={() => { window.location.hash = '/sandbox-orders' }} />
    </main>
    <footer><span>Verdex™</span><span>Paper trading · Matching sandbox</span></footer>
  </div></>
}
function App() { return <AuthProvider><VerdexWorkspace /></AuthProvider> }
export default App
