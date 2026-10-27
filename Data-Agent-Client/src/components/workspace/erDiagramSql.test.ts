import { describe, expect, it } from 'vitest';
import type { ErDiagramDocument, ErDiagramDocumentRelation } from './erDiagramDocument';
import {
  erDiagramSqlIncomplete,
  erDiagramSqlSettings,
  renderErDiagramSql,
  type ErDiagramSqlNotes,
} from './erDiagramSql';

const notes: ErDiagramSqlNotes = {
  incomplete: '这不是完整库结构',
  omittedColumn: (table, column) => `${table}.${column} 缺少类型，已省略`,
  omittedTable: (table) => `${table} 没有可用的列类型，已省略`,
  omittedForeignKey: (name) => `外键 ${name} 已省略`,
};

function relation(overrides: Partial<ErDiagramDocumentRelation>): ErDiagramDocumentRelation {
  return {
    name: 'fk',
    fromCatalog: null,
    fromSchema: null,
    fromTable: 'film',
    fromColumn: 'id',
    fromColumns: ['id'],
    toCatalog: null,
    toSchema: null,
    toTable: 'language',
    toColumn: 'id',
    toColumns: ['id'],
    cardinality: 'ONE_TO_MANY',
    viaCatalog: null,
    viaSchema: null,
    viaTable: null,
    ...overrides,
  };
}

function mysqlDocument(): ErDiagramDocument {
  return {
    tables: [
      {
        name: 'film',
        catalog: 'sakila',
        schema: null,
        external: true,
        comment: "影片'说明",
        columns: [
          { name: 'film_id', typeName: 'smallint', comment: '', primaryKey: true },
          { name: 'language_id', typeName: 'smallint', comment: '', primaryKey: false },
          { name: 'tenant_id', typeName: 'int', comment: '', primaryKey: false },
          { name: 'title', typeName: 'varchar(255)', comment: '标题\\a', primaryKey: false },
          { name: 'price', typeName: 'decimal(10,2)', comment: '', primaryKey: false },
          { name: 'stub', typeName: '', comment: '不应出现', primaryKey: true },
        ],
      },
      {
        name: 'language',
        catalog: 'other',
        schema: 'dict',
        external: false,
        comment: '',
        columns: [
          { name: 'language_id', typeName: 'tinyint', comment: '', primaryKey: true },
          { name: 'tenant_id', typeName: 'int', comment: '', primaryKey: true },
        ],
      },
      {
        name: 'ghost',
        catalog: null,
        schema: null,
        external: false,
        comment: '无',
        columns: [
          { name: 'id', typeName: ' ', comment: '', primaryKey: true },
        ],
      },
    ],
    relations: [
      relation({
        name: 'fk_film_language',
        fromCatalog: 'sakila',
        fromTable: 'film',
        fromColumn: 'language_id',
        fromColumns: ['language_id', 'tenant_id'],
        toCatalog: 'other',
        toSchema: 'dict',
        toTable: 'language',
        toColumn: 'language_id',
        toColumns: ['language_id', 'tenant_id'],
      }),
      relation({
        name: 'film_actor_link',
        cardinality: 'MANY_TO_MANY',
        fromCatalog: 'sakila',
        fromTable: 'film',
        fromColumns: ['film_id'],
        toCatalog: 'other',
        toSchema: 'dict',
        toTable: 'language',
        toColumns: ['language_id'],
        viaSchema: 'sakila',
        viaTable: 'film_actor',
      }),
      relation({
        name: 'fk_stub',
        fromCatalog: 'sakila',
        fromTable: 'film',
        fromColumn: 'stub',
        fromColumns: ['stub'],
        toCatalog: 'other',
        toSchema: 'dict',
        toTable: 'language',
        toColumns: ['language_id'],
      }),
      relation({
        name: ' ',
        fromCatalog: 'sakila',
        fromTable: 'film',
        toCatalog: 'other',
        toSchema: 'dict',
        toTable: 'language',
      }),
    ],
    truncated: true,
  };
}

