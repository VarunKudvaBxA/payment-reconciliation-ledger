import { Fragment, useEffect, useState } from 'react'
import { api, money } from '../api.js'

export default function Exceptions() {
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(null)       // id of the line being looked at
  const [suggestion, setSuggestion] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = () => api('/exceptions').then(setData).catch((e) => setError(e.message))
  useEffect(() => { load() }, [])

  const investigate = async (line) => {
    if (open === line.id) {
      setOpen(null)
      return
    }
    setOpen(line.id)
    setSuggestion(null)
    setBusy(true)
    setError('')
    try {
      setSuggestion(await api('/exceptions/' + line.id + '/suggest', { method: 'POST' }))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const matchTo = async (lineId, entryId) => {
    setError('')
    try {
      await api('/matches/manual', { method: 'POST', body: { statementLineId: lineId, entryId } })
      setOpen(null)
      load()
    } catch (e) {
      setError(e.message)
    }
  }

  const ignore = async (lineId) => {
    setError('')
    try {
      await api('/exceptions/' + lineId + '/ignore', { method: 'POST' })
      setOpen(null)
      load()
    } catch (e) {
      setError(e.message)
    }
  }

  if (!data) return error ? <div className="error">{error}</div> : <p className="muted">Loading...</p>

  return (
    <>
      <div className="head">
        <h1>Exceptions</h1>
        <p className="muted">
          {data.unmatchedLines.length} bank lines with no ledger match, {data.missingSettlements.length} ledger entries the bank never showed.
        </p>
      </div>
      {error && <div className="error">{error}</div>}

      <div className="panel">
        <h2>Bank lines without a match</h2>
        {data.unmatchedLines.length === 0 ? (
          <p className="muted">Nothing left to review.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Date</th><th>Description</th><th>Flags</th><th className="num">Amount</th><th></th></tr>
            </thead>
            <tbody>
              {data.unmatchedLines.map((l) => (
                <Fragment key={l.id}>
                  <tr>
                    <td>{l.txn_date}</td>
                    <td>
                      {l.description}
                      {l.reference && <div className="muted small">ref {l.reference}</div>}
                    </td>
                    <td>
                      {l.flags.map((f) => (
                        <span key={f.kind} className={'tag ' + f.kind} title={f.detail}>{f.kind.replace('_', ' ').toLowerCase()}</span>
                      ))}
                    </td>
                    <td className="num">{money(l.amount)}</td>
                    <td>
                      <button className="small-btn secondary" onClick={() => investigate(l)}>
                        {open === l.id ? 'Close' : 'Investigate'}
                      </button>
                    </td>
                  </tr>
                  {open === l.id && (
                    <tr>
                      <td colSpan={5}>
                        {busy && <p className="muted">Looking at the ledger...</p>}
                        {suggestion && (
                          <>
                            <div className="suggestion">
                              <div>{suggestion.cause}</div>
                              <div><strong>Suggested step:</strong> {suggestion.action}</div>
                              <div className="muted small">
                                {suggestion.source === 'LLM' ? 'Written by an LLM from the data above.' : 'Simple rules, no LLM key set.'} A person decides.
                              </div>
                            </div>
                            {suggestion.candidates.length > 0 && (
                              <table>
                                <thead>
                                  <tr><th>Closest open ledger entries</th><th>Date</th><th className="num">Amount</th><th></th></tr>
                                </thead>
                                <tbody>
                                  {suggestion.candidates.map((c) => (
                                    <tr key={c.entry_id}>
                                      <td>
                                        {c.description}
                                        <div className="muted small">{c.reference}</div>
                                      </td>
                                      <td>{c.entry_date}</td>
                                      <td className="num">{money(c.bank_amount)}</td>
                                      <td>
                                        <button className="small-btn" onClick={() => matchTo(l.id, c.entry_id)}>
                                          Match to this
                                        </button>
                                        {suggestion.bestCandidateEntryId === c.entry_id && <span className="tag MANUAL" style={{ marginLeft: 6 }}>suggested</span>}
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            )}
                            <div style={{ marginTop: 10 }}>
                              <button className="secondary small-btn" onClick={() => ignore(l.id)}>Ignore this line</button>
                            </div>
                          </>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Ledger entries the bank never showed</h2>
        {data.missingSettlements.length === 0 ? (
          <p className="muted">None.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Posted</th><th>Entry</th><th>Why it is here</th><th className="num">Amount</th></tr>
            </thead>
            <tbody>
              {data.missingSettlements.map((m) => (
                <tr key={m.entry_id}>
                  <td>{m.entry_date}</td>
                  <td>{m.description}<div className="muted small">{m.reference}</div></td>
                  <td>{m.detail}</td>
                  <td className="num">{money(m.bank_amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
