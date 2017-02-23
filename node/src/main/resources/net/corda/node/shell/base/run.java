package net.corda.node.shell.base;

import com.google.common.collect.*;
import net.corda.core.messaging.*;
import net.corda.core.utilities.*;
import net.corda.jackson.*;
import org.crsh.cli.*;
import org.crsh.command.*;
import org.crsh.text.*;
import org.jetbrains.annotations.*;

import java.util.*;

// This file is actually compiled at runtime with a bundled Java compiler by CRaSH. That's pretty weak: being able
// to do this is a neat party trick and means people can write new commands in Java then just drop them into
// their node directory, but it makes the first usage of the command slower for no good reason. There is a PR
// in the upstream CRaSH project that adds an ExternalResolver which might be useful. Then we could convert this
// file to Kotlin too.
//
// TODO: Find a way to inject this directly into CRaSH as a command, without needing JIT source compilation.
// TODO: Add serialisers for InputStream so attachments can be uploaded through the shell.
// TODO: Do something sensible with commands that return a future.
// TODO: Set up handling of responses with observables in them.

public class run extends BaseCommand {
    @Command
    @Man(
        "Runs a method from the CordaRPCOps interface, which is the same interface exposed to RPC clients.\n\n" +

        "You can learn more about what commands are available by typing 'run' just by itself, or by\n" +
        "consulting the developer guide at https://docs.corda.net/api/kotlin/corda/net.corda.core.messaging/-corda-r-p-c-ops/index.html"
    )
    @Usage("runs a method from the CordaRPCOps interface on the node.")
    public Object main(
            InvocationContext<Map> context,
            @Usage("The command to run") @Argument(unquote = false) List<String> command
    ) {
        CordaRPCOps ops = (CordaRPCOps) context.getAttributes().get("ops");
        StringToMethodCallParser<CordaRPCOps> parser = new StringToMethodCallParser<>(CordaRPCOps.class);

        if (command == null) {
            emitHelp(context, parser);
            return null;
        }

        String cmd = String.join(" ", command).trim();
        if (cmd.toLowerCase().startsWith("startflow")) {
            // The flow command provides better support and startFlow requires special handling anyway due to
            // the generic startFlow RPC interface which offers no type information with which to parse the
            // string form of the command.

            // TODO: Actually write the flow command.
            // out.println("Please use the 'flow' command to interact with flows rather than the 'run' command.", Color.red);

            out.println("Flow support is not yet implemented, sorry", Color.red);
            return null;
        }

        Object result = null;
        try {
            StringToMethodCallParser.ParsedMethodCall call = parser.parse(ops, cmd);
            result = call.call();
            if (result != null) {
                out.println(result.toString());
            }
        } catch (StringToMethodCallParser.UnparseableCallException e) {
            out.println(e.getMessage(), Color.red);
            out.println("Please try 'man run' to learn what syntax is acceptable", Color.red);
        }
        return result;
    }

    private void emitHelp(InvocationContext<Map> context, StringToMethodCallParser<CordaRPCOps> parser) {
        // Sends data down the pipeline about what commands are available. CRaSH will render it nicely.
        // Each element we emit is a map of column -> content.
        Map<String, String> cmdsAndArgs = parser.getAvailableCommands();
        for (Map.Entry<String, String> entry : cmdsAndArgs.entrySet()) {
            // Skip these entries as they aren't really interesting for the user.
            if (entry.getKey().equals("startFlowDynamic")) continue;
            if (entry.getKey().equals("getProtocolVersion")) continue;

            // Use a LinkedHashMap to ensure that the Command column comes first.
            Map<String, String> m = new LinkedHashMap<>();
            m.put("Command", entry.getKey());
            m.put("Parameter types", entry.getValue());
            try {
                context.provide(m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
