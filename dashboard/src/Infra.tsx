import { useMemo, useState } from 'react';
import { api, type Cola, type EventoEnVivo, type Governor } from './api';
import { Aviso, hora, mensajeDe } from './comunes';
import { useAhora, useSondeo, useSse } from './hooks';
import { duracion, porcentaje, serieDePeticiones } from './logica';

export function Infra() {
  const colas = useSondeo(api.colas, 2000);
  const governor = useSondeo(api.governor, 1000);
  const [eventos, setEventos] = useState<EventoEnVivo[]>([]);
  const conexion = useSse('/api/infra/events', (nombre, datos) => {
    if (nombre === 'evento') setEventos((previos) => [datos as EventoEnVivo, ...previos].slice(0, 60));
  });

  return (
    <>
      <div className="rejilla">
        <GobernadorPanel g={governor.datos} error={governor.error} />
        <CachePanel g={governor.datos} />
      </div>
      <ColasPanel colas={colas.datos} error={colas.error} />
      <HistogramaPanel />
      <DlqPanel />
      <CaosPanel pausado={governor.datos?.consumidoresPausados ?? false} alCambiar={governor.recargar} />
      <section className="tarjeta" aria-labelledby="ev-global">
        <div className="tarjeta-cabecera">
          <h2 id="ev-global">Eventos del sistema</h2>
          <span className="suave">Conexión en vivo: {conexion}</span>
        </div>
        <div className="registro" role="log" aria-live="polite">
          {eventos.length === 0 && <span className="suave">Sin eventos todavía.</span>}
          {eventos.map((e, i) => (
            <div key={`${e.instante}-${i}`}>
              <time>{hora(e.instante)}</time>
              <b>{e.tipo}</b>
              <span className="suave">{e.detalle}</span>
            </div>
          ))}
        </div>
      </section>
    </>
  );
}

// ── Gobernador y breaker ─────────────────────────────────────────────────────

function GobernadorPanel({ g, error }: { g: Governor | null; error: string | null }) {
  const ahora = useAhora();
  const [instanteDeLectura, restante] = useMemo(() => [Date.now(), g?.breaker.restanteSegundos ?? 0], [g]);
  const cuentaAtras = Math.max(0, restante - (ahora - instanteDeLectura) / 1000);

  return (
    <section className="tarjeta" aria-labelledby="gob">
      <div className="tarjeta-cabecera">
        <h2 id="gob">Gobernador de tráfico hacia la Rama</h2>
      </div>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {g && (
        <>
          <div className="metricas">
            <div className="metrica">
              <b>
                {g.enVuelo} / {g.limite.maxEnVuelo}
              </b>
              <span>peticiones en vuelo (tope duro {g.limite.topeEnVuelo})</span>
            </div>
            <div className="metrica">
              <b>{g.limite.intervaloMs} ms</b>
              <span>separación entre peticiones (base {g.limite.intervaloBaseMs})</span>
            </div>
            <div className="metrica">
              <b>{g.limite.rachaDeExitos}</b>
              <span>éxitos seguidos (recupera cada 50)</span>
            </div>
          </div>
          <h3 style={{ marginTop: 14 }}>Circuit breaker</h3>
          <div className={`aviso ${g.breaker.estado === 'CERRADO' ? 'ok' : 'error'}`} style={{ marginTop: 6 }}>
            {g.breaker.estado === 'CERRADO' && 'Cerrado: las peticiones fluyen.'}
            {g.breaker.estado === 'ABIERTO' && (
              <>
                Abierto: no sale ninguna petición. Se reintenta en <b className="num">{duracion(cuentaAtras)}</b> (apertura n.º{' '}
                {g.breaker.aperturasSeguidas}).
              </>
            )}
            {g.breaker.estado === 'SEMIABIERTO' && 'Semiabierto: se deja pasar una petición de prueba.'}
          </div>
          {g.consumidoresPausados && <Aviso tipo="alerta">Los consumidores están pausados manualmente.</Aviso>}
        </>
      )}
    </section>
  );
}

function CachePanel({ g }: { g: Governor | null }) {
  const c = g?.cacheDeListados;
  return (
    <section className="tarjeta" aria-labelledby="cache">
      <div className="tarjeta-cabecera">
        <h2 id="cache">Caché de listados (Redis)</h2>
      </div>
      {c && (
        <div className="metricas">
          <div className="metrica">
            <b>{c.tasaDeAciertos === null ? '–' : porcentaje(c.tasaDeAciertos)}</b>
            <span>tasa de aciertos</span>
          </div>
          <div className="metrica">
            <b>{c.aciertos}</b>
            <span>aciertos (peticiones ahorradas)</span>
          </div>
          <div className="metrica">
            <b>{c.fallos}</b>
            <span>fallos</span>
          </div>
        </div>
      )}
      <p className="suave">
        Los listados de entidades, especialidades y despachos casi no cambian: una vez leídos no se vuelven a pedir durante 24 h.
      </p>
    </section>
  );
}

