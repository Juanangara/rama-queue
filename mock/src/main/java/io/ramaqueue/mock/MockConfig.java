package io.ramaqueue.mock;

import io.ramaqueue.mock.dataset.Dataset;
import io.ramaqueue.mock.dataset.Territorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MockConfig {

    private static final Logger log = LoggerFactory.getLogger(MockConfig.class);

    @Bean
    Dataset dataset(MockProperties propiedades) {
        long inicio = System.nanoTime();
        Dataset dataset = new Dataset(Territorio.cargar(), propiedades.seed(), propiedades.congested());
        log.info("Universo sintético listo: {} despachos, {} procesos (semilla {}, {} ms)",
                dataset.despachos().size(), dataset.totalProcesos(), propiedades.seed(),
                (System.nanoTime() - inicio) / 1_000_000);
        return dataset;
    }
}
