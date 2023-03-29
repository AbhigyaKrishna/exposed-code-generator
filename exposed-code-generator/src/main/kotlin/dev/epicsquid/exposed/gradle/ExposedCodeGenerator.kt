package dev.epicsquid.exposed.gradle

import com.sksamuel.hoplite.ConfigLoader
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import dev.epicsquid.exposed.gradle.builders.TableBuilder
import dev.epicsquid.exposed.gradle.builders.TableBuilderData
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import schemacrawler.schema.Column
import schemacrawler.schema.Table
import java.io.File
import org.jetbrains.exposed.sql.Table as ExposedTable


// TODO support schemas
/**
 * Generates files containing Exposed code for given [tables] using config [configuration] for a given DB [dialect].
 */
class ExposedCodeGenerator {
	private val tables: List<Table>
	private val dialect: dev.epicsquid.exposed.gradle.DBDialect?
	private val configuration: dev.epicsquid.exposed.gradle.ExposedCodeGeneratorConfiguration

	constructor(
		tables: List<Table>,
		config: dev.epicsquid.exposed.gradle.ExposedCodeGeneratorConfiguration = dev.epicsquid.exposed.gradle.ExposedCodeGeneratorConfiguration(),
		dialect: dev.epicsquid.exposed.gradle.DBDialect? = null
	) {
		this.tables = tables
		this.configuration = config
		this.dialect = dialect
	}

	constructor(tables: List<Table>, configFileName: String, dialect: dev.epicsquid.exposed.gradle.DBDialect? = null) {
		this.tables = tables
		this.configuration = ConfigLoader().loadConfigOrThrow(files = listOf(File(configFileName)))
		this.dialect = dialect
	}


	private val columnToPropertySpec = mutableMapOf<Column, PropertySpec>()
	private val columnToTableSpec = mutableMapOf<Column, TypeSpec>()

	private val columnNameToInitializerBlock = mutableMapOf<String, String>()


	// returns a TypeSpec used for Exposed Kotlin code generation
	private fun generateExposedTable(
		table: Table,
		configuration: dev.epicsquid.exposed.gradle.ExposedCodeGeneratorConfiguration = dev.epicsquid.exposed.gradle.ExposedCodeGeneratorConfiguration()
	): TypeSpec {
		val builder = TableBuilder(
			table,
			TableBuilderData(columnToPropertySpec, columnToTableSpec, columnNameToInitializerBlock, dialect, configuration)
		)

		builder.generateExposedTableDeclaration()
		builder.generateExposedTableColumns()
		builder.generateExposedTablePrimaryKey()
		builder.generateExposedTableMulticolumnIndexes()

		return builder.build()
	}

	/**
	 * Generates file specs for [tables] using [configuration] and minding [dialect] DB dialect.
	 */
	fun generateExposedTables(): List<FileSpec> {
		if (configuration.columnMappings.isNotEmpty()) {
			columnNameToInitializerBlock.putAll(configuration.columnMappings)
		}

		return if (configuration.generateSingleFile) {
			val fileSpec = FileSpec.builder(
				configuration.packageName,
				if (configuration.generatedFileName.isNullOrBlank()) {
					dev.epicsquid.exposed.gradle.ExposedCodeGenerator.Companion.defaultFilename
				} else {
					configuration.generatedFileName
				}
			)
			tables.forEach { fileSpec.addType(generateExposedTable(it, configuration)) }
			listOf(fileSpec.build())
		} else {
			val fileSpecs = mutableListOf<FileSpec>()
			for (table in tables) {
				val fileSpec = FileSpec.builder(
					configuration.packageName,
					if (configuration.useFullNames) table.fullName.toCamelCase(capitalizeFirst = true)
					else table.name.toCamelCase(capitalizeFirst = true)
				)
				fileSpec.addType(generateExposedTable(table, configuration))
				fileSpecs.add(fileSpec.build())
			}

			fileSpecs
		}
	}

	companion object {
		val exposedPackage: Package = ExposedTable::class.java.`package`
		private const val defaultFilename = "GeneratedTables"
		private val publicVisibilityRegex = "(\\s+)*public (.+)".toRegex(RegexOption.MULTILINE)

		fun postProcessOutput(content: String): String {
			return content.replace(dev.epicsquid.exposed.gradle.ExposedCodeGenerator.Companion.publicVisibilityRegex, "$1$2")
		}
	}
}

val logger: Logger = LoggerFactory.getLogger("ExposedCodeGeneratorLogger")