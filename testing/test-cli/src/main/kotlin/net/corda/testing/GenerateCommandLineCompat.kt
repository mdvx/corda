package net.corda.testing

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import picocli.CommandLine
import java.io.InputStream
import java.util.*
import kotlin.collections.ArrayList


class CommandLineCompatibilityChecker {

    fun topoSort(commandLine: CommandLine): List<CommandDescription> {
        val toVisit = Stack<CommandLine>()
        toVisit.push(commandLine)
        val sorted: MutableList<CommandLine> = ArrayList();
        while (toVisit.isNotEmpty()) {
            val visiting = toVisit.pop()
            sorted.add(visiting)
            visiting.subcommands.values.sortedBy { it.commandName }.forEach {
                toVisit.push(it)
            }
        }
        return buildDescriptors(sorted)
    }

    private fun buildDescriptors(result: MutableList<CommandLine>): List<CommandDescription> {
        return result.map { ::parseToDescription.invoke(it) }
    }

    internal fun parseToDescription(it: CommandLine): CommandDescription {
        val commandSpec = it.commandSpec
        val options = commandSpec.options().filterNot { it.usageHelp() || it.versionHelp() }
                .map { hit -> hit.names().map { it to hit } }
                .flatMap { it }
                .sortedBy { it.first }
                .map {
                    val type = it.second.type()
                    ParameterDescription(it.first, type.componentType?.canonicalName
                            ?: type.canonicalName, it.second.required(), isMultiple(type))
                }

        val positionals = commandSpec.positionalParameters().sortedBy { it.index() }.map {
            val type = it.type()
            ParameterDescription(it.index().toString(), type.componentType?.canonicalName
                    ?: type.canonicalName, it.required(), isMultiple(type))
        }
        return CommandDescription(it.commandName, positionals, options)
    }

    data class CommandDescription(val commandName: String, val positionalParams: List<ParameterDescription>, val params: List<ParameterDescription>)
    data class ParameterDescription(val parameterName: String, val parameterType: String, val required: Boolean, val multiParam: Boolean)

    fun isMultiple(clazz: Class<*>): Boolean {
        return Iterable::class.java.isAssignableFrom(clazz) || Array<Any>::class.java.isAssignableFrom(clazz)
    }

    fun printCommandDescription(commandLine: CommandLine) {
        val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val results = topoSort(commandLine)
        println(objectMapper.writeValueAsString(results))
    }

    fun readCommandDescription(inputStream: InputStream): List<CommandDescription> {
        val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        return objectMapper.readValue<List<CommandDescription>>(inputStream, object : TypeReference<List<CommandDescription>>() {});
    }

    fun checkAllCommandsArePresent(old: List<CommandDescription>, new: List<CommandDescription>): List<CliBackwardsCompatibilityValidationCheck> {
        val oldSet = old.map { it.commandName }.toSet()
        val newSet = new.map { it.commandName }.toSet()
        val newIsSuperSetOfOld = newSet.containsAll(oldSet)
        return if (!newIsSuperSetOfOld) {
            oldSet.filterNot { newSet.contains(it) }.map {
                CliBackwardsCompatibilityValidationCheck("SubCommand: $it has been removed from the CLI interface")
            }
        } else {
            emptyList()
        }
    }

    fun checkAllOptionsArePresent(old: CommandDescription, new: CommandDescription): List<CliBackwardsCompatibilityValidationCheck> {
        if (old.commandName != new.commandName) {
            throw IllegalArgumentException("Commands must match (${old.commandName} != ${new.commandName})")
        }
        val oldSet = old.params.sortedBy { it.parameterName }.toSet()
        val newSet = new.params.sortedBy { it.parameterName }.toSet()

        val newIsSuperSetOfOld = newSet.containsAll(oldSet)

        return if (!newIsSuperSetOfOld) {
            oldSet.filterNot { newSet.contains(it) }.map {
                CliBackwardsCompatibilityValidationCheck("Parameter: ${it.parameterName} has been removed from subcommand: ${old.commandName}")
            }
        } else {
            emptyList()
        }
    }

    fun checkAllPositionalCharactersArePresent(old: CommandDescription, new: CommandDescription): List<CliBackwardsCompatibilityValidationCheck> {
        if (old.commandName != new.commandName) {
            throw IllegalArgumentException("Commands must match (${old.commandName} != ${new.commandName})")
        }
        val oldSet = old.positionalParams.sortedBy { it.parameterName }.toSet()
        val newSet = new.positionalParams.sortedBy { it.parameterName }.toSet()
        val newIsSuperSetOfOld = newSet.containsAll(oldSet)
        return if (!newIsSuperSetOfOld) {
            oldSet.filterNot { newSet.contains(it) }.map {
                CliBackwardsCompatibilityValidationCheck("Positional Parameter [ ${it.parameterName} ] has been removed from subcommand: ${old.commandName}")
            }
        } else {
            emptyList()
        }
    }

    fun checkAllParamsAreOfTheSameType(old: CommandDescription, new: CommandDescription): List<CliBackwardsCompatibilityValidationCheck> {

        val oldMap = old.params.map { it.parameterName to it.parameterType }.toMap()
        val newMap = new.params.map { it.parameterName to it.parameterType }.toMap()

        return oldMap.filter { newMap[it.key] != null && newMap[it.key] != it.value }.map {
            CliBackwardsCompatibilityValidationCheck("Parameter [ ${it.key} has changed from type: ${it.value} to ${newMap[it.key]}")
        }

    }

    fun checkCommandLineIsBackwardsCompatible(commandLineToCheck: Class<*>): List<CliBackwardsCompatibilityValidationCheck> {
        val commandLineToCheckName = commandLineToCheck.canonicalName
        val instance = commandLineToCheck.newInstance()
        val resourceAsStream = this.javaClass.classLoader.getResourceAsStream("$commandLineToCheckName.yml")
                ?: throw IllegalStateException("no Descriptor for $commandLineToCheckName found on classpath")
        val old = readCommandDescription(resourceAsStream)
        val new = topoSort(CommandLine(instance))
        val results = ArrayList<CliBackwardsCompatibilityValidationCheck>()
        results += checkAllCommandsArePresent(old, new)
        for (oldCommand in old) {
            new.find { it.commandName == oldCommand.commandName }?.let { newCommand ->
                results += checkAllOptionsArePresent(oldCommand, newCommand)
                results += checkAllParamsAreOfTheSameType(oldCommand, newCommand)
                results += checkAllPositionalCharactersArePresent(oldCommand, newCommand)
            }
        }

        return results
    }
}


data class CliBackwardsCompatibilityValidationCheck(val message: String)
