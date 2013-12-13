package me.schiz.jmeter.protocol.technetium.callbacks;

import me.schiz.jmeter.argentum.reporters.ArgentumListener;
import me.schiz.jmeter.protocol.technetium.pool.NetflixUtils;
import me.schiz.jmeter.protocol.technetium.pool.TcInstance;
import me.schiz.jmeter.protocol.technetium.pool.TcPool;
import me.schiz.jmeter.protocol.technetium.samplers.TcCQL3StatementSampler;
import org.apache.cassandra.thrift.*;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TcCQL3StatementCallback implements AsyncMethodCallback<Cassandra.AsyncClient.execute_cql3_query_call> {
    private static final Logger log = LoggingManager.getLoggerForClass();
    private static String separator = "========================================";

    private SampleResult result;
    private ConcurrentLinkedQueue<SampleResult> queue;
    private TcPool pool;
    private TcInstance instance;
    private boolean notifyOnlyArgentumListeners;

    public TcCQL3StatementCallback(SampleResult result, ConcurrentLinkedQueue<SampleResult> asyncQueue, TcPool pool, TcInstance instance, boolean notifyOnlyArgentumListeners) {
        this.result = result;
        this.queue = asyncQueue;
        this.pool = pool;
        this.instance = instance;
        this.notifyOnlyArgentumListeners = notifyOnlyArgentumListeners;
    }
    @Override
    public void onComplete(Cassandra.AsyncClient.execute_cql3_query_call response) {
        this.result.sampleEnd();

        try {
            CqlResult cqlResult = response.getResult();
            StringBuilder _response = new StringBuilder();
            List<CqlRow> rows= cqlResult.getRows();
            if(rows != null) {
                if(rows.size() == 0) {
                    _response.append("EMPTY RESPONSE");
                }
                for(CqlRow row : cqlResult.getRows()) {
                    _response.append(separator + "\n");
                    _response.append("key: ");
                    _response.append(new String(row.getKey()));
                    _response.append("\n");
                    for(Column col : row.getColumns()) {
                        _response.append(new String(col.getName()));
                        _response.append(" : ");
                        _response.append(new String(col.getValue()));
                        _response.append("\n");
                    }
                    _response.append("\n");
                }
            } else _response.append("NULL RESPONSE");
            this.result.setResponseData(_response.toString().getBytes());
            this.result.setSuccessful(true);
        } catch (InvalidRequestException e) {
            this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
            this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
            this.result.setSuccessful(false);
        } catch (UnavailableException e) {
            this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
            this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
            this.result.setSuccessful(false);
        } catch (TimedOutException e) {
            this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
            this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
            this.result.setSuccessful(false);
        } catch (SchemaDisagreementException e) {
            this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
            this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
            this.result.setSuccessful(false);
        } catch (TException e) {
            this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
            this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
            this.result.setSuccessful(false);
        } finally {
            try {
                pool.releaseInstance(instance);
            } catch (InterruptedException e) {
                log.warn("cannot release instance. I'll destroy him! ", e);
                pool.destroyInstance(instance);
            }
//            while(!queue.add(this.result)) {}
            if(notifyOnlyArgentumListeners) ArgentumListener.sampleOccured(new SampleEvent(this.result, null));
            else while(!queue.add(this.result)) {}
        }
    }

    @Override
    public void onError(Exception e) {
        this.result.sampleEnd();
        this.result.setResponseData(NetflixUtils.getStackTrace(e).getBytes());
        this.result.setResponseCode(TcCQL3StatementSampler.ERROR_RC);
        this.result.setSuccessful(false);

        try {
            pool.releaseInstance(instance);
        } catch (InterruptedException ie) {
            log.warn("cannot release instance. I'll destroy him! ", ie);
            pool.destroyInstance(instance);
        }

        while(!queue.add(this.result)) {}
    }
}
