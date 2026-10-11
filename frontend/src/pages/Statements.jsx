import { useEffect, useRef, useState } from 'react'
import { api, percent } from '../api.js'

export default function Statements() {
  const [batches, setBatches] = useState(null)
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const fileInput = useRef(null)

  const load = () => api('/statements').then(setBatches).catch((e) => setError(e.message))
  useEffect(() => { load() }, [])

  const upload = async (file) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return api('/statements', { method: 'POST', body: form })
  }

  const onFile = async (e) => {
    const file = e.target.files[0]
    if (!file) return
    setBusy('Uploading ' + file.name)
    setError('')
    try {
      await upload(file)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy('')
      e.target.value = ''
    }
  }

  const loadDemo = async () => {
    setBusy('Creating demo ledger entries and a statement')
    setError('')
    try {
      await api('/demo/seed?count=150', { method: 'POST' })
      const res = await fetch('/api/demo/statement.csv')
      if (!res.ok) throw new Error('Could not build the demo statement')
      const blob = await res.blob()
      await upload(new File([blob], 'demo-statement.csv', { type: 'text/csv' }))
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy('')
    }
  }

  const reconcile = async (id) => {
    setBusy('Matching statement ' + id)
    setError('')
    try {
      setResult(await api('/statements/' + id + '/reconcile', { method: 'POST' }))
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy('')
    }
  }

  return (
    <>
      <div className="head">
        <h1>Bank statements</h1>
        <p className="muted">Upload a csv with the columns date, amount, description and reference. Deposits are positive, payouts negative.</p>
      </div>

      <div className="panel">
        <div className="row">
          <div className="fit">
            <input ref={fileInput} type="file" accept=".csv,text/csv" onChange={onFile} style={{ display: 'none' }} />
            <button disabled={!!busy} onClick={() => fileInput.current.click()}>Upload csv</button>
          </div>
          <div className="fit">
            <button className="secondary" disabled={!!busy} onClick={loadDemo}>Load demo data</button>
          </div>
        </div>
        {busy && <p className="muted" style={{ marginTop: 12 }}>{busy}...</p>}
        {error && <div className="error">{error}</div>}
      </div>

      {result && (
        <div className="panel">
          <h2>Statement {result.batchId} reconciled</h2>
          <div className="figures">
            <div className="figure"><div className="value">{percent(result.matchRate)}</div><div className="label">matched</div></div>
            <div className="figure"><div className="value">{result.exact}</div><div className="label">exact</div></div>
            <div className="figure"><div className="value">{result.fuzzy}</div><div className="label">fuzzy</div></div>
            <div className="figure"><div className="value">{result.semantic}</div><div className="label">semantic</div></div>
            <div className="figure"><div className="value">{result.unmatched}</div><div className="label">left over</div></div>
            <div className="figure"><div className="value">{result.anomalies}</div><div className="label">flags raised</div></div>
          </div>
        </div>
      )}

      <div className="panel">
        {!batches ? (
          <p className="muted">Loading...</p>
        ) : batches.length === 0 ? (
          <p className="muted">No statements yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>File</th>
                <th>Uploaded</th>
                <th className="num">Lines</th>
                <th className="num">Matched</th>
                <th className="num">Open</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {batches.map((b) => (
                <tr key={b.id}>
                  <td>{b.id}</td>
                  <td>{b.filename}</td>
                  <td>{new Date(b.uploaded_at).toLocaleString()}</td>
                  <td className="num">{b.lines}</td>
                  <td className="num">{b.matched}</td>
                  <td className="num">{b.unmatched}</td>
                  <td>
                    <button className="small-btn" disabled={!!busy || b.unmatched === 0} onClick={() => reconcile(b.id)}>
                      Run matching
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
