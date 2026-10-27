import type { ReactNode } from 'react';
import type { EstadoItem, EstadoJob } from './api';
import { ESTADOS_ITEM, etiquetaDeEstado, etiquetaDeJob } from './logica';

export function InsigniaItem({ estado }: { estado: EstadoItem }) {
  return <span className={`insignia e-${estado}`}>{etiquetaDeEstado(estado)}</span>;
}

export function InsigniaJob({ estado }: { estado: EstadoJob }) {
  return <span className={`insignia e-${estado}`}>{etiquetaDeJob(estado)}</span>;
}

export function Aviso({ tipo, children }: { tipo: 'error' | 'ok' | 'alerta'; children: ReactNode }) {
  return (
    <div className={`aviso ${tipo}`} role={tipo === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

/** Barra segmentada por estado de ítem, con leyenda (el color nunca va solo: cada estado lleva su texto y su número). */
export function BarraDeEstados({ conteos, total }: { conteos: Record<EstadoItem, number>; total: number }) {
  return (
    <>
      <div className="barra" role="img" aria-label={`Ítems por estado, ${total} en total`}>
        {ESTADOS_ITEM.map((estado) =>
          conteos[estado] > 0 ? (
            <span key={estado} className={`e-${estado}`} style={{ width: `${(conteos[estado] / Math.max(1, total)) * 100}%` }} />
          ) : null,
        )}
      </div>
      <div className="leyenda">
        {ESTADOS_ITEM.map((estado) => (
          <span key={estado} className={`insignia e-${estado}`}>
            {etiquetaDeEstado(estado)} <b className="num">{conteos[estado] ?? 0}</b>
          </span>
        ))}
      </div>
    </>
  );
}

export function hora(instante: string | number | null | undefined): string {
  if (instante === null || instante === undefined) return '–';
  const fecha = new Date(instante);
  return Number.isNaN(fecha.getTime()) ? '–' : fecha.toLocaleTimeString('es-CO', { hour12: false });
}

export function fechaHora(instante: string | null | undefined): string {
  if (!instante) return '–';
  const fecha = new Date(instante);
  return Number.isNaN(fecha.getTime()) ? '–' : fecha.toLocaleString('es-CO', { hour12: false });
}

export function mensajeDe(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
