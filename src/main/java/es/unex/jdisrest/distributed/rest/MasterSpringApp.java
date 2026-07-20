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
 * <p>This class is <em>not</em> started via a {@code main()} method. Instead,
 * {@link es.unex.jdisrest.distributed.SteadyStateMaster} or {@link es.unex.jdisrest.distributed.GenerationalMaster} call
 * {@code SpringApplication.run(MasterSpringApp.class, ...)} programmatically
 * once the algorithm object has been constructed and registered as the active
 * singleton. This ordering guarantees that {@link MasterFacade} can resolve
 * the master instance as soon as the first HTTP request arrives.
 *
 * <p>The {@code @SpringBootApplication} annotation triggers a component scan
 * rooted at the {@code es.unex.jdisrest.distributed.rest} package, which automatically picks up
 * all controllers, the watchdog scheduler, and the facade.
 *
 * <p>{@code @EnableScheduling} activates Spring's task-scheduling infrastructure
 * required by {@link WatchdogScheduler}.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SpringBootApplication
@EnableScheduling
public class MasterSpringApp {

    /**
     * Provides a Reactor {@link Scheduler} backed by Java 21 virtual threads.
     *
     * <p>Controllers use this scheduler (injected as {@code virtualThreadScheduler})
     * to run blocking operations — such as {@link MasterFacade#claimNextTask} which
     * may block for up to 30 seconds during long-polling — without ever occupying a
     * Netty event-loop thread. Each submitted callable receives its own lightweight
     * virtual thread, so thousands of concurrent long-polls impose negligible
     * platform-thread pressure.
     *
     * <p>The scheduler is wired automatically by name into {@link TaskController}
     * via constructor injection, because Spring matches the bean name
     * {@code virtualThreadScheduler} to the constructor parameter of the same name.
     *
     * @return a {@link Scheduler} wrapping {@link Executors#newVirtualThreadPerTaskExecutor()}
     */
    @Bean
    public Scheduler virtualThreadScheduler() {
        return Schedulers.fromExecutorService(
                Executors.newVirtualThreadPerTaskExecutor(),
                "virtual-threads"
        );
    }
}
