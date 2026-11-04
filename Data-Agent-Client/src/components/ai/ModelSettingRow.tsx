import type { ReactNode } from 'react';

export const modelSettingColumns = 'grid gap-2 md:grid-cols-[200px_minmax(0,1fr)] md:gap-8';

export function ModelSettingRow({ title, htmlFor, children }: { title: string; htmlFor?: string; children: ReactNode }) {
  return <div className={`${modelSettingColumns} md:items-start`}>
    {htmlFor ? <label htmlFor={htmlFor} className="text-sm font-medium md:pt-3">{title}</label>
      : <span className="text-sm font-medium md:pt-3">{title}</span>}
    <div className="min-w-0 space-y-2">{children}</div>
  </div>;
}
