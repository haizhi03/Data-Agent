import * as React from 'react'
import * as ReactDOM from 'react-dom/client'
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-quartz.css'
import App from './App.tsx'
import './index.css'
import './i18n'
import { initializeRuntime } from './lib/localRuntime'

ModuleRegistry.registerModules([AllCommunityModule])

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            refetchOnWindowFocus: false,
            retry: 1,
        },
    },
})

void initializeRuntime().then(() => ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <QueryClientProvider client={queryClient}>
            <App />
        </QueryClientProvider>
    </React.StrictMode>,
))
