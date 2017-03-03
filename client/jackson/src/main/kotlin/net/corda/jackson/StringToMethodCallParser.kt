package net.corda.jackson

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import net.corda.jackson.StringToMethodCallParser.ParsedMethodCall
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.concurrent.Callable
import javax.annotation.concurrent.ThreadSafe
import kotlin.reflect.KClass
import kotlin.reflect.jvm.kotlinFunction

/**
 * This class parses strings in a format designed for human usability into [ParsedMethodCall] objects representing a
 * ready-to-invoke call on the given target object. The strings accepted by this class are a minor variant of
 * [Yaml](http://www.yaml.org/spec/1.2/spec.html) and can be easily typed at a command line. Intended use cases include
 * things like the Corda shell, text-based RPC dispatch, simple scripting and so on.
 *
 * The format of the string is as follows. The first word is the name of the method and must always be present. The rest,
 * which is optional, is wrapped in curly braces and parsed as if it were a Yaml object. The keys of this object are then
 * mapped to the parameters of the method via the usual Jackson mechanisms. The standard [java.lang.Object] methods are
 * excluded.
 *
 * One convenient feature of Yaml is that barewords collapse into strings, thus you can write a call like the following:
 *
 *     fun someCall(note: String, option: Boolean)
 *
 *     someCall note: This is a really helpful feature, option: true
 *
 * ... and it will be parsed in the intuitive way. Quotes are only needed if you want to put a comma into the string.
 *
 * There is a convenient [online Yaml parser](http://yaml-online-parser.appspot.com/) which can be used to explore
 * the allowed syntax.
 *
 * # Usage
 *
 * This class is thread safe. Multiple strings may be parsed in parallel, and the resulting [ParsedMethodCall]
 * objects may be reused multiple times and also invoked in parallel, as long as the underling target object is
 * thread safe itself.
 *
 * You may pass in an alternative [ObjectMapper] to control what types can be parsed, but it must be configured
 * with the [YAMLFactory] for the class to work.
 *
 * # Limitations
 *
 * - The target class must be either a Kotlin class, or a Java class compiled with the -parameters command line
 *   switch, as the class relies on knowing the names of parameters which isn't data provided by default by the
 *   Java compiler.
 *
 * # Examples
 *
 *     fun simple() = ...
 *     "simple"   -> runs the no-args function 'simple'
 *
 *     fun attachmentExists(id: SecureHash): Boolean
 *     "attachmentExists id: b6d7e826e87"  -> parses the given ID as a SecureHash
 *
 *     fun addNote(id: SecureHash, note: String)
 *     "addNote id: b6d7e826e8739ab2eb6e077fc4fba9b04fb880bb4cbd09bc618d30234a8827a4, note: Some note"
 */
@ThreadSafe
open class StringToMethodCallParser<in T : Any>(targetType: Class<out T>,
                                                private val om: ObjectMapper = JacksonSupport.createNonRpcMapper(YAMLFactory())) {
    /** Same as the regular constructor but takes a Kotlin reflection [KClass] instead of a Java [Class]. */
    constructor(targetType: KClass<out T>) : this(targetType.java)

    companion object {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val ignoredNames = Object::class.java.methods.map { it.name }
        private fun methodsFromType(clazz: Class<*>) = clazz.methods.map { it.name to it }.toMap().filterKeys { it !in ignoredNames }
        private val log = LoggerFactory.getLogger(StringToMethodCallParser::class.java)!!
    }

    /** The methods that can be invoked via this parser. */
    protected val methodMap = methodsFromType(targetType)
    /** A map of method name to parameter names for the target type. */
    protected val methodParamNames: Map<String, List<String>> = targetType.declaredMethods.map { it.name to paramNamesFromMethod(it) }.toMap()

    inner class ParsedMethodCall(private val target: T?, val methodName: String, val args: Array<Any?>) : Callable<Any?> {
        operator fun invoke(): Any? = call()
        override fun call(): Any? {
            if (target == null)
                throw IllegalStateException("No target object was specified")
            if (log.isDebugEnabled)
                log.debug("Invoking call $methodName($args)")
            return methodMap[methodName]!!.invoke(target, *args)
        }
    }

    protected fun paramNamesFromMethod(method: Method): List<String> {
        val kf = method.kotlinFunction
        return method.parameters.mapIndexed { index, param ->
            when {
                param.isNamePresent -> param.name
            // index + 1 because the first Kotlin reflection param is 'this', but that doesn't match Java reflection.
                kf != null -> kf.parameters[index + 1].name ?: throw UnparseableCallException.ReflectionDataMissing(method.name, index)
                else -> throw UnparseableCallException.ReflectionDataMissing(method.name, index)
            }
        }
    }

    open class UnparseableCallException(command: String) : Exception("Could not parse as a command: $command") {
        class UnknownMethod(val methodName: String) : UnparseableCallException("Unknown command name: $methodName")
        class MissingParameter(methodName: String, paramName: String, command: String) : UnparseableCallException("Parameter $paramName missing from attempt to invoke $methodName in command: $command")
        class ReflectionDataMissing(methodName: String, argIndex: Int) : UnparseableCallException("Method $methodName missing parameter name at index $argIndex")
    }

    /**
     * Parses the given command as a call on the target type. The target should be specified, if it's null then
     * the resulting [ParsedMethodCall] can't be invoked, just inspected.
     */
    @Throws(UnparseableCallException::class)
    fun parse(target: T?, command: String): ParsedMethodCall {
        log.debug("Parsing call command from string: {}", command)
        val spaceIndex = command.indexOf(' ')
        val name = if (spaceIndex != -1) command.substring(0, spaceIndex) else command
        // If we have parameters, wrap them in {} to allow the Yaml parser to eat them on a single line.
        val parameterString = if (spaceIndex != -1) "{ " + command.substring(spaceIndex) + " }" else null
        val method = methodMap[name] ?: throw UnparseableCallException.UnknownMethod(name)
        log.debug("Parsing call for method {}", name)

        if (parameterString == null) {
            if (method.parameterCount == 0)
                return ParsedMethodCall(target, name, emptyArray())
            else
                throw UnparseableCallException.MissingParameter(name, methodParamNames[name]!![0], command)
        } else {
            val tree: JsonNode = om.readTree(parameterString) ?: throw UnparseableCallException(command)
            val inOrderParams: List<Any?> = methodParamNames[name]!!.mapIndexed { index, argName ->
                val entry = tree[argName] ?: throw UnparseableCallException.MissingParameter(name, argName, command)
                om.readValue(entry.traverse(), method.parameters[index].type)
            }
            if (log.isDebugEnabled) {
                inOrderParams.forEachIndexed { i, param ->
                    val tmp = if (param != null) "${param.javaClass.name} -> $param" else "(null)"
                    log.debug("Parameter $i $tmp")
                }
            }
            return ParsedMethodCall(target, name, inOrderParams.toTypedArray())
        }
    }
}