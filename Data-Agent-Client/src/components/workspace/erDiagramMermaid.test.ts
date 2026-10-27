import { describe, expect, it } from 'vitest';
import type { ErDiagramDocument } from './erDiagramDocument';
import { renderErDiagramMermaid } from './erDiagramMermaid';

function shown(): ErDiagramDocument {
  return {
    tables: [
      {
        name: 'film',
        catalog: null,
        schema: 'sakila',
        external: false,
        comment: '影片',
        columns: [
          { name: 'film_id', typeName: 'smallint', comment: '主键说明', primaryKey: true },
          { name: 'title', typeName: 'varchar(255)', comment: '标题说明', primaryKey: false },
          { name: 'updated_at', typeName: 'timestamp with time zone', comment: '', primaryKey: false },
          { name: 'price', typeName: 'decimal(10, 2)', comment: '', primaryKey: false },
        ],
      },
      {
        name: 'language',
        catalog: 'other',
        schema: 'dict',
        external: true,
        comment: '外部表注释',
        columns: [
          { name: 'language_id', typeName: 'tinyint', comment: '', primaryKey: true },
          { name: 'tenant_id', typeName: 'int', comment: '', primaryKey: true },
        ],
      },
      {
        name: 'film_text',
        catalog: null,
        schema: 'sakila',
        external: false,
        comment: '',
        columns: [
          { name: 'film_id', typeName: 'smallint', comment: '', primaryKey: true },
        ],
      },
      {
        name: 'actor',
        catalog: null,
        schema: 'sakila',
        external: false,
        comment: '',
        columns: [
          { name: 'actor_id', typeName: 'smallint', comment: '', primaryKey: true },
        ],
      },
      {
        name: 'user',
        catalog: null,
        schema: 'app',
        external: false,
        comment: '',
        columns: [],
      },
      {
        name: 'user',
        catalog: null,
        schema: 'auth',
        external: false,
        comment: '',
        columns: [
          { name: '说"明', typeName: 'text', comment: '不要出现', primaryKey: false },
        ],
      },
      {
        name: 'note',
        catalog: 'app',
        schema: null,
        external: false,
        comment: '',
        columns: [],
      },
    ],
    relations: [
      {
        name: 'fk_film_language',
        fromCatalog: null,
        fromSchema: 'sakila',
        fromTable: 'film',
        fromColumn: 'language_id',
        fromColumns: ['language_id', 'tenant_id'],
        toCatalog: 'other',
        toSchema: 'dict',
        toTable: 'language',
        toColumn: 'language_id',
        toColumns: ['language_id', 'tenant_id'],
        cardinality: 'ONE_TO_MANY',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
      {
        name: 'fk_one',
        fromCatalog: null,
        fromSchema: 'sakila',
        fromTable: 'film',
        fromColumn: 'film_id',
        fromColumns: ['film_id'],
        toCatalog: null,
        toSchema: 'sakila',
        toTable: 'film_text',
        toColumn: 'film_id',
        toColumns: ['film_id'],
        cardinality: 'ONE_TO_ONE',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
      {
        name: 'film_actor',
        fromCatalog: null,
        fromSchema: 'sakila',
        fromTable: 'film',
        fromColumn: 'film_id',
        fromColumns: ['film_id'],
        toCatalog: null,
        toSchema: 'sakila',
        toTable: 'actor',
        toColumn: 'actor_id',
        toColumns: ['actor_id'],
        cardinality: 'MANY_TO_MANY',
        viaCatalog: null,
        viaSchema: 'sakila',
        viaTable: 'film_actor',
      },
      {
        name: 'fk_missing',
        fromCatalog: null,
        fromSchema: 'sakila',
        fromTable: 'film',
        fromColumn: 'film_id',
        fromColumns: ['film_id'],
        toCatalog: null,
        toSchema: 'sakila',
        toTable: 'missing',
        toColumn: 'film_id',
        toColumns: ['film_id'],
        cardinality: 'ONE_TO_MANY',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
      {
        name: 'fk_user',
        fromCatalog: null,
        fromSchema: 'app',
        fromTable: 'user',
        fromColumn: 'user_id',
        fromColumns: ['user_id'],
        toCatalog: null,
        toSchema: 'auth',
        toTable: 'user',
        toColumn: 'id',
        toColumns: ['id'],
        cardinality: 'ONE_TO_MANY',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
    ],
    truncated: true,
  };
}

describe('ER diagram mermaid', () => {
  it('writes crow\'s foot relations before tables and leaves comments out', () => {
    const text = renderErDiagramMermaid(shown());

    expect(text.startsWith('erDiagram\n')).toBe(true);
    expect(text.endsWith('\n')).toBe(true);
    expect(text.includes('\r')).toBe(false);
    expect(text.startsWith('\uFEFF')).toBe(false);
    expect(text).toBe([
      'erDiagram',
      '    language ||--o{ film : "language_id, tenant_id"',
      '    film ||--|| film_text : film_id',
      '    film }o--o{ actor : "film_id → actor_id via sakila.film_actor"',
      '    "auth.user" ||--o{ "app.user" : "user_id → id"',
      '    film {',
      '        smallint film_id PK',
      '        varchar(255) title',
      '        timestamp_with_time_zone updated_at',
      '        decimal(10,2) price',
      '    }',
      '    language {',
      '        tinyint language_id PK',
      '        int tenant_id PK',
      '    }',
      '    film_text {',
      '        smallint film_id PK',
      '    }',
      '    actor {',
      '        smallint actor_id PK',
      '    }',
      '    "app.user" {',
      '    }',
      '    "auth.user" {',
      '        text "说#quot;明"',
      '    }',
      '    note {',
      '    }',
      '',
    ].join('\n'));
    expect(text).not.toContain('影片');
    expect(text).not.toContain('主键说明');
    expect(text).not.toContain('标题说明');
    expect(text).not.toContain('外部表注释');
    expect(text).not.toContain('不要出现');
    expect(text).not.toContain('fk_film_language');
    expect(text).not.toContain('fk_missing');
    expect(text).not.toContain('truncated');
  });

  it('escapes quotes and newlines inside a relationship label', () => {
    const document = shown();
    document.tables = [
      {
        name: 'a',
        catalog: null,
        schema: null,
        external: false,
        comment: '表注释',
        columns: [
          { name: 'say\n"hi', typeName: 'text', comment: '字段注释', primaryKey: true },
        ],
      },
      {
        name: 'b',
        catalog: null,
        schema: null,
        external: false,
        comment: '',
        columns: [],
      },
    ];
    document.relations = [
      {
        name: 'fk_quote',
        fromCatalog: null,
        fromSchema: null,
        fromTable: 'a',
        fromColumn: 'say\n"hi',
        fromColumns: ['say\n"hi'],
        toCatalog: null,
        toSchema: null,
        toTable: 'b',
        toColumn: 'say\n"hi',
        toColumns: ['say\n"hi'],
        cardinality: 'ONE_TO_ONE',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
    ];

    const text = renderErDiagramMermaid(document);
    expect(text).toContain('    a ||--|| b : "say #quot;hi"');
    expect(text).toContain('        text "say #quot;hi" PK');
    expect(text).not.toContain('表注释');
    expect(text).not.toContain('字段注释');
    expect(text).not.toContain('fk_quote');
  });

  it('emits an empty diagram as a single erDiagram line', () => {
    const document = shown();
    document.tables = [];
    document.relations = [];
    expect(renderErDiagramMermaid(document)).toBe('erDiagram\n');
  });
});
