import { useMemo, useRef, useState } from 'react';
import { api, type EventoEnVivo, type FiltroResultados, type JobProgreso, type Nodo, type Proceso } from './api';
import { Aviso, BarraDeEstados, InsigniaItem, InsigniaJob, fechaHora, hora, mensajeDe } from './comunes';
import { useSondeo, useSse } from './hooks';
import { categoriaLegible, construirArbol, contarPorEstado, esFinal, limpiarTexto, porcentaje, type NodoVisual } from './logica';

interface Muestra {
  t: number;
  terminados: number;
}

export function JobDetail({ id, volver }: { id: string; volver: () => void }) {
  const [progreso, setProgreso] = useState<JobProgreso | null>(null);
  const [eventos, setEventos] = useState<EventoEnVivo[]>([]);
  const [accion, setAccion] = useState<{ tipo: 'ok' | 'error'; texto: string } | null>(null);
  const muestras = useRef<Muestra[]>([]);
  const [ritmo, setRitmo] = useState<number | null>(null);

  const conexion = useSse(`/api/jobs/${id}/events`, (nombre, datos) => {
    if (nombre === 'progreso') {
      const p = datos as JobProgreso;
      setProgreso(p);
      const terminados = p.itemsTotales - p.itemsPendientes;
      const ahora = Date.now();
      muestras.current = [...muestras.current, { t: ahora, terminados }].filter((m) => ahora - m.t <= 30_000);
      const primera = muestras.current[0];
      setRitmo(
        muestras.current.length > 1 && ahora > primera.t ? ((terminados - primera.terminados) / (ahora - primera.t)) * 60_000 : null,
      );
    } else if (nombre === 'evento') {
      setEventos((anteriores) => [datos as EventoEnVivo, ...anteriores].slice(0, 100));
    }
  });

  const terminado = progreso ? esFinal(progreso.job.estado) : false;
  const arbol = useSondeo(() => api.arbol(id), terminado ? 60_000 : 3000, [id, terminado]);

  async function ejecutar(nombre: string, f: () => Promise<unknown>) {
    setAccion(null);
    try {
      await f();
      setAccion({ tipo: 'ok', texto: nombre });
    } catch (e) {
      setAccion({ tipo: 'error', texto: mensajeDe(e) });
    }
  }

  const fallidos = progreso?.itemsPorEstado.FAILED ?? 0;

  return (
    <>
      <div className="fila">
        <button onClick={volver}>← Búsquedas</button>
        <span className="suave">Conexión en vivo: {conexion}</span>
      </div>

      {!progreso && <p className="suave">Cargando…</p>}

      {progreso && (
        <section className="tarjeta" aria-labelledby="progreso">
          <div className="tarjeta-cabecera">
            <div>
              <h2 id="progreso">
                {progreso.job.nombre} · {progreso.job.tipoPersona === 'nat' ? 'natural' : 'jurídica'} ·{' '}
                {categoriaLegible(progreso.job.categoria)}
              </h2>
              <span className="suave">
                Creado {fechaHora(progreso.job.creadoEn)}
                {progreso.job.finalizadoEn && ` · terminó ${fechaHora(progreso.job.finalizadoEn)}`}
              </span>
            </div>
            <div className="fila">
              <InsigniaJob estado={progreso.job.estado} />
              <button
                className="peligro"
                disabled={terminado}
                onClick={() => void ejecutar('Búsqueda cancelada', () => api.cancelar(id))}
              >
                Cancelar
              </button>
              <button
                disabled={fallidos === 0}
                title="Vuelve a intentar los ítems que fallaron definitivamente y los retira de la DLQ"
                onClick={() => void ejecutar('Ítems fallidos reencolados', () => api.reencolar(id))}
              >
                Reencolar fallidos ({fallidos})
              </button>
            </div>
          </div>

          <div className="metricas" style={{ marginBottom: 12 }}>
            <div className="metrica">
              <b>{porcentaje(progreso.avance)}</b>
              <span>explorado (crece si el árbol se expande)</span>
            </div>
            <div className="metrica">
              <b>{progreso.itemsTotales}</b>
              <span>ítems (peticiones) en total</span>
            </div>
            <div className="metrica">
              <b>{progreso.procesosAceptados}</b>
              <span>procesos que cumplen</span>
            </div>
            <div className="metrica">
              <b>{progreso.procesosCompletos}</b>
              <span>con detalle completo</span>
            </div>
            <div className="metrica">
              <b>{ritmo === null || terminado ? '–' : ritmo.toFixed(0)}</b>
              <span>ítems por minuto (últimos 30 s)</span>
            </div>
          </div>

          <BarraDeEstados conteos={progreso.itemsPorEstado} total={progreso.itemsTotales} />
          {accion && <Aviso tipo={accion.tipo}>{accion.texto}</Aviso>}
          {progreso.job.estado === 'PARTIAL' && (
            <Aviso tipo="alerta">
              Terminó con ramas sin respuesta: en esos despachos la Rama tiene más de mil procesos y no los muestra. Se ven marcadas en el árbol.
            </Aviso>
          )}
        </section>
      )}

      <div className="rejilla">
        <ArbolDeBusqueda nodos={arbol.datos} error={arbol.error} />
        <Registro eventos={eventos} />
      </div>

      <Resultados id={id} enCurso={!terminado} />
    </>
  );
}