describe('ER diagram SQL', () => {
  it('writes MySQL tables and real foreign keys without inventing nullability', () => {
    const sql = renderErDiagramSql(mysqlDocument(), {
      quote: 'backtick',
      dialect: 'mysql',
      incomplete: true,
      notes,
    });

    expect(sql.startsWith('\uFEFF')).toBe(false);
    expect(sql.includes('\r')).toBe(false);
    expect(sql).toBe([
      '-- 这不是完整库结构',
      '',
      '-- sakila.film.stub 缺少类型，已省略',
      'CREATE TABLE `sakila`.`film` (',
      '  `film_id` smallint,',
      '  `language_id` smallint,',
      '  `tenant_id` int,',
      '  `title` varchar(255) COMMENT \'标题\\\\a\',',
      '  `price` decimal(10,2)',
      ') COMMENT=\'影片\'\'说明\';',
      '',
      'CREATE TABLE `dict`.`language` (',
      '  `language_id` tinyint,',
      '  `tenant_id` int,',
      '  PRIMARY KEY (`language_id`, `tenant_id`)',
      ');',
      '',
      '-- ghost 没有可用的列类型，已省略',
      '-- ghost.id 缺少类型，已省略',
      '',
      'ALTER TABLE `sakila`.`film` ADD CONSTRAINT `fk_film_language` FOREIGN KEY (`language_id`, `tenant_id`) REFERENCES `dict`.`language` (`language_id`, `tenant_id`);',
      '',
      '-- 外键 fk_stub 已省略',
      '',
      '-- 外键 film -> language 已省略',
      '',
    ].join('\n'));
    expect(sql).not.toContain('film_actor_link');
    expect(sql).not.toContain('不应出现');
    expect(sql).not.toMatch(/\bNULL\b/);
    expect(sql).not.toMatch(/\bDEFAULT\b/);
    expect(sql).not.toContain('AUTO_INCREMENT');
    expect(sql).not.toContain('UNSIGNED');
    expect(sql).not.toContain('PRIMARY KEY (`film_id`)');
  });

  it('writes DM comments after the table and prefers schema over catalog', () => {
    const sql = renderErDiagramSql({
      tables: [
        {
          name: 'A"B',
          catalog: 'db',
          schema: 'app',
          external: false,
          comment: '影片',
          columns: [
            { name: 'id', typeName: 'int', comment: "it's", primaryKey: true },
          ],
        },
      ],
      relations: [],
      truncated: false,
    }, {
      quote: 'double_quote',
      dialect: 'comment_on',
      incomplete: false,
      notes,
    });

    expect(sql).toBe([
      'CREATE TABLE "app"."A""B" (',
      '  "id" int,',
      '  PRIMARY KEY ("id")',
      ');',
      'COMMENT ON TABLE "app"."A""B" IS \'影片\';',
      'COMMENT ON COLUMN "app"."A""B"."id" IS \'it\'\'s\';',
      '',
    ].join('\n'));
    expect(sql).not.toContain('这不是完整库结构');
    expect(sql).not.toContain('"db"');
  });

  it('marks a diagram incomplete when it is truncated or contains an external table', () => {
    expect(erDiagramSqlIncomplete({ truncated: true, tables: [] })).toBe(true);
    expect(erDiagramSqlIncomplete({ truncated: false, externalTruncated: true, tables: [] })).toBe(true);
    expect(erDiagramSqlIncomplete({ truncated: false, tables: [{ external: true }] })).toBe(true);
    expect(erDiagramSqlIncomplete({ truncated: false, tables: [{ external: false }] })).toBe(false);
  });

  it('uses backticks for MySQL and double quotes when the connection type is missing', () => {
    expect(erDiagramSqlSettings('mysql', 'backtick')).toEqual({ quote: 'backtick', dialect: 'mysql' });
    expect(erDiagramSqlSettings('mysql', undefined)).toEqual({ quote: 'backtick', dialect: 'mysql' });
    expect(erDiagramSqlSettings('dm', undefined)).toEqual({ quote: 'double_quote', dialect: 'comment_on' });
    expect(erDiagramSqlSettings(undefined, undefined)).toEqual({ quote: 'double_quote', dialect: 'comment_on' });
  });
});
