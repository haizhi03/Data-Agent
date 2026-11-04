import { describe, expect, it } from 'vitest';
import { resolveProfile } from './dialect';
import { generateObjectScript } from './scripts';

const mysql = resolveProfile('mysql');
const dm = resolveProfile('dm');

describe('generateObjectScript', () => {
  it('renders SELECT * for a qualified table', () => {
    expect(
      generateObjectScript(
        { kind: 'SELECT', table: { schema: 'app', name: 'users' } },
        mysql,
      ),
    ).toBe('SELECT * FROM `app`.`users`;');
  });

  it('renders parameterized INSERT with columns', () => {
    expect(
      generateObjectScript(
        { kind: 'INSERT', table: { schema: 'app', name: 'users' }, columns: ['id', 'name'] },
        mysql,
      ),
    ).toBe('INSERT INTO `app`.`users` (`id`, `name`) VALUES (?, ?);');
  });

  it('renders UPDATE skeleton', () => {
    const sql = generateObjectScript(
      { kind: 'UPDATE', table: { name: 'users' }, columns: ['name'] },
      dm,
    );
    expect(sql).toContain('UPDATE "users"');
    expect(sql).toContain('"name" = ?');
    expect(sql).toContain('WHERE');
  });

  it('renders DELETE skeleton', () => {
    const sql = generateObjectScript({ kind: 'DELETE', table: { name: 'users' } }, mysql);
    expect(sql).toContain('DELETE FROM `users`');
    expect(sql).toContain('WHERE');
  });

  it('renders DROP TABLE and DROP VIEW by object type', () => {
    expect(generateObjectScript({ kind: 'DROP', table: { name: 'users' } }, mysql)).toBe(
      'DROP TABLE `users`;',
    );
    expect(
      generateObjectScript({ kind: 'DROP', table: { name: 'v' }, objectType: 'view' }, mysql),
    ).toBe('DROP VIEW `v`;');
  });
});
