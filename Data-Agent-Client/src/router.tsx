import { lazy, Suspense } from 'react';
import { Navigate, type RouteObject } from 'react-router-dom';

const Home = lazy(() => import('./pages/Home'));
const ModelSettings = lazy(() => import('./pages/ModelSettings'));

/** Local-only routes. Old account, organization, archived-chat and permission pages have no entry. */
export const routerConfig: RouteObject[] = [
    { path: '/', element: <Suspense fallback={null}><Home /></Suspense> },
    { path: '/local/models', element: <Suspense fallback={null}><ModelSettings /></Suspense> },
    { path: '*', element: <Navigate to="/" replace /> },
];
