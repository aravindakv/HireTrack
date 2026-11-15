import { createContext, useContext, useEffect, useState } from 'react';
import { api, onSessionChange, refreshSession, setSession } from '../api/client.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    // Restore the session from the refresh cookie on page load.
    refreshSession().then((s) => setUser(s.user)).catch(() => setUser(null)).finally(() => setReady(true));
    return onSessionChange((token) => { if (!token) setUser(null); });
  }, []);

  async function login(email, password) {
    const { data } = await api('/auth/login', { method: 'POST', body: { email, password }, auth: false });
    setSession(data);
    setUser(data.user);
  }

  async function signup(name, email, password) {
    const { data } = await api('/auth/signup', { method: 'POST', body: { name, email, password }, auth: false });
    setSession(data);
    setUser(data.user);
  }

  async function logout() {
    try { await api('/auth/logout', { method: 'POST', auth: false }); } finally {
      setSession(null);
      setUser(null);
    }
  }

  return <AuthContext.Provider value={{ user, ready, login, signup, logout }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);