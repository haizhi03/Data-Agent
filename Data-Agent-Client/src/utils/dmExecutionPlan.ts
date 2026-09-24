export function formatDmExecutionPlan(plan: string): string {
  return plan.replace(/[ \t]+(?=\d+[ \t]+#[A-Z][A-Z0-9_]*[ \t]*:)/gi, '\n');
}
