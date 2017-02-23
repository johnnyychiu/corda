package net.corda.node

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import net.corda.core.div
import net.corda.core.utilities.Emoji
import net.corda.jackson.JacksonSupport
import net.corda.node.internal.Node
import net.corda.node.services.User
import net.corda.node.services.messaging.ArtemisMessagingComponent
import net.corda.node.services.messaging.CURRENT_RPC_USER
import org.crsh.console.jline.JLineProcessor
import org.crsh.console.jline.TerminalFactory
import org.crsh.console.jline.console.ConsoleReader
import org.crsh.shell.ShellFactory
import org.crsh.standalone.Bootstrap
import org.crsh.util.InterruptHandler
import org.crsh.util.Utils
import org.crsh.vfs.FS
import org.crsh.vfs.spi.file.FileMountFactory
import org.crsh.vfs.spi.url.ClassPathMountFactory
import rx.Observable
import rx.Subscriber
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread

/**
 * Starts an interactive shell connected to the local terminal. This shell gives administrator access to the node
 * internals.
 */
internal fun startShell(dir: Path, cmdLineOptions: CmdLineOptions, node: Node) {
    var runSSH = cmdLineOptions.sshdServer
    if (cmdLineOptions.noLocalShell && !runSSH) return

    try {
        // Disable JDK logging, which CRaSH uses.
        Logger.getLogger("").level = Level.OFF

        val classpathDriver = ClassPathMountFactory(Thread.currentThread().contextClassLoader)
        val fileDriver = FileMountFactory(Utils.getCurrentDirectory());

        val extraCommandsPath = (dir / "shell-commands").toAbsolutePath()
        Files.createDirectories(extraCommandsPath)
        val commandsFS = FS.Builder()
                .register("file", fileDriver)
                .mount("file:" + extraCommandsPath)
                .register("classpath", classpathDriver)
                .mount("classpath:/net/corda/node/shell/")
                .mount("classpath:/crash/commands/")
                .build()
        // TODO: Re-point to our own conf path.
        val confFS = FS.Builder()
                .register("classpath", classpathDriver)
                .mount("classpath:/crash")
                .build()

        val bootstrap = Bootstrap(Thread.currentThread().contextClassLoader, confFS, commandsFS)

        val config = Properties()
        if (runSSH) {
            // TODO: Finish and enable SSH access.
            // This means bringing the CRaSH SSH plugin into the Corda tree and applying Marek's patches
            // found in https://github.com/marekdapps/crash/commit/8a37ce1c7ef4d32ca18f6396a1a9d9841f7ff643
            // to that local copy, as CRaSH is no longer well maintained by the upstream and the SSH plugin
            // that it comes with is based on a very old version of Apache SSHD which can't handle connections
            // from newer SSH clients. It also means hooking things up to the authentication system.
            printBasicNodeInfo("SSH server access is not fully implemented, sorry.")
            runSSH = false
        }

        if (runSSH) {
            // Enable SSH access. Note: these have to be strings, even though raw object assignments also work.
            config["crash.ssh.keypath"] = (dir / "sshkey").toString()
            config["crash.ssh.keygen"] = "true"
            config["crash.ssh.port"] = node.configuration.sshdAddress.port.toString()
            config["crash.auth"] = "simple"
            config["crash.auth.simple.username"] = "admin"
            config["crash.auth.simple.password"] = "admin"
        }

        bootstrap.config = config
        bootstrap.setAttributes(mapOf(Pair("node", node)))
        bootstrap.setAttributes(mapOf(Pair("ops", node.rpcOps)))
        bootstrap.bootstrap()

        // TODO: Automatically set up the JDBC sub-command with a connection to the database.

        if (runSSH)
            printBasicNodeInfo("SSHD listening on address", node.configuration.sshdAddress.toString())

        // Possibly bring up a local shell in the launching terminal window, unless it's disabled.
        if (cmdLineOptions.noLocalShell)
            return
        val shell = bootstrap.context.getPlugin(ShellFactory::class.java).create(null)
        val terminal = TerminalFactory.create()
        val consoleReader = ConsoleReader("Corda", FileInputStream(FileDescriptor.`in`), System.out, terminal)
        val jlineProcessor = JLineProcessor(terminal.isAnsiSupported, shell, consoleReader, System.out)
        InterruptHandler { jlineProcessor.interrupt() }.install()
        thread(name = "Command line shell processor", isDaemon = true) {
            // Give whoever has local shell access administrator access to the node.
            CURRENT_RPC_USER.set(User(ArtemisMessagingComponent.NODE_USER, "", setOf()))
            Emoji.renderIfSupported {
                jlineProcessor.run()
            }
        }
        thread(name = "Command line shell terminator", isDaemon = true) {
            // Wait for the shell to finish.
            jlineProcessor.closed()
            terminal.restore()
            node.stop()
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}

private object ObservableSerializer : JsonSerializer<Observable<*>>() {
    override fun serialize(value: Observable<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString("(observable)")
    }
}

private fun createOutputMapper(factory: JsonFactory): ObjectMapper {
    return JacksonSupport.createNonRpcMapper(factory).apply({
        // Register serializers for stateful objects from libraries that are special to the RPC system and don't
        // make sense to print out to the screen. For classes we own, annotations can be used instead.
        val rpcModule = SimpleModule("RPC module")
        rpcModule.addSerializer(Observable::class.java, ObservableSerializer)
        registerModule(rpcModule)

        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        enable(SerializationFeature.INDENT_OUTPUT)
    })
}

private val yamlMapper by lazy { createOutputMapper(YAMLFactory()) }
private val jsonMapper by lazy { createOutputMapper(JsonFactory()) }

enum class RpcResponsePrintingFormat {
    yaml, json, tostring
}

fun printAndFollowRPCResponse(outputFormat: RpcResponsePrintingFormat, response: Any?, toStream: PrintStream = System.out): CompletableFuture<Unit>? {
    val printerFun = when (outputFormat) {
        RpcResponsePrintingFormat.yaml -> { obj: Any? -> yamlMapper.writeValueAsString(obj) }
        RpcResponsePrintingFormat.json -> { obj: Any? -> jsonMapper.writeValueAsString(obj) }
        RpcResponsePrintingFormat.tostring -> { obj: Any? -> Emoji.renderIfSupported { obj.toString() } }
    }
    toStream.println(printerFun(response))
    return maybeFollow(response, printerFun, toStream)
}

private class PrintingSubscriber(private val printerFun: (Any?) -> String, private val toStream: PrintStream) : Subscriber<Any>() {
    private var count = 0;
    val future = CompletableFuture<Unit>()

    init {
        // The future is public and can be completed by something else to indicate we don't wish to follow
        // anymore (e.g. the user pressing Ctrl-C).
        future.thenAccept {
            if (!isUnsubscribed)
                unsubscribe()
        }
        println("Waiting for observations")
    }

    @Synchronized
    override fun onCompleted() {
        toStream.println("Observable has completed")
        future.complete(Unit)
    }

    @Synchronized
    override fun onNext(t: Any?) {
        count++
        toStream.println("Observation $count: " + printerFun(t))
    }

    @Synchronized
    override fun onError(e: Throwable) {
        toStream.println("Observable completed with an error")
        e.printStackTrace()
        future.completeExceptionally(e)
    }
}

// Kotlin bug: USELESS_CAST warning is generated below but the IDE won't let us remove it.
@Suppress("USELESS_CAST", "UNCHECKED_CAST")
private fun maybeFollow(response: Any?, printerFun: (Any?) -> String, toStream: PrintStream): CompletableFuture<Unit>? {
    // Match on a couple of common patterns for "important" observables. It's tough to do this in a generic
    // way because observables can be embedded anywhere in the object graph, and can emit other arbitrary
    // object graphs that contain yet more observables. So we just look for top level responses that follow
    // the standard "track" pattern, and print them until the user presses Ctrl-C
    if (response == null) return null

    val observable: Observable<*> = when (response) {
        is Observable<*> -> response
        is Pair<*, *> -> when {
            response.first is Observable<*> -> response.first as Observable<*>
            response.second is Observable<*> -> response.second as Observable<*>
            else -> null
        }
        else -> null
    } ?: return null

    val subscriber = PrintingSubscriber(printerFun, toStream)
    (observable as Observable<Any>).subscribe(subscriber)
    return subscriber.future
}