// ── Colas ────────────────────────────────────────────────────────────────────

function ColasPanel({ colas, error }: { colas: Cola[] | null; error: string | null }) {
  return (
    <section className="tarjeta" aria-labelledby="colas">
      <div className="tarjeta-cabecera">
        <h2 id="colas">Colas de RabbitMQ</h2>
        <span className="suave">
          Las <code>.retry.*</code> son la espera con TTL antes de reintentar; las <code>.dlq</code> guardan lo que falló del todo.
        </span>
      </div>
      {error && <Aviso tipo="error">{error}</Aviso>}
      {colas && (
        <div className="tabla-envoltura">
          <table>
            <thead>
              <tr>
                <th>Cola</th>
                <th className="num">Listos</th>
                <th className="num">Sin confirmar</th>
                <th className="num">Consumidores</th>
                <th className="num">Publicados/s</th>
                <th className="num">Entregados/s</th>
                <th className="num">Confirmados/s</th>
              </tr>
            </thead>
            <tbody>
              {[...colas]
                .sort((a, b) => a.nombre.localeCompare(b.nombre))
                .map((q) => (
                  <tr key={q.nombre}>
                    <td className="mono">{q.nombre}</td>
                    <td className="num">{q.listos}</td>
                    <td className="num">{q.sinConfirmar}</td>
                    <td className="num">{q.consumidores}</td>
                    <td className="num">{q.publicadosPorSegundo.toFixed(1)}</td>
                    <td className="num">{q.entregadosPorSegundo.toFixed(1)}</td>
                    <td className="num">{q.confirmadosPorSegundo.toFixed(1)}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ── Histograma de peticiones salientes ───────────────────────────────────────

function HistogramaPanel() {
  const minutos = 10;
  const datos = useSondeo(() => api.peticiones(minutos), 5000);
  const ahora = useAhora();
  const serie = useMemo(() => (datos.datos ? serieDePeticiones(datos.datos, ahora, minutos) : []), [datos.datos, ahora]);
  const maximo = Math.max(1, ...serie.map((b) => b.ok + b.bloqueadas + b.errores));
  const total = serie.reduce((s, b) => s + b.ok + b.bloqueadas + b.errores, 0);
  const bloqueadas = serie.reduce((s, b) => s + b.bloqueadas, 0);

  return (
    <section className="tarjeta" aria-labelledby="hist">
      <div className="tarjeta-cabecera">
        <h2 id="hist">Peticiones salientes a la Rama (últimos {minutos} min, cubos de 10 s)</h2>
        <span className="suave num">
          {total} en total · {bloqueadas} bloqueadas (403)
        </span>
      </div>
      {datos.error && <Aviso tipo="error">{datos.error}</Aviso>}
      <div className="histograma" role="img" aria-label={`${total} peticiones en los últimos ${minutos} minutos, ${bloqueadas} bloqueadas`}>
        {serie.map((b) => (
          <div
            className="col"
            key={b.inicio}
            title={`${hora(b.inicio)} · ${b.ok} ok, ${b.bloqueadas} bloqueadas, ${b.errores} con error`}
          >
            <div className="seg ok" style={{ height: `${(b.ok / maximo) * 100}%` }} />
            <div className="seg err" style={{ height: `${(b.errores / maximo) * 100}%` }} />
            <div className="seg bloq" style={{ height: `${(b.bloqueadas / maximo) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="leyenda">
        <span className="insignia e-DONE">Correctas</span>
        <span className="insignia e-EXCEEDED">Con error</span>
        <span className="insignia e-FAILED">Bloqueadas (403)</span>
      </div>
    </section>
  );
}

// ── DLQ ──────────────────────────────────────────────────────────────────────

function DlqPanel() {
  const dlq = useSondeo(api.dlq, 3000);
  const [mensaje, setMensaje] = useState<{ tipo: 'ok' | 'error'; texto: string } | null>(null);

  async function reencolar() {
    try {
      const r = await api.reencolarDlq();
      setMensaje({ tipo: 'ok', texto: `${r.reencolados} ítems reencolados, ${r.retiradosDeLaDlq} mensajes retirados de la DLQ` });
      dlq.recargar();
    } catch (e) {
      setMensaje({ tipo: 'error', texto: mensajeDe(e) });
    }
  }

  const entradas = dlq.datos ?? [];
  return (
    <section className="tarjeta" aria-labelledby="dlq">
      <div className="tarjeta-cabecera">
        <h2 id="dlq">Cola de mensajes muertos (DLQ){dlq.datos && ` · ${entradas.length}`}</h2>
        <button disabled={entradas.length === 0} onClick={() => void reencolar()}>
          Reencolar todo
        </button>
      </div>
      {mensaje && <Aviso tipo={mensaje.tipo}>{mensaje.texto}</Aviso>}
      {dlq.error && <Aviso tipo="error">{dlq.error}</Aviso>}
      {entradas.length === 0 ? (
        <p className="suave">Vacía: ningún ítem ha fallado definitivamente.</p>
      ) : (
        <div className="tabla-envoltura">
          <table>
            <thead>
              <tr>
                <th>Cola</th>
                <th>Tipo</th>
                <th>Motivo</th>
                <th className="num">Intentos</th>
                <th>Ítem</th>
              </tr>
            </thead>
            <tbody>
              {entradas.map((e) => (
                <tr key={e.itemId}>
                  <td className="mono">{e.grupo}</td>
                  <td>{e.tipo}</td>
                  <td>{e.motivo}</td>
                  <td className="num">{e.intentos}</td>
                  <td className="mono">{e.itemId.slice(0, 8)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ── Panel de caos ────────────────────────────────────────────────────────────

function CaosPanel({ pausado, alCambiar }: { pausado: boolean; alCambiar: () => void }) {
  const [mensaje, setMensaje] = useState<{ tipo: 'ok' | 'error'; texto: string } | null>(null);
  const [consumidores, setConsumidores] = useState(2);
  const [segundos, setSegundos] = useState(30);
  const [latencia, setLatencia] = useState(150);

  async function hacer(texto: string, f: () => Promise<unknown>) {
    try {
      await f();
      setMensaje({ tipo: 'ok', texto });
      alCambiar();
    } catch (e) {
      setMensaje({ tipo: 'error', texto: `${texto}: ${mensajeDe(e)}` });
    }
  }

  return (
    <section className="tarjeta" aria-labelledby="caos">
      <div className="tarjeta-cabecera">
        <h2 id="caos">Panel de caos</h2>
        <span className="suave">Para ver cómo reacciona el sistema: nada de esto debería colgar la cola.</span>
      </div>
      <div className="rejilla">
        <div className="fila">
          <h3 style={{ flexBasis: '100%' }}>Consumidores (llegan a los workers por Redis)</h3>
          <button onClick={() => void hacer('Consumidores pausados', api.pausar)} disabled={pausado}>
            Pausar
          </button>
          <button onClick={() => void hacer('Consumidores reanudados', api.reanudar)} disabled={!pausado}>
            Reanudar
          </button>
          <label>
            Concurrencia
            <input type="number" min={1} max={8} value={consumidores} onChange={(e) => setConsumidores(Number(e.target.value))} style={{ width: 70 }} />
          </label>
          <button onClick={() => void hacer(`Concurrencia de búsqueda = ${consumidores}`, () => api.concurrencia('search-listener', consumidores))}>
            Aplicar a búsqueda
          </button>
          <button onClick={() => void hacer(`Concurrencia de detalle = ${consumidores}`, () => api.concurrencia('detail-listener', consumidores))}>
            Aplicar a detalle
          </button>
          <button
            className="peligro"
            title="Solo con el perfil demo: un worker se detiene sin hacer ack y Docker lo reinicia"
            onClick={() => void hacer('Un worker se detendrá en su próximo ciclo', api.caidaDeWorker)}
          >
            Matar un worker
          </button>
        </div>
        <div className="fila">
          <h3 style={{ flexBasis: '100%' }}>Servidor simulado (mock de la Rama)</h3>
          <label>
            Segundos de bloqueo
            <input type="number" min={1} max={600} value={segundos} onChange={(e) => setSegundos(Number(e.target.value))} style={{ width: 90 }} />
          </label>
          <button className="peligro" onClick={() => void hacer(`Mock bloqueado ${segundos} s (403)`, () => api.mockBloquear(segundos))}>
            Bloquear
          </button>
          <label>
            Latencia (ms)
            <input type="number" min={0} max={5000} value={latencia} onChange={(e) => setLatencia(Number(e.target.value))} style={{ width: 90 }} />
          </label>
          <button onClick={() => void hacer(`Latencia del mock = ${latencia} ms`, () => api.mockLatencia(latencia, Math.round(latencia / 2)))}>
            Aplicar
          </button>
          <button onClick={() => void hacer('Mock restablecido', api.mockReiniciar)}>Restablecer</button>
        </div>
      </div>
      {mensaje && <Aviso tipo={mensaje.tipo}>{mensaje.texto}</Aviso>}
    </section>
  );
}
