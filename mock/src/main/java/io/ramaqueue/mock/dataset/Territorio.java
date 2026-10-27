package io.ramaqueue.mock.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Departamentos y municipios (códigos DANE) leídos de {@code territorio.json}. */
public record Territorio(List<Departamento> departamentos) {

    public record Departamento(String nombre, String codigo, List<Municipio> municipios) {
    }

    public record Municipio(String nombre, String codigo) {
    }

    public static Territorio cargar() {
        try (InputStream in = Territorio.class.getResourceAsStream("/territorio.json")) {
            if (in == null) {
                throw new IllegalStateException("No se encontró territorio.json en el classpath");
            }
            List<Departamento> departamentos = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            return new Territorio(departamentos);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer territorio.json", e);
        }
    }
}
