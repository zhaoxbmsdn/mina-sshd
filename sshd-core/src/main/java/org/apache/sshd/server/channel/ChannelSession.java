/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.server.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.Factory;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.channel.ChannelRequestHandler;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.file.FileSystemAware;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.DefaultCloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.util.CloseableUtils;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.io.LoggingFilterOutputStream;
import org.apache.sshd.server.AsyncCommand;
import org.apache.sshd.server.ChannelSessionAware;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.ServerFactoryManager;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.forward.ForwardingFilter;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.x11.X11ForwardSupport;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ChannelSession extends AbstractServerChannel {

    public static final long DEFAULT_COMMAND_EXIT_TIMEOUT = 5000;

    protected static class StandardEnvironment implements Environment {

        private final Map<Signal, Set<SignalListener>> listeners;
        private final Map<String, String> env;
        private final Map<PtyMode, Integer> ptyModes;

        public StandardEnvironment() {
            listeners = new ConcurrentHashMap<>(3);
            env = new ConcurrentHashMap<>();
            ptyModes = new ConcurrentHashMap<>();
        }

        @Override
        public void addSignalListener(SignalListener listener, Signal... signals) {
            if (signals == null) {
                throw new IllegalArgumentException("signals may not be null");
            }
            
            addSignalListener(listener, Arrays.asList(signals));
        }

        @Override
        public void addSignalListener(SignalListener listener) {
            addSignalListener(listener, Signal.SIGNALS);
        }

        /*
         * NOTE: we don't care if the collection is a Set or not - after all,
         * we hold the listeners inside a Set, so even if we add several times
         * the same listener to the same signal set, it is harmless
         */
        @Override
        public void addSignalListener(SignalListener listener, Collection<Signal> signals) {
            if (listener == null) {
                throw new IllegalArgumentException("listener may not be null");
            }

            if (signals == null) {
                throw new IllegalArgumentException("signals may not be null");
            }

            for (Signal s : signals) {
                getSignalListeners(s, true).add(listener);
            }
        }

        @Override
        public Map<String, String> getEnv() {
            return env;
        }

        @Override
        public Map<PtyMode, Integer> getPtyModes() {
            return ptyModes;
        }

        @Override
        public void removeSignalListener(SignalListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener may not be null");
            }
            for (Signal s : Signal.SIGNALS) {
                final Set<SignalListener> ls = getSignalListeners(s, false);
                if (ls != null) {
                    ls.remove(listener);
                }
            }
        }

        public void signal(Signal signal) {
            final Set<SignalListener> ls = getSignalListeners(signal, false);
            if (ls != null) {
                for (SignalListener l : ls) {
                    l.signal(signal);
                }
            }
        }

        /**
         * adds a variable to the environment. This method is called <code>set</code>
         * according to the name of the appropriate posix command <code>set</code>
         * @param key environment variable name
         * @param value environment variable value
         */
        public void set(String key, String value) {
            // TODO: listening for property changes would be nice too.
            getEnv().put(key, value);
        }

        protected Set<SignalListener> getSignalListeners(Signal signal, boolean create) {
            Set<SignalListener> ls = listeners.get(signal);
            if (ls == null && create) {
                synchronized (listeners) {
                    ls = listeners.get(signal);
                    if (ls == null) {
                        ls = new CopyOnWriteArraySet<>();
                        listeners.put(signal, ls);
                    }
                }
            }
            // may be null in case create=false
            return ls;
        }
    }

    protected String type;
    protected ChannelAsyncOutputStream asyncOut;
    protected ChannelAsyncOutputStream asyncErr;
    protected OutputStream out;
    protected OutputStream err;
    protected Command command;
    protected ChannelDataReceiver receiver;
    protected StandardEnvironment env = new StandardEnvironment();
    protected Buffer tempBuffer;
    protected final CloseFuture commandExitFuture = new DefaultCloseFuture(lock);

    public ChannelSession() {
        addRequestHandler(new ChannelSessionRequestHandler());
        addRequestHandler(new PuttyRequestHandler());
    }

    @Override
    public void handleWindowAdjust(Buffer buffer) throws IOException {
        super.handleWindowAdjust(buffer);
        if (asyncOut != null) {
            asyncOut.onWindowExpanded();
        }
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .sequential(new CommandCloseable(), new GracefulChannelCloseable())
                .parallel(asyncOut, asyncErr)
                .build();
    }

    public class CommandCloseable extends CloseableUtils.IoBaseCloseable {
        @Override
        public boolean isClosed() {
            return commandExitFuture.isClosed();
        }
        @Override
        public boolean isClosing() {
            return isClosed();
        }
        @Override
        public CloseFuture close(boolean immediately) {
            if (immediately || command == null) {
                commandExitFuture.setClosed();
            } else if (!commandExitFuture.isClosed()) {
                IoUtils.closeQuietly(receiver);
                final TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        commandExitFuture.setClosed();
                    }
                };

                FactoryManager manager = getSession().getFactoryManager();
                long timeout = FactoryManagerUtils.getLongProperty(manager, ServerFactoryManager.COMMAND_EXIT_TIMEOUT, DEFAULT_COMMAND_EXIT_TIMEOUT);
                if (log.isDebugEnabled()) {
                    log.debug("Wait {} ms for shell to exit cleanly", Long.valueOf(timeout));
                }

                manager.getScheduledExecutorService().schedule(task, timeout, TimeUnit.MILLISECONDS);
                commandExitFuture.addListener(new SshFutureListener<CloseFuture>() {
                    @Override
                    public void operationComplete(CloseFuture future) {
                        task.cancel();
                    }
                });
            }
            return commandExitFuture;
        }
    }

    @Override
    protected void doCloseImmediately() {
        if (command != null) {
            command.destroy();
            command = null;
        }
        remoteWindow.notifyClosed();
        IoUtils.closeQuietly(out, err, receiver);
        super.doCloseImmediately();
    }

    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        IoUtils.closeQuietly(receiver);
    }

    @Override
    protected void doWriteData(byte[] data, int off, int len) throws IOException {
        // If we're already closing, ignore incoming data
        if (isClosing()) {
            return;
        }
        if (receiver != null) {
            int r = receiver.data(this, data, off, len);
            if (r > 0) {
                localWindow.consumeAndCheck(r);
            }
        } else {
            if (tempBuffer == null) {
                tempBuffer = new ByteArrayBuffer(len);
            }
            tempBuffer.putRawBytes(data, off, len);
        }
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, int len) throws IOException {
        throw new UnsupportedOperationException("Server channel does not support extended data");
    }

    /**
     * @param type The request type
     * @param buffer The {@link Buffer} containing extra request-specific content
     * @return A {@link Boolean} representing the success/failure of handling
     * the request - {@code null} if unknown request received
     * @throws IOException If request requires some extra response and failed
     * to generate it
     */
    public Boolean handleRequest(String type, Buffer buffer) throws IOException {
        if ("env".equals(type)) {
            return Boolean.valueOf(handleEnv(buffer));
        }
        if ("pty-req".equals(type)) {
            return Boolean.valueOf(handlePtyReq(buffer));
        }
        if ("window-change".equals(type)) {
            return Boolean.valueOf(handleWindowChange(buffer));
        }
        if ("signal".equals(type)) {
            return Boolean.valueOf(handleSignal(buffer));
        }
        if ("break".equals(type)) {
            return Boolean.valueOf(handleBreak(buffer));
        }
        if ("shell".equals(type)) {
            if ((this.type == null) && handleShell(buffer)) {
                this.type = type;
                return Boolean.TRUE;
            } else {
                return Boolean.FALSE;
            }
        }
        if ("exec".equals(type)) {
            if ((this.type == null) && handleExec(buffer)) {
                this.type = type;
                return Boolean.TRUE;
            } else {
                return Boolean.FALSE;
            }
        }
        if ("subsystem".equals(type)) {
            if ((this.type == null) && handleSubsystem(buffer)) {
                this.type = type;
                return Boolean.TRUE;
            } else {
                return Boolean.FALSE;
            }
        }
        if ("auth-agent-req@openssh.com".equals(type)) {
            return Boolean.valueOf(handleAgentForwarding(buffer));
        }
        if ("x11-req".equals(type)) {
            return Boolean.valueOf(handleX11Forwarding(buffer));
        }
        return null;
    }

    protected boolean handleEnv(Buffer buffer) throws IOException {
        String name = buffer.getString();
        String value = buffer.getString();
        addEnvVariable(name, value);
        if (log.isDebugEnabled()) {
            log.debug("env for channel {}: {} = {}", Integer.valueOf(id), name, value);
        }
        return true;
    }

    protected boolean handlePtyReq(Buffer buffer) throws IOException {
        String term = buffer.getString();
        int tColumns = buffer.getInt();
        int tRows = buffer.getInt();
        int tWidth = buffer.getInt();
        int tHeight = buffer.getInt();
        byte[] modes = buffer.getBytes();
        Environment environment = getEnvironment();
        Map<PtyMode, Integer> ptyModes = environment.getPtyModes();
        for (int i = 0; i < modes.length && modes[i] != 0;) {
            int opcode = modes[i++] & 0x00FF;
            PtyMode mode = PtyMode.fromInt(opcode);
            /**
             * According to section 8 of RFC 4254:
             * "Opcodes 160 to 255 are not yet defined, and cause parsing to stop"
             */
            if (mode == null) {
                log.warn("Unknown pty opcode value: " + opcode);
                break;
            }
            int val  = ((modes[i++] << 24) & 0xff000000) |
                       ((modes[i++] << 16) & 0x00ff0000) |
                       ((modes[i++] <<  8) & 0x0000ff00) |
                       ((modes[i++]) & 0x000000ff);
            ptyModes.put(mode, Integer.valueOf(val));
        }
        if (log.isDebugEnabled()) {
            log.debug("pty for channel {}: term={}, size=({} - {}), pixels=({}, {}), modes=[{}]",
                      Integer.valueOf(id), term, 
                      Integer.valueOf(tColumns), Integer.valueOf(tRows),
                      Integer.valueOf(tWidth), Integer.valueOf(tHeight),
                      ptyModes);
        }

        addEnvVariable(Environment.ENV_TERM, term);
        addEnvVariable(Environment.ENV_COLUMNS, Integer.toString(tColumns));
        addEnvVariable(Environment.ENV_LINES, Integer.toString(tRows));
        return true;
    }

    protected boolean handleWindowChange(Buffer buffer) throws IOException {
        int tColumns = buffer.getInt();
        int tRows = buffer.getInt();
        int tWidth = buffer.getInt();
        int tHeight = buffer.getInt();
        if (log.isDebugEnabled()) {
            log.debug("window-change for channel {}: ({} - {}), ({}, {})",
                      Integer.valueOf(id),
                      Integer.valueOf(tColumns), Integer.valueOf(tRows),
                      Integer.valueOf(tWidth), Integer.valueOf(tHeight));
        }

        final StandardEnvironment e = getEnvironment();
        e.set(Environment.ENV_COLUMNS, Integer.toString(tColumns));
        e.set(Environment.ENV_LINES, Integer.toString(tRows));
        e.signal(Signal.WINCH);
        return true;
    }

    protected boolean handleSignal(Buffer buffer) throws IOException {
        String name = buffer.getString();
        if (log.isDebugEnabled()) {
            log.debug("Signal received on channel {}: {}", Integer.valueOf(id), name);
        }

        final Signal signal = Signal.get(name);
        if (signal != null) {
            getEnvironment().signal(signal);
        } else {
            log.warn("Unknown signal received: " + name);
        }
        return true;
    }

    protected boolean handleBreak(Buffer buffer) throws IOException {
        String name = buffer.getString();
        if (log.isDebugEnabled()) {
            log.debug("Break received on channel {}: {}", Integer.valueOf(id), name);
        }

        getEnvironment().signal(Signal.INT);
        return true;
    }

    protected boolean handleShell(Buffer buffer) throws IOException {
        // If we're already closing, ignore incoming data
        if (isClosing()) {
            log.debug("handleShell - closing");
            return false;
        }
        
        ServerFactoryManager manager = ((ServerSession) session).getFactoryManager();
        Factory<Command> factory = manager.getShellFactory();
        if (factory == null) {
            log.debug("handleShell - no shell factory");
            return false;
        }
        
        if ((command = factory.create()) == null) {
            log.debug("handleShell - no shell command");
            return false;
        }

        prepareCommand();
        command.start(getEnvironment());
        return true;
    }

    protected boolean handleExec(Buffer buffer) throws IOException {
        // If we're already closing, ignore incoming data
        if (isClosing()) {
            return false;
        }

        String commandLine = buffer.getString();
        ServerFactoryManager manager = ((ServerSession) session).getFactoryManager();
        CommandFactory factory = manager.getCommandFactory();
        if (factory == null) {
            log.warn("No command factory for command: {}", commandLine);
            return false;
        }

        if (log.isDebugEnabled()) {
            log.debug("Executing command: {}", commandLine);
        }

        try {
            command = factory.createCommand(commandLine);
        } catch (RuntimeException iae) {
            log.warn("Failed (" + iae.getClass().getSimpleName() + ") to create command for " + commandLine + ": " + iae.getMessage());
            return false;
        }

        prepareCommand();
        // Launch command
        command.start(getEnvironment());
        return true;
    }

    protected boolean handleSubsystem(Buffer buffer) throws IOException {
        String subsystem = buffer.getString();
        ServerFactoryManager manager = ((ServerSession) session).getFactoryManager();
        List<NamedFactory<Command>> factories = manager.getSubsystemFactories();
        if (GenericUtils.isEmpty(factories)) {
            log.warn("No factories for subsystem: {}", subsystem);
            return false;
        }

        if ((command = NamedFactory.Utils.create(factories, subsystem)) == null) {
            log.warn("Unsupported subsystem: {}", subsystem);
            return false;
        }

        prepareCommand();
        // Launch command
        command.start(getEnvironment());
        return true;
    }

    /**
     * For {@link Command} to install {@link ChannelDataReceiver}.
     * When you do this, {@link Command#setInputStream(InputStream)} or
     * {@link org.apache.sshd.server.AsyncCommand#setIoInputStream(org.apache.sshd.common.io.IoInputStream)}
     * will no longer be invoked. If you call this method from {@link Command#start(Environment)},
     * the input stream you received in {@link Command#setInputStream(InputStream)} will
     * not read any data.
     */
    public void setDataReceiver(ChannelDataReceiver receiver) {
        this.receiver = receiver;
    }

    protected void prepareCommand() throws IOException {
        // Add the user
        addEnvVariable(Environment.ENV_USER, session.getUsername());
        // If the shell wants to be aware of the session, let's do that
        if (command instanceof SessionAware) {
            ((SessionAware) command).setSession((ServerSession) session);
        }
        if (command instanceof ChannelSessionAware) {
            ((ChannelSessionAware) command).setChannelSession(this);
        }
        // If the shell wants to be aware of the file system, let's do that too
        if (command instanceof FileSystemAware) {
            FileSystemFactory factory = ((ServerSession) session).getFactoryManager().getFileSystemFactory();
            ((FileSystemAware) command).setFileSystem(factory.createFileSystem(session));
        }
        // If the shell wants to use non-blocking io
        if (command instanceof AsyncCommand) {
            asyncOut = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA);
            asyncErr = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_EXTENDED_DATA);
            ((AsyncCommand) command).setIoOutputStream(asyncOut);
            ((AsyncCommand) command).setIoErrorStream(asyncErr);
        } else {
            out = new ChannelOutputStream(this, remoteWindow, log, SshConstants.SSH_MSG_CHANNEL_DATA);
            err = new ChannelOutputStream(this, remoteWindow, log, SshConstants.SSH_MSG_CHANNEL_EXTENDED_DATA);
            if (log.isTraceEnabled()) {
                // Wrap in logging filters
                out = new LoggingFilterOutputStream(out, "OUT:", log);
                err = new LoggingFilterOutputStream(err, "ERR:", log);
            }
            command.setOutputStream(out);
            command.setErrorStream(err);
        }
        if (this.receiver==null) {
            // if the command hasn't installed any ChannelDataReceiver, install the default
            // and give the command an InputStream
            if (command instanceof AsyncCommand) {
                AsyncDataReceiver recv = new AsyncDataReceiver(this);
                setDataReceiver(recv);
                ((AsyncCommand) command).setIoInputStream(recv.getIn());
            } else {
                PipeDataReceiver recv = new PipeDataReceiver(localWindow);
                setDataReceiver(recv);
                command.setInputStream(recv.getIn());
            }
        }
        if (tempBuffer != null) {
            Buffer buffer = tempBuffer;
            tempBuffer = null;
            doWriteData(buffer.array(), buffer.rpos(), buffer.available());
        }
        command.setExitCallback(new ExitCallback() {
            @Override
            public void onExit(int exitValue) {
                onExit(exitValue, "");
            }

            @Override
            @SuppressWarnings("synthetic-access")
            public void onExit(int exitValue, String exitMessage) {
                try {
                    closeShell(exitValue);
                    if (log.isDebugEnabled()) {
                        log.debug("onExit(" + exitValue + ")[" + exitMessage + ") shell closed");
                    }
                } catch (IOException e) {
                    log.warn("onExit(" + exitValue + ")[" + exitMessage + ") Error closing shell", e);
                }
            }
        });
    }

    protected int getPtyModeValue(PtyMode mode) {
        Number v = getEnvironment().getPtyModes().get(mode);
        return v != null ? v.intValue() : 0;
    }

    protected boolean handleAgentForwarding(Buffer buffer) throws IOException {
        ValidateUtils.checkTrue(session instanceof ServerSession, "Session not a server one", GenericUtils.EMPTY_OBJECT_ARRAY);
        FactoryManager manager = session.getFactoryManager();
        ForwardingFilter filter = manager.getTcpipForwardingFilter();
        SshAgentFactory factory = manager.getAgentFactory();
        if ((factory == null) || (filter == null) || (!filter.canForwardAgent(session))) {
            if (log.isDebugEnabled()) {
                log.debug("handleAgentForwarding(" + session + ")[haveFactory=" + (factory != null) + ",haveFilter=" + (filter != null) + "] filtered out");
            }
            return false;
        }

        String authSocket = service.initAgentForward();
        addEnvVariable(SshAgent.SSH_AUTHSOCKET_ENV_NAME, authSocket);
        return true;
    }

    protected boolean handleX11Forwarding(Buffer buffer) throws IOException {
        ValidateUtils.checkTrue(session instanceof ServerSession, "Session not a server one", GenericUtils.EMPTY_OBJECT_ARRAY);
        FactoryManager manager = session.getFactoryManager();
        ForwardingFilter filter = manager.getTcpipForwardingFilter();
        if ((filter == null) || (!filter.canForwardX11(session))) {
            if (log.isDebugEnabled()) {
                log.debug("handleX11Forwarding(" + session + ")[haveFilter=" + (filter != null) + "] filtered out");
            }
            return false;
        }

        boolean singleConnection = buffer.getBoolean();
        String authProtocol = buffer.getString();
        String authCookie = buffer.getString();
        int screenId = buffer.getInt();
        String display = service.createX11Display(singleConnection, authProtocol, authCookie, screenId);
        if (GenericUtils.isEmpty(display)) {
            if (log.isDebugEnabled()) {
                log.debug("handleX11Forwarding(" + session + ") no X.11 display created");
            }
            return false;
        }

        addEnvVariable(X11ForwardSupport.ENV_DISPLAY, display);
        return true;
    }

    protected void addEnvVariable(String name, String value) {
        getEnvironment().set(name, value);
    }

    protected StandardEnvironment getEnvironment() {
        return env;
    }

    protected void closeShell(int exitValue) throws IOException {
        if (!isClosing()) {
            sendEof();
            sendExitStatus(exitValue);
            commandExitFuture.setClosed();
            close(false);
        } else {
            commandExitFuture.setClosed();
        }
    }

    private class ChannelSessionRequestHandler implements ChannelRequestHandler {
        public ChannelSessionRequestHandler() {
            super();
        }

        @Override
        public Result process(Channel channel, String request, boolean wantReply, Buffer buffer) throws Exception {
            Boolean r = handleRequest(request, buffer);
            if (r == null) {
                return Result.Unsupported;
            } else {
                return r.booleanValue() ? Result.ReplySuccess : Result.ReplyFailure;
            }
        }
    }
}
