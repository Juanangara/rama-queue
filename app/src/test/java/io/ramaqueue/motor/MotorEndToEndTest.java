package io.ramaqueue.motor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.in.ConsultarProgreso;
import io.ramaqueue.application.port.in.IniciarBusqueda;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.JobStatus;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.simulacion.MundoSintetico;
import io.ramaqueue.infrastructure.in.scheduler.ControlDeConsumidores;
import io.ramaqueue.infrastructure.out.messaging.Colas;
import io.ramaqueue.domain.model.Territorio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El motor completo, de verdad: Postgres, RabbitMQ y Redis reales en contenedores, el contexto de Spring completo y una
 * Rama Judicial falsa por HTTP que sirve el mundo sintético. Cada escenario se compara con la verdad de referencia.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MotorEndToEndTest {

    private static final int LIMITE = 60;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withStartupTimeout(Duration.ofMinutes(4));
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management")
            .withStartupTimeout(Duration.ofMinutes(4));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(4));

    static final RamaFalsa RAMA = RamaFalsa.iniciar();

    /**
     * Un único mundo para todos los escenarios: el motor expande la raíz con SU territorio, así que el territorio del
     * mundo sintético (departamentos y municipios inventados) se inyecta en lugar del real.
     */
    static final MundoSintetico MUNDO = new MundoSintetico(13, LIMITE);

    @TestConfiguration
    static class TerritorioDelMundo {
        @Bean
        @Primary
        Territorio territorioDePrueba() {
            return MUNDO.territorio();
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("rama.base-url", RAMA::urlBase);
        r.add("rama.broker-api.url", () -> "http://" + RABBIT.getHost() + ":" + RABBIT.getHttpPort());
        r.add("rama.broker-api.user", () -> "guest");
        r.add("rama.broker-api.password", () -> "guest");
        // Todo mucho más rápido que en producción para que la prueba dure segundos y no minutos.
        r.add("rama.governor.initial-in-flight", () -> "2");
        r.add("rama.governor.min-interval-ms", () -> "5");
        r.add("rama.governor.max-interval-ms", () -> "40");
        r.add("rama.breaker.cooldown-base", () -> "1s");
        r.add("rama.breaker.cooldown-max", () -> "3s");
        r.add("rama.retry.tiers", () -> "1s,2s,3s");
        r.add("rama.watchdog.stuck-after", () -> "3s");
        r.add("rama.watchdog.interval", () -> "1s");
        r.add("rama.watchdog.republish-grace", () -> "1s");
        r.add("rama.outbox.interval", () -> "100ms");
    }

    @AfterAll
    static void cerrar() {
        RAMA.close();
    }

    @Autowired
    IniciarBusqueda iniciar;
    @Autowired
    ConsultarProgreso consultar;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ControlDeConsumidores control;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @LocalServerPort
    int puerto;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limpiar() {
        control.reanudar();
        jdbc.execute("TRUNCATE request_log, proceso_hit, work_item, search_job CASCADE");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        RAMA.reiniciar();
    }

    // ─── Escenarios ──────────────────────────────────────────────────────────────

    @Test
    void unJobCompletoCoincideConLaVerdadDeReferenciaYHaceUnaPeticionPorItem() {
        MundoSintetico mundo = MUNDO;
        UUID job = lanzar(mundo);

        esperarFin(job, Duration.ofSeconds(120));

        verificarResultado(job, mundo);
        assertThat(RAMA.peticiones()).as("1 ítem = 1 petición").isEqualTo(totalDeItems(job));
        assertThat(RAMA.picoEnVuelo()).as("pico de peticiones en vuelo").isLessThanOrEqualTo(2);
        assertThat(RAMA.respuestas403()).isZero();
    }

    @Test
    void dosTrabajosSimultaneosTerminanCorrectosSinPisarseYRepartiendoElPresupuesto() {
        UUID uno = lanzar(MUNDO);
        await().atMost(Duration.ofSeconds(60)).until(() -> totalDeItems(uno) >= 30);
        UUID dos = lanzar(MUNDO); // llega con el primero ya en marcha

        esperarFin(uno, Duration.ofSeconds(240));
        esperarFin(dos, Duration.ofSeconds(240));

        verificarResultado(uno, MUNDO);
        verificarResultado(dos, MUNDO);
        assertThat(totalDeItems(dos)).as("ambos recorren el mismo árbol").isEqualTo(totalDeItems(uno));
        // Con la caché de listados el segundo trabajo se ahorra peticiones: nunca más de una por ítem.
        assertThat(RAMA.peticiones()).isLessThanOrEqualTo((int) (totalDeItems(uno) + totalDeItems(dos)));
        assertThat(RAMA.picoEnVuelo()).as("el gobernador es global: dos trabajos no duplican la presión").isLessThanOrEqualTo(2);
        assertThat(RAMA.respuestas403()).isZero();
    }

    @Test
    void unBloqueoDelServidorDetieneYReanudaElMotorSinPerderNada() {
        MundoSintetico mundo = MUNDO;
        UUID job = lanzar(mundo);
        await().atMost(Duration.ofSeconds(60)).until(() -> itemsTerminados(job) >= 20);

        RAMA.bloquearDurante(Duration.ofSeconds(4));
        esperarFin(job, Duration.ofSeconds(180));

        verificarResultado(job, mundo);
        assertThat(RAMA.respuestas403()).as("403 recibidos").isBetween(1, 12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_item WHERE job_id = ? AND attempts > 0", Long.class, job))
                .as("un bloqueo no consume intentos").isZero();
    }

    @Test
    void unMensajePerdidoEnElBrokerSeRecuperaPorReconciliacion() {
        MundoSintetico mundo = MUNDO;
        control.pausar();
        UUID job = lanzar(mundo);
        await().atMost(Duration.ofSeconds(30)).until(() -> publicados(job) >= 1);

        // El mensaje de la raíz desaparece del broker (por ejemplo, un fallo de disco): en la base sigue «publicado».
        rabbitAdmin.purgeQueue(Colas.TRABAJO_BUSQUEDA, true);
        control.reanudar();

        esperarFin(job, Duration.ofSeconds(180));
        verificarResultado(job, mundo);
    }

    @Test
    void unWorkerQueMurioConUnItemEnCursoSeRecuperaPorWatchdog() {
        MundoSintetico mundo = MUNDO;
        control.pausar();
        UUID job = lanzar(mundo);
        await().atMost(Duration.ofSeconds(30)).until(() -> publicados(job) >= 1);

        // Un worker tomó la raíz y murió: el ítem quedó EN CURSO con un reclamo viejo y su mensaje se consumió.
        jdbc.update("UPDATE work_item SET status = 'IN_PROGRESS', locked_at = now() - interval '1 hour' "
                + "WHERE job_id = ? AND parent_id IS NULL", job);
        rabbitAdmin.purgeQueue(Colas.TRABAJO_BUSQUEDA, true);
        control.reanudar();

        esperarFin(job, Duration.ofSeconds(180));
        verificarResultado(job, mundo);
    }

    // ─── API REST (fase 6) ───────────────────────────────────────────────────────

    @Test
    void laApiRestLanzaConsultaYListaResultadosConFiltrosYArbol() throws Exception {
        RAMA.usar(MUNDO);
        MundoSintetico.Esperado esperado = MUNDO.esperado();

        HttpResponse<String> creado = enviar("POST", "/api/jobs", "{\"nombre\":\"" + MundoSintetico.NOMBRE_BUSCADO
                + "\",\"tipoPersona\":\"nat\",\"categoria\":\"penal\"}");
        assertThat(creado.statusCode()).isEqualTo(202);
        UUID job = UUID.fromString(json.readTree(creado.body()).get("id").asText());
        esperarFin(job, Duration.ofSeconds(120));

        JsonNode progreso = get("/api/jobs/" + job);
        assertThat(progreso.at("/job/estado").asText()).isEqualTo(esperado.excedidos() > 0 ? "PARTIAL" : "COMPLETED");
        assertThat(progreso.get("avance").asDouble()).isEqualTo(1.0);
        assertThat(progreso.get("procesosCompletos").asLong()).isEqualTo(esperado.aceptados().size());
        assertThat(get("/api/jobs").findValuesAsText("id")).contains(job.toString());

        // Resultados: paginados, con el detalle completo y filtrables.
        JsonNode primera = get("/api/jobs/" + job + "/results?tamano=5");
        long total = esperado.aceptados().size();
        assertThat(primera.get("total").asLong()).isEqualTo(total);
        assertThat(primera.get("paginas").asLong()).isEqualTo((total + 4) / 5);
        assertThat(primera.get("elementos")).hasSize(5);
        JsonNode proceso = primera.get("elementos").get(0);
        assertThat(proceso.at("/detalle/tipoProceso").asText()).isEqualTo("Penal");
        assertThat(proceso.at("/ultimaActuacion/actuacion").asText()).isEqualTo("Auto");
        assertThat(proceso.get("sujetos").size()).isGreaterThanOrEqualTo(2);
        assertThat(proceso.get("estadoDetalle").asText()).isEqualTo("COMPLETO");
        assertThat(get("/api/jobs/" + job + "/results?texto=NO-EXISTE-NADA-ASI").get("total").asLong()).isZero();
        assertThat(get("/api/jobs/" + job + "/results?departamento=departamento").get("total").asLong()).isEqualTo(total);
        assertThat(get("/api/jobs/" + job + "/results?tamano=500").get("elementos")).hasSize((int) total);

        // Árbol: la raíz expandida y exactamente las ramas sin respuesta que la verdad de referencia predice.
        JsonNode arbol = get("/api/jobs/" + job + "/tree");
        List<JsonNode> nodos = new java.util.ArrayList<>();
        arbol.forEach(nodos::add);
        assertThat(nodos).filteredOn(n -> n.get("nivel").asText().equals("RAIZ")).singleElement()
                .satisfies(raiz -> assertThat(raiz.get("estado").asText()).isEqualTo("EXPANDED"));
        assertThat(nodos).filteredOn(n -> n.get("estado").asText().equals("EXCEEDED")).hasSize(esperado.excedidos());
        assertThat(nodos).filteredOn(n -> n.get("tipo").asText().equals("LIST")).isNotEmpty();
        assertThat(nodos).noneMatch(n -> n.get("tipo").asText().equals("DETALLE"));

        // Errores bien tratados.
        assertThat(enviar("GET", "/api/jobs/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(enviar("GET", "/api/jobs/" + UUID.randomUUID() + "/results", null).statusCode()).isEqualTo(404);
        HttpResponse<String> invalido = enviar("POST", "/api/jobs", "{\"nombre\":\"AB\",\"tipoPersona\":\"nat\",\"categoria\":\"penal\"}");
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(invalido.body()).contains("palabra");
        assertThat(enviar("POST", "/api/jobs", "{\"nombre\":\"JUAN GARCIA\",\"tipoPersona\":\"nat\",\"categoria\":\"tributario\"}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void elSseEntregaLaFotografiaInicialElAvanceYElFinDelJob() throws Exception {
        RAMA.usar(MUNDO);
        control.pausar();
        UUID job = lanzar(MUNDO);
        List<String> recibidas = new CopyOnWriteArrayList<>();
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(base() + "/api/jobs/" + job + "/events"))
                .header("Accept", "text/event-stream").GET().build();
        Thread lector = new Thread(() -> {
            try (var lineas = http.send(peticion, HttpResponse.BodyHandlers.ofLines()).body()) {
                lineas.filter(l -> l.startsWith("event:") || l.startsWith("data:")).forEach(recibidas::add);
            } catch (IOException | InterruptedException | RuntimeException e) {
                // el cliente se cierra al terminar la prueba
            }
        });
        lector.setDaemon(true);
        lector.start();

        await().atMost(Duration.ofSeconds(30)).until(() -> recibidas.contains("event:progreso"));
        assertThat(recibidas).anyMatch(l -> l.startsWith("data:") && l.contains("\"RUNNING\""));

        control.reanudar();
        await().atMost(Duration.ofSeconds(120)).until(
                () -> recibidas.stream().anyMatch(l -> l.startsWith("data:") && l.contains("JOB_FINALIZADO")));
        esperarFin(job, Duration.ofSeconds(60));
        await().atMost(Duration.ofSeconds(10)).until(
                () -> recibidas.stream().anyMatch(l -> l.startsWith("data:") && l.contains("\"estado\":\"PARTIAL\"")));
        assertThat(recibidas.stream().filter("event:progreso"::equals).count()).as("fotografías de avance").isGreaterThan(1);
        assertThat(enviar("GET", "/api/jobs/" + UUID.randomUUID() + "/events", null).statusCode()).isEqualTo(404);
    }

    @Test
    void cancelarUnJobLoDetieneSinDejarTrabajoActivoNiGastarPeticiones() throws Exception {
        RAMA.usar(MUNDO);
        control.pausar();
        Thread.sleep(2_500); // el vigilante detiene los consumidores en su siguiente ciclo
        UUID job = lanzar(MUNDO);
        await().atMost(Duration.ofSeconds(30)).until(() -> publicados(job) >= 1);

        assertThat(enviar("POST", "/api/jobs/" + job + "/cancel", null).statusCode()).isEqualTo(200);

        JsonNode cancelado = get("/api/jobs/" + job);
        assertThat(cancelado.at("/job/estado").asText()).isEqualTo("CANCELLED");
        assertThat(cancelado.get("itemsPendientes").asLong()).isZero();
        control.reanudar();
        Thread.sleep(3_000);
        assertThat(get("/api/jobs/" + job).at("/job/estado").asText()).isEqualTo("CANCELLED");
        assertThat(RAMA.peticiones()).as("un job cancelado no gasta peticiones").isZero();
        assertThat(enviar("POST", "/api/jobs/" + job + "/cancel", null).statusCode()).isEqualTo(409);
        assertThat(enviar("POST", "/api/jobs/" + UUID.randomUUID() + "/cancel", null).statusCode()).isEqualTo(404);
    }

    @Test
    void losItemsFallidosLlegaranALaDlqYSeReencolanHastaCompletarElJob() throws Exception {
        RAMA.romperDetalle(true);
        UUID job = lanzar(MUNDO);
        esperarFin(job, Duration.ofSeconds(120));

        int aceptados = MUNDO.esperado().aceptados().size();
        JsonNode antes = get("/api/jobs/" + job);
        assertThat(antes.at("/job/estado").asText()).isEqualTo("PARTIAL");
        assertThat(antes.at("/itemsPorEstado/FAILED").asLong()).as("un DETALLE fallido por proceso aceptado").isEqualTo(aceptados);
        assertThat(antes.get("procesosCompletos").asLong()).isZero();
        JsonNode dlq = get("/api/infra/dlq?maximo=200");
        assertThat(dlq).hasSize(aceptados);
        assertThat(dlq.get(0).get("motivo").asText()).contains("404");
        assertThat(dlq.get(0).get("tipo").asText()).isEqualTo("DETALLE");

        RAMA.romperDetalle(false);
        JsonNode reencolado = json.readTree(enviar("POST", "/api/jobs/" + job + "/requeue", null).body());
        assertThat(reencolado.get("reencolados").asInt()).isEqualTo(aceptados);
        assertThat(reencolado.get("retiradosDeLaDlq").asInt()).isEqualTo(aceptados);

        esperarFin(job, Duration.ofSeconds(120));
        verificarResultado(job, MUNDO);
        assertThat(get("/api/infra/dlq")).as("la DLQ queda limpia").isEmpty();
    }

    @Test
    void losEndpointsDeInfraestructuraYAdministracionRespondenConDatosReales() throws Exception {
        JsonNode colas = get("/api/infra/colas");
        assertThat(colas.findValuesAsText("nombre")).contains("search.fetch", "detail.fetch", "search.dlq", "search.retry.1s");
        assertThat(colas).filteredOn(c -> c.get("nombre").asText().equals("search.fetch"))
                .singleElement().satisfies(c -> assertThat(c.get("consumidores").asInt()).isPositive());

        JsonNode governor = get("/api/infra/governor");
        assertThat(governor.at("/breaker/estado").asText()).isEqualTo("CERRADO");
        assertThat(governor.at("/limite/maxEnVuelo").asInt()).isEqualTo(2);
        assertThat(governor.at("/limite/intervaloBaseMs").asInt()).isEqualTo(5);
        assertThat(governor.get("consumidoresPausados").asBoolean()).isFalse();
        assertThat(get("/api/infra/peticiones?minutos=5").isArray()).isTrue();

        assertThat(json.readTree(enviar("POST", "/api/admin/consumidores/pausar", null).body()).get("pausados").asBoolean()).isTrue();
        assertThat(get("/api/infra/governor").get("consumidoresPausados").asBoolean()).isTrue();
        control.reanudar();
        assertThat(enviar("POST", "/api/admin/consumidores/concurrencia?listener=search-listener&consumidores=2", null)
                .statusCode()).isEqualTo(200);
        assertThat(enviar("POST", "/api/admin/consumidores/concurrencia?listener=otro&consumidores=2", null).statusCode()).isEqualTo(400);
        assertThat(enviar("POST", "/api/admin/workers/caida", null).statusCode()).as("sin perfil demo").isEqualTo(403);
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────────

    private String base() {
        return "http://localhost:" + puerto;
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String cuerpo) throws IOException, InterruptedException {
        HttpRequest.Builder constructor = HttpRequest.newBuilder(URI.create(base() + ruta)).header("Content-Type", "application/json");
        constructor.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        return http.send(constructor.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String ruta) throws IOException, InterruptedException {
        HttpResponse<String> respuesta = enviar("GET", ruta, null);
        assertThat(respuesta.statusCode()).as("GET %s -> %s", ruta, respuesta.body()).isEqualTo(200);
        return json.readTree(respuesta.body());
    }

    private UUID lanzar(MundoSintetico mundo) {
        RAMA.usar(mundo);
        return iniciar.iniciar(MundoSintetico.NOMBRE_BUSCADO, TipoPersona.NATURAL, Categoria.PENAL);
    }

    private void esperarFin(UUID job, Duration maximo) {
        await().atMost(maximo).pollInterval(Duration.ofMillis(250))
                .until(() -> consultar.progreso(job).orElseThrow().job().estado().esFinal());
    }

    private void verificarResultado(UUID job, MundoSintetico mundo) {
        MundoSintetico.Esperado esperado = mundo.esperado();
        var progreso = consultar.progreso(job).orElseThrow();

        String diagnostico = "conteos=" + progreso.conteos().porEstado() + " esperado[aceptados=" + esperado.aceptados().size()
                + ", excedidos=" + esperado.excedidos() + "] pruebaRama[peticiones=" + RAMA.peticiones() + ", 403=" + RAMA.respuestas403() + "]";
        assertThat(progreso.job().estado()).as(diagnostico)
                .isEqualTo(esperado.excedidos() > 0 ? JobStatus.PARTIAL : JobStatus.COMPLETED);
        assertThat(progreso.conteos().cantidad(EstadoTrabajo.FAILED)).as("ítems fallidos").isZero();
        assertThat(progreso.conteos().cantidad(EstadoTrabajo.EXCEEDED)).as("ramas sin respuesta").isEqualTo(esperado.excedidos());
        assertThat(progreso.conteos().activos()).isZero();

        Set<Long> aceptados = new HashSet<>(jdbc.queryForList(
                "SELECT id_proceso FROM proceso_hit WHERE job_id = ? AND detail_status IN ('PENDIENTE','COMPLETO')", Long.class, job));
        assertThat(aceptados).as("procesos aceptados").isEqualTo(esperado.aceptados());
        assertThat(progreso.procesosCompletos()).as("procesos con detalle completo").isEqualTo(esperado.aceptados().size());
    }

    private long totalDeItems(UUID job) {
        return jdbc.queryForObject("SELECT count(*) FROM work_item WHERE job_id = ?", Long.class, job);
    }

    private long itemsTerminados(UUID job) {
        return jdbc.queryForObject("SELECT count(*) FROM work_item WHERE job_id = ? AND status NOT IN ('PENDING','IN_PROGRESS')",
                Long.class, job);
    }

    private long publicados(UUID job) {
        return jdbc.queryForObject("SELECT count(*) FROM work_item WHERE job_id = ? AND published_at IS NOT NULL", Long.class, job);
    }

}
