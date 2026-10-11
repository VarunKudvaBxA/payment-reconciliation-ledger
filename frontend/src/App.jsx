import { NavLink, Route, Routes } from 'react-router-dom'
import Dashboard from './pages/Dashboard.jsx'
import Statements from './pages/Statements.jsx'
import Exceptions from './pages/Exceptions.jsx'
import Ledger from './pages/Ledger.jsx'

export default function App() {
  return (
    <>
      <header className="top">
        <div className="top-inner">
          <div className="brand">Reconciliation ledger</div>
          <nav>
            <NavLink to="/" end>Overview</NavLink>
            <NavLink to="/statements">Bank statements</NavLink>
            <NavLink to="/exceptions">Exceptions</NavLink>
            <NavLink to="/ledger">Ledger</NavLink>
          </nav>
        </div>
      </header>
      <main className="page">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/statements" element={<Statements />} />
          <Route path="/exceptions" element={<Exceptions />} />
          <Route path="/ledger" element={<Ledger />} />
        </Routes>
      </main>
    </>
  )
}
