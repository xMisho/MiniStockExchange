import { useEffect, useState } from 'react'
import { AuthProvider } from './auth/AuthContext'
import { AccountMenu } from './components/AccountMenu'
import { ExternalMarketDashboard } from './pages/ExternalMarketDashboard'
import { LocalExchange } from './pages/LocalExchange'
import './App.css'

type View = 'markets' | 'exchange' | 'orders'
const currentView = (): View => window.location.hash === '#/exchange' ? 'exchange' : window.location.hash === '#/orders' ? 'orders' : 'markets'
export function VerdexWorkspace() {
  const [view, setView] = useState<View>(currentView)
  useEffect(() => {
    const changed = () => { setView(currentView()); window.scrollTo?.({ top: 0 }) }
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  useEffect(() => { document.title = `Verdex | ${view === 'markets' ? 'Markets' : view === 'exchange' ? 'Local Exchange' : 'My Orders'}` }, [view])
  return <><a className="skip-link" href="#main-content">Skip to workspace</a><div className="app-shell">
    <header className="site-header app-header">
      <a className="brand" href="#/markets" aria-label="Verdex home"><span className="brand-mark" aria-hidden="true">V<span>↗</span></span><span>Verdex</span></a>
      <nav className="primary-nav" aria-label="Primary navigation">{([['markets', 'Markets'], ['exchange', 'Local Exchange'], ['orders', 'My Orders']] as const).map(([key, label]) => <a key={key} href={`#/${key}`} aria-current={view === key ? 'page' : undefined}>{label}</a>)}</nav>
      <AccountMenu />
    </header>
    <div id="trading-feedback" />
    <main id="main-content" tabIndex={-1}>
      {view === 'markets' && <ExternalMarketDashboard />}
      <LocalExchange view={view === 'markets' ? 'hidden' : view === 'orders' ? 'orders' : 'local'} onShowOrders={() => { window.location.hash = '/orders' }} />
    </main>
    <footer><span>Verdex <span className="brand-origin">· Verde + index</span></span><span>External markets · Local exchange</span></footer>
  </div></>
}
function App() { return <AuthProvider><VerdexWorkspace /></AuthProvider> }
export default App
