package io.ramaqueue.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.domain.model.Territorio;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Carga los departamentos y municipios (códigos DANE) del recurso {@code territorio.json}. */
final class TerritorioLoader {

    private TerritorioLoader() {
    }

    static Territorio cargar(ObjectMapper json) {
        try (InputStream in = TerritorioLoader.class.getResourceAsStream("/territorio.json")) {
            if (in == null) {
                throw new IllegalStateException("No se encontró territorio.json en el classpath");
            }
            List<Territorio.Departamento> departamentos = json.readValue(in, new TypeReference<>() {
            });
            return new Territorio(departamentos);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer territorio.json", e);
        }
    }
}
