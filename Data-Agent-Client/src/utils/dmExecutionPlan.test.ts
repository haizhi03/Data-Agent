import { describe, expect, it } from 'vitest';
import { formatDmExecutionPlan } from './dmExecutionPlan';

describe('formatDmExecutionPlan', () => {
  it('puts numbered plan nodes on separate lines without changing their content', () => {
    const plan = '1 #NSET2: [1, 1096, 397] 2 #PRJT2: [1, 1096, 397]; exp_num(17), is_atom(FALSE) 3 #CSCN2: [1, 1096, 397]; SYSINDEXSYSOBJECTS(SYSOBJECTS as SYSOBJECTS); btr_scan(1)';

    expect(formatDmExecutionPlan(plan)).toBe(
      '1 #NSET2: [1, 1096, 397]\n2 #PRJT2: [1, 1096, 397]; exp_num(17), is_atom(FALSE)\n3 #CSCN2: [1, 1096, 397]; SYSINDEXSYSOBJECTS(SYSOBJECTS as SYSOBJECTS); btr_scan(1)'
    );
  });

  it('preserves driver-provided line breaks and plans without numbered nodes', () => {
    expect(formatDmExecutionPlan('1 #NSET2: [1]\n2 #PRJT2: [1]')).toBe('1 #NSET2: [1]\n2 #PRJT2: [1]');
    expect(formatDmExecutionPlan('custom plan; exp_num(17)')).toBe('custom plan; exp_num(17)');
  });
});
