import { useState, type FormEvent } from 'react';
import { api } from './api';
import { Aviso, InsigniaJob, fechaHora, mensajeDe } from './comunes';
import { useSondeo } from './hooks';
import { categoriaLegible } from './logica';

const CATEGORIAS = ['penal', 'civil', 'laboral', 'familiar', 'extincionDominio'];

// Nombres del universo sintético del mock, con distinta profundidad de drill-down (guía §20). Con la Rama real no aplican.
const SUGERENCIAS: Record<string, string[]> = {
  nat: ['ORTIZ', 'GONZALEZ', 'LOPEZ', 'MARTINEZ', 'GARCIA'],
  jur: ['ANDINA'],
};

export function Jobs({ abrir }: { abrir: (id: string) => void }) {
  const lista = useSondeo(api.jobs, 3000);
  const [nombre, setNombre] = useState('');
  const [tipoPersona, setTipoPersona] = useState('nat');
  const [categoria, setCategoria] = useState('penal');
  const [enviando, setEnviando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const palabraValida = nombre.trim().split(/\s+/).some((p) => p.length >= 3);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    setEnviando(true);
    setError(null);
    try {
      const { id } = await api.crearJob(nombre.trim(), tipoPersona, categoria);
      abrir(id);
    } catch (err) {
      setError(mensajeDe(err));
    } finally {
      setEnviando(false);
    }
  }

  return (
    <>
      <section className="tarjeta" aria-labelledby="nuevo">
        <div className="tarjeta-cabecera">
          <h2 id="nuevo">Nueva búsqueda</h2>
          <span className="suave">Un nombre, un tipo de persona y una categoría. El resto lo decide el motor.</span>
        </div>
        <form className="fila" onSubmit={enviar}>
          <label style={{ flex: '2 1 220px' }}>
            Nombre o razón social
            <input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="p. ej. ORTIZ" required list="sugerencias" />
            <datalist id="sugerencias">
              {SUGERENCIAS[tipoPersona].map((s) => (
                <option key={s} value={s} />
              ))}
            </datalist>
          </label>
          <label>
            Persona
            <select value={tipoPersona} onChange={(e) => setTipoPersona(e.target.value)}>
              <option value="nat">Natural</option>
              <option value="jur">Jurídica</option>
            </select>
          </label>
          <label>
            Categoría
            <select value={categoria} onChange={(e) => setCategoria(e.target.value)}>
              {CATEGORIAS.map((c) => (
                <option key={c} value={c}>
                  {categoriaLegible(c)}
                </option>
              ))}
            </select>
          </label>
          <button className="primario" disabled={enviando || !palabraValida} style={{ alignSelf: 'end' }}>
            {enviando ? 'Creando…' : 'Buscar'}
          </button>
        </form>
        {nombre !== '' && !palabraValida && <p className="suave">La Rama exige al menos una palabra de 3 letras.</p>}
        {error && <Aviso tipo="error">{error}</Aviso>}
      </section>

      <section className="tarjeta" aria-labelledby="recientes">
        <div className="tarjeta-cabecera">
          <h2 id="recientes">Búsquedas recientes</h2>
          {lista.error && <Aviso tipo="error">No se pudo cargar la lista: {lista.error}</Aviso>}
        </div>
        {lista.datos && lista.datos.length === 0 && <p className="suave">Todavía no hay búsquedas. Crea la primera arriba.</p>}
        {lista.datos && lista.datos.length > 0 && (
          <div className="tabla-envoltura">
            <table>
              <thead>
                <tr>
                  <th>Nombre</th>
                  <th>Persona</th>
                  <th>Categoría</th>
                  <th>Estado</th>
                  <th className="num">Creado</th>
                  <th className="num">Terminó</th>
                </tr>
              </thead>
              <tbody>
                {lista.datos.map((job) => (
                  <tr key={job.id} className="clicable" onClick={() => abrir(job.id)}>
                    <td>
                      <a
                        href={`#/jobs/${job.id}`}
                        onClick={(e) => {
                          e.preventDefault();
                          abrir(job.id);
                        }}
                      >
                        {job.nombre}
                      </a>
                    </td>
                    <td>{job.tipoPersona === 'nat' ? 'Natural' : 'Jurídica'}</td>
                    <td>{categoriaLegible(job.categoria)}</td>
                    <td>
                      <InsigniaJob estado={job.estado} />
                    </td>
                    <td className="num">{fechaHora(job.creadoEn)}</td>
                    <td className="num">{fechaHora(job.finalizadoEn)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
