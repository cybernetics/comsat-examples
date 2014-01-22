/*
 * Copyright (C) 2013 Parallel Universe Software Co.
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package co.paralleluniverse.spaceships;

import co.paralleluniverse.actors.ActorRef;
import co.paralleluniverse.actors.ActorSpec;
import co.paralleluniverse.actors.behaviors.Supervisor;
import co.paralleluniverse.actors.behaviors.SupervisorActor;
import co.paralleluniverse.common.monitoring.Metrics;
import co.paralleluniverse.common.monitoring.MonitorType;
import co.paralleluniverse.comsat.webactors.WebDataMessage;
import co.paralleluniverse.data.record.Record;
import co.paralleluniverse.fibers.*;
import co.paralleluniverse.spacebase.AABB;
import co.paralleluniverse.spacebase.quasar.SpaceBase;
import co.paralleluniverse.spacebase.quasar.SpaceBaseBuilder;
import co.paralleluniverse.strands.concurrent.Phaser;
import com.codahale.metrics.Counter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jsr166e.LongAdder;

public class Spaceships {
    public static Spaceships spaceships;
    public static final int POSTPONE_GLPORT_UNTIL_SB_CYCLE_UNDER_X_MILLIS = 250;
    public final SpaceBase<Record<SpaceshipState>> sb;
    public final RandSpatial random;
    private final int N;
    public final AABB bounds;
    public final boolean extrapolate;
    public final double speedVariance;
    public final double range;
    private final Phaser phaser;
    private File metricsDir;
    private PrintStream configStream;
    private PrintStream timeStream;
    final LongAdder spaceshipsCycles = new LongAdder();
    private long cycleStart;
    private Supervisor supervisor;
    private AtomicInteger controlledAmmount = new AtomicInteger();
    public final int players;
    private final static Counter counterMetric = Metrics.counter("players");

    public Spaceships(Properties props) throws Exception {
        if (props.getProperty("parallelism") != null)
            System.setProperty("co.paralleluniverse.fibers.DefaultFiberPool.parallelism", props.getProperty("parallelism"));
        final int parallelism = ((FiberForkJoinScheduler) DefaultFiberScheduler.getInstance()).getForkJoinPool().getParallelism();// Integer.parseInt(props.getProperty("parallelism", "2"));        
        double b = Double.parseDouble(props.getProperty("world-length", "20000"));
        this.bounds = AABB.create(-b / 2, b / 2, -b / 2 * 0.7, b / 2 * 0.7, -b / 2, b / 2);
        this.N = Integer.parseInt(props.getProperty("N", "10000"));
        this.players = Integer.parseInt(props.getProperty("players", "500"));
        this.speedVariance = Double.parseDouble(props.getProperty("speed-variance", "1"));
        this.range = Double.parseDouble(props.getProperty("radar-range", "10"));
        this.extrapolate = Boolean.parseBoolean(props.getProperty("extrapolate", "true"));

        this.phaser = Boolean.parseBoolean(props.getProperty("phaser", "false")) ? new Phaser() : null;

        if (props.getProperty("dir") != null) // collect performance metrics in csv files
            createMetricsFiles(props);

        println("World bounds: " + bounds);
        println("N: " + N);
        println("Parallelism: " + parallelism);
        println("Phaser: " + (phaser != null));
        println("Extrapolate: " + extrapolate);
        println();

        this.random = new RandSpatial();

        this.sb = initSpaceBase(props);
    }

    public ActorRef<Object> getSpaceship(String id) throws SuspendExecution, InterruptedException {
        if (supervisor != null)
            return supervisor.getChild(id);
        return null;
    }

    private void createMetricsFiles(Properties props) throws FileNotFoundException {
        this.metricsDir = new File(System.getProperty("user.home") + "/" + props.getProperty("dir"));

        if (metricsDir.isDirectory()) {
            for (File file : metricsDir.listFiles())
                file.delete();
        }
        metricsDir.mkdirs();

        final File configFile = new File(metricsDir, "config.txt");
        this.configStream = new PrintStream(new FileOutputStream(configFile), true);

        final File timeFile = new File(metricsDir, "times.csv");
        this.timeStream = new PrintStream(new FileOutputStream(timeFile), true);
    }

    public co.paralleluniverse.spacebase.SpaceBase<Record<SpaceshipState>> getPlainSpaceBase() {
        return co.paralleluniverse.spacebase.SpaceBaseBuilder.from(sb);
    }

    /**
     * reads properties file and creates a SpaceBase instance with the requested properties.
     */
    private SpaceBase<Record<SpaceshipState>> initSpaceBase(Properties props) {
        final boolean optimistic = Boolean.parseBoolean(props.getProperty("optimistic", "true"));
        final int optimisticHeight = Integer.parseInt(props.getProperty("optimistic-height", "1"));
        final int optimisticRetryLimit = Integer.parseInt(props.getProperty("optimistic-retry-limit", "3"));
        final boolean compressed = Boolean.parseBoolean(props.getProperty("compressed", "false"));
        final boolean singlePrecision = Boolean.parseBoolean(props.getProperty("single-precision", "false"));
        final int nodeWidth = Integer.parseInt(props.getProperty("node-width", "10"));

        println("SpaceBase properties");
        println("Optimistic: " + optimistic);
        println("Optimistic height: " + optimisticHeight);
        println("Optimistic retry limit: " + optimisticRetryLimit);
        println("Node width: " + nodeWidth);
        println("Compressed: " + compressed);
        println("Single precision: " + singlePrecision);
        println();

        SpaceBaseBuilder builder = new SpaceBaseBuilder();

        builder.setQueueBackpressure(1000);

        if (optimistic)
            builder.setOptimisticLocking(optimisticHeight, optimisticRetryLimit);
        else
            builder.setPessimisticLocking();

        builder.setDimensions(2);

        builder.setSinglePrecision(singlePrecision).setCompressed(compressed);
        builder.setNodeWidth(nodeWidth);

        builder.setMonitoringType(MonitorType.JMX);
        if (metricsDir != null) {
            com.codahale.metrics.CsvReporter.forRegistry(Metrics.registry())
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .build(metricsDir)
                    .start(1, TimeUnit.SECONDS);
        }
        final SpaceBase<Record<SpaceshipState>> space = builder.build("base1");
        return space;
    }

    /**
     * Main loop: loops over all spaceships and initiates each spaceship's actions. Simulates an IO thread receiving commands over the net.
     */
    public void run() throws Exception {
        supervisor = new SupervisorActor(SupervisorActor.RestartStrategy.ONE_FOR_ONE) {
            @Override
            protected void init() throws InterruptedException, SuspendExecution {
                for (int i = 0; i < N; i++)
                    addChild(new Supervisor.ChildSpec("ship-" + i, Supervisor.ChildMode.PERMANENT, 5, 1, TimeUnit.SECONDS, 3,
                            ActorSpec.of(Spaceship.class, Spaceships.this, i, phaser)));
            }
        }.spawn();
    }

    public ActorRef<Object> spawnControlledSpaceship(ActorRef<WebDataMessage> controller, String name) {
        if (controlledAmmount.incrementAndGet() > players) {
            controlledAmmount.decrementAndGet();
            return null;
        }
        counterMetric.inc();
        final Spaceship spaceship = new Spaceship(this, N + 1, phaser, controller);
        spaceship.setName(name);
        return spaceship.spawn();
    }

    public void notifyControlledSpaceshipDied() {
        counterMetric.dec();
        controlledAmmount.decrementAndGet();
    }

    public int getN() {
        return N;
    }

    public AtomicInteger getControlledAmmount() {
        return controlledAmmount;
    }

    public void mrun() throws Exception {
        Thread.sleep(5000); // wait for things to optimize a bit.

        if (timeStream != null)
            timeStream.println("# time, millis, millis1, millis0");

        if (true || phaser == null) {
            long prevTime = System.nanoTime();
            for (int k = 0;; k++) {
                Thread.sleep(1000);
//                ActorRef<Object> child = sup.getChild("ship-1");
                long cycles = spaceshipsCycles.sumThenReset();
                long now = System.nanoTime();

                double seconds = (double) (now - prevTime) * 1e-9;
                double frames = (double) cycles / (double) N;

                double fps = frames / seconds;
                System.out.println(k + "\tRATE: " + fps + " fps");
                if (timeStream != null)
                    timeStream.println(k + "," + fps);

                prevTime = now;
            }
        } else {
            for (int k = 0;; k++) {
                cycleStart = System.nanoTime();

                phaser.awaitAdvance(k);
                //phaser.arriveAndAwaitAdvance();

                float millis = millis(cycleStart);
                if (timeStream != null)
                    timeStream.println(k + "," + millis);

                if (millis(cycleStart) < 10) // don't work too hard: if the cycle has taken less than 10 millis, wait a little.
                    Thread.sleep(10 - (int) millis(cycleStart));

                millis = millis(cycleStart);

                System.out.println("CYCLE: " + millis + " millis ");
            }
        }
    }

    public long now() {
        return System.currentTimeMillis();
    }

    private float millis(long nanoStart) {
        return (float) (System.nanoTime() - nanoStart) / 1000000;
    }

    private void println() {
        println("");
    }

    private void println(String str) {
        if (configStream != null)
            configStream.println(str);
        System.out.println(str);
    }
}
