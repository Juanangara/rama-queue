import { describe, expect, it } from 'vitest';
import type { CuboDePeticiones, Nodo } from './api';
import { construirArbol, contarPorEstado, duracion, limpiarTexto, porcentaje, serieDePeticiones } from './logica';

function nodo(id: string, padre: string | null, estado: Nodo['estado'], codigo = id): Nodo {
  return {
    id, padre, tipo: 'SEARCH', nivel: 'RAIZ', codigo, etiqueta: null, estado, intentos: 0,
    registros: null, paginas: null, paginasTerminadas: 0, error: null,
  };
}

describe('construirArbol', () => {
  it('une hijos con padres y cuenta solo las hojas sin respuesta', () => {
    const arbol = construirArbol([
      nodo('raiz', null, 'EXPANDED'),
      nodo('a', 'raiz', 'EXPANDED'),
      nodo('b', 'raiz', 'DONE'),
      nodo('a1', 'a', 'EXCEEDED'),
      nodo('a2', 'a', 'EXCEEDED'),
    ]);
    expect(arbol).toHaveLength(1);
    expect(arbol[0].descendientes).toBe(5);
    expect(arbol[0].sinRespuesta).toBe(2);
    expect(arbol[0].hijos.map((h) => h.nodo.id)).toEqual(['a', 'b']);
  });

  it('un EXCEEDED con hijos (se expandió) no cuenta como sin respuesta', () => {
    const arbol = construirArbol([nodo('r', null, 'EXCEEDED'), nodo('h', 'r', 'DONE')]);
    expect(arbol[0].sinRespuesta).toBe(0);
  });

  it('los huérfanos cuelgan de la raíz virtual en vez de perderse', () => {
    const arbol = construirArbol([nodo('x', 'no-existe', 'DONE')]);
    expect(arbol.map((n) => n.nodo.id)).toEqual(['x']);
  });
});

describe('contarPorEstado', () => {
  it('incluye todos los estados, también los que no aparecen', () => {
    const conteo = contarPorEstado([nodo('a', null, 'DONE'), nodo('b', null, 'DONE'), nodo('c', null, 'FAILED')]);
    expect(conteo.DONE).toBe(2);
    expect(conteo.FAILED).toBe(1);
    expect(conteo.PENDING).toBe(0);
  });
});

describe('serieDePeticiones', () => {
  const ahora = Date.parse('2026-01-01T10:00:35Z');

  it('rellena con ceros y separa peticiones correctas, bloqueadas y con error', () => {
    const cubos: CuboDePeticiones[] = [
      { cubo: '2026-01-01T10:00:30Z', total: 10, bloqueadas: 3, errores: 2, duracion_media_ms: 100 },
    ];
    const serie = serieDePeticiones(cubos, ahora, 1);
    expect(serie).toHaveLength(6);
    const ultima = serie[serie.length - 1];
    expect(ultima).toMatchObject({ ok: 5, bloqueadas: 3, errores: 2 });
    expect(serie.slice(0, -1).every((b) => b.ok + b.bloqueadas + b.errores === 0)).toBe(true);
  });

  it('acepta el cubo como instante en milisegundos', () => {
    const serie = serieDePeticiones(
      [{ cubo: Date.parse('2026-01-01T10:00:30Z'), total: 4, bloqueadas: 0, errores: 0, duracion_media_ms: null }], ahora, 1);
    expect(serie[serie.length - 1].ok).toBe(4);
  });

  it('ignora cubos con fecha inválida', () => {
    const serie = serieDePeticiones([{ cubo: 'basura', total: 9, bloqueadas: 0, errores: 0, duracion_media_ms: null }], ahora, 1);
    expect(serie.every((b) => b.ok === 0)).toBe(true);
  });
});

describe('formatos', () => {
  it('porcentaje acota entre 0 y 100', () => {
    expect(porcentaje(0.456)).toBe('46 %');
    expect(porcentaje(3)).toBe('100 %');
    expect(porcentaje(-1)).toBe('0 %');
  });

  it('duracion', () => {
    expect(duracion(45)).toBe('45 s');
    expect(duracion(125)).toBe('2 min 05 s');
    expect(duracion(-3)).toBe('0 s');
  });

  it('limpiarTexto elimina los saltos de línea incrustados de la Rama', () => {
    expect(limpiarTexto('Demandante: ANA\r\n\t\t\t\t\tPEREZ ')).toBe('Demandante: ANA PEREZ');
    expect(limpiarTexto(null)).toBe('');
  });
});
