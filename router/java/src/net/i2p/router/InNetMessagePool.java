package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Manage a pool of inbound InNetMessages.  This pool is filled by the 
 * Network communication system when it receives messages, and various jobs 
 * periodically retrieve them for processing.
 *
 */
public class InNetMessagePool implements Service {
    private Log _log;
    private RouterContext _context;
    private HandlerJobBuilder _handlerJobBuilders[];
    private List _pendingDataMessages;
    private List _pendingDataMessagesFrom;
    private List _pendingGatewayMessages;
    private SharedShortCircuitDataJob _shortCircuitDataJob;
    private SharedShortCircuitGatewayJob _shortCircuitGatewayJob;
    private boolean _alive;
    private boolean _dispatchThreaded;
    
    /**
     * If set to true, we will have two additional threads - one for dispatching
     * tunnel data messages, and another for dispatching tunnel gateway messages.
     * These will not use the JobQueue but will operate sequentially.  Otherwise,
     * if this is set to false, the messages will be queued up in the jobQueue,
     * using the jobQueue's single thread.
     *
     */
    public static final String PROP_DISPATCH_THREADED = "router.dispatchThreaded";
    public static final boolean DEFAULT_DISPATCH_THREADED = false;
    
    public InNetMessagePool(RouterContext context) {
        _context = context;
        _handlerJobBuilders = new HandlerJobBuilder[20];
        _pendingDataMessages = new ArrayList(16);
        _pendingDataMessagesFrom = new ArrayList(16);
        _pendingGatewayMessages = new ArrayList(16);
        _shortCircuitDataJob = new SharedShortCircuitDataJob(context);
        _shortCircuitGatewayJob = new SharedShortCircuitGatewayJob(context);
        _log = _context.logManager().getLog(InNetMessagePool.class);
        _alive = false;
        _context.statManager().createRateStat("inNetPool.dropped", "How often do we drop a message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedDeliveryStatusDelay", "How long after a delivery status message is created do we receive it back again (for messages that are too slow to be handled)", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.duplicate", "How often do we receive a duplicate message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedTunnelCreateStatusMessage", "How often we drop a slow-to-arrive tunnel request response", "InNetPool", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedDbLookupResponseMessage", "How often we drop a slow-to-arrive db search response", "InNetPool", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("pool.dispatchDataTime", "How long a tunnel dispatch takes", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("pool.dispatchGatewayTime", "How long a tunnel gateway dispatch takes", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
  
    public HandlerJobBuilder registerHandlerJobBuilder(int i2npMessageType, HandlerJobBuilder builder) {
        HandlerJobBuilder old = _handlerJobBuilders[i2npMessageType];
        _handlerJobBuilders[i2npMessageType] = builder;
        return old;
    }
  
    public HandlerJobBuilder unregisterHandlerJobBuilder(int i2npMessageType) {
        HandlerJobBuilder old = _handlerJobBuilders[i2npMessageType];
        _handlerJobBuilders[i2npMessageType] = null;
        return old;
    }
    
    /**
     * Add a new message to the pool, returning the number of messages in the 
     * pool so that the comm system can throttle inbound messages.  If there is 
     * a HandlerJobBuilder for the inbound message type, the message is loaded
     * into a job created by that builder and queued up for processing instead
     * (though if the builder doesn't create a job, it is added to the pool)
     *
     */
    public int add(I2NPMessage messageBody, RouterIdentity fromRouter, Hash fromRouterHash) {
        long exp = messageBody.getMessageExpiration();
        
        if (_log.shouldLog(Log.INFO))
                _log.info("Received inbound " 
                          + " with id " + messageBody.getUniqueId()
                          + " expiring on " + exp
                          + " of type " + messageBody.getClass().getName());
        
        if (messageBody instanceof TunnelDataMessage) {
            // do not validate the message with the validator - the IV validator is sufficient
        } else { 
            boolean valid = _context.messageValidator().validateMessage(messageBody.getUniqueId(), exp);
            if (!valid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Duplicate message received [" + messageBody.getUniqueId() 
                              + " expiring on " + exp + "]: " + messageBody.getClass().getName());
                _context.statManager().addRateData("inNetPool.dropped", 1, 0);
                _context.statManager().addRateData("inNetPool.duplicate", 1, 0);
                _context.messageHistory().droppedOtherMessage(messageBody);
                _context.messageHistory().messageProcessingError(messageBody.getUniqueId(), 
                                                                    messageBody.getClass().getName(), 
                                                                    "Duplicate/expired");
                return -1;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Message received [" + messageBody.getUniqueId() 
                               + " expiring on " + exp + "] is NOT a duplicate or exipired");
            }
        }

        boolean jobFound = false;
        int type = messageBody.getType();
        boolean allowMatches = true;
        
        if (messageBody instanceof TunnelGatewayMessage) {
            shortCircuitTunnelGateway(messageBody);
            allowMatches = false;
        } else if (messageBody instanceof TunnelDataMessage) {
            shortCircuitTunnelData(messageBody, fromRouterHash);
            allowMatches = false;
        } else {
            HandlerJobBuilder builder = _handlerJobBuilders[type];

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add message to the inNetMessage pool - builder: " + builder 
                           + " message class: " + messageBody.getClass().getName());

            if (builder != null) {
                Job job = builder.createJob(messageBody, fromRouter, 
                                            fromRouterHash);
                if (job != null) {
                    _context.jobQueue().addJob(job);
                    jobFound = true;
                }
            }
        }

        if (allowMatches) {
            List origMessages = _context.messageRegistry().getOriginalMessages(messageBody);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Original messages for inbound message: " + origMessages.size());
            if (origMessages.size() > 1) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Orig: " + origMessages + " \nthe above are replies for: " + messageBody, 
                               new Exception("Multiple matches"));
            }

            for (int i = 0; i < origMessages.size(); i++) {
                OutNetMessage omsg = (OutNetMessage)origMessages.get(i);
                ReplyJob job = omsg.getOnReplyJob();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Original message [" + i + "] " + omsg.getReplySelector() 
                               + " : " + omsg + ": reply job: " + job);

                if (job != null) {
                    job.setMessage(messageBody);
                    _context.jobQueue().addJob(job);
                }
            }

            if (origMessages.size() <= 0) {
                // not handled as a reply
                if (!jobFound) { 
                    // was not handled via HandlerJobBuilder
                    _context.messageHistory().droppedOtherMessage(messageBody);
                    if (type == DeliveryStatusMessage.MESSAGE_TYPE) {
                        long timeSinceSent = _context.clock().now() - 
                                            ((DeliveryStatusMessage)messageBody).getArrival();
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping unhandled delivery status message created " + timeSinceSent + "ms ago: " + messageBody);
                        _context.statManager().addRateData("inNetPool.droppedDeliveryStatusDelay", timeSinceSent, timeSinceSent);
                    } else if (type == TunnelCreateStatusMessage.MESSAGE_TYPE) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Dropping slow tunnel create request response: " + messageBody);
                        _context.statManager().addRateData("inNetPool.droppedTunnelCreateStatusMessage", 1, 0);
                    } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Dropping slow db lookup response: " + messageBody);
                        _context.statManager().addRateData("inNetPool.droppedDbLookupResponseMessage", 1, 0);
                    } else if (type == DatabaseLookupMessage.MESSAGE_TYPE) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Dropping netDb lookup due to throttling");
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Message expiring on " 
                                      + (messageBody != null ? (messageBody.getMessageExpiration()+"") : " [unknown]")
                                      + " was not handled by a HandlerJobBuilder - DROPPING: " + messageBody);
                        _context.statManager().addRateData("inNetPool.dropped", 1, 0);
                    }
                } else {
                    String mtype = messageBody.getClass().getName();
                    _context.messageHistory().receiveMessage(mtype, messageBody.getUniqueId(), 
                                                             messageBody.getMessageExpiration(), 
                                                             fromRouterHash, true);	
                    return 0; // no queue
                }
            }
        }

        String mtype = messageBody.getClass().getName();
        _context.messageHistory().receiveMessage(mtype, messageBody.getUniqueId(), 
                                                 messageBody.getMessageExpiration(), 
                                                 fromRouterHash, true);	
        return 0; // no queue
    }
    
    // the following short circuits the tunnel dispatching - i'm not sure whether
    // we'll want to run the dispatching in jobs or whether it shuold go inline with
    // others and/or on other threads (e.g. transport threads).  lets try 'em both.
    
    private void shortCircuitTunnelGateway(I2NPMessage messageBody) {
        if (false) {
            doShortCircuitTunnelGateway(messageBody);
        } else {
            synchronized (_pendingGatewayMessages) { 
                _pendingGatewayMessages.add(messageBody); 
                _pendingGatewayMessages.notifyAll();
            }
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitGatewayJob);
        }
    }
    private void doShortCircuitTunnelGateway(I2NPMessage messageBody) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Shortcut dispatch tunnelGateway message " + messageBody);
        long before = _context.clock().now();
        _context.tunnelDispatcher().dispatch((TunnelGatewayMessage)messageBody);
        long dispatchTime = _context.clock().now() - before;
        _context.statManager().addRateData("tunnel.dispatchGatewayTime", dispatchTime, dispatchTime);
    }
    
    private void shortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        if (false) {
            doShortCircuitTunnelData(messageBody, from);
        } else {
            synchronized (_pendingDataMessages) { 
                _pendingDataMessages.add(messageBody);
                _pendingDataMessagesFrom.add(from);
                _pendingDataMessages.notifyAll();
                //_context.jobQueue().addJob(new ShortCircuitDataJob(_context, messageBody, from));
            }
            if (!_dispatchThreaded)
                _context.jobQueue().addJob(_shortCircuitDataJob);
        }
    }
    private void doShortCircuitTunnelData(I2NPMessage messageBody, Hash from) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Shortcut dispatch tunnelData message " + messageBody);
        _context.tunnelDispatcher().dispatch((TunnelDataMessage)messageBody, from);
    }
    
    public void renderStatusHTML(Writer out) {}
    public void restart() { 
        shutdown(); 
        try { Thread.sleep(100); } catch (InterruptedException ie) {}
        startup(); 
    }
    public void shutdown() {
        _alive = false;
        synchronized (_pendingDataMessages) {
            _pendingDataMessages.clear();
            _pendingDataMessagesFrom.clear();
            _pendingDataMessages.notifyAll();
        }
    }
    
    public void startup() {
        _alive = true;
        _dispatchThreaded = DEFAULT_DISPATCH_THREADED;
        String threadedStr = _context.getProperty(PROP_DISPATCH_THREADED);
        if (threadedStr != null) {
            _dispatchThreaded = Boolean.valueOf(threadedStr).booleanValue();
        }
        if (_dispatchThreaded) {
            I2PThread data = new I2PThread(new TunnelDataDispatcher(), "Tunnel data dispatcher");
            data.setDaemon(true);
            data.start();
            I2PThread gw = new I2PThread(new TunnelGatewayDispatcher(), "Tunnel gateway dispatcher");
            gw.setDaemon(true);
            gw.start();
        }
    }
    
    private class SharedShortCircuitDataJob extends JobImpl {
        public SharedShortCircuitDataJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Dispatch tunnel participant message"; }
        public void runJob() { 
            int remaining = 0;
            I2NPMessage msg = null;
            Hash from = null;
            synchronized (_pendingDataMessages) {
                if (_pendingDataMessages.size() > 0) {
                    msg = (I2NPMessage)_pendingDataMessages.remove(0);
                    from = (Hash)_pendingDataMessagesFrom.remove(0);
                }
                remaining = _pendingDataMessages.size();
            }
            if (msg != null)
                doShortCircuitTunnelData(msg, from); 
            if (remaining > 0)
                getContext().jobQueue().addJob(SharedShortCircuitDataJob.this);
        }
    }
    private class SharedShortCircuitGatewayJob extends JobImpl {
        public SharedShortCircuitGatewayJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Dispatch tunnel gateway message"; }
        public void runJob() { 
            I2NPMessage msg = null;
            int remaining = 0;
            synchronized (_pendingGatewayMessages) {
                if (_pendingGatewayMessages.size() > 0)
                    msg = (I2NPMessage)_pendingGatewayMessages.remove(0);
                remaining = _pendingGatewayMessages.size();
            }
            if (msg != null)
                doShortCircuitTunnelGateway(msg); 
            if (remaining > 0)
                getContext().jobQueue().addJob(SharedShortCircuitGatewayJob.this);
        }
    }
    
    private class TunnelGatewayDispatcher implements Runnable {
        public void run() {
            while (_alive) {
                I2NPMessage msg = null;
                try {
                    synchronized (_pendingGatewayMessages) {
                        if (_pendingGatewayMessages.size() <= 0)
                            _pendingGatewayMessages.wait();
                        else
                            msg = (I2NPMessage)_pendingGatewayMessages.remove(0);
                    }
                    if (msg != null) {
                        long before = _context.clock().now();
                        doShortCircuitTunnelGateway(msg);
                        long elapsed = _context.clock().now() - before;
                        _context.statManager().addRateData("pool.dispatchGatewayTime", elapsed, elapsed);
                    }
                } catch (InterruptedException ie) {
                    
                } catch (OutOfMemoryError oome) {
                    throw oome;
                } catch (Exception e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the tunnel gateway dispatcher", e);
                }
            }
        }
    }
    private class TunnelDataDispatcher implements Runnable {
        public void run() {
            while (_alive) {
                I2NPMessage msg = null;
                Hash from = null;
                try {
                    synchronized (_pendingDataMessages) {
                        if (_pendingDataMessages.size() <= 0) {
                            _pendingDataMessages.wait();
                        } else {
                            msg = (I2NPMessage)_pendingDataMessages.remove(0);
                            from = (Hash)_pendingDataMessagesFrom.remove(0);
                        }
                    }
                    if (msg != null) {
                        long before = _context.clock().now();
                        doShortCircuitTunnelData(msg, from);
                        long elapsed = _context.clock().now() - before;
                        _context.statManager().addRateData("pool.dispatchDataTime", elapsed, elapsed);
                    }
                } catch (InterruptedException ie) {
                    
                } catch (OutOfMemoryError oome) {
                    throw oome;
                } catch (Exception e) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in the tunnel data dispatcher", e);
                }
            }
        }
    }
}
