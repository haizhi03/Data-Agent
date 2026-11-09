import { describe, expect, it } from 'vitest';
import {
  buildDmCommentOnTableStatement,
  buildMysqlTableCommentClause,
  escapeSqlStringLiteral,
  quoteCreateTableIdentifier,
} from './sqlComment';

describe('quoteCreateTableIdentifier', () => {
  it('uses backticks for MySQL and double quotes otherwise', () => {
    expect(quoteCreateTableIdentifier('users', 'mysql')).toBe('`users`');
    expect(quoteCreateTableIdentifier('users', 'dm')).toBe('"users"');
    expect(quoteCreateTableIdentifier('users')).toBe('"users"');
  });

  it('escapes embedded double quotes for the ANSI form', () => {
    expect(quoteCreateTableIdentifier('we"ird', 'dm')).toBe('"we""ird"');
  });
});

describe('escapeSqlStringLiteral', () => {
  it('doubles quotes and backslashes for MySQL', () => {
    expect(escapeSqlStringLiteral("Owner's \\ note", 'mysql')).toBe("Owner''s \\\\ note");
  });

  it('only doubles quotes for DM', () => {
    expect(escapeSqlStringLiteral("Owner's \\ note", 'dm')).toBe("Owner''s \\ note");
  });
});

describe('buildMysqlTableCommentClause', () => {
  it('builds an inline clause for a non-blank comment', () => {
    expect(buildMysqlTableCommentClause("Owner's note", 'mysql')).toBe(" COMMENT='Owner''s note'");
  });

  it('returns an empty clause when the comment is blank', () => {
    expect(buildMysqlTableCommentClause('   ', 'mysql')).toBe('');
    expect(buildMysqlTableCommentClause('', 'mysql')).toBe('');
  });

  it('returns an empty clause for non-MySQL dialects', () => {
    expect(buildMysqlTableCommentClause('note', 'dm')).toBe('');
  });
});

describe('buildDmCommentOnTableStatement', () => {
  it('qualifies the name with the schema and escapes the literal', () => {
    expect(buildDmCommentOnTableStatement('HR', 'users', "Owner's note", 'dm'))
      .toBe(`COMMENT ON TABLE "HR"."users" IS 'Owner''s note'`);
  });

  it('omits the schema when it is missing or blank', () => {
    expect(buildDmCommentOnTableStatement(undefined, 'users', 'note', 'dm'))
      .toBe(`COMMENT ON TABLE "users" IS 'note'`);
    expect(buildDmCommentOnTableStatement('  ', 'users', 'note', 'dm'))
      .toBe(`COMMENT ON TABLE "users" IS 'note'`);
  });
});
