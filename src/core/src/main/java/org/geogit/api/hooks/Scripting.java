/* Copyright (c) 2011 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the LGPL 2.1 license, available at the root
 * application directory.
 */
package org.geogit.api.hooks;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.plumbing.ResolveRepository;
import org.geogit.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

/**
 * Utilities to execute scripts representing hooks for GeoGit operations
 * 
 */
public class Scripting {

    private static final Logger LOGGER = LoggerFactory.getLogger(Scripting.class);

    private static final String PARAMS = "params";

    private static final String GEOGIT = "geogit";

    private static ScriptEngineManager factory = new ScriptEngineManager();

    /**
     * Runs a script
     * 
     * @param scriptFile the script file to run
     * @param operation the operation triggering the script, to provide context for the script. This
     *        object might get modified if the script modifies it to alter how the command is called
     *        (for instance, changing the commit message in a commit operation)
     * @throws CannotRunGeogitOperationException
     */
    public static void runJVMScript(AbstractGeoGitOp<?> operation, File scriptFile)
            throws CannotRunGeogitOperationException {

        checkArgument(scriptFile.exists(), "Script file does not exist %s", scriptFile.getPath());

        LOGGER.info("Running jvm script {}", scriptFile.getAbsolutePath());
        final String filename = scriptFile.getName();
        final String ext = Files.getFileExtension(filename);

        final ScriptEngine engine = factory.getEngineByExtension(ext);

        try {
            Map<String, Object> params = getParamMap(operation);
            engine.put(PARAMS, params);
            Repository repo = operation.command(ResolveRepository.class).call();
            engine.put(GEOGIT, new GeoGitAPI(repo));
            engine.eval(new FileReader(scriptFile));
            Object map = engine.get(PARAMS);
            setParamMap((Map<String, Object>) map, operation);
        } catch (ScriptException e) {
            Throwable cause = Throwables.getRootCause(e);
            // TODO: improve this hack to check exception type
            if (cause.toString().contains(CannotRunGeogitOperationException.class.getSimpleName())) {
                String msg = cause.getMessage();
                msg = msg.substring(CannotRunGeogitOperationException.class.getName().length() + 2,
                        msg.lastIndexOf("(")).trim();
                msg += " (command aborted by .geogit/hooks/" + scriptFile.getName() + ")";
                throw new CannotRunGeogitOperationException(msg);
            } else {
                // we ignore all exceptions caused by malformed scripts. We consider them as if
                // there was no script for this hook
                return;
            }
        } catch (Exception e) {
        }
    }

    public static void runShellScript(final File scriptFile)
            throws CannotRunGeogitOperationException {

        LOGGER.info("Running shell script {}", scriptFile.getAbsolutePath());

        // try running the script directly as an executable file
        List<String> commandAndArgs = Lists.newArrayList();
        ProcessBuilder pb = new ProcessBuilder(commandAndArgs);
        if (isWindows()) {
            commandAndArgs.add("cmd.exe");
            commandAndArgs.add("/C");
            commandAndArgs.add(scriptFile.getPath());
        } else {
            if (!scriptFile.canExecute()) {
                return;
            }
            commandAndArgs.add(scriptFile.getPath());
        }

        try {
            LOGGER.debug("-- starting process {}", scriptFile);
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            LOGGER.debug("-- process {} started", scriptFile);

            final StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(),
                    System.out);
            outputGobbler.start();
            int exitCode;
            try {
                LOGGER.debug("-- waiting for process {} to finish", scriptFile);
                exitCode = process.waitFor();
                LOGGER.debug("process {} exit code: {}", scriptFile, exitCode);
            } finally {
                outputGobbler.stop();
            }
            if (exitCode != 0) {
                // the script exited with non-zero code, so we indicate it throwing the
                // corresponding exception.
                // TODO: get message?
                throw new CannotRunGeogitOperationException(
                        "Hook script exited with non-zero error code");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return; // can't run scripts, so there is nothing that blocks running the
                    // command, and we can return
        } catch (InterruptedException e) {
            e.printStackTrace();
            return; // can't run scripts, so there is nothing that blocks running the
                    // command, and we can return

        }
    }

    /**
     * Method for getting values of parameters, including private fields. This is to be used from
     * scripting languages to create hooks for available commands. TODO: Review this and maybe
     * change this way of accessing values
     * 
     * @param operation
     * 
     * @param param the name of the parameter
     * @return the value of the parameter
     */
    public static Map<String, Object> getParamMap(AbstractGeoGitOp<?> operation) {
        Map<String, Object> map = Maps.newHashMap();
        try {
            Field[] fields = operation.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                String name = field.getName();
                Object value = field.get(operation);
                map.put(name, value);
            }
        } catch (SecurityException e) {
            return map;
        } catch (IllegalArgumentException e) {
            return map;
        } catch (IllegalAccessException e) {
            return map;
        }
        return map;

    }

    /**
     * Method to set fields in the operation object. This is to be used to communicate with script
     * hooks, so the operation can be modified in the hook, changing the values of its fields.
     * Entries corresponding to inexistent fields are ignored
     * 
     * @param operation
     * 
     * @param a map of new field values. Keys are field names
     */
    public static void setParamMap(Map<String, Object> map, AbstractGeoGitOp<?> operation) {
        try {
            Field[] fields = operation.getClass().getDeclaredFields();
            Set<String> keys = map.keySet();
            for (Field field : fields) {
                if (keys.contains(field.getName())) {
                    field.setAccessible(true);
                    field.set(operation, map.get(field.getName()));
                }
            }
        } catch (Exception e) {
            // if the script contains wrong variables, and it causes exceptions, or there
            // is any other problem, we just ignore it, and the original command will be executed
        }

    }

    public static boolean isWindows() {
        final String os = System.getProperty("os.name").toLowerCase();
        return (os.indexOf("win") >= 0);
    }

    private static class StreamGobbler extends Thread {

        InputStream is;

        OutputStream out;

        StreamGobbler(final InputStream is, final OutputStream out) {
            this.is = is;
            this.out = out;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                String line = null;
                int c;
                while ((c = is.read()) != -1) {
                    out.write(c);
                }
            } catch (final IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    public static CommandHook createScriptHook(final File file, final boolean preHook) {
        final String filename = file.getName();
        final String ext = Files.getFileExtension(filename);

        final File preScript = preHook ? file : null;
        final File postScript = preHook ? null : file;

        final CommandHook hook;
        final ScriptEngine engine = factory.getEngineByExtension(ext);
        if (engine == null) {
            hook = new ShellScriptHook(preScript, postScript);
        } else {
            hook = new JVMScriptHook(preScript, postScript);
        }
        return hook;
    }
}
