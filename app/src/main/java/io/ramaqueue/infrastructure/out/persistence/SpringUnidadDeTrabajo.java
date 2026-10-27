package io.ramaqueue.infrastructure.out.persistence;

import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public class SpringUnidadDeTrabajo implements UnidadDeTrabajo {

    private final TransactionTemplate plantilla;

    public SpringUnidadDeTrabajo(PlatformTransactionManager administrador) {
        this.plantilla = new TransactionTemplate(administrador);
    }

    @Override
    public <T> T ejecutar(Supplier<T> trabajo) {
        return plantilla.execute(estado -> trabajo.get());
    }
}
