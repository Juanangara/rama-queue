import { useCallback, useEffect, useRef, useState } from 'react';

export interface Sondeo<T> {
  datos: T | null;
  error: string | null;
  recargar: () => void;
}

/** Llama a `cargar` ahora y cada `cadaMs`. Un fallo no borra los últimos datos buenos: solo muestra el error. */
export function useSondeo<T>(cargar: () => Promise<T>, cadaMs: number, claves: unknown[] = []): Sondeo<T> {
  const [datos, setDatos] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const cargarRef = useRef(cargar);
  cargarRef.current = cargar;

  useEffect(() => {
    let vivo = true;
    const ciclo = async () => {
      try {
        const nuevos = await cargarRef.current();
        if (vivo) {
          setDatos(nuevos);
          setError(null);
        }
      } catch (e) {
        if (vivo) setError(e instanceof Error ? e.message : String(e));
      }
    };
    void ciclo();
    const temporizador = window.setInterval(() => void ciclo(), cadaMs);
    return () => {
      vivo = false;
      window.clearInterval(temporizador);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cadaMs, version, ...claves]);

  const recargar = useCallback(() => setVersion((v) => v + 1), []);
  return { datos, error, recargar };
}

export type EstadoDeConexion = 'conectando' | 'en vivo' | 'reconectando';

/**
 * Suscripción SSE. EventSource se reconecta solo; aquí solo se informa del estado de la conexión.
 * `alRecibir` recibe el nombre del evento y su JSON ya interpretado.
 */
export function useSse(url: string | null, alRecibir: (nombre: string, datos: unknown) => void): EstadoDeConexion {
  const [estado, setEstado] = useState<EstadoDeConexion>('conectando');
  const manejador = useRef(alRecibir);
  manejador.current = alRecibir;

  useEffect(() => {
    if (!url) return;
    setEstado('conectando');
    const fuente = new EventSource(url);
    fuente.onopen = () => setEstado('en vivo');
    fuente.onerror = () => setEstado('reconectando');
    for (const nombre of ['progreso', 'evento']) {
      fuente.addEventListener(nombre, (e) => {
        try {
          manejador.current(nombre, JSON.parse((e as MessageEvent<string>).data));
        } catch {
          // un mensaje mal formado no debe tumbar la suscripción
        }
      });
    }
    return () => fuente.close();
  }, [url]);

  return estado;
}

/** Hora actual que se refresca cada segundo (cuentas atrás y «hace N s»). */
export function useAhora(): number {
  const [ahora, setAhora] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setAhora(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  return ahora;
}