// ── Árbol ────────────────────────────────────────────────────────────────────

function ArbolDeBusqueda({ nodos, error }: { nodos: Nodo[] | null; error: string | null }) {
  const raices = useMemo(() => (nodos ? construirArbol(nodos) : []), [nodos]);
  const conteo = useMemo(() => (nodos ? contarPorEstado(nodos) : null), [nodos]);
  const sinRespuesta = conteo?.EXCEEDED ?? 0;

  return (
    <section className="tarjeta" aria-labelledby="arbol" style={{ gridColumn: '1 / -1' }}>
      <div className="tarjeta-cabecera">
        <h2 id="arbol">Árbol de búsqueda</h2>
        <span className="suave">
          Cada nivel nace solo cuando la Rama responde «más de mil registros».
          {nodos && ` ${nodos.length} ${nodos.length === 1 ? 'nodo' : 'nodos'}`}
          {sinRespuesta > 0 && ` · ${sinRespuesta} en EXCEEDED`}
        </span>
      </div>
      {error && <Aviso tipo="error">{error}</Aviso>}
      <div className="arbol" tabIndex={0} aria-label="Árbol de búsqueda">
        <ul>
          {raices.map((r) => (
            <RamaDelArbol key={r.nodo.id} visual={r} profundidad={0} />
          ))}
        </ul>
      </div>
    </section>
  );
}

function RamaDelArbol({ visual, profundidad }: { visual: NodoVisual; profundidad: number }) {
  const { nodo, hijos } = visual;
  const titulo = (
    <>
      <span className="nivel">{nodo.nivel ?? nodo.tipo}</span>
      <span>{nodo.etiqueta ?? nodo.codigo ?? '(raíz)'}</span>
      <InsigniaItem estado={nodo.estado} />
      {nodo.registros !== null && <span className="suave num">{nodo.registros} reg.</span>}
      {nodo.paginas !== null && nodo.paginas > 1 && (
        <span className="suave num">
          pág. {nodo.paginasTerminadas}/{nodo.paginas}
        </span>
      )}
      {nodo.intentos > 0 && <span className="suave">{nodo.intentos} intentos</span>}
      {hijos.length > 0 && visual.sinRespuesta > 0 && <span className="insignia e-EXCEEDED">{visual.sinRespuesta} sin respuesta</span>}
      {nodo.error &&
        (nodo.estado === 'FAILED' || nodo.estado === 'EXCEEDED' ? (
          <span className="e-FAILED insignia">{nodo.error}</span>
        ) : (
          // El ítem terminó bien: el error es de un intento anterior que se superó con un reintento.
          <span className="suave" title={nodo.error}>
            superó: {nodo.error}
          </span>
        ))}
    </>
  );
  if (hijos.length === 0) {
    return (
      <li>
        <div className="hoja">{titulo}</div>
      </li>
    );
  }
  return (
    <li>
      <details open={profundidad < 1 || visual.sinRespuesta > 0 || nodo.estado === 'FAILED'}>
        <summary>{titulo}</summary>
        <ul>
          {hijos.map((h) => (
            <RamaDelArbol key={h.nodo.id} visual={h} profundidad={profundidad + 1} />
          ))}
        </ul>
      </details>
    </li>
  );
}

// ── Registro de eventos ──────────────────────────────────────────────────────

function Registro({ eventos }: { eventos: EventoEnVivo[] }) {
  return (
    <section className="tarjeta" aria-labelledby="registro" style={{ gridColumn: '1 / -1' }}>
      <div className="tarjeta-cabecera">
        <h2 id="registro">Eventos importantes</h2>
        <span className="suave">Fin del job, breaker abierto o cerrado, ramas sin respuesta y fallos.</span>
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
  );
}

