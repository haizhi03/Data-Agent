import { describe, expect, it } from 'vitest';
import type { ErDiagram } from '../../services/table.service';
import { renderErDiagramJson, toErDiagramDocument } from './erDiagramDocument';

function shownDiagram(): ErDiagram {
  return {
    tables: [
      {
        name: 'film',
        schema: 'sakila',
        comment: 'films',
        columns: [
          { name: 'film_id', typeName: 'smallint', comment: '', primaryKey: true },
          { name: 'title', typeName: 'varchar', comment: 'title', primaryKey: false },
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
    ],
    relations: [
      {
        name: 'fk_film_language',
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
      },
      {
        name: 'film_actor',
        fromTable: 'film',
        fromColumn: 'film_id',
        fromColumns: ['film_id'],
        toTable: 'actor',
        toColumn: 'actor_id',
        toColumns: ['actor_id'],
        cardinality: 'MANY_TO_MANY',
        viaSchema: 'sakila',
        viaTable: 'film_actor',
      },
    ],
    truncated: true,
    externalTruncated: true,
  };
}

describe('ER diagram document', () => {
  it('copies the shown tables, four column fields, relations, and truncated', () => {
    const document = toErDiagramDocument(shownDiagram());

    expect(Object.keys(document)).toEqual(['tables', 'relations', 'truncated']);
    expect(document.truncated).toBe(true);
    expect(document).not.toHaveProperty('externalTruncated');
    expect(Object.keys(document.tables[0])).toEqual(['name', 'catalog', 'schema', 'external', 'comment', 'columns']);
    expect(document.tables[0]).toMatchObject({
      name: 'film',
      catalog: null,
      schema: 'sakila',
      external: false,
      comment: 'films',
    });
    expect(Object.keys(document.tables[0].columns[0])).toEqual(['name', 'typeName', 'comment', 'primaryKey']);
    expect(document.tables[0].columns).toEqual([
      { name: 'film_id', typeName: 'smallint', comment: '', primaryKey: true },
      { name: 'title', typeName: 'varchar', comment: 'title', primaryKey: false },
    ]);
    expect(document.tables[1]).toMatchObject({
      name: 'language',
      catalog: 'other',
      schema: 'dict',
      external: true,
    });
    expect(Object.keys(document.relations[0])).toEqual([
      'name', 'fromCatalog', 'fromSchema', 'fromTable', 'fromColumn', 'fromColumns',
      'toCatalog', 'toSchema', 'toTable', 'toColumn', 'toColumns',
      'cardinality', 'viaCatalog', 'viaSchema', 'viaTable',
    ]);
    expect(document.relations[0]).toMatchObject({
      fromColumns: ['language_id', 'tenant_id'],
      toColumns: ['language_id', 'tenant_id'],
      cardinality: 'ONE_TO_MANY',
      viaTable: null,
    });
    expect(document.relations[1]).toMatchObject({
      cardinality: 'MANY_TO_MANY',
      viaSchema: 'sakila',
      viaTable: 'film_actor',
    });
  });

  it('serializes that document as indented json', () => {
    const document = toErDiagramDocument(shownDiagram());
    expect(renderErDiagramJson(document)).toBe(JSON.stringify(document, null, 2));
    expect(renderErDiagramJson(document)).toContain('"truncated": true');
    expect(renderErDiagramJson(document)).not.toContain('externalTruncated');
  });

  it('copies column lists so later edits to the source stay out of the document', () => {
    const shown = shownDiagram();
    const document = toErDiagramDocument(shown);
    shown.relations[0].fromColumns.push('extra');
    expect(document.relations[0].fromColumns).toEqual(['language_id', 'tenant_id']);
  });
});
