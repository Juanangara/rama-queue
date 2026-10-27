// Tipos y llamadas a la API REST. Todas las rutas son relativas (/api): proxy de Vite en desarrollo, nginx en Compose.

export type EstadoItem = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'EXPANDED' | 'EXCEEDED' | 'FAILED' | 'CANCELLED';
export type EstadoJob = 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

export interface JobResumen {
  id: string;
  nombre: string;
  tipoPersona: string;
  categoria: string;
  estado: EstadoJob;
  creadoEn: string;
  finalizadoEn: string | null;
}

export interface JobProgreso {
  job: JobResumen;
  itemsPorEstado: Record<EstadoItem, number>;
  itemsTotales: number;
  itemsPendientes: number;
  avance: number;
  procesosAceptados: number;
  procesosCompletos: number;
}

export interface Nodo {
  id: string;
  padre: string | null;
  tipo: string;
  nivel: string | null;
  codigo: string | null;
  etiqueta: string | null;
  estado: EstadoItem;
  intentos: number;
  registros: number | null;
  paginas: number | null;
  paginasTerminadas: number;
  error: string | null;
}

export interface Proceso {
  idProceso: number;
  llaveProceso: string;
  despacho: string;
  departamento: string;
  ciudad: string | null;
  categoria: string;
  sujetosProcesales: string;
  sujetosTruncados: boolean;
  fechaProceso: string | null;
  fechaUltimaActuacion: string | null;
  estadoDetalle: string;
  detalle: Record<string, unknown> | null;
  ultimaActuacion: Record<string, unknown> | null;
  sujetos: unknown;
}

export interface PaginaDeProcesos {
  elementos: Proceso[];
  total: number;
  pagina: number;
  tamano: number;
  paginas: number;
}

export interface FiltroResultados {
  ciudad?: string;
  departamento?: string;
  texto?: string;
  soloCompletos?: boolean;
}

export interface Cola {
  nombre: string;
  listos: number;
  sinConfirmar: number;
  consumidores: number;
  publicadosPorSegundo: number;
  entregadosPorSegundo: number;
  confirmadosPorSegundo: number;
}

export interface Governor {
  enVuelo: number;
  limite: { maxEnVuelo: number; intervaloMs: number; rachaDeExitos: number; topeEnVuelo: number; intervaloBaseMs: number };
  breaker: { estado: 'CERRADO' | 'ABIERTO' | 'SEMIABIERTO'; restanteSegundos: number; aperturasSeguidas: number; abiertoHasta: string | null };
  cacheDeListados: { aciertos: number; fallos: number; tasaDeAciertos: number | null };
  consumidoresPausados: boolean;
}

export interface EntradaDlq {
  grupo: string;
  itemId: string;
  jobId: string;
  tipo: string;
  motivo: string;
  intentos: number;
}

export interface CuboDePeticiones {
  cubo: string | number;
  total: number;
  bloqueadas: number;
  errores: number;
  duracion_media_ms: number | null;
}

export interface EventoEnVivo {
  tipo: string;
  jobId: string | null;
  itemId: string | null;
  detalle: string | null;
  instante: string;
}

export class ErrorDeApi extends Error {
  constructor(
    public estado: number,
    mensaje: string,
  ) {
    super(mensaje);
  }
}

async function pedir<T>(ruta: string, metodo: 'GET' | 'POST' = 'GET', cuerpo?: unknown): Promise<T> {
  const respuesta = await fetch(ruta, {
    method: metodo,
    headers: cuerpo === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  if (!respuesta.ok) {
    let mensaje = respuesta.statusText;
    try {
      const datos = await respuesta.json();
      mensaje = datos.error ?? datos.message ?? mensaje;
    } catch {
      // el cuerpo no era JSON: nos quedamos con el texto del estado
    }
    throw new ErrorDeApi(respuesta.status, mensaje || `HTTP ${respuesta.status}`);
  }
  const texto = await respuesta.text();
  return (texto ? JSON.parse(texto) : undefined) as T;
}

function consulta(parametros: Record<string, string | number | boolean | undefined>): string {
  const p = new URLSearchParams();
  for (const [clave, valor] of Object.entries(parametros)) {
    if (valor !== undefined && valor !== '' && valor !== false) p.set(clave, String(valor));
  }
  const texto = p.toString();
  return texto ? `?${texto}` : '';
}

export const api = {
  crearJob: (nombre: string, tipoPersona: string, categoria: string) =>
    pedir<{ id: string }>('/api/jobs', 'POST', { nombre, tipoPersona, categoria }),
  jobs: () => pedir<JobResumen[]>('/api/jobs'),
  progreso: (id: string) => pedir<JobProgreso>(`/api/jobs/${id}`),
  arbol: (id: string) => pedir<Nodo[]>(`/api/jobs/${id}/tree`),
  resultados: (id: string, pagina: number, tamano: number, filtro: FiltroResultados) =>
    pedir<PaginaDeProcesos>(`/api/jobs/${id}/results${consulta({ pagina, tamano, ...filtro })}`),
  cancelar: (id: string) => pedir<{ estado: string }>(`/api/jobs/${id}/cancel`, 'POST'),
  reencolar: (id: string) => pedir<{ reencolados: number; retiradosDeLaDlq: number }>(`/api/jobs/${id}/requeue`, 'POST'),

  colas: () => pedir<Cola[]>('/api/infra/colas'),
  governor: () => pedir<Governor>('/api/infra/governor'),
  dlq: () => pedir<EntradaDlq[]>('/api/infra/dlq?maximo=100'),
  reencolarDlq: () => pedir<{ reencolados: number; retiradosDeLaDlq: number }>('/api/infra/dlq/requeue', 'POST'),
  peticiones: (minutos: number) => pedir<CuboDePeticiones[]>(`/api/infra/peticiones?minutos=${minutos}`),

  pausar: () => pedir<unknown>('/api/admin/consumidores/pausar', 'POST'),
  reanudar: () => pedir<unknown>('/api/admin/consumidores/reanudar', 'POST'),
  concurrencia: (listener: string, consumidores: number) =>
    pedir<unknown>(`/api/admin/consumidores/concurrencia${consulta({ listener, consumidores })}`, 'POST'),
  caidaDeWorker: () => pedir<{ estado: string }>('/api/admin/workers/caida', 'POST'),

  // Mandos del mock de la Rama (solo existen si se usa el mock; el proxy quita el prefijo /mock).
  mockBloquear: (segundos: number) => pedir<unknown>(`/mock/__admin/block?seconds=${segundos}`, 'POST'),
  mockLatencia: (ms: number, jitterMs: number) => pedir<unknown>(`/mock/__admin/latency?ms=${ms}&jitterMs=${jitterMs}`, 'POST'),
  mockReiniciar: () => pedir<unknown>('/mock/__admin/reset', 'POST'),
  mockEstadisticas: () => pedir<Record<string, unknown>>('/mock/__admin/stats'),
};
