package me.schiz.jmeter.protocol.technetium.pool;

import me.schiz.jmeter.protocol.technetium.callbacks.TcSetKeyspaceCallback;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocolFactory;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TcPool {
    protected ConcurrentHashMap<Long, TcInstance> instanceMap;
    protected LinkedBlockingQueue<Long> freeInstances;
    protected String keyspace;
    protected int maxManagers;
    protected int maxInstances;
    protected volatile int initInstances;

    protected TcClientManagerPool managersPool;
    protected TProtocolFactory protocolFactory;
    protected ArrayList<AbstractMap.SimpleEntry<String, Integer>> hosts;
    protected int timeout;

    private int DEFAULT_POLL_TIMEOUT  = 100; //100mks

    public TcPool(String keyspace, int maxManagers, int maxInstances, int timeout) throws IOException {
        this.keyspace = keyspace;
        this.maxManagers = maxManagers;
        this.maxInstances = maxInstances;
        this.instanceMap = new ConcurrentHashMap<Long, TcInstance>(maxInstances);
        this.freeInstances = new LinkedBlockingQueue<Long>(maxInstances);
        this.hosts = new ArrayList<AbstractMap.SimpleEntry<String, Integer>>();

        this.initInstances = 0;

        this.managersPool = new TcClientManagerPool(maxManagers);
        this.protocolFactory = new TBinaryProtocol.Factory();
        this.timeout = timeout;

    }

    public void addServer(String host, int port) {
        synchronized (hosts) {
            for(AbstractMap.SimpleEntry<String,Integer> row : hosts) {
                if(row.getKey().equals(host) && row.getValue() == port) return;
            }
            hosts.add(new AbstractMap.SimpleEntry<String, Integer>(host, port));
        }
    }

    protected AbstractMap.SimpleEntry<String, Integer> getRandomHost() throws NotFoundHostException {
        synchronized (hosts) {
            if(hosts.isEmpty()) throw new NotFoundHostException();
            return hosts.get(ThreadLocalRandom.current().nextInt(hosts.size()));
        }
    }


    public TcInstance getInstance(long pollTimeout) throws IOException, NotFoundHostException, InterruptedException, PoolTimeoutException, TException, FailureKeySpace {
        //System.out.println("m:" + maxInstances + " i:" + initInstances + " f:" + freeInstances.size());

        TcInstance instance;
        if(initInstances == 0) {
            synchronized (this.getClass()) {
                if(initInstances == 0) {
                    AbstractMap.SimpleEntry<String, Integer> host = getRandomHost();
                    instance = new TcInstance(host.getKey(), host.getValue(), this.timeout, this.managersPool.getClientManager(), this.protocolFactory);

                    AtomicInteger flag = new AtomicInteger(TcSetKeyspaceCallback.PENDING_STATUS);
                    Object monitor = new Object();

                    instance.getClient().set_keyspace(this.keyspace, new TcSetKeyspaceCallback(flag, monitor));
                    synchronized (monitor) {
                        while(flag.get() == TcSetKeyspaceCallback.PENDING_STATUS) { monitor.wait();}
                    }

                    if(flag.get() == TcSetKeyspaceCallback.FAILURE_STATUS) throw new FailureKeySpace();

                    instanceMap.put(instance.getId(), instance);
                    initInstances++;
                    return instance;
               } else return getInstance(pollTimeout);
            }
        }

        Long freeId;

        if(initInstances == maxInstances) {
            freeId = freeInstances.poll(pollTimeout, TimeUnit.MICROSECONDS);
            if(freeId == null) throw new PoolTimeoutException();
            else {
                instance = instanceMap.get(freeId);
                instance.state = true;

                return instance;
            }
        } else {
            synchronized (this.getClass()) {
                if(initInstances == maxInstances) {
                    return getInstance(pollTimeout);
                } else {
                    AbstractMap.SimpleEntry<String, Integer> host = getRandomHost();
                    instance = new TcInstance(host.getKey(), host.getValue(), this.timeout, this.managersPool.getClientManager(), this.protocolFactory);
                    instance.state = true;

                    AtomicInteger flag = new AtomicInteger(TcSetKeyspaceCallback.PENDING_STATUS);
                    Object monitor = new Object();

                    instance.getClient().set_keyspace(this.keyspace, new TcSetKeyspaceCallback(flag, monitor));
                    synchronized (monitor) {
                        while(flag.get() == TcSetKeyspaceCallback.PENDING_STATUS) { monitor.wait();}
                    }

                    if(flag.get() == TcSetKeyspaceCallback.FAILURE_STATUS) throw new FailureKeySpace();

                    instanceMap.put(instance.getId(), instance);
                    initInstances++;
                    return instance;
                }

            }

        }
    }

//    public TcInstance getInstance(long pollTimeout) throws IOException, NotFoundHostException, InterruptedException, PoolTimeoutException {
//        System.out.println("m: " + maxInstances + "i: " + initInstances + " f:" + freeInstances.size());
//        if(initInstances == 0) {
//            synchronized (this.getClass()) {
//                if(initInstances == 0) {
//                    AbstractMap.SimpleEntry<String, Integer> host = getRandomHost();
//                    TcInstance instance = new TcInstance(host.getKey(), host.getValue(), this.timeout, this.managersPool.getClientManager(), this.protocolFactory);
//                    instanceMap.put(instance.getId(), instance);
//                    initInstances++;
//                    return instance;
//               }
//            }
//        } else {
//            Long freeId;
//            TcInstance instance;
//            if(initInstances != maxInstances) {
//                freeId = freeInstances.poll(pollTimeout, TimeUnit.MICROSECONDS);
//                if(freeId == null) {
//                    instance = new TcInstance(getRandomHost().getKey(), getRandomHost().getValue(), this.timeout, this.managersPool.getClientManager(), this.protocolFactory);
//                    initInstances++;
//                    return instance;
//                } else {
//                    instance = instanceMap.get(freeId);
//                    instance.state = true;
//                }
//            } else {
//                synchronized (this.getClass()) {
//                    if(initInstances == maxInstances) {
//                        freeId = freeInstances.poll(pollTimeout, TimeUnit.MICROSECONDS);
//                        if(freeId == null) throw new PoolTimeoutException();
//                        instance = instanceMap.get(freeId);
//                        instance.state = true;
//                    } else {
//                        AbstractMap.SimpleEntry<String, Integer> host = getRandomHost();
//                        instance = new TcInstance(host.getKey(), host.getValue(), this.timeout, this.managersPool.getClientManager(), this.protocolFactory);
//                        instanceMap.put(instance.getId(), instance);
//                        initInstances++;
//                        return instance;
//                    }
//                }
//            }
//
//            return instance;
//        }
//        return null;
//    }

    public void releaseInstance(TcInstance instance) throws InterruptedException {
        instance.release();
        if(!instanceMap.containsKey(instance.getId())) return;
        freeInstances.put(instance.getId());
    }

    public void destroyInstance(TcInstance instance) {
        if(instance == null)    return;
        instance.release();
        if(instanceMap.containsKey(instance.getId())) {
            instanceMap.remove(instance.getId());
            initInstances--;
        }

        instance.getTransport().close();
    }

    public void shutdown() {
        for(Long id : instanceMap.keySet()) {
            this.destroyInstance(instanceMap.get(id));
        }
        this.managersPool.shutdown();
    }
}