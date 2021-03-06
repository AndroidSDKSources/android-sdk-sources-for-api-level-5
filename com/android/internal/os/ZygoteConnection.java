/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.net.Credentials;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;

import dalvik.system.PathClassLoader;
import dalvik.system.Zygote;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;

/**
 * A connection that can make spawn requests.
 */
class ZygoteConnection {
    private static final String TAG = "Zygote";

    /** a prototype instance for a future List.toArray() */
    private static final int[][] intArray2d = new int[0][0];

    /**
     * {@link android.net.LocalSocket#setSoTimeout} value for connections.
     * Effectively, the amount of time a requestor has between the start of
     * the request and the completed request. The select-loop mode Zygote
     * doesn't have the logic to return to the select loop in the middle of
     * a request, so we need to time out here to avoid being denial-of-serviced.
     */
    private static final int CONNECTION_TIMEOUT_MILLIS = 1000;

    /** max number of arguments that a connection can specify */
    private static final int MAX_ZYGOTE_ARGC=1024;

    /**
     * The command socket.
     *
     * mSocket is retained in the child process in "peer wait" mode, so
     * that it closes when the child process terminates. In other cases,
     * it is closed in the peer.
     */
    private final LocalSocket mSocket;
    private final DataOutputStream mSocketOutStream;
    private final BufferedReader mSocketReader;
    private final Credentials peer;

    /**
     * A long-lived reference to the original command socket used to launch
     * this peer. If "peer wait" mode is specified, the process that requested
     * the new VM instance intends to track the lifetime of the spawned instance
     * via the command socket. In this case, the command socket is closed
     * in the Zygote and placed here in the spawned instance so that it will
     * not be collected and finalized. This field remains null at all times
     * in the original Zygote process, and in all spawned processes where
     * "peer-wait" mode was not requested.
     */
    private static LocalSocket sPeerWaitSocket = null;

