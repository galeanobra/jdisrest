package es.unex.jdisrest.distributed.rest;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

/**
 * Spring Boot application entry point for the master's REST layer.
 *
 * <p>This class is <em>not</em> started via a {@code main()} method. Instead, the
 * constructor of {@link es.unex.jdisrest.distributed.AbstractMaster} (which every master
 * calls first) runs it programmatically, before the master has registered itself as the
 * active singleton and before the subclass constructors have built the algorithm's state.
 * Requests that arrive in that window are harmless: {@link MasterFacade} has no master
 * yet, or one that is not ready ({@link MasterFacade#isReady()}), and the endpoints answer
 * accordingly (see {@code AbstractMaster.isReady()}). {@code AbstractMaster.shutdown()}
 * closes the application again.
 *
 * <p>The {@code @SpringBootApplication} annotation triggers a component scan
 * rooted at the {@code es.unex.jdisrest.distributed.rest} package, which automatically picks up
 * all controllers and the watchdog scheduler ({@link MasterFacade} is a static
 * utility, not a bean).
 *
 * <p>{@code @EnableScheduling} activates Spring's task-scheduling infrastructure
 * required by {@link WatchdogScheduler}.
 *
 * <p>The Spring properties it starts with are listed in
 * {@code AbstractMaster.defaultServerProperties()}; any of them, and any other Spring
 * property, can be set with a {@code -D} system property, an environment variable or an
 * {@code application.properties} file.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SpringBootApplication
@EnableScheduling
public class MasterSpringApp {

    /**
     * Provides a Reactor {@link Scheduler} backed by virtual threads.
     *
     * <p>{@link TaskController} uses this scheduler (injected as {@code virtualThreadScheduler})
     * to run blocking operations — such as {@link MasterFacade#claimNextTask}, which may
     * block for up to {@link es.unex.jdisrest.util.Timings#TASK_LONGPOLL_S} seconds during
     * long-polling, or wait for the master's locks while the algorithm thread updates the
     * population — without ever occupying a Netty event-loop thread. Each submitted callable
     * receives its own lightweight virtual thread, so thousands of concurrent long-polls
     * impose negligible platform-thread pressure, and a result never waits for a free thread
     * behind long-polls.
     *
     * <p>The scheduler is injected into {@link TaskController} by type (it is the only
     * {@link Scheduler} bean), and disposed, which shuts its executor down, when the
     * application context closes.
     *
     * @return a {@link Scheduler} wrapping {@link Executors#newVirtualThreadPerTaskExecutor()}
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler virtualThreadScheduler() {
        return Schedulers.fromExecutorService(
                Executors.newVirtualThreadPerTaskExecutor(),
                "virtual-threads"
        );
    }
}
