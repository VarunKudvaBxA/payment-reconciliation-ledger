import { useEffect, useRef, useState } from 'react'
import { api, money } from '../api.js'

export default function Ledger() {
  const [accounts, setAccounts] = useState(null)
  const [entries, setEntries] = useState([])
  const [kind, setKind] = useState('transfers')
  const [from, setFrom] = useState('WALLET_ALICE')
  const [to, setTo] = useState('WALLET_BOB')
  const [amount, setAmount] = useState('25.00')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  // one key per attempt. If the request fails and the person clicks again, the same key is reused,
  // so a retry can never post the payment twice.
  const key = useRef(crypto.randomUUID())

  const load = () => {
    api('/accounts').then(setAccounts).catch((e) => setError(e.message))
    api('/entries?limit=15').then(setEntries).catch((e) => setError(e.message))
  }
  useEffect(load, [])

  const wallets = (accounts || []).filter((a) => a.type === 'LIABILITY')

  const submit = async (e) => {
    e.preventDefault()
    setError('')
    setMessage('')
    try {
      const body = { amount: Number(amount), description: '' }
      if (kind !== 'deposits') body.from = from
      if (kind !== 'withdrawals') body.to = to
      const res = await api('/' + kind, { method: 'POST', body, headers: { 'Idempotency-Key': key.current } })
      setMessage(res.replayed ? 'This request was already processed, entry ' + res.entryId + '.' : 'Posted as entry ' + res.entryId + '.')
      key.current = crypto.randomUUID()
      load()
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <>
      <div className="head">
        <h1>Ledger</h1>
        <p className="muted">Every payment is a balanced entry: the debits and credits always add up to the same amount.</p>
      </div>

      <div className="panel">
        <h2>Balances</h2>
        {!accounts ? (
          <p className="muted">Loading...</p>
        ) : (
          <table>
            <thead><tr><th>Account</th><th>Type</th><th className="num">Balance</th></tr></thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id}>
                  <td>{a.name}<div className="muted small">{a.code}</div></td>
                  <td>{a.type.toLowerCase()}</td>
                  <td className="num">{money(a.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <form className="panel" onSubmit={submit}>
        <h2>Move money</h2>
        <div className="row">
          <div>
            <label htmlFor="kind">Type</label>
            <select id="kind" value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="transfers">Transfer between wallets</option>
              <option value="deposits">Deposit from bank</option>
              <option value="withdrawals">Withdraw to bank</option>
            </select>
          </div>
          {kind !== 'deposits' && (
            <div>
              <label htmlFor="from">From</label>
              <select id="from" value={from} onChange={(e) => setFrom(e.target.value)}>
                {wallets.map((w) => <option key={w.code} value={w.code}>{w.name}</option>)}
              </select>
            </div>
          )}
          {kind !== 'withdrawals' && (
            <div>
              <label htmlFor="to">To</label>
              <select id="to" value={to} onChange={(e) => setTo(e.target.value)}>
                {wallets.map((w) => <option key={w.code} value={w.code}>{w.name}</option>)}
              </select>
            </div>
          )}
          <div>
            <label htmlFor="amount">Amount (USD)</label>
            <input id="amount" type="number" step="0.01" min="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </div>
          <div className="fit"><button type="submit">Post entry</button></div>
        </div>
        {message && <p className="good" style={{ marginTop: 12 }}>{message}</p>}
        {error && <div className="error">{error}</div>}
      </form>

      <div className="panel">
        <h2>Latest entries</h2>
        {entries.length === 0 ? (
          <p className="muted">No entries yet.</p>
        ) : (
          entries.map((e) => (
            <div className="entry" key={e.id}>
              <div>
                <strong>{e.description}</strong>{' '}
                <span className="muted small">{e.reference}, {new Date(e.posted_at).toLocaleDateString()}</span>
              </div>
              {e.lines.map((l, i) => (
                <div className="entry-lines" key={i}>
                  {l.direction === 'D' ? 'Debit ' : 'Credit'} {l.account} {money(l.amount)}
                </div>
              ))}
            </div>
          ))
        )}
      </div>
    </>
  )
}
