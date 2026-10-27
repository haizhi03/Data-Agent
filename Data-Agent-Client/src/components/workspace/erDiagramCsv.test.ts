import { describe, expect, it } from 'vitest';
import type { ErDiagramDocument } from './erDiagramDocument';
import { renderErDiagramCsv, type ErDiagramCsvHeaders } from './erDiagramCsv';

const headers: ErDiagramCsvHeaders = {
  tables: {
    schema: 'schema',
    table: '表名',
    tableComment: '表注释',
    column: '字段名',
    type: '类型',
    primaryKey: '是否主键',
    columnComment: '字段注释',
    external: '是否外部表',
  },
  relations: {
    name: '约束名',
    fromSchema: '来源 schema',
    fromTable: '来源表',
    fromColumns: '来源字段',
    toSchema: '目标 schema',
    toTable: '目标表',
    toColumns: '目标字段',
    cardinality: '基数',
    viaTable: '关联表',
    crossSchema: '跨 schema',
  },
};

function document(): ErDiagramDocument {
  return {
    tables: [
      {
        name: 'film',
        catalog: null,
        schema: 'sakila',
        external: false,
        comment: 'films',
        columns: [
          { name: 'film_id', typeName: 'smallint', comment: '', primaryKey: true },
          { name: 'title', typeName: 'varchar', comment: '标题,说明', primaryKey: false },
        ],
      },
      {
        name: 'language',
        catalog: 'other',
        schema: 'dict',
        external: true,
        comment: '',
        columns: [
          { name: 'language_id', typeName: 'tinyint', comment: '', primaryKey: true },
        ],
      },
      {
        name: 'note',
        catalog: 'app',
        schema: null,
        external: false,
        comment: 'line1\n"quoted"',
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
        viaTable: 'ignored',
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
        name: 'fk_fallback',
        fromCatalog: 'left',
        fromSchema: null,
        fromTable: 'a',
        fromColumn: 'a_id',
        fromColumns: [],
        toCatalog: null,
        toSchema: null,
        toTable: 'b',
        toColumn: 'b_id',
        toColumns: [],
        cardinality: 'ONE_TO_MANY',
        viaCatalog: null,
        viaSchema: null,
        viaTable: null,
      },
    ],
    truncated: true,
  };
}

describe('ER diagram CSV', () => {
  it('writes one quoted row per column, repeating table fields, with a BOM', () => {
    const csv = renderErDiagramCsv(document(), headers);

    expect(csv.tables.startsWith('\uFEFF')).toBe(true);
    expect(csv.tables).toBe([
      '\uFEFFschema,表名,表注释,字段名,类型,是否主键,字段注释,是否外部表',
      'sakila,film,films,film_id,smallint,true,,false',
      'sakila,film,films,title,varchar,false,"标题,说明",false',
      'dict,language,,language_id,tinyint,true,,true',
      'app,note,"line1\n""quoted""",,,,,false',
      '',
    ].join('\r\n'));
    expect(csv.tables).not.toContain('truncated');
  });

  it('writes one row per relation, with directional cardinality and a qualified via table', () => {
    const csv = renderErDiagramCsv(document(), headers);

    expect(csv.relations).toBe([
      '\uFEFF约束名,来源 schema,来源表,来源字段,目标 schema,目标表,目标字段,基数,关联表,跨 schema',
      'fk_film_language,sakila,film,"language_id, tenant_id",dict,language,"language_id, tenant_id",N:1,,true',
      'fk_one,sakila,film,film_id,sakila,film_text,film_id,1:1,,false',
      'film_actor,sakila,film,film_id,sakila,actor,actor_id,N:M,sakila.film_actor,false',
      'fk_fallback,left,a,a_id,,b,b_id,N:1,,true',
      '',
    ].join('\r\n'));
  });

  it('keeps a header when the shown diagram has no relations', () => {
    const empty = document();
    empty.relations = [];
    empty.tables = [];
    const csv = renderErDiagramCsv(empty, headers);

    expect(csv.tables).toBe('\uFEFFschema,表名,表注释,字段名,类型,是否主键,字段注释,是否外部表\r\n');
    expect(csv.relations).toBe('\uFEFF约束名,来源 schema,来源表,来源字段,目标 schema,目标表,目标字段,基数,关联表,跨 schema\r\n');
  });
});
