package io.deephaven.demo;

import io.deephaven.base.Lazy;
import io.deephaven.demo.deploy.*;
import io.smallrye.common.constraint.NotNull;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.mutiny.core.Vertx;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static io.deephaven.demo.NameConstants.*;
import static io.deephaven.demo.deploy.GoogleDeploymentManager.*;

/**
 * DomainController:
 * <p>
 * <p> The brains enforcing our cluster management policies via live machine-to-dns-to-ip mappings.
 * <p>
 * <p> This class reads in data from google, and uses that to glue together a set of IP addresses {@link IpPool},
 * <p> to DNS records {@link DnsPool}.
 * <p>
 * Created by James X. Nelson (James@WeTheInter.net) on 25/09/2021 @ 2:25 a.m..
 */
public class ClusterController {

    private static final Logger LOG = Logger.getLogger(ClusterController.class);
    private static final Executor exec = Executors.newCachedThreadPool(new ThreadFactory() {
        ThreadFactory realFactory = Executors.defaultThreadFactory();
        @Override
        public Thread newThread(@org.jetbrains.annotations.NotNull final Runnable r) {
            final Thread thread = realFactory.newThread(r);
            // we don't want to block the jvm on our background tasks
            thread.setDaemon(true);
            return thread;
        }
    });

    private final GoogleDeploymentManager manager;
    private final IpPool ips;
    private final MachinePool machines;
    private final CountDownLatch latch;
    private final OkHttpClient client;
    private Lazy<Boolean> leader;
    private static ZoneId TZ_NY = ZoneOffset.of("-6");
    private static LocalTime BIZ_START = LocalTime.of(6, 0);
    // We are using NY timezone, but want to cover all N.A. business hours, so our end is 9pm NY
    private static LocalTime BIZ_END = LocalTime.of(21, 0);
    private long checkLatency = 1;