    /**
     * Constructs instance from connected socket.
     *
     * @param socket non-null; connected socket
     * @throws IOException
     */
    ZygoteConnection(LocalSocket socket) throws IOException {
        mSocket = socket;

        mSocketOutStream
                = new DataOutputStream(socket.getOutputStream());

        mSocketReader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()), 256);

        mSocket.setSoTimeout(CONNECTION_TIMEOUT_MILLIS);
                
        try {
            peer = mSocket.getPeerCredentials();
        } catch (IOException ex) {
            Log.e(TAG, "Cannot read peer credentials", ex);
            throw ex;
        }
    }

    /**
     * Returns the file descriptor of the associated socket.
     *
     * @return null-ok; file descriptor
     */
    FileDescriptor getFileDesciptor() {
        return mSocket.getFileDescriptor();
    }

    /**
     * Reads start commands from an open command socket.
     * Start commands are presently a pair of newline-delimited lines
     * indicating a) class to invoke main() on b) nice name to set argv[0] to.
     * Continues to read commands and forkAndSpecialize children until
     * the socket is closed. This method is used in ZYGOTE_FORK_MODE
     *
     * @throws ZygoteInit.MethodAndArgsCaller trampoline to invoke main()
     * method in child process
     */
    void run() throws ZygoteInit.MethodAndArgsCaller {

        int loopCount = ZygoteInit.GC_LOOP_COUNT;

        while (true) {
            /*
             * Call gc() before we block in readArgumentList().
             * It's work that has to be done anyway, and it's better
             * to avoid making every child do it.  It will also
             * madvise() any free memory as a side-effect.
             *
             * Don't call it every time, because walking the entire
             * heap is a lot of overhead to free a few hundred bytes.
             */
            if (loopCount <= 0) {
                ZygoteInit.gc();
                loopCount = ZygoteInit.GC_LOOP_COUNT;
            } else {
                loopCount--;
            }

            if (runOnce()) {
                break;
            }
        }
    }

    /**
     * Reads one start command from the command socket. If successful,
     * a child is forked and a {@link ZygoteInit.MethodAndArgsCaller}
     * exception is thrown in that child while in the parent process,
     * the method returns normally. On failure, the child is not
     * spawned and messages are printed to the log and stderr. Returns
     * a boolean status value indicating whether an end-of-file on the command
     * socket has been encountered.
     *
     * @return false if command socket should continue to be read from, or
     * true if an end-of-file has been encountered.
     * @throws ZygoteInit.MethodAndArgsCaller trampoline to invoke main()
     * method in child process
     */
    boolean runOnce() throws ZygoteInit.MethodAndArgsCaller {

        String args[];
        Arguments parsedArgs = null;
        FileDescriptor[] descriptors;

        try {
            args = readArgumentList();
            descriptors = mSocket.getAncillaryFileDescriptors();
        } catch (IOException ex) {
            Log.w(TAG, "IOException on command socket " + ex.getMessage());
            closeSocket();
            return true;
        }

        if (args == null) {
            // EOF reached.
            closeSocket();
            return true;
        }

        /** the stderr of the most recent request, if avail */
        PrintStream newStderr = null;

        if (descriptors != null && descriptors.length >= 3) {
            newStderr = new PrintStream(
                    new FileOutputStream(descriptors[2]));
        }

        int pid;

        try {
            parsedArgs = new Arguments(args);

            applyUidSecurityPolicy(parsedArgs, peer);
            applyDebuggerSecurityPolicy(parsedArgs);
            applyRlimitSecurityPolicy(parsedArgs, peer);
            applyCapabilitiesSecurityPolicy(parsedArgs, peer);

            int[][] rlimits = null;

            if (parsedArgs.rlimits != null) {
                rlimits = parsedArgs.rlimits.toArray(intArray2d);
            }

            pid = Zygote.forkAndSpecialize(parsedArgs.uid, parsedArgs.gid,
                    parsedArgs.gids, parsedArgs.debugFlags, rlimits);
        } catch (IllegalArgumentException ex) {
            logAndPrintError (newStderr, "Invalid zygote arguments", ex);
            pid = -1;
        } catch (ZygoteSecurityException ex) {
            logAndPrintError(newStderr,
                    "Zygote security policy prevents request: ", ex);
            pid = -1;
        }

        if (pid == 0) {
            // in child
            handleChildProc(parsedArgs, descriptors, newStderr);
            // should never happen
            return true;
        } else { /* pid != 0 */
            // in parent...pid of < 0 means failure
            return handleParentProc(pid, descriptors, parsedArgs);
        }
    }

    /**
     * Closes socket associated with this connection.
     */
    void closeSocket() {
        try {
            mSocket.close();
        } catch (IOException ex) {
            Log.e(TAG, "Exception while closing command "
                    + "socket in parent", ex);
        }
    }

    /**
     * Handles argument parsing for args related to the zygote spawner.<p>

     * Current recognized args:
     * <ul>
     *   <li> --setuid=<i>uid of child process, defaults to 0</i>
     *   <li> --setgid=<i>gid of child process, defaults to 0</i>
     *   <li> --setgroups=<i>comma-separated list of supplimentary gid's</i>
     *   <li> --capabilities=<i>a pair of comma-separated integer strings
     * indicating Linux capabilities(2) set for child. The first string
     * represents the <code>permitted</code> set, and the second the
     * <code>effective</code> set. Precede each with 0 or
     * 0x for octal or hexidecimal value. If unspecified, both default to 0.
     * This parameter is only applied if the uid of the new process will
     * be non-0. </i>
     *   <li> --rlimit=r,c,m<i>tuple of values for setrlimit() call.
     *    <code>r</code> is the resource, <code>c</code> and <code>m</code>
     *    are the settings for current and max value.</i>
     *   <li> --peer-wait indicates that the command socket should
     * be inherited by (and set to close-on-exec in) the spawned process
     * and used to track the lifetime of that process. The spawning process
     * then exits. Without this flag, it is retained by the spawning process
     * (and closed in the child) in expectation of a new spawn request.
     *   <li> --classpath=<i>colon-separated classpath</i> indicates
     * that the specified class (which must b first non-flag argument) should
     * be loaded from jar files in the specified classpath. Incompatible with
     * --runtime-init
     *   <li> --runtime-init indicates that the remaining arg list should
     * be handed off to com.android.internal.os.RuntimeInit, rather than
     * processed directly
     * Android runtime startup (eg, Binder initialization) is also eschewed.
     *   <li> If <code>--runtime-init</code> is present:
     *      [--] &lt;args for RuntimeInit &gt;
     *   <li> If <code>--runtime-init</code> is absent:
     *      [--] &lt;classname&gt; [args...]
     * </ul>
     */
    static class Arguments {
        /** from --setuid */
        int uid = 0;
        boolean uidSpecified;

        /** from --setgid */
        int gid = 0;
        boolean gidSpecified;

        /** from --setgroups */
        int[] gids;

        /** from --peer-wait */
        boolean peerWait;

        /** from --enable-debugger, --enable-checkjni, --enable-assert */
        int debugFlags;

        /** from --classpath */
        String classpath;

        /** from --runtime-init */
        boolean runtimeInit;

        /** from --capabilities */
        boolean capabilitiesSpecified;
        long permittedCapabilities;
        long effectiveCapabilities;

        /** from all --rlimit=r,c,m */
        ArrayList<int[]> rlimits;

        /**
         * Any args after and including the first non-option arg
         * (or after a '--')
         */
        String remainingArgs[];

        /**
         * Constructs instance and parses args
         * @param args zygote command-line args
         * @throws IllegalArgumentException
         */
        Arguments(String args[]) throws IllegalArgumentException {
            parseArgs(args);
        }

        /**
         * Parses the commandline arguments intended for the Zygote spawner
         * (such as "--setuid=" and "--setgid=") and creates an array
         * containing the remaining args.
         *
         * Per security review bug #1112214, duplicate args are disallowed in
         * critical cases to make injection harder.
         */
        private void parseArgs(String args[])
                throws IllegalArgumentException {
            int curArg = 0;

            for ( /* curArg */ ; curArg < args.length; curArg++) {
                String arg = args[curArg];

                if (arg.equals("--")) {
                    curArg++;
                    break;
                } else if (arg.startsWith("--setuid=")) {
                    if (uidSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    uidSpecified = true;
                    uid = Integer.parseInt(
                            arg.substring(arg.indexOf('=') + 1));
                } else if (arg.startsWith("--setgid=")) {
                    if (gidSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    gidSpecified = true;
                    gid = Integer.parseInt(
                            arg.substring(arg.indexOf('=') + 1));
                } else if (arg.equals("--enable-debugger")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
                } else if (arg.equals("--enable-checkjni")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
                } else if (arg.equals("--enable-assert")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
                } else if (arg.equals("--peer-wait")) {
                    peerWait = true;
                } else if (arg.equals("--runtime-init")) {
                    runtimeInit = true;
                } else if (arg.startsWith("--capabilities=")) {
                    if (capabilitiesSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    capabilitiesSpecified = true;
                    String capString = arg.substring(arg.indexOf('=')+1);

                    String[] capStrings = capString.split(",", 2);

                    if (capStrings.length == 1) {
                        effectiveCapabilities = Long.decode(capStrings[0]);
                        permittedCapabilities = effectiveCapabilities;
                    } else {
                        permittedCapabilities = Long.decode(capStrings[0]);
                        effectiveCapabilities = Long.decode(capStrings[1]);
                    }
                } else if (arg.startsWith("--rlimit=")) {
                    // Duplicate --rlimit arguments are specifically allowed.
                    String[] limitStrings
                            = arg.substring(arg.indexOf('=')+1).split(",");

                    if (limitStrings.length != 3) {
                        throw new IllegalArgumentException(
                                "--rlimit= should have 3 comma-delimited ints");
                    }
                    int[] rlimitTuple = new int[limitStrings.length];

                    for(int i=0; i < limitStrings.length; i++) {
                        rlimitTuple[i] = Integer.parseInt(limitStrings[i]);
                    }

                    if (rlimits == null) {
                        rlimits = new ArrayList();
                    }

                    rlimits.add(rlimitTuple);
                } else if (arg.equals("-classpath")) {
                    if (classpath != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    try {
                        classpath = args[++curArg];
                    } catch (IndexOutOfBoundsException ex) {
                        throw new IllegalArgumentException(
                                "-classpath requires argument");
                    }
                } else if (arg.startsWith("--setgroups=")) {
                    if (gids != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }

                    String[] params
                            = arg.substring(arg.indexOf('=') + 1).split(",");

                    gids = new int[params.length];

                    for (int i = params.length - 1; i >= 0 ; i--) {
                        gids[i] = Integer.parseInt(params[i]);
                    }
                } else {
                    break;
                }
            }

            if (runtimeInit && classpath != null) {
                throw new IllegalArgumentException(
                        "--runtime-init and -classpath are incompatible");
            }

            remainingArgs = new String[args.length - curArg];

            System.arraycopy(args, curArg, remainingArgs, 0,
                    remainingArgs.length);
        }
    }

    /**
     * Reads an argument list from the command socket/
     * @return Argument list or null if EOF is reached
     * @throws IOException passed straight through
     */
    private String[] readArgumentList()
            throws IOException {

        /**
         * See android.os.Process.zygoteSendArgsAndGetPid()
         * Presently the wire format to the zygote process is:
         * a) a count of arguments (argc, in essence)
         * b) a number of newline-separated argument strings equal to count
         *
         * After the zygote process reads these it will write the pid of
         * the child or -1 on failure.
         */

        int argc;

        try {
            String s = mSocketReader.readLine();

            if (s == null) {
                // EOF reached.
                return null;
            }
            argc = Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            Log.e(TAG, "invalid Zygote wire format: non-int at argc");
            throw new IOException("invalid wire format");
        }

        // See bug 1092107: large argc can be used for a DOS attack
        if (argc > MAX_ZYGOTE_ARGC) {   
            throw new IOException("max arg count exceeded");
        }

        String[] result = new String[argc];
        for (int i = 0; i < argc; i++) {
            result[i] = mSocketReader.readLine();
            if (result[i] == null) {
                // We got an unexpected EOF.
                throw new IOException("truncated request");
            }
        }

        return result;
    }

    /**
     * Applies zygote security policy per bugs #875058 and #1082165. 
     * Based on the credentials of the process issuing a zygote command:
     * <ol>
     * <li> uid 0 (root) may specify any uid, gid, and setgroups() list
     * <li> uid 1000 (Process.SYSTEM_UID) may specify any uid &gt; 1000 in normal
     * operation. It may also specify any gid and setgroups() list it chooses.
     * In factory test mode, it may specify any UID.
     * <li> Any other uid may not specify any uid, gid, or setgroups list. The
     * uid and gid will be inherited from the requesting process.
     * </ul>
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyUidSecurityPolicy(Arguments args, Credentials peer)
            throws ZygoteSecurityException {

        int peerUid = peer.getUid();

        if (peerUid == 0) {
            // Root can do what it wants
        } else if (peerUid == Process.SYSTEM_UID ) {
            // System UID is restricted, except in factory test mode
            String factoryTest = SystemProperties.get("ro.factorytest");
            boolean uidRestricted;

            /* In normal operation, SYSTEM_UID can only specify a restricted
             * set of UIDs. In factory test mode, SYSTEM_UID may specify any uid.
             */
            uidRestricted  
                 = !(factoryTest.equals("1") || factoryTest.equals("2"));

            if (uidRestricted
                    && args.uidSpecified && (args.uid < Process.SYSTEM_UID)) {
                throw new ZygoteSecurityException(
                        "System UID may not launch process with UID < "
                                + Process.SYSTEM_UID);
            }
        } else {
            // Everything else
            if (args.uidSpecified || args.gidSpecified
                || args.gids != null) {
                throw new ZygoteSecurityException(
                        "App UIDs may not specify uid's or gid's");
            }
        }

        // If not otherwise specified, uid and gid are inherited from peer
        if (!args.uidSpecified) {
            args.uid = peer.getUid();
            args.uidSpecified = true;
        }
        if (!args.gidSpecified) {
            args.gid = peer.getGid();
            args.gidSpecified = true;
        }
    }


    /**
     * Applies debugger security policy.
     * If "ro.debuggable" is "1", all apps are debuggable. Otherwise,
     * the debugger state is specified via the "--enable-debugger" flag
     * in the spawn request.
     *
     * @param args non-null; zygote spawner args
     */
    private static void applyDebuggerSecurityPolicy(Arguments args) {
        if ("1".equals(SystemProperties.get("ro.debuggable"))) {
            args.debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
        }
    }

    /**
     * Applies zygote security policy per bug #1042973. Based on the credentials
     * of the process issuing a zygote command:
     * <ol>
     * <li> peers of  uid 0 (root) and uid 1000 (Process.SYSTEM_UID)
     * may specify any rlimits.
     * <li> All other uids may not specify rlimits.
     * </ul>
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyRlimitSecurityPolicy(
            Arguments args, Credentials peer)
            throws ZygoteSecurityException {

        int peerUid = peer.getUid();

        if (!(peerUid == 0 || peerUid == Process.SYSTEM_UID)) {
            // All peers with UID other than root or SYSTEM_UID
            if (args.rlimits != null) {
                throw new ZygoteSecurityException(
                        "This UID may not specify rlimits.");
            }
        }
    }

    /**
     * Applies zygote security policy per bug #1042973. A root peer may
     * spawn an instance with any capabilities. All other uids may spawn
     * instances with any of the capabilities in the peer's permitted set
     * but no more.
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyCapabilitiesSecurityPolicy(
            Arguments args, Credentials peer)
            throws ZygoteSecurityException {

        if (args.permittedCapabilities == 0
                && args.effectiveCapabilities == 0) {
            // nothing to check
            return;
        }

        if (peer.getUid() == 0) {
            // root may specify anything
            return;
        }

        long permittedCaps;

        try {
            permittedCaps = ZygoteInit.capgetPermitted(peer.getPid());
        } catch (IOException ex) {
            throw new ZygoteSecurityException(
                    "Error retrieving peer's capabilities.");
        }

        /*
         * Ensure that the client did not specify an effective set larger
         * than the permitted set. The kernel will enforce this too, but we
         * do it here to make the following check easier.
         */
        if (((~args.permittedCapabilities) & args.effectiveCapabilities) != 0) {
            throw new ZygoteSecurityException(
                    "Effective capabilities cannot be superset of "
                            + " permitted capabilities" );
        }

        /*
         * Ensure that the new permitted (and thus the new effective) set is
         * a subset of the peer process's permitted set
         */

        if (((~permittedCaps) & args.permittedCapabilities) != 0) {
            throw new ZygoteSecurityException(
                    "Peer specified unpermitted capabilities" );
        }
    }

    /**
     * Handles post-fork setup of child proc, closing sockets as appropriate,
     * reopen stdio as appropriate, and ultimately throwing MethodAndArgsCaller
     * if successful or returning if failed.
     *
     * @param parsedArgs non-null; zygote args
     * @param descriptors null-ok; new file descriptors for stdio if available.
     * @param newStderr null-ok; stream to use for stderr until stdio
     * is reopened.
     *
     * @throws ZygoteInit.MethodAndArgsCaller on success to
     * trampoline to code that invokes static main.
     */
    private void handleChildProc(Arguments parsedArgs,
            FileDescriptor[] descriptors, PrintStream newStderr)
            throws ZygoteInit.MethodAndArgsCaller {

        /*
         * First, set the capabilities if necessary
         */

        if (parsedArgs.uid != 0) {
            try {
                ZygoteInit.setCapabilities(parsedArgs.permittedCapabilities,
                        parsedArgs.effectiveCapabilities);
            } catch (IOException ex) {
                Log.e(TAG, "Error setting capabilities", ex);
            }
        }

        /*
         * Close the socket, unless we're in "peer wait" mode, in which
         * case it's used to track the liveness of this process.
         */

        if (parsedArgs.peerWait) {
            try {
                ZygoteInit.setCloseOnExec(mSocket.getFileDescriptor(), true);
                sPeerWaitSocket = mSocket;
            } catch (IOException ex) {
                Log.e(TAG, "Zygote Child: error setting peer wait "
                        + "socket to be close-on-exec", ex);
            }
        } else {
            closeSocket();
            ZygoteInit.closeServerSocket();
        }

        if (descriptors != null) {
            try {
                ZygoteInit.reopenStdio(descriptors[0],
                        descriptors[1], descriptors[2]);

                for (FileDescriptor fd: descriptors) {
                    ZygoteInit.closeDescriptor(fd);
                }
                newStderr = System.err;
            } catch (IOException ex) {
                Log.e(TAG, "Error reopening stdio", ex);
            }
        }

        if (parsedArgs.runtimeInit) {
            RuntimeInit.zygoteInit(parsedArgs.remainingArgs);
        } else {
            ClassLoader cloader;

            if (parsedArgs.classpath != null) {
                cloader
                    = new PathClassLoader(parsedArgs.classpath,
                    ClassLoader.getSystemClassLoader());
            } else {
                cloader = ClassLoader.getSystemClassLoader();
            }

            String className;
            try {
                className = parsedArgs.remainingArgs[0];
            } catch (ArrayIndexOutOfBoundsException ex) {
                logAndPrintError (newStderr,
                        "Missing required class name argument", null);
                return;
            }
            String[] mainArgs
                    = new String[parsedArgs.remainingArgs.length - 1];

            System.arraycopy(parsedArgs.remainingArgs, 1,
                    mainArgs, 0, mainArgs.length);

            try {
                ZygoteInit.invokeStaticMain(cloader, className, mainArgs);
            } catch (RuntimeException ex) {
                logAndPrintError (newStderr, "Error starting. ", ex);
            }
        }
    }

    /**
     * Handles post-fork cleanup of parent proc
     *
     * @param pid != 0; pid of child if &gt; 0 or indication of failed fork
     * if &lt; 0;
     * @param descriptors null-ok; file descriptors for child's new stdio if
     * specified.
     * @param parsedArgs non-null; zygote args
     * @return true for "exit command loop" and false for "continue command
     * loop"
     */
    private boolean handleParentProc(int pid,
            FileDescriptor[] descriptors, Arguments parsedArgs) {

        if(pid > 0) {
            // Try to move the new child into the peer's process group.
            try {
                ZygoteInit.setpgid(pid, ZygoteInit.getpgid(peer.getPid()));
            } catch (IOException ex) {
                // This exception is expected in the case where
                // the peer is not in our session
                // TODO get rid of this log message in the case where
                // getsid(0) != getsid(peer.getPid())
                Log.i(TAG, "Zygote: setpgid failed. This is "
                    + "normal if peer is not in our session");
            }
        }

        try {
            if (descriptors != null) {
                for (FileDescriptor fd: descriptors) {
                    ZygoteInit.closeDescriptor(fd);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Error closing passed descriptors in "
                    + "parent process", ex);
        }

        try {
            mSocketOutStream.writeInt(pid);
        } catch (IOException ex) {
            Log.e(TAG, "Error reading from command socket", ex);
            return true;
        }

        /*
         * If the peer wants to use the socket to wait on the
         * newly spawned process, then we're all done.
         */
        if (parsedArgs.peerWait) {
            try {
                mSocket.close();
            } catch (IOException ex) {
                Log.e(TAG, "Zygote: error closing sockets", ex);
            }
            return true;
        }
        return false;
    }

    /**
     * Logs an error message and prints it to the specified stream, if
     * provided
     *
     * @param newStderr null-ok; a standard error stream
     * @param message non-null; error message
     * @param ex null-ok an exception
     */
    private static void logAndPrintError (PrintStream newStderr,
            String message, Throwable ex) {
        Log.e(TAG, message, ex);
        if (newStderr != null) {
            newStderr.println(message + (ex == null ? "" : ex));
        }
    }
}
