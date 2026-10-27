// Lógica pura de la interfaz (sin React ni red), para poder probarla con Vitest.
import type { CuboDePeticiones, EstadoItem, EstadoJob, Nodo } from './api';

export const ESTADOS_ITEM: EstadoItem[] = ['PENDING', 'IN_PROGRESS', 'DONE', 'EXPANDED', 'EXCEEDED', 'FAILED', 'CANCELLED'];

const ETIQUETAS: Record<EstadoItem, string> = {
  PENDING: 'Pendiente',
  IN_PROGRESS: 'En curso',
  DONE: 'Hecho',
  EXPANDED: 'Expandido',
  EXCEEDED: 'Sin respuesta (>1000)',
  FAILED: 'Fallido',
  CANCELLED: 'Cancelado',
};

export function etiquetaDeEstado(estado: EstadoItem): string {
  return ETIQUETAS[estado];
}

const ETIQUETAS_JOB: Record<EstadoJob, string> = {
  RUNNING: 'En curso',
  COMPLETED: 'Completo',
  PARTIAL: 'Parcial',
  FAILED: 'Fallido',
  CANCELLED: 'Cancelado',
};

export function etiquetaDeJob(estado: EstadoJob): string {
  return ETIQUETAS_JOB[estado];
}

export function esFinal(estado: EstadoJob): boolean {
  return estado !== 'RUNNING';
}

// ── Árbol de búsqueda ────────────────────────────────────────────────────────

export interface NodoVisual {
  nodo: Nodo;
  hijos: NodoVisual[];
  /** Cuántos descendientes (incluido él) terminaron en EXCEEDED: ramas sin respuesta. */
  sinRespuesta: number;
  descendientes: number;
}

/** Arma el árbol a partir de la lista plana; los huérfanos (padre ausente) cuelgan de la raíz virtual. */
export function construirArbol(nodos: Nodo[]): NodoVisual[] {
  const porId = new Map<string, NodoVisual>();
  for (const nodo of nodos) {
    porId.set(nodo.id, { nodo, hijos: [], sinRespuesta: 0, descendientes: 1 });
  }
  const raices: NodoVisual[] = [];
  for (const visual of porId.values()) {
    const padre = visual.nodo.padre ? porId.get(visual.nodo.padre) : undefined;
    (padre ? padre.hijos : raices).push(visual);
  }
  const ordenar = (lista: NodoVisual[]) =>
    lista.sort((a, b) => (a.nodo.codigo ?? '').localeCompare(b.nodo.codigo ?? '') || a.nodo.tipo.localeCompare(b.nodo.tipo));
  const acumular = (visual: NodoVisual): void => {
    ordenar(visual.hijos);
    visual.sinRespuesta = visual.nodo.estado === 'EXCEEDED' && visual.hijos.length === 0 ? 1 : 0;
    for (const hijo of visual.hijos) {
      acumular(hijo);
      visual.sinRespuesta += hijo.sinRespuesta;
      visual.descendientes += hijo.descendientes;
    }
  };
  ordenar(raices);
  raices.forEach(acumular);
  return raices;
}

/** Cuenta los nodos por estado (para la leyenda del árbol). */
export function contarPorEstado(nodos: Nodo[]): Record<EstadoItem, number> {
  const conteo = Object.fromEntries(ESTADOS_ITEM.map((e) => [e, 0])) as Record<EstadoItem, number>;
  for (const nodo of nodos) conteo[nodo.estado]++;
  return conteo;
}

// ── Histograma de peticiones ─────────────────────────────────────────────────

export interface Barra {
  inicio: number;
  ok: number;
  bloqueadas: number;
  errores: number;
}

const CUBO_MS = 10_000;

/** Convierte los cubos del servidor en una serie continua (con ceros donde no hubo tráfico) de los últimos `minutos`. */
export function serieDePeticiones(cubos: CuboDePeticiones[], ahora: number, minutos: number): Barra[] {
  const fin = Math.floor(ahora / CUBO_MS) * CUBO_MS;
  const inicio = fin - minutos * 60_000 + CUBO_MS;
  const porInicio = new Map<number, CuboDePeticiones>();
  for (const cubo of cubos) {
    const t = Math.floor(new Date(cubo.cubo).getTime() / CUBO_MS) * CUBO_MS;
    if (!Number.isNaN(t)) porInicio.set(t, cubo);
  }
  const barras: Barra[] = [];
  for (let t = inicio; t <= fin; t += CUBO_MS) {
    const c = porInicio.get(t);
    const total = c ? Number(c.total) : 0;
    const bloqueadas = c ? Number(c.bloqueadas) : 0;
    const errores = c ? Number(c.errores) : 0;
    barras.push({ inicio: t, ok: Math.max(0, total - bloqueadas - errores), bloqueadas, errores });
  }
  return barras;
}

// ── Formatos ─────────────────────────────────────────────────────────────────

export function porcentaje(fraccion: number): string {
  return `${Math.round(Math.min(1, Math.max(0, fraccion)) * 100)} %`;
}

export function duracion(segundos: number): string {
  const s = Math.max(0, Math.round(segundos));
  const m = Math.floor(s / 60);
  return m === 0 ? `${s} s` : `${m} min ${String(s % 60).padStart(2, '0')} s`;
}

/** Limpia los saltos de línea y tabulaciones incrustados que trae la Rama en los nombres de sujetos. */
export function limpiarTexto(texto: string | null | undefined): string {
  return (texto ?? '').replace(/\s+/g, ' ').trim();
}

export function categoriaLegible(codigo: string): string {
  return codigo === 'extincionDominio' ? 'extinción de dominio' : codigo;
}
