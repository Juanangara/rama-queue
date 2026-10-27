package io.ramaqueue.domain.model;

import java.util.List;

/** Departamentos y municipios (códigos DANE): los niveles estáticos del drill-down. */
public record Territorio(List<Departamento> departamentos) {

    public record Departamento(String nombre, String codigo, List<Municipio> municipios) {
    }

    public record Municipio(String nombre, String codigo) {
    }

    public Territorio {
        departamentos = List.copyOf(departamentos);
    }

    public List<Municipio> municipiosDe(String codigoDepartamento) {
        return departamentos.stream()
                .filter(d -> d.codigo().equals(codigoDepartamento))
                .findFirst()
                .map(Departamento::municipios)
                .orElse(List.of());
    }
}
