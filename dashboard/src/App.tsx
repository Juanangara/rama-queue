import { useEffect, useState } from 'react';
import { Infra } from './Infra';
import { JobDetail } from './JobDetail';
import { Jobs } from './Jobs';

type Ruta = { pantalla: 'jobs' } | { pantalla: 'job'; id: string } | { pantalla: 'infra' };

function leerRuta(): Ruta {
  const hash = window.location.hash.replace(/^#\/?/, '');
  if (hash === 'infra') return { pantalla: 'infra' };
  const coincidencia = /^jobs\/([0-9a-f-]{36})$/i.exec(hash);
  return coincidencia ? { pantalla: 'job', id: coincidencia[1] } : { pantalla: 'jobs' };
}

function ir(destino: string) {
  window.location.hash = destino;
}

export function App() {
  const [ruta, setRuta] = useState<Ruta>(leerRuta);
  useEffect(() => {
    const alCambiar = () => setRuta(leerRuta());
    window.addEventListener('hashchange', alCambiar);
    return () => window.removeEventListener('hashchange', alCambiar);
  }, []);

  const host = window.location.hostname;
  const enJobs = ruta.pantalla !== 'infra';

  return (
    <>
      <header className="cabecera">
        <div className="cabecera-interior">
          <span className="marca">rama-queue</span>
          <nav className="pestanas" aria-label="Secciones">
            <button className="pestana" aria-current={enJobs ? 'page' : undefined} onClick={() => ir('/')}>
              Búsquedas
            </button>
            <button className="pestana" aria-current={!enJobs ? 'page' : undefined} onClick={() => ir('/infra')}>
              Infraestructura
            </button>
          </nav>
          <div className="enlaces">
            <a href={`http://${host}:15672`} target="_blank" rel="noreferrer">
              RabbitMQ ↗
            </a>
            <a href={`http://${host}:3000`} target="_blank" rel="noreferrer">
              Grafana ↗
            </a>
          </div>
        </div>
      </header>
      <main>
        {ruta.pantalla === 'jobs' && <Jobs abrir={(id) => ir(`/jobs/${id}`)} />}
        {ruta.pantalla === 'job' && <JobDetail key={ruta.id} id={ruta.id} volver={() => ir('/')} />}
        {ruta.pantalla === 'infra' && <Infra />}
      </main>
    </>
  );
}
