import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HashRouter } from 'react-router-dom';
import './index.css';
import { App } from './App';
import { shouldRetryApiQuery } from './api/client';

const container = document.getElementById('root');
if (!container) {
  throw new Error('root 요소를 찾을 수 없습니다.');
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryApiQuery,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
});

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <App />
      </HashRouter>
    </QueryClientProvider>
  </StrictMode>,
);
