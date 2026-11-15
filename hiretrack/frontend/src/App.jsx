import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext.jsx';
import AuthPage from './pages/AuthPage.jsx';
import ResumePage from './pages/ResumePage.jsx';
import MatchPage from './pages/MatchPage.jsx';
import BoardPage from './pages/BoardPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import JobFeedPage from './pages/JobFeedPage.jsx';

function Protected({ children }) {
  const { user, ready } = useAuth();
  if (!ready) return <p className="muted pad">Loading…</p>;
  return user ? children : <Navigate to="/login" replace />;
}

export default function App() {
  const { user, logout } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <strong className="brand">HireTrack AI</strong>
        {user && (
          <nav>
            <NavLink to="/dashboard">Dashboard</NavLink>
            <NavLink to="/board">Pipeline</NavLink>
            <NavLink to="/match">Match</NavLink>
            <NavLink to="/jobs">Job Feed</NavLink>
            <NavLink to="/resume">Resume</NavLink>
            <button className="link" onClick={logout}>Log out</button>
          </nav>
        )}
      </header>
      <main className="content">
        <Routes>
          <Route path="/login" element={<AuthPage mode="login" />} />
          <Route path="/signup" element={<AuthPage mode="signup" />} />
          <Route path="/dashboard" element={<Protected><DashboardPage /></Protected>} />
          <Route path="/board" element={<Protected><BoardPage /></Protected>} />
          <Route path="/match" element={<Protected><MatchPage /></Protected>} />
          <Route path="/jobs" element={<Protected><JobFeedPage /></Protected>} />
          <Route path="/resume" element={<Protected><ResumePage /></Protected>} />
          <Route path="*" element={<Navigate to={user ? '/dashboard' : '/login'} replace />} />
        </Routes>
      </main>
    </div>
  );
}