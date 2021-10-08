package io.deephaven.demo.deploy;

import io.deephaven.demo.ClusterController;
import io.deephaven.demo.NameGen;
import io.vertx.core.impl.ConcurrentHashSet;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

import static io.deephaven.demo.NameConstants.LABEL_LEASE;
import static io.deephaven.demo.NameConstants.LABEL_PURPOSE;

/**
 * MachinePool:
 * <p>
 * <p>
 * <p> A class containing information about all running machines.
 * <p>
 */
public class MachinePool {

    // NOTE: this is dangerous! we could lose an item if we don't remove before changing expiry and put back after.
    // thus, it's private, you shouldn't use this.
    private static final Comparator<Machine> CMP = (a, b) -> {
        if (a.getExpiry() == b.getExpiry()) {
            return a.getHost().compareTo(b.getHost());
        }
        // this stops being correct if workers are more than 42 days old.
        long diff = a.getExpiry() - b.getExpiry();
        // take the oldest last!
        return diff > 0 ? -1 : diff < 0 ? 1 : 0;
    };

    private static final Logger LOG = Logger.getLogger(MachinePool.class);

    private final Map<String, Machine> machinesByName = new ConcurrentHashMap<>();
    private final Set<Machine> machines = new ConcurrentSkipListSet<>(CMP);

    public Machine createMachine(final GoogleDeploymentManager manager, final String name, final IpMapping ip) {
        final String newName = name == null || name.isEmpty() ? NameGen.newName() : name;
        final Machine machine = getOrCreate(newName);
        if (ip != null) {
            // important: setting the --address ip-name will create a machine with a stable IP address across restarts
            //            settings the --address 1.2.3.4 to an IP will a) fail b/c IP is used by our gcloud address name, and b) change on restart
            machine.setIp(ip.getName());
        }
        try {
            manager.createMachine(machine);
            machines.add(machine);
            if (machine.getIp() == null && ip != null && ip.getIp() != null) {
                machine.setIp(ip.getIp());
            }
        } catch (IOException | InterruptedException e) {
            String msg = "Failed to create machine " + name;
            System.err.println(msg);
            throw new RuntimeException(msg, e);
        }
        return machine;
    }

    public void addMachine(final Machine machine) {
        machines.add(machine);
    }

    public Optional<Machine> maybeGetMachine(final GoogleDeploymentManager manager) {
        Machine candidate = null;
        synchronized (machines) {
            for (Machine next : machines) {
                if (!next.isInUse()) {
                    if (next.isOnline()) {
                        LOG.info("Sending user already-warm machine " + next);
                        next.setInUse(true);
                        return Optional.of(next);
                    }
                    candidate = next;
                }
            }
        }
        if (candidate != null) {
            candidate.setInUse(true);
            LOG.warn("Sending user a machine we must turn on: " + candidate);
            try {
                if (!candidate.isOnline()) {
                    candidate.setOnline(true);
                    manager.turnOn(candidate);
                }
            } catch (IOException | InterruptedException e) {
                String msg = "Unable to turn on machine " + candidate;
                LOG.error(msg, e);
                throw new IllegalStateException(msg, e);
            }
        }
        return Optional.ofNullable(candidate);
    }

    public Stream<Machine> getAllMachines() {
        return machines.parallelStream();
    }

    public void removeMachine(final Machine machine) {
        machines.remove(machine);
        machinesByName.remove(machine.getHost());
    }

    public boolean needsMoreMachines(final int poolBuffer, final int poolSize, final int maxPoolSize) {
        if (machines.size() >= maxPoolSize) {
            return false;
        }
        int unused = 0, total = 0;
        for (Machine machine : machines) {
            total++;
            if (!machine.isInUse() && machine.isOnline()) {
                unused++;
            }
        }
        return unused < poolBuffer || total < poolSize;
    }

    public int getNumberMachines() {
        return machines.size();
    }

    public Machine findByName(final String name) {
        return machinesByName.get(name);
    }
    public Machine getOrCreate(final String name) {
        return machinesByName.computeIfAbsent(name, missing-> {
            final Machine machine = new Machine(missing);
            machines.add(machine);
            return machine;
        });
    }

    public void expireInMillis(final Machine machine, final long sessionTtl) {
        // we remove a machine from this set before updating the expiry,
        // and then put it back after, because our comparator looks at expiry, so we don't want to lose items!
        machines.remove(machine);
        machine.setExpiry(System.currentTimeMillis() + sessionTtl);
        machine.setInUse(machine.isOnline() && System.currentTimeMillis() < machine.getExpiry());
        machines.add(machine);
    }

    public void expireInTimeString(final Machine machine, final String lease) {
        final long parsedTime = ClusterController.parseTime(lease);
        if (parsedTime < machine.getExpiry()) {
            // machine was taken while we were loading metadata. ignore!
            return;
        }
        expireInMillis(machine, parsedTime - System.currentTimeMillis());
    }
}
