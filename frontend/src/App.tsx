import { MarketDashboard } from './pages/MarketDashboard'
import './App.css'

function App() {
  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="MiniStockExchange home">
          <span className="brand-mark" aria-hidden="true">M<span>↗</span></span><span>MiniStockExchange</span>
        </a>
        <span className="environment-label"><span aria-hidden="true" />Local development</span>
      </header>
      <main><MarketDashboard /></main>
      <footer><span>MiniStockExchange</span><span>Local simulation · No real-money trading</span></footer>
    </div>
  )
}

export default App
