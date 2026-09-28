import { useMemo, useState } from 'react';
import { Database } from 'lucide-react';

interface DatabaseTypeIconProps {
  dbType?: string;
  className?: string;
  fallbackClassName?: string;
}

interface DbIconRule {
  matchers: string[];
  iconUrl: string;
}

// Dameng mark: navy oval with white / red / white waves. Inline so the menu
// does not depend on an external icon CDN. Load-error still falls back.
const DM_ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 44">
  <defs>
    <clipPath id="dm-oval">
      <ellipse cx="32" cy="22" rx="30" ry="20"/>
    </clipPath>
  </defs>
  <ellipse cx="32" cy="22" rx="30" ry="20" fill="#0B3A8C"/>
  <g clip-path="url(#dm-oval)">
    <path fill="#FFFFFF" d="M-2 14C10 4 22 24 34 14S54 4 66 14v8C54 12 42 32 34 22S10 12-2 22z"/>
    <path fill="#E10600" d="M-2 22C10 12 22 32 34 22S54 12 66 22v9C54 21 42 41 34 31S10 21-2 31z"/>
    <path fill="#FFFFFF" d="M-2 31C10 21 22 41 34 31S54 21 66 31v9C54 30 42 50 34 40S10 30-2 40z"/>
  </g>
</svg>`;
const DM_ICON_URL = `data:image/svg+xml,${encodeURIComponent(DM_ICON_SVG)}`;

const DB_ICON_RULES: DbIconRule[] = [
  { matchers: ['dameng', 'dm'], iconUrl: DM_ICON_URL },
  { matchers: ['mysql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mysql/mysql-original.svg' },
  { matchers: ['mariadb'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mariadb/mariadb-original.svg' },
  { matchers: ['postgres', 'postgresql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/postgresql/postgresql-original.svg' },
  { matchers: ['sqlserver', 'mssql'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/microsoftsqlserver/microsoftsqlserver-plain.svg' },
  { matchers: ['oracle'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/oracle/oracle-original.svg' },
  { matchers: ['sqlite'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/sqlite/sqlite-original.svg' },
  { matchers: ['clickhouse'], iconUrl: 'https://cdn.simpleicons.org/clickhouse/FFCC01' },
  { matchers: ['tidb'], iconUrl: 'https://cdn.simpleicons.org/tidb/EA4E20' },
  { matchers: ['db2'], iconUrl: 'https://cdn.simpleicons.org/ibmdb2/052FAD' },
  { matchers: ['mongodb', 'mongo'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/mongodb/mongodb-original.svg' },
  { matchers: ['redis'], iconUrl: 'https://cdn.jsdelivr.net/gh/devicons/devicon/icons/redis/redis-original.svg' },
];

export function DatabaseTypeIcon({
  dbType,
  className = 'w-4 h-4',
  fallbackClassName = 'text-blue-400',
}: DatabaseTypeIconProps) {
  const [failed, setFailed] = useState(false);

  const iconSrc = useMemo(() => {
    const normalized = (dbType || '').toLowerCase();
    if (!normalized) return '';

    const matchedRule = DB_ICON_RULES.find((rule) =>
      rule.matchers.some((matcher) => normalized.includes(matcher))
    );
    return matchedRule?.iconUrl ?? '';
  }, [dbType]);

  if (!iconSrc || failed) {
    return <Database className={`${className} ${fallbackClassName}`.trim()} />;
  }

  return (
    <img
      src={iconSrc}
      alt={dbType ? `${dbType} icon` : 'database icon'}
      className={`${className} object-contain`}
      onError={() => setFailed(true)}
      loading="lazy"
    />
  );
}