    public ClusterController() {
        this(new GoogleDeploymentManager("/tmp"));
    }
    public ClusterController(@NotNull final GoogleDeploymentManager manager) {
        this(manager, true);
    }
    public ClusterController(@NotNull final GoogleDeploymentManager manager, boolean loadMachines) {
        this.manager = manager;
        this.client = new OkHttpClient();
        latch = new CountDownLatch(loadMachines ? 4 : 3);
        if (manager == null) {
            throw new NullPointerException("Manager cannot be null");
        }
        this.ips = manager.getIpPool();
        this.machines = new MachinePool();

        resetLeader();

        // resolve the leader off-thread, since there's no need to block now
        setTimer("Leader Check", ()-> {
            if (leader.get()) {
                LOG.info("We are the leader!");
            } else {
                LOG.info("We are not the leader.");
            }
        });
        // always load IPs... we want them in cases when we manually create named machines
        setTimer("Load Used IPs", this::loadIpsUsedInitial); // load used first, so we can figure out machine IPs
        setTimer("Load Unused IPs", this::loadIpsUnusedInitial);
        setTimer("Load Domains", this::loadDomainsInitial);
        if (loadMachines) {
            // don't load machines or start any other controller threads if we are manually creating machines (ImageDeployer)
            setTimer("Load Machines", this::loadMachinesInitial);
            setTimer("Wait until loaded", ()->{
                waitUntilReady();
                // a little extra delay: give user a chance to request a machine before we possibly clean it up!
                setTimer("Refresh state", ()-> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        checkState();
                    } finally {
                        monitorLoop();
                    }
                });
            });
        }
    }

    private void resetLeader() {
        leader = new Lazy<>(()->{
            try {
                final Execute.ExecutionResult result = Execute.executeQuiet("bash", "-c",
                        "[[ $(dig +short " + DOMAIN + ") == $(dig +short $(hostname)." + DOMAIN + ") ]] && echo leader || echo not");
                return "leader".equals(result.out.trim());
            } catch (IOException | InterruptedException e) {
                LOG.error("Unable to tell if we are leader", e);
                return false;
            }
        });
    }

    public static void setTimer(String name, Callable<?> r) {
        setTimer(name, ()-> {
            try {
                r.call();
            } catch (Exception e) {
                LOG.error("Error on thread " + name, e);
            }
        });
    }
    public static void setTimer(String name, Runnable r) {
        exec.execute(()-> {
            Thread.currentThread().setName(name);
            try {
                r.run();
            } catch (Throwable t) {
                LOG.error("Unexpected error occurred running " + name, t);
                throw t;
            }
        });
    }

    private static final String TIME_FMT = "yyyyMMdd-HHmmss";
    private static final DateTimeFormatter fmt =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyyMMdd")
                    .appendLiteral("-")
                    .appendPattern("HHmmss")
                    .toFormatter();

    public static long parseTime(String lease, final String debugString) {
        final LocalDateTime time;
        if (!lease.startsWith("20")) {
            // temporary fix so we don't have to throw away all our machines
            lease = "20" + lease;
        }
        try {
            if (!lease.startsWith("202")) {
                LOG.errorf("INVALID LEASE FORMAT: \"%s\" for %s must start with 202 unless it's the 2030s by now", lease, debugString);
                // purposely set this expiry way in the past.
                return System.currentTimeMillis() - 500_000_000L;
            }
            time = LocalDateTime.from(fmt.parse(lease));
        } catch (Throwable t) {
            throw new IllegalArgumentException("Time string \"" + lease + "\" invalid, should match: " + TIME_FMT, t);
        }
        final ZonedDateTime sameLocal = time.atZone(TZ_NY);
        final Instant localInstant = sameLocal.toInstant();
        //noinspection UnnecessaryLocalVariable
        final long epochMilli = localInstant.toEpochMilli();
        return epochMilli;
    }
    public static String toTime(long timestamp) {
        return fmt.format(Instant.ofEpochMilli(timestamp).atZone(TZ_NY));
    }

    private void monitorLoop() {
        final Vertx vx = Vertx.vertx();
        vx.setTimer(TimeUnit.MINUTES.toMillis(checkLatency), t->{
            setTimer("Monitor Loop", ()->{
                try {
                    checkState();
                } finally {
                    monitorLoop();
                }
            });
        });
    }

    private synchronized void checkState() {
        // make sure to re-check if we are still the leader.
        // This will prevent an old controller from acting like the leader when a new one is promoted
        resetLeader();
        setTimer("Check IPs", ()->{
            loadIpsUsed();
            loadIpsUnused();
        });
        loadMachines();
        // check for machines that have exceeded their limit, reboot them, and then put them back in the usable pool
        Set<Machine> runningMachines = new ConcurrentHashSet<>();
        Set<Machine> availableMachines = new ConcurrentHashSet<>();
        Set<Machine> offlineMachines = new ConcurrentHashSet<>();
        Set<Machine> usedMachines = new ConcurrentHashSet<>();
        machines.getAllMachines().forEach(machine -> {
            if (machine.isOnline()) {
                runningMachines.add(machine);
                if (machine.isInUse()) {
                    usedMachines.add(machine);
                } else {
                    availableMachines.add(machine);
                }
            } else {
                machine.setInUse(false);
                offlineMachines.add(machine);
            }
            if (machine.isInUse()) {
                if (machine.getExpiry() > 0 && machine.getExpiry() < System.currentTimeMillis()) {
                    // machine is past expiry... lets turn this box off, unless it's version is old, in which case, delete it
                    turnOff(machine);
                }
            }
        });
        int numRunning = runningMachines.size();
        int poolSize = getPoolSize();
        int numBuffer = getPoolBuffer();
        LOG.info("Done checking state:\n" +
                runningMachines.size() + " running machines\n" +
                offlineMachines.size() + " offline machines\n" +
                usedMachines.size() + " used machines\n" +
                availableMachines.size() + " available machines\n" +
                poolSize + " or more running machines desired\n" +
                numBuffer + " available machines desired\n");
        if (!leader.get()) {
            LOG.info("We are not the leader, refusing to alter the machine pool");
            return;
        }
        if (numRunning < poolSize){
            // not enough nodes are running... turn some on!
            for (Machine next : offlineMachines) {
                if (!next.isOnline()) {
                    numRunning++;
                    next.setOnline(true);
                    // purposely don't parallelize: google will bark at us if we scale up too fast
                    try {
                        manager.turnOn(next);
                        availableMachines.add(next);
                    } catch (Exception e) {
                        LOG.error("Error turning on machine " + next, e);
                    }
                    if (numRunning >= poolSize) {
                        break;
                    }
                }
            }
            // if fewer than needed nodes running, turn things on
            // if we don't have enough machines, add however many machines we need to fill pool
            while (numRunning < poolSize) {
                if (numRunning >= getMaxPoolSize()) {
                    LOG.error("There are already " + numRunning + " running instances, and max size is " + getMaxPoolSize());
                } else {
                    LOG.warn("Only " + numRunning + " machines, but want " + poolSize + "; adding a new machine");
                    numRunning++;
                    final Machine newMachine = requestMachine(NameGen.newName(), false);
                    availableMachines.add(newMachine);
                    runningMachines.add(newMachine);
                }
            }
        }
        if (availableMachines.size() < numBuffer) {
            while (availableMachines.size() < getPoolBuffer()) {
                LOG.warn("Only " + availableMachines.size() + " machines are running and unusued, but want " + getPoolBuffer() + " available machines; adding a new machine");
                final Machine newMachine = requestMachine(NameGen.newName(), false);
                availableMachines.add(newMachine);
                runningMachines.add(newMachine);
            }
        } else if (numRunning > poolSize) {
            LOG.info(numRunning + " running machines > " + poolSize + " preferred pool size; trying to shut down extra machines");
            // if more than needed nodes are running, turn off any nodes not servicing clients
            int numShut = 0;
            for (Machine next : runningMachines) {
                if (next.getExpiry() < System.currentTimeMillis()) {
                    next.setInUse(false);
                }
                if (next.isInUse()) {
                    final long millis = next.getExpiry() - System.currentTimeMillis();
                    final long minutes = TimeUnit.MILLISECONDS.toMinutes(millis); // rounds off millis
                    LOG.infof("%s (%s) expires in %s minutes", next.getHost(), next.getDomainName(), 1 + minutes); // +1 b/c we rounded down already
                    if (millis > getSessionTtl()) {
                        // this should not happen once we get all existing machine labels fixed up
                        LOG.errorf("%s has an expiry (%s minutes) greater than session TTL (%s minutes)!", next.getHost(), minutes, TimeUnit.MILLISECONDS.toMinutes(getSessionTtl()));
                        fixLease(next);
                    }
                } else {
                    // leave a buffer of unused machines running, even if we've exceeded pool size
                    if (numBuffer--<=0) {
                        LOG.info("Turning off unneeded machine " + next.getHost());
                        numRunning--;
                        numShut++;
                        availableMachines.remove(next);
                        turnOff(next);
                        if (numRunning <= poolSize) {
                            break;
                        }
                    }
                }
            }
            LOG.info("Shut off " + numShut + " machines; warm-machines available: " + availableMachines.size());

        }

    }

    private void turnOff(final Machine machine) {
        machine.setOnline(false);
        boolean validVersion = isValidVersion(machine);
        if (!validVersion) {
            LOG.infof("Machine %s had incorrect version %s", machine, machine.getVersion());
            // immediately remove this machine from rotation, so we don't consider it beyond the scope of this method.
            machines.removeMachine(machine);
            // declare the machine as in-use, so any methods with a reference to it won't try to use it.
            machine.setInUse(true);
        }
        // get off the fork-join pool to do IO:
        setTimer("Decommission " + machine.getHost(), ()-> {
            try {
                // TODO: ping the machine and have a procedure that warns the user this is going to happen:
                //  also, we should be hooking up a new DNS name to the machine and deleting the one that is in use
                if (validVersion) {
                    gcloud(true, "instances", "stop", "-q", machine.getHost());
                    machine.setInUse(false);
                    machine.setOnline(false);
                    // setup new DNS for next user
                    manager.replaceDNS(this, machine);
                    // put the machine back in the pool
                    machines.addMachine(machine);
                } else {
                    // only the leader gets to delete instances.
                    if (leader.get()) {
                        if (VERSION.compareTo(machine.getVersion()) > 0) {
                            // only delete versions older than ourselves. we don't want an old controller to touch new machines,
                            // but we do want new controllers to delete old machines!
                            gcloud( true, "instances", "delete", "-q", machine.getHost());
                        } else {
                            LOG.infof("Not shutting down machine newer (%s) than ourselves %s", machine.getVersion(), machine);
                        }
                    }
                    // mark as "in use" b/c it should not be used anymore (it was already removed from the map)
                    machine.setInUse(true);
                }
                if (leader.get() && machines.needsMoreMachines(getPoolBuffer(), getPoolSize(), getMaxPoolSize())) {
                    requestMachine(NameGen.newName(), false);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Error trying to restart " + machine.getHost());
                e.printStackTrace();
            }
        });
    }

    /**
     * The maximum number of machines to allow at one time (prevents creating new machines)
     * @return 150 during business hours, 75 otherwise.
     */
    private int getMaxPoolSize() {
        String poolSize = System.getenv("MAX_POOL_SIZE");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        poolSize = System.getProperty("dh-maxPoolSize");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        if (isPeakHours()) {
            return 150;
        }
        return 75;
    }

    /**
     * @return The number of warm machines to keep around. Default is 15 during business hours, 5 otherwise.
     */
    private int getPoolBuffer() {
        String poolSize = System.getenv("POOL_BUFFER");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        poolSize = System.getProperty("dh-poolBuffer");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        if (isPeakHours()) {
            return 15;
        }
        return 5;
    }

    /**
     * @return The desired size of the "always on" pool of machines.
     * During business hours, we keep 20 machines always available, only 5 afterhours.
     * We start adding new machines to keep at least {@link #getPoolBuffer()} (15 biz | 5 afterhours) machines available.
     */
    private int getPoolSize() {
        String poolSize = System.getenv("POOL_SIZE");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        poolSize = System.getProperty("dh-poolSize");
        if (poolSize != null) {
            return Integer.parseInt(poolSize);
        }
        if (isPeakHours()) {
            return 20;
        }
        return 5;
    }

    private boolean isPeakHours() {
        LocalTime time = LocalTime.now(TZ_NY);
        return time.isAfter(BIZ_START) && time.isBefore(BIZ_END);
    }

    private void loadMachinesInitial() {
        loadMachines();
        latch.countDown();
    }
    private void loadMachines() {
        String rawOut = null;
        try {
            LOG.info("Reloading machine metadata from google");
            long mark = System.currentTimeMillis();
            final Execute.ExecutionResult result;
            result = gcloudQuiet(true, false, "instances", "list",
                    "--filter",
                        // so annoying... google prints a warning about = operator changing;
                        // for whatever terrible reason, key<=val && key>=val is how to express key=val,
                        // because key=val is deprecated form of contains(), see `gcloud topic filters`
                        "labels." + LABEL_PURPOSE + "<=" + PURPOSE_WORKER +
                        " AND " +
                        "labels." + LABEL_PURPOSE + ">=" + PURPOSE_WORKER
                    ,
                    "--format", getMachineFormatFlag(),
                    "--page-size", Integer.toString(getMaxPoolSize() * 2),
                    "-q"
            );
            rawOut = result.out;
            if (rawOut.contains("Quota exceeded")) {
                checkLatency = checkLatency + 1;
                return;
            } else {
                checkLatency = Math.max(1, checkLatency - 1);
            }
            final String[] lines = rawOut.split("\n");
            if (rawOut.contains("not present")) {
                // empty, no parsing...
                LOG.info("0 active workers found");
            } else {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Got " + lines.length + " active workers:");
                    LOG.trace(rawOut);
                } else {
                    LOG.info("Got " + lines.length + " total workers (use trace logging to see response)");
                }
                int online = 0;
                for (String line : lines) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    final Machine updated = updateMachineFromCsv(line);
                    if (updated != null) {
                        updated.setMark(mark);
                        if (updated.isOnline()) {
                            online++;
                        }
                    }
                }
                LOG.infof("Found %s running machines", online);
            }
            machines.getAllMachines().forEach(m -> {
                if (m.getMark() < mark) {
                    // this machine has disappeared! ...forget about it!
                    LOG.infof("Machine %s seems to have disappeared!", m.getHost());
                    machines.removeMachine(m);
                }
            });

        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to get active workers", e);
        }
    }

    private Machine updateMachineFromCsv(final String line) {
        if (line.contains("Google API")) {
            return null;
        }
        String[] bits = line.split(",");
        final String name = bits[getIndexName()];

        if (bits.length < getIndexLength()) {
            // note: we purposely put a never-null item last in the response, so split() doesn't trick us about size
            LOG.error("UNEXPECTED MACHINE METADATA FORMAT: " + line);
            return null;
        }
        final String ipAddr, ipName;
        if (bits[getIndexIp()].length() > 0) {
            ipAddr = bits[getIndexIp()].trim();
        } else {
            ipAddr = null;
        }
        boolean needsIpLabel = false;
        if (bits[getIndexIpName()].length() > 0) {
            ipName = bits[getIndexIpName()].trim();
        } else {
            final IpMapping ip = ips.findByIp(ipAddr);
            ipName = ip == null ? null : ip.getName();
            needsIpLabel = ip != null;
        }
        final IpMapping ip = ipName == null ? null : ips.updateOrCreate(ipName, ipAddr);
        Machine mach = machines.getOrCreate(name, this, ip, bits[getIndexIp()]);

        if (needsIpLabel)
        try {
            GoogleDeploymentManager.gcloud(true, "instances", "add-labels", mach.getHost(),
                    "--labels=" + LABEL_IP_NAME + "=" + ipName);
        } catch (IOException | InterruptedException e) {
            LOG.warnf("Unable to update IP label for %s to %s", mach.getHost(), ipName);
        }
        if (bits[getIndexHostname()].length() > 0) {
            // we aren't really setting a string field, rather we're selecting an existing DomainMapping from the object.
            String domain = mach.domain() == null ? DOMAIN : mach.domain().getDomainRoot();
            mach.setDomainName(bits[getIndexHostname()].trim() + "." + domain);
        }
        if (bits[getIndexStatus()].length() > 0) {
            mach.setOnline(
                    "RUNNING".equals(bits[getIndexStatus()]) ||
                    "PROVISIONING".equals(bits[getIndexStatus()])
            );
        }
        if (bits[getIndexVersion()].length() > 0) {
            mach.setVersion(bits[getIndexVersion()]);
        }
        if (isValidVersion(mach)) {
            machines.addMachine(mach);
        } else if (!mach.isInUse()){
            turnOff(mach);
            machines.removeMachine(mach);
        }
        String purpose = bits[getIndexPurpose()];
        switch (purpose) {
            case PURPOSE_WORKER:
                mach.setController(false);
                mach.setSnapshotCreate(false);
                break;
            case PURPOSE_CONTROLLER:
                mach.setController(true);
                mach.setSnapshotCreate(false);
                break;
            case PURPOSE_CREATOR_WORKER:
                mach.setSnapshotCreate(false);
                mach.setController(false);
                break;
            case PURPOSE_CREATOR_CONTROLLER:
                mach.setSnapshotCreate(false);
                mach.setController(true);
                break;
        }
        if (bits[getIndexLease()].length() > 0) {
            if (!bits[getIndexLease()].startsWith("2")) {
                    // this lease is broken. fix it.
                   fixLease(mach);
            }
            machines.expireInTimeString(mach, bits[getIndexLease()]);
        }
        return mach;
    }

    private void fixLease(final Machine mach) {
        try {
            LOG.infof("Fixing broken lease; expiry was %sms on %s", mach.getExpiry(), mach);
            GoogleDeploymentManager.gcloud(true, "instances", "add-labels", mach.getHost(),
                    "--labels="
                            + LABEL_LEASE + "=" + ClusterController.toTime(System.currentTimeMillis() + getSessionTtl())
            );
        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Unable to clear lease for machine %s", mach);
        }
    }

    private boolean isValidVersion(final Machine machine) {
        return machine.getVersion() != null && VERSION_MANGLE.equals(machine.getVersion());
    }

    private String getMachineFormatFlag() {
        return "csv[box,no-heading](" +
                // if you change the order of these arguments, please update the getIndex*() methods below too.
                "name," +
                "labels." + LABEL_DOMAIN + "," +
                "labels." + LABEL_USER + "," +
                "networkInterfaces[0].accessConfigs[0].natIP," +
                "labels." + LABEL_PURPOSE + "," +
                "labels." + LABEL_LEASE + "," +
                "labels." + LABEL_IP_NAME + "," +
                "labels." + LABEL_VERSION + "," +
                "status" + // status is never empty, so it's best to keep this the last item in this response.
        ")";
    }
    private int getIndexName() { return 0; }
    private int getIndexHostname() { return 1; }
    private int getIndexUser() { return 2; }
    private int getIndexIp() { return 3; }
    private int getIndexPurpose() { return 4; }
    private int getIndexLease() { return 5; }
    private int getIndexIpName() { return 6; }
    private int getIndexVersion() { return 7; }
    private int getIndexStatus() {
        return 8; // The last item in our csv response should be non-empty, so String.split() gives accurate sized arrays
    }
    // STOP! Make sure that new indices are added above, and that getIndexLength(), below, is the total number of indices
    // STOP MORE: The last item in this list should be never-empty, or semantics around length-checking the split() csv line may fail.
    private int getIndexLength() { return 9; }

    private void loadDomainsInitial() {
        loadDomains();
        latch.countDown();
        ClusterController.setTimer("Cleanup DNS", ()->{
            waitUntilReady();
            waitUntilIpsCreated();
            // now! map all our known domains to known IP addresses, and delete anything left hanging
            cleanupDNS();
        });
    }


    private void loadDomains() {
        try {
            final DomainPool domains = manager.getDomainPool();
            long mark = domains.markAll();
            Collection<DomainMapping> liveDomains = manager.getAllDNS(false, domains);
            for (DomainMapping liveDomain : liveDomains) {
                liveDomain.setMark(mark);
            }
            domains.sweep(mark);
        } catch (IOException | InterruptedException e) {
            LOG.error("Error loading domains", e);
        }
    }

    private void cleanupDNS() {
        try {
            final DomainPool domainPool = manager.getDomainPool();
            Collection<DomainMapping> domains = manager.getAllDNS(true, domainPool);

        } catch (IOException | InterruptedException e) {
            LOG.error("Error loading domains", e);
        }

    }

    private void loadIpsUnusedInitial() {
        loadIpsUnused();
        latch.countDown();
    }
    private void loadIpsUnused() {
        String[] ipBits = null;
        try {
            Execute.ExecutionResult result = gcloudQuiet(true, false, "addresses", "list",
                    "--filter", "region:" + REGION + " AND status:reserved",
                    "--format", "csv[box,no-heading](name,ADDRESS)",
                    "-q"
            );
            if (result.out.contains("Quota exceeded")) {
                checkLatency = checkLatency + 1;
                return;
            } else {
                checkLatency = Math.max(1, checkLatency - 1);
            }
            for (String ipRec : result.out.split("\\s+")) {
                if (ipRec.isEmpty()) {
                    continue;
                }
                ipBits = ipRec.split(",");
                String name = ipBits[0];
                String addr = ipBits[1];
                IpMapping ip = ips.updateOrCreate(name, addr);
                ips.addIpUnused(ip);
            }
            LOG.info("Found " + ips.getNumUnused() + " unused IP addresses");
        } catch (Exception e) {
            System.err.println("Unable to load IPs! Last seen item: " + (ipBits == null ? null : Arrays.asList(ipBits)));
            e.printStackTrace();
        }
    }

    private void loadIpsUsedInitial() {
        loadIpsUsed();
        latch.countDown();
    }
    private void loadIpsUsed() {
        String[] ipBits = null;
        try {
            Execute.ExecutionResult result = gcloudQuiet(true, false, "addresses", "list",
                   // TODO: label our addresses so we can filter more correctly:
                    "--filter", "region:" + REGION + " AND status:in_use",
                    "--format", "csv[box,no-heading](name,ADDRESS)",
                    "-q"
            );
            if (result.out.contains("Quota exceeded")) {
                checkLatency = checkLatency + 1;
                return;
            } else {
                checkLatency = Math.max(1, checkLatency - 1);
            }
            for (String ipRec : result.out.split("\\s+")) {
                ipBits = ipRec.split(",");
                String name = ipBits[0];
                String addr = ipBits[1];
                if (name.startsWith("dh-") || name.startsWith("perf-")) {
                    // ignore these addresses
                    continue;
                }
                IpMapping ip = ips.updateOrCreate(name, addr);
                ips.addIpUsed(ip);
            }
            LOG.info("Found " + ips.getNumUsed() + " used IP addresses");
        } catch (Exception e) {
            System.err.println("Unable to load IPs! Last seen item: " + (ipBits == null ? null : Arrays.asList(ipBits)));
            e.printStackTrace();
        }
    }

    public boolean isReady() {
        return latch.getCount() == 0;
    }

    public void waitUntilReady() {
        if (latch.getCount() <= 0) {
            return;
        }
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            String msg = "Interrupted waiting for controller to read metadata; check for error logs!";
            System.err.println(msg);
            Thread.currentThread().interrupt();
            throw new RuntimeException(msg, e);
        }
    }

    public Machine requestMachine() {
        return requestMachine(true);
    }
    public Machine requestMachine(boolean reserve) {
        waitUntilReady();
        Optional<Machine> machine = machines.maybeGetMachine(manager);
        if (machine.isPresent()) {
            final Machine mach = machine.get();
            moveToRunningState(mach, reserve);
            LOG.info("Sending user to pre-existing machine " + mach);
            return mach;
        }
        // hm... we should probably send user to interstitial page immediately...
        // no need to have them wait until machine spins up to see "you gonna have to wait" screen.
        String newName = NameGen.newName();
        LOG.info("Sending user to new machine " + newName);
        return requestMachine(NameGen.newName(), reserve);
    }

    public Machine requestMachine(String name, boolean reserve) {
        return requestMachine(name, reserve, true);
    }
    public Machine requestMachine(String name, boolean reserve, boolean multiDomain) {
        // reserve an IP while getting a machine going.
        final Machine machine = machines.createMachine(this, name, multiDomain);
        moveToRunningState(machine, reserve);
        return machine;
    }

    void reloadMetadata(Machine machine) {
        // unless this machine is currently running a metadata reload, start one.

    }

    /**
     * Handles all the work to make sure a worker we believe to be runnable is actually online and correctly routed to DNS.
     *
     * @param machine The machine to turn on
     * @param reserve Whether or not to actually claim the machine, or just turn it on and let it idle.
     */
    void moveToRunningState(final Machine machine, final boolean reserve) {
        LOG.info("Moving machine " + machine.getDomainName() + " to a running state");
        if (reserve) {
            machines.expireInMillis(machine, getSessionTtl());
            machine.setInUse(true);
        }
        setTimer("Move to running state", ()->{
            Execute.ExecutionResult result = null;
            IpMapping machineIp = machine.getIp();
            try {
                String purpose = machine.isController() ? PURPOSE_CONTROLLER :
                                 machine.isSnapshotCreate() ?
                                         machine.isController() ? PURPOSE_CREATOR_CONTROLLER :
                                         PURPOSE_CREATOR_WORKER :
                                PURPOSE_WORKER;
                int tries = 5;
                while (tries --> 0) {
                    result = gcloud(true, "instances",
                            "describe", machine.getHost(),
                            "-q",
                            "--format", getMachineFormatFlag());
                    if (result.code == 0) {
                        break;
                    } else {
                        Thread.sleep(500);
                    }
                }
                String data = result.out.trim();
                final Machine updated = updateMachineFromCsv(data);
                if (updated == null) {
                    LOG.error("NO MACHINE FOUND FOR " + machine);
                    // this is a serious state error, we should slack-nag in #demo
                    result = new Execute.ExecutionResult();
                    result.code = 111;
                    result.out = "not found"; // this is also found in the real "no machine found" message.
                } else if (reserve) {
                    result = gcloud(true, "instances", "add-labels", machine.getHost(),
                            "--labels=" + LABEL_LEASE + "=" + toTime(machine.getExpiry())
                    );
                }
                if (result.code != 0) {
                    // check if this was a "not found" message
                    if (result.out.contains("not found")) {
                        // yikes! the machine doesn't exist... create one w/ the specified ip address (by name, so it is stable!)
                        if (machines.getNumberMachines() > getMaxPoolSize()) {
                            LOG.error("Refusing to create more than " + getMaxPoolSize() + " machines (currently " + machines.getNumberMachines() + ")");
                            // serious error... send slack ping!
                        } else if (isAllowUnexpectedMachines()) {
                            manager.createMachine(machine, ips);
                            result.code = 0;
                        }
                    }
                }
                if (result.code != 0) {
                    final String msg = "Unable to add label to machine " + machine.getHost();
                    LOG.error(msg);
                    GoogleDeploymentManager.warnResult(result);
                    return;
                }

                assert updated == machine : "Somehow get " + updated + " from pool using supplied " + machine;

                if (machine.isController()) {
                    LOG.warn("Instance " + machine.getHost() + " had label '" + machine.getPurpose() + "' instead of " + purpose + "; forgetting this instance");
                    machines.removeMachine(machine);
                }
                if (!machineIp.equals(machine.getIp())) {
                    LOG.warn("Replaced IP " + machineIp + " with " + machine.getIp());
                }
            } catch (Exception e) {
                LOG.error("Unable to move machine to running state: " + machine, e);
            }
            // machine (should be) alive, make sure it has DNS!
            // We check DNS with google; nobody is listening to this method, so we don't care if public DNS resolves yet!
            final Execute.ExecutionResult dnsCheck = manager.checkDns(machine.getDomainName(), DNS_GOOGLE);
            if (dnsCheck.code == 0 && dnsCheck.out.trim().equals(machineIp.getIp())) {
                // machine is alive, DNS is all good.
                LOG.trace("DNS resolved for " + machine.getDomainName());
            } else {
                // machine is NOT alive, glue on some ad-hoc DNS
                LOG.warn("DNS does not resolve correctly for " + machine.getDomainName() + " @ " + machine.getIp() + "; setting up DNS records");
                try {
                    setupDns(machine);
                } catch (Exception e) {
                    LOG.error("Unable to setup DNS for machine " + machine, e);
                }
            }

        });
    }

    private boolean isAllowUnexpectedMachines() {
        return false; // for now, we'll be strict. If we want to let people request machines by name, we can change this.
    }

    private long getSessionTtl() {
        return TimeUnit.MINUTES.toMillis(45);
    }

    private void setupDns(final Machine machine) {
        final IpMapping machineIp = machine.getIp();
        if (machineIp == null || machineIp.getIp() == null || machineIp.getIp().isEmpty()) {
            final IpMapping mapping = ips.reserveIp(manager, machine);
            ensureAccessConfig(machine, mapping);
            machine.setIp(mapping);
            ips.addIpUsed(mapping);
        } else {
//            if (machineIp.indexOf('.') == -1 && machineIp.indexOf(':') == -1) {
//                final IpMapping mapping = ips.reserveIp(this, machine, ip);
//                // TODO: make sure this ip mapping is the correct access-config:
//                //    https://cloud.google.com/compute/docs/ip-addresses/reserve-static-external-ip-address#IP_assign
//                ensureAccessConfig(machine, mapping);
//                machine.setIp(mapping);
//                ips.addIpUsed(mapping);
//            }
        }

        manager.assignDns(this, Stream.of(machine));
    }

    private void ensureAccessConfig(final Machine machine, final IpMapping mapping) {
    }

    public IpMapping requestIp() {
        if (ips.getNumUnused() > 0) {
            final IpMapping unused = ips.getUnusedIp(manager);
            if (unused != null) {
                return unused;
            }
        }
        waitUntilReady();
        return ips.getUnusedIp(manager);
    }

    public void waitUntilIpsCreated() {
        manager.waitUntilIpsCreated();
    }

    public boolean isMachineReady(final String domainName) {
        String uri = "https://" + domainName + "/health";
        final Request req = new Request.Builder().get()
                .url(uri)
                .addHeader("User-Agent", "DeephavenBot")
                .build();
        final Response response;
        try {
            response = client.newCall(req).execute();
        } catch (IOException e) {
            return false;
        }
        boolean success = response.isSuccessful();
        response.close();
        return success;
    }

    public GoogleDeploymentManager getDeploymentManager() {
        return manager;
    }

    public void waitUntilHealthy(final Machine machine) throws IOException, InterruptedException {
        final String key = "finished code: ";
        final String failed = "ran out of tries";
        final Execute.ExecutionResult result = Execute.ssh(true, machine.getDomainName(), //"bash", "-c",
//        GoogleDeploymentManager.gcloud(false, true, "ssh", machine.getHost(),
//                "--command",
                        "function watch_logs() {\n" +
                        "  echo Watching log file /var/log/vm-startup.log\n" +
                        "  while ! test -f /var/log/vm-startup.log ; do sleep 1 ; done\n" +
                        "  tail -f /var/log/vm-startup.log\n" +
                        "}\n" +
                        "function wait_til_ready() {\n" +
                        "  # we sleep 1 per try, so 720 tries > 12 minutes after ssh is online\n" +
                        "  local tries=720\n" +
                        "  echo waiting for localhost:10000 to be responsive\n" +
                        "  watch_logs &\n" +
                        "  pid=$!\n" +
                        "  while (( tries > 0 )) && \n" +
                                "! grep -q InitialDeephavenSetupComplete /var/log/vm-startup.log 2> /dev/null; do\n" +
                        "    tries=$((tries-1))\n" +
                        "    (( tries%10 )) || echo \"start-monitor tries remaining: $tries\"\n" +
                        "    sleep 1\n" +
                        "  done\n" +
                        "  kill $pid\n" +
                        "  if (( tries > 0 )) && curl -k https://localhost:10000/health &> /dev/null; then\n" +
                        "    echo \"localhost:10000 is responsive; $(hostname) is alive!\"\n" +
                        "  else\n" +
                        "    echo Tried 720 times to reach https://localhost:10000/health but " + failed + "\n" +
                        "  fi\n" +
                        "} ; TIMEFORMAT='\n" +
                        "wait_til_ready exited after: %3Rs\n' ; time wait_til_ready ; code=$? ; " +
                        "sleep 1 ; echo '\n'" + key + "${code}'\n' ; echo ; sleep 1 ; kill $PPID "
        );
        int realCodeLoc = result.out.lastIndexOf(key);
        if (result.out.contains(failed)) {
            throw new IllegalStateException("wait_til_ready failed:\n\n" + result.out);
        }
        String code = result.out.substring(realCodeLoc + key.length(), result.out.indexOf('\n', realCodeLoc + key.length()));
        if (!"0".equals(code)) {
            throw new IllegalStateException("wait_til_ready returned code " + code);
        }
    }

    public Machine findMachine(final String name, final boolean newIfMissing, final boolean noStableIp) {
        final Machine machine = machines.findByName(name);
        if (machine == null) {
            if (newIfMissing) {
                final IpMapping ip;
                if (noStableIp) {
                    ip = ips.updateOrCreate(name, null);
                    ip.setState(IpState.Unclaimed);
                } else {
                    ip = null;
                }
                final Machine created = machines.getOrCreate(name, this, ip, null);
                created.setNoStableIP(noStableIp);
                return created;
            }
        }
        return machine;
    }

    public boolean renewLease(final String machineName) {
        final Machine machine = machines.findByName(machineName);
        if (machine == null) {
            return false;
        }
        machines.expireInMillis(machine, getSessionTtl());
        return true;
    }
}
