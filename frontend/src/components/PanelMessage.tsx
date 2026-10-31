interface Props { loading: boolean; name: string; onRetry: () => void }

export function PanelMessage({ loading, name, onRetry }: Props) {
  return loading ? <p className="panel-message" role="status">Loading {name}…</p> : (
    <div className="panel-message" role="alert"><p>Couldn’t load {name}. Other panels are still available.</p>
      <button className="primary-button" type="button" onClick={onRetry}>Retry {name}</button>
    </div>
  )
}
