import { AuthProvider } from './auth/AuthContext'
import { AccountMenu } from './components/AccountMenu'
import { MarketDashboard } from './pages/MarketDashboard'
import './App.css'

function App() {
  return (
    <AuthProvider><a className="skip-link" href="#main-content">Skip to market workspace</a><div className="app-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="MiniStockExchange home">
          <span className="brand-mark" aria-hidden="true">M<span>↗</span></span><span>MiniStockExchange</span>
        </a>
        <AccountMenu />
      </header>
      <main id="main-content" tabIndex={-1}><MarketDashboard /></main>
      <footer><span>MiniStockExchange</span><span>Local simulation · No real-money trading</span></footer>
    </div></AuthProvider>
  )
}

export default App
