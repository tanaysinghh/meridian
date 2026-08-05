import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import './styles/globals.css';

import Landing from './pages/Landing.jsx';
import Login from './pages/Login.jsx';
import AppShell from './pages/AppShell.jsx';
import Overview from './pages/Overview.jsx';
import PRs from './pages/PRs.jsx';
import PRDetail from './pages/PRDetail.jsx';
import Reviewers from './pages/Reviewers.jsx';
import Analytics from './pages/Analytics.jsx';
import Repositories from './pages/Repositories.jsx';
import Incidents from './pages/Incidents.jsx';
import Settings from './pages/Settings.jsx';
import { AuthProvider, RequireAuth } from './lib/auth.jsx';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/login" element={<Login />} />
          <Route path="/app" element={<RequireAuth><AppShell /></RequireAuth>}>
            <Route index element={<Overview />} />
            <Route path="prs" element={<PRs />} />
            <Route path="prs/:id" element={<PRDetail />} />
            <Route path="reviewers" element={<Reviewers />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="repositories" element={<Repositories />} />
            <Route path="incidents" element={<Incidents />} />
            <Route path="settings" element={<Settings />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>
);