// ── Resultados ───────────────────────────────────────────────────────────────

const TAMANO = 20;

function Resultados({ id, enCurso }: { id: string; enCurso: boolean }) {
  const [pagina, setPagina] = useState(1);
  const [borrador, setBorrador] = useState<FiltroResultados>({});
  const [filtro, setFiltro] = useState<FiltroResultados>({});
  const datos = useSondeo(() => api.resultados(id, pagina, TAMANO, filtro), enCurso ? 4000 : 60_000, [id, pagina, filtro, enCurso]);

  function aplicar() {
    setPagina(1);
    setFiltro({ ...borrador });
  }

  return (
    <section className="tarjeta" aria-labelledby="resultados">
      <div className="tarjeta-cabecera">
        <h2 id="resultados">Procesos encontrados{datos.datos && ` (${datos.datos.total})`}</h2>
        <form
          className="fila"
          onSubmit={(e) => {
            e.preventDefault();
            aplicar();
          }}
        >
          <label>
            Texto
            <input value={borrador.texto ?? ''} onChange={(e) => setBorrador({ ...borrador, texto: e.target.value })} />
          </label>
          <label>
            Departamento
            <input value={borrador.departamento ?? ''} onChange={(e) => setBorrador({ ...borrador, departamento: e.target.value })} />
          </label>
          <label>
            Ciudad
            <input value={borrador.ciudad ?? ''} onChange={(e) => setBorrador({ ...borrador, ciudad: e.target.value })} />
          </label>
          <label style={{ gridAutoFlow: 'column', alignItems: 'center', alignSelf: 'end' }}>
            <input
              type="checkbox"
              checked={borrador.soloCompletos ?? false}
              onChange={(e) => setBorrador({ ...borrador, soloCompletos: e.target.checked })}
            />
            Solo completos
          </label>
          <button style={{ alignSelf: 'end' }}>Filtrar</button>
        </form>
      </div>

      {datos.error && <Aviso tipo="error">{datos.error}</Aviso>}
      {datos.datos && datos.datos.elementos.length === 0 && <p className="suave">Ningún proceso coincide (todavía).</p>}
      {datos.datos && datos.datos.elementos.length > 0 && (
        <div className="tabla-envoltura">
          <table>
            <thead>
              <tr>
                <th>Radicado</th>
                <th>Despacho</th>
                <th>Ciudad / depto.</th>
                <th>Sujetos</th>
                <th>Última actuación</th>
                <th>Detalle</th>
              </tr>
            </thead>
            <tbody>
              {datos.datos.elementos.map((p) => (
                <FilaDeProceso key={p.idProceso} p={p} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {datos.datos && datos.datos.paginas > 1 && (
        <div className="fila" style={{ marginTop: 10 }}>
          <button disabled={pagina <= 1} onClick={() => setPagina(pagina - 1)}>
            ← Anterior
          </button>
          <span className="num">
            Página {datos.datos.pagina} de {datos.datos.paginas}
          </span>
          <button disabled={pagina >= datos.datos.paginas} onClick={() => setPagina(pagina + 1)}>
            Siguiente →
          </button>
        </div>
      )}
    </section>
  );
}

function FilaDeProceso({ p }: { p: Proceso }) {
  const [abierto, setAbierto] = useState(false);
  return (
    <>
      <tr className="clicable" onClick={() => setAbierto(!abierto)}>
        <td className="mono">{p.llaveProceso}</td>
        <td>{limpiarTexto(p.despacho)}</td>
        <td>
          {p.ciudad ?? '–'}
          <div className="suave">{p.departamento}</div>
        </td>
        <td>
          {limpiarTexto(p.sujetosProcesales)}
          {p.sujetosTruncados && <span className="suave"> (truncados)</span>}
        </td>
        <td className="num">{p.fechaUltimaActuacion ? p.fechaUltimaActuacion.slice(0, 10) : '–'}</td>
        <td>{p.estadoDetalle}</td>
      </tr>
      {abierto && (
        <tr>
          <td colSpan={6}>
            <pre>{JSON.stringify({ detalle: p.detalle, ultimaActuacion: p.ultimaActuacion, sujetos: p.sujetos }, null, 2)}</pre>
          </td>
        </tr>
      )}
    </>
  );
}
