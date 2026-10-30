import { useEffect, useState } from 'react'
import { checkBackendHealth } from './api/health'
import './App.css'

type Connection = 'checking' | 'connected' | 'unavailable'

function App() {
  const [connection, setConnection] = useState<Connection>('checking')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(8_000)])

    checkBackendHealth(signal)
      .then(() => {
        if (!controller.signal.aborted) setConnection('connected')
      })
      .catch(() => {
        if (!controller.signal.aborted) setConnection('unavailable')
      })

    return () => controller.abort()
  }, [attempt])

  function retryConnection() {
    setConnection('checking')
    setAttempt((value) => value + 1)
  }

  const connected = connection === 'connected'
  const status = connected ? 'Backend connected' : connection === 'checking' ? 'Checking backend' : 'Backend unavailable'

  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="MiniStockExchange home">
          <span className="brand-mark" aria-hidden="true">M<span>↗</span></span>
          <span>MiniStockExchange</span>
        </a>
        <span className="environment-label"><span aria-hidden="true" />Local development</span>
      </header>
      <main>
        <section className="workspace-intro" aria-labelledby="workspace-title">
          <p className="eyebrow">V3 / WEB WORKSPACE</p>
          <h1 id="workspace-title">A new view of<br />your exchange.</h1>
          <p className="intro-description">The matching engine has a home on the web. The frontend foundation is ready; the market dashboard comes next.</p>
        </section>
        <section className="connection-panel" aria-labelledby="connection-title">
          <div>
            <p className="eyebrow">CONNECTION CHECK</p>
            <h2 id="connection-title">Frontend meets backend.</h2>
            <p className="panel-description">A live check of your Spring Boot server. Market data and account screens will be added in the next steps.</p>
          </div>
          <div className="connection-details">
            <div className={`connection-status ${connection}`} role="status" aria-live="polite">
              <span className="status-dot" aria-hidden="true" />{status}
            </div>
            <p className="connection-help">
              {connected ? 'The health endpoint is responding. This checks the server connection, not live trading readiness.'
                : connection === 'checking' ? 'Reaching your local server…'
                  : <>Start the backend from the project root with <code>./mvnw spring-boot:run</code>, then retry.</>}
            </p>
            <button type="button" onClick={retryConnection} disabled={connection === 'checking'}>
              {connection === 'checking' ? 'Checking…' : 'Check connection'}<span aria-hidden="true">↗</span>
            </button>
          </div>
        </section>
        <section className="next-section" aria-labelledby="next-title">
          <div className="section-heading"><h2 id="next-title">What comes next</h2><span>The next milestones</span></div>
          <div className="milestone-grid">
            <article><span className="milestone-number">01</span><h3>Market dashboard</h3><p>Stock summaries, order-book depth, recent trades, and execution-based charts.</p></article>
            <article><span className="milestone-number">02</span><h3>Accounts & trading</h3><p>Sign in, submit an order, and manage your own trading history.</p></article>
            <article><span className="milestone-number">03</span><h3>Live market updates</h3><p>Keep the workspace current as new orders meet and trades happen.</p></article>
          </div>
        </section>
      </main>
      <footer><span>MiniStockExchange</span><span>Local simulation · No real-money trading</span></footer>
    </div>
  )
}

export default App
