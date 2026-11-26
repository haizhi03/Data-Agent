export interface StructureTypeRules {
  engine: boolean;
  charset: boolean;
  collation: boolean;
  unsigned: boolean;
  lengthTypes: string[];
  scaleTypes: string[];
  autoIncrementTypes: string[];
  numericTypes: string[];
}

export function typeAllows(types: string[] | undefined, typeName: string | null | undefined): boolean {
  const normalized = (typeName ?? '').toLowerCase().replace(/\(.*$/, '').trim();
  if (!normalized || !types?.length) return false;
  return types.some((type) => {
    if (normalized === type) return true;
    if (!normalized.startsWith(type)) return false;
    const next = normalized.charAt(type.length);
    return next !== '' && !/[a-z]/.test(next);
  });
}
