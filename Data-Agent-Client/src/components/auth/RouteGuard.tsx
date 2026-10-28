import { useEffect } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useRuntimeStore } from '../../store/runtimeStore';
import { Navigate } from 'react-router-dom';

interface RouteGuardProps {
    children: React.ReactNode;
}

export function RouteGuard({ children }: RouteGuardProps) {
    const { user, openLoginModal } = useAuthStore();
    const local = useRuntimeStore((s) => s.mode === 'LOCAL');

    useEffect(() => {
        if (!user && !local) {
            openLoginModal();
        }
    }, [user, openLoginModal, local]);

    if (local) return <Navigate to="/" replace />;

    if (user) {
        return <>{children}</>;
    }

    return null;
}
