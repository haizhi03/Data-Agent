import { useState } from "react";
import { Button } from "../ui/Button";
import { Input } from "../ui/Input";
import { DialogContent, DialogDescription, DialogHeader, DialogTitle } from "../ui/Dialog";
import { Eye, EyeOff } from "lucide-react";
import { authService } from "../../services/auth.service";
import { Alert } from "../ui/Alert";
import { useAuthStore } from "../../store/authStore";
import { resolveErrorMessage } from "../../lib/errorMessage";
import { useTranslation } from "react-i18next";
import { I18N_KEYS } from "../../constants/i18nKeys";

interface LoginModalProps {
    onSwitchToRegister: () => void;
    onClose: () => void;
}

export function LoginModal({ onSwitchToRegister, onClose }: LoginModalProps) {
    const { t } = useTranslation();
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [rememberMe, setRememberMe] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const { setAuth } = useAuthStore();

    const handleLogin = async (e?: React.FormEvent) => {
        e?.preventDefault();
        try {
            setError(null);
            setLoading(true);
            const response = await authService.login({ email, password, rememberMe });
            setAuth(null, response.accessToken, response.refreshToken, rememberMe);
            const fullUser = await authService.getCurrentUser();
            setAuth(fullUser, response.accessToken, response.refreshToken, rememberMe);
            useAuthStore.getState().reconcileWorkspaceWithUser();
            onClose(); // Close modal after successful login
        } catch (error) {
            console.error("Login failed", error);
            setError(resolveErrorMessage(error, t(I18N_KEYS.AUTH.LOGIN_FAILED)));
        } finally {
            setLoading(false);
        }
    };

    return (
        <DialogContent className="sm:max-w-[425px]">
            <DialogHeader>
                <DialogTitle className="text-2xl text-center">{t(I18N_KEYS.AUTH.LOGIN)}</DialogTitle>
                <DialogDescription className="text-center">
                    {t(I18N_KEYS.AUTH.LOGIN_DESC)}
                </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleLogin} className="grid gap-4 py-4">
                {error && (
                    <Alert variant="destructive">
                        {error}
                    </Alert>
                )}
                <div className="grid gap-2">
                    <label htmlFor="email" className="text-sm font-medium text-foreground">{t(I18N_KEYS.AUTH.EMAIL)}</label>
                    <Input
                        id="email"
                        type="email"
                        placeholder={t(I18N_KEYS.AUTH.EMAIL_PLACEHOLDER)}
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        autoComplete="email"
                    />
                </div>
                <div className="grid gap-2">
                    <label htmlFor="password" className="text-sm font-medium text-foreground">{t(I18N_KEYS.AUTH.PASSWORD)}</label>
                    <div className="relative">
                        <Input
                            id="password"
                            type={showPassword ? "text" : "password"}
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            autoComplete="current-password"
                            className="pr-10"
                        />
                        <button
                            type="button"
                            onClick={() => setShowPassword(!showPassword)}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
                            aria-label={showPassword ? t(I18N_KEYS.AUTH.HIDE_PASSWORD) : t(I18N_KEYS.AUTH.SHOW_PASSWORD)}
                        >
                            {showPassword ? (
                                <EyeOff className="h-4 w-4" />
                            ) : (
                                <Eye className="h-4 w-4" />
                            )}
                        </button>
                    </div>
                </div>
                <div className="flex items-center space-x-2">
                    <input
                        type="checkbox"
                        id="remember"
                        className="h-4 w-4 rounded border border-input bg-background text-primary accent-primary focus:ring-ring"
                        checked={rememberMe}
                        onChange={(e) => setRememberMe(e.target.checked)}
                    />
                    <label
                        htmlFor="remember"
                        className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70 text-foreground"
                    >
                        {t(I18N_KEYS.AUTH.REMEMBER_ME)}
                    </label>
                </div>
                <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? t(I18N_KEYS.AUTH.SIGNING_IN) : t(I18N_KEYS.AUTH.SIGN_IN)}
                </Button>
            </form>
            <div className="text-sm text-center text-muted-foreground">
                {t(I18N_KEYS.AUTH.NO_ACCOUNT)}{" "}
                <button className="text-primary hover:underline" onClick={onSwitchToRegister}>
                    {t(I18N_KEYS.AUTH.SIGN_UP)}
                </button>
            </div>
        </DialogContent>
    );
}
