package dev.epicsquid.exposed.gradle.builders

import com.facebook.presto.sql.parser.ParsingOptions
import com.facebook.presto.sql.parser.SqlParser
import com.facebook.presto.sql.tree.ComparisonExpression
import com.facebook.presto.sql.tree.Expression
import com.facebook.presto.sql.tree.LogicalBinaryExpression
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dev.epicsquid.exposed.gradle.*
import dev.epicsquid.exposed.gradle.info.ColumnInfo
import dev.epicsquid.exposed.gradle.time.getDateTimeProviderFromConfig
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import schemacrawler.schema.Column
import schemacrawler.schema.IndexType
import schemacrawler.schema.TableConstraint
import schemacrawler.schema.TableConstraintType
import java.math.BigDecimal
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import org.jetbrains.exposed.sql.Column as ExposedColumn

/**
 * Builds a [PropertySpec] used for column code generation for a column using [data].
 */
open class ColumnBuilder(column: Column, private val data: TableBuilderData) {
	protected val columnInfo = ColumnInfo(column, data)
	protected open val builder: PropertySpec.Builder = generateBuilder()

	private fun generateBuilder(): PropertySpec.Builder {
		val columnKClass = columnInfo.columnKClass
		if (columnKClass == null
			&& columnInfo.columnExposedFunction == null
			&& columnInfo.columnStringFunction == null
			&& columnInfo.columnStringPackage == null
			&& columnInfo.enumConfig == null
		) {
			val column = columnInfo.column
			throw UnsupportedTypeException("Unable to map column ${column.name} of type ${column.columnDataType.name}, ${column.columnDataType.typeMappedClass} to an Exposed column object.")
		}
		if (data.configuration.useDao && columnInfo.column.referencedColumn != null) {
			return PropertySpec.builder(
				getPropertyNameForColumn(columnInfo.column),
				ExposedColumn::class.asTypeName().parameterizedBy(
					EntityID::class.asTypeName().parameterizedBy(
						columnInfo.columnKClass!!.asTypeName()
					).copy(nullable = columnInfo.nullable)
				)
			)
		}
		if (columnKClass == null && columnInfo.columnStringClass != null) {
			val pack = columnInfo.columnStringClass!!.substringBeforeLast(".")
			val clazz = columnInfo.columnStringClass!!.substringAfterLast(".")
			return PropertySpec.builder(
				getPropertyNameForColumn(columnInfo.column),
				ExposedColumn::class.asTypeName().parameterizedBy(ClassName(pack, clazz).copy(nullable = columnInfo.nullable))
			)
		}
		return PropertySpec.builder(
			getPropertyNameForColumn(columnInfo.column),
			ExposedColumn::class.asTypeName()
				.parameterizedBy(columnInfo.columnKClass!!.asTypeName().copy(nullable = columnInfo.nullable))
		)
	}

	/**
	 * Generates column initialization code including column definition function call and constraints
	 * using [columnToPropertySpec] and [columnToTableSpec] for proper name generation.
	 */
	open fun generateExposedColumnInitializer(
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		val initializerBlock = buildCodeBlock {
			generateExposedColumnFunctionCall(columnInfo, columnToPropertySpec, columnToTableSpec)
			generateExposedColumnConstraints(columnInfo, columnToPropertySpec, columnToTableSpec)
		}

		builder.initializer(initializerBlock)
	}

	/**
	 * Generates an Exposed function call used to initialize the column from [columnInfo].
	 */
	open fun CodeBlock.Builder.generateExposedColumnFunctionCall(
		columnInfo: ColumnInfo,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		val column = columnInfo.column
		val columnKClass = columnInfo.columnKClass
		val columnExposedFunction = columnInfo.columnExposedFunction
		val columnExposedPackage = columnExposedFunction?.javaMethod?.declaringClass?.`package`
		val packageName = columnExposedPackage?.let {
			if (columnExposedPackage == ExposedCodeGenerator.exposedPackage) {
				""
			} else {
				columnExposedPackage.name
			}
		} ?: columnInfo.columnStringPackage!!

		val memberName = MemberName(packageName, columnExposedFunction?.name ?: columnInfo.columnStringFunction!!)

		if (data.configuration.useDao && column.referencedColumn != null) {
			val referencedColumnTable = columnToTableSpec[column.referencedColumn]
			add(
				"%M(%S, %N)",
				memberName,
				columnInfo.columnName,
				referencedColumnTable
			)
		} else if (columnInfo.columnExposedFunction != null && columnInfo.columnExposedFunction!!.valueParameters.size > 1) {
			val arguments = getColumnFunctionArguments()
			when (columnKClass) {
				// decimal -> precision, scale
				BigDecimal::class -> {
					val precision: String
					val scale: String
					if (arguments.isNotEmpty()) {
						precision = arguments[0]
						scale = arguments[1]
					} else {
						precision =
							if (column.size >= 0 && column.size <= MaxSize.MAX_DECIMAL_PRECISION) {
								column.size
							} else {
								MaxSize.MAX_DECIMAL_PRECISION
							}.toString()
						scale = when {
							// it's unlikely that this is to ever happen but just to cover the possibility
							column.decimalDigits > MaxSize.MAX_DECIMAL_SCALE -> MaxSize.MAX_DECIMAL_SCALE
							column.decimalDigits < 0 -> 0
							else -> column.decimalDigits
						}.toString()
					}
					add("%M(%S, $precision, $scale)", memberName, columnInfo.columnName)
				}
				// char, varchar, binary -> length
				String::class, ByteArray::class -> {
					val collate = if (columnKClass == String::class && data.configuration.collate != null) {
						CodeBlock.of(", %S", data.configuration.collate)
					} else {
						CodeBlock.of("")
					}
					if (columnExposedFunction?.name in listOf("char", "varchar", "binary")) {
						val size = when {
							arguments.isNotEmpty() -> arguments[0]
							column.size >= 0 -> column.size.toString()
							else -> MaxSize.MAX_VARCHAR_SIZE.toString()
						}
						add("%M(%S, $size$collate)", memberName, columnInfo.columnName)
					} else {
						add("%M(%S$collate)", memberName, columnInfo.columnName)
					}

				}

				else -> add("%M(%S)", memberName, columnInfo.columnName)
			}
		} else if (columnInfo.enumConfig != null) {
			val enumConfig = columnInfo.enumConfig!!
			val pack = enumConfig.enumClassName.substringBeforeLast(".")
			val clazz = enumConfig.enumClassName.substringAfterLast(".")
			val className = ClassName(pack, clazz)

			when (data.dialect) {
				DBDialect.H2 -> enumConfig.databaseDeclaration?.let {
					add(
						"%M(%S, %S, { value -> %T.values()[it as Int] }, { it.name })",
						memberName,
						columnInfo.columnName,
						enumConfig.databaseDeclaration,
						className
					)
				} ?: add(
					"%M(%S, { value -> %T.values()[it as Int] }, { it.name })",
					memberName,
					columnInfo.columnName,
					className
				)

				DBDialect.POSTGRESQL -> {
					val pgPack = enumConfig.pgEnumClassName!!.substringBeforeLast(".")
					val pgClazz = enumConfig.pgEnumClassName!!.substringAfterLast(".")
					val pgClassName = ClassName(pgPack, pgClazz)
					add(
						"%M(%S, %S, { value -> %T.valueOf(value as String) }, { %T(%S, it) })",
						memberName,
						columnInfo.columnName,
						enumConfig.databaseDeclaration,
						className,
						pgClassName,
						enumConfig.databaseDeclaration
					)
				}

				DBDialect.MYSQL -> enumConfig.databaseDeclaration?.let {
					add(
						"%M(%S, %S, { value -> %T.valueOf(value as String) }, { it.name })",
						memberName,
						columnInfo.columnName,
						enumConfig.databaseDeclaration,
						className
					)
				} ?: add(
					"%M(%S, { value -> %T.valueOf(value as String) }, { it.name })",
					memberName,
					columnInfo.columnName,
					className
				)

				else -> throw UnsupportedTypeException("The type ${enumConfig.enumClassName} is not supported with this database dialect.")
			}
		} else {
			if (columnInfo.isColumnTyped)
				add(
					"%M<%L>(%S)",
					memberName,
					columnInfo.columnKClass?.simpleName ?: columnInfo.columnStringClass!!.substringAfterLast("."),
					columnInfo.columnName
				)
			else add("%M(%S)", memberName, columnInfo.columnName)
		}
	}

	private fun CodeBlock.Builder.generateAutoIncrementCall(column: Column) {
		if (column.isAutoIncremented) {
			add(".%M()", MemberName("", "autoIncrement"))
		}
	}

	private fun CodeBlock.Builder.generateForeignKeyCall(
		column: Column,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		if (!data.configuration.useDao && column.referencedColumn != null) {
			val referencedColumnProperty = columnToPropertySpec[column.referencedColumn]
				?: throw ReferencedColumnNotFoundException(
					"Column ${column.referencedColumn.fullName} referenced by ${column.fullName} not found."
				)
			val referencedColumnTable = columnToTableSpec[column.referencedColumn]
			if (column.parent == column.referencedColumn.parent) {
				add(
					".%M(%N)",
					MemberName("", "references"),
					referencedColumnProperty
				)
			} else {
				add(
					".%M(%N.%N)",
					MemberName("", "references"),
					referencedColumnTable, // should be not null
					referencedColumnProperty
				)
			}
		}
	}

	private fun CodeBlock.Builder.generateNullableCall(column: Column) {
		if (column.isNullable && !column.isPartOfPrimaryKey) {
			add(".%M()", MemberName("", "nullable"))
		}
	}

	private fun CodeBlock.Builder.generateIndexCall(column: Column) {
		if (column.isPartOfIndex) {
			val indexes = column.parent.indexes.filter { it.contains(column) }
				.filter { it.columns.size == 1 && (it.indexType in listOf(IndexType.other, IndexType.unknown)) }
			for (index in indexes) {
				if (index.isUnique && column.isPartOfPrimaryKey) {
					val primaryKeyColumns: List<Column> = column.parent.primaryKey.constrainedColumns
					// don't generate calls for the autogenerated primary key index
					if (index.columns.containsAll(primaryKeyColumns) && index.columns.size == primaryKeyColumns.size) {
						continue
					}
				}
				// don't generate calls for the autogenerated foreign key referencing column index
				if (!index.isUnique && index.columns.size == 1 && column.referencedColumn != null &&
					this@ColumnBuilder.data.dialect in ForeignKeyAutomaticIndexDBs
				) {
					continue
				}
				// don't generate calls for the autogenerated foreign key referenced column index in H2
				if (index.isUnique && index.columns.size == 1 && column.isPartOfForeignKey && this@ColumnBuilder.data.dialect == DBDialect.H2) {
					continue
				}
				val indexName = getIndexName(index)
				if (column.isPartOfUniqueIndex) {
					add(".%M(%S)", MemberName("", "uniqueIndex"), indexName)
				} else {
					add(".%M(%S)", MemberName("", "index"), indexName)
				}
			}
		}
	}

	private fun CodeBlock.Builder.generateCheckConstraints(column: Column) {
		val tableConstraints = column.parent.tableConstraints
			.filter { it.type == TableConstraintType.check }
			// it's better to check for `it.columns == listOf(column)` but there's a SchemaCrawler problem with this
			.filter { column.name in it.definition }
		tableConstraints.forEach {
			generateCheckConstraint(it, column)
		}
	}

	private fun CodeBlock.Builder.generateCheckConstraint(constraint: TableConstraint, column: Column) {
		if (constraint.type != TableConstraintType.check) {
			return
		}

		val constraintExpr = SqlParser().createExpression(constraint.definition, ParsingOptions.builder().build())
		add(generateConstraintFunctionCall(constraintExpr, column))
	}

	private fun generateConstraintFunctionCall(expr: Expression, column: Column): CodeBlock = when (expr) {
		is LogicalBinaryExpression -> {
			buildCodeBlock {
				add(generateConstraintFunctionCall(expr.left, column))
				add(generateConstraintFunctionCall(expr.right, column))
			}
		}

		is ComparisonExpression -> {
			buildCodeBlock {
				add(".%N{ it", MemberName("", "check"))
				add(generateComparisonFunctionCall(expr, column))
				add(" }")
			}
		}

		else -> CodeBlock.of("")
	}

	// always in form of [column on which the constraint is created] [operator] [constraint value]
	private fun normalizeExpression(expr: ComparisonExpression, column: Column): ComparisonExpression {
		val switch = column.name !in expr.left.toString()
		if (!switch) {
			return expr
		}
		val operator =
			when (expr.operator) {
				ComparisonExpression.Operator.LESS_THAN -> ComparisonExpression.Operator.GREATER_THAN
				ComparisonExpression.Operator.LESS_THAN_OR_EQUAL -> ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL
				ComparisonExpression.Operator.GREATER_THAN -> ComparisonExpression.Operator.LESS_THAN
				ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL -> ComparisonExpression.Operator.LESS_THAN_OR_EQUAL
				else -> expr.operator
			}
		return ComparisonExpression(operator, expr.right, expr.left)
	}

	private fun generateComparisonFunctionCall(expr: ComparisonExpression, column: Column): CodeBlock {
		val expression = normalizeExpression(expr, column)
		val right = expression.right
		return when (expression.operator) {
			ComparisonExpression.Operator.EQUAL -> CodeBlock.of(".%N($right)", MemberName("", "eq"))
			ComparisonExpression.Operator.NOT_EQUAL -> CodeBlock.of(".%N($right)", MemberName("", "neq"))
			ComparisonExpression.Operator.LESS_THAN -> CodeBlock.of(".%N($right)", MemberName("", "less"))
			ComparisonExpression.Operator.LESS_THAN_OR_EQUAL -> CodeBlock.of(".%N($right)", MemberName("", "lessEq"))
			ComparisonExpression.Operator.GREATER_THAN -> CodeBlock.of(".%N($right)", MemberName("", "greater"))
			ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL -> CodeBlock.of(".%N($right)", MemberName("", "greaterEq"))
			else -> CodeBlock.of("")
		}
	}

	protected fun CodeBlock.Builder.generateBasicConstraints(
		column: Column,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		generateAutoIncrementCall(column)
		generateForeignKeyCall(column, columnToPropertySpec, columnToTableSpec)
		generateIndexCall(column)
		generateCheckConstraints(column)
	}

	/**
	 * Generates column constraint function calls (such as [org.jetbrains.exposed.sql.Table.nullable])
	 * used to initialize the column from [columnInfo].
	 */
	open fun CodeBlock.Builder.generateExposedColumnConstraints(
		columnInfo: ColumnInfo,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		val column = columnInfo.column
		generateBasicConstraints(column, columnToPropertySpec, columnToTableSpec)
		generateNullableCall(column)
	}

	private fun getColumnFunctionArguments(): List<String> {
		// for columns like 'varchar(30)' the arguments are contained in the full name
		val columnType = columnInfo.column.columnDataType.fullName
		val arguments = mutableListOf<String>()

		val argumentsStart = columnType.indexOfFirst { it == '(' }
		val argumentsEnd = columnType.indexOfLast { it == ')' }
		if (argumentsStart != -1 && argumentsEnd != -1) {
			val argumentsString = columnType.substring(argumentsStart + 1, argumentsEnd)
			arguments.addAll(argumentsString.split(",").map { it.trim() })
		}

		return arguments
	}

	fun build() = builder.build()
}

/**
 * Builds a [PropertySpec] used for id column code generation for a column using [data].
 */
class IdColumnBuilder(column: Column, data: TableBuilderData) : ColumnBuilder(column, data) {
	override val builder = PropertySpec.builder(
		"id",
		ExposedColumn::class.asTypeName()
			.parameterizedBy(
				EntityID::class.asTypeName()
					.parameterizedBy(columnInfo.columnKClass?.asTypeName() ?: ClassName.bestGuess(columnInfo.columnStringClass!!))
			),
		KModifier.OVERRIDE
	)

	/**
	 * Generates id column constraints for the id column from [columnInfo] using [columnToPropertySpec] and [columnToTableSpec].
	 */
	override fun CodeBlock.Builder.generateExposedColumnConstraints(
		columnInfo: ColumnInfo,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		generateBasicConstraints(columnInfo.column, columnToPropertySpec, columnToTableSpec)
		add(".%M()", MemberName("", "entityId"))
	}
}

/**
 * Builds a [PropertySpec] used for column code generation for a column using [data] and mapping [columnMapping].
 * The column is initialized with [columnMapping] instead of Exposed code generated by using column metadata.
 * if [columnMapping] is set to the name of a column, the [customMappings] config is used to generate the mapping.
 */
open class MappedColumnBuilder(column: Column, private val columnMapping: String, data: TableBuilderData) :
	ColumnBuilder(column, data) {
	protected val mappedColumnType = getColumnTypeByFunctionCall(columnMapping)
	private val dateTimeProvider = getDateTimeProviderFromConfig(data.configuration.dateTimeProvider)
	private val customMappings = data.configuration.customMappings

	private fun getColumnTypeByFunctionCall(functionCall: String) = when (functionCall.takeWhile { it != '(' }) {
		"byte" -> Byte::class
		"short" -> Short::class
		"integer" -> Integer::class
		"long" -> Long::class
		"decimal" -> BigDecimal::class
		"float" -> Float::class
		"double" -> Double::class
		"boolean" -> Boolean::class
		"binary" -> ByteArray::class
		"blob" -> ExposedBlob::class
		"char", "varchar", "text" -> String::class
		"date" -> dateTimeProvider.dateClass
		"datetime" -> dateTimeProvider.dateTimeClass
		in customMappings -> Class.forName(customMappings[functionCall]!!.columnPropertyClassName).kotlin
		else -> throw UnparseableExposedCallException("Unable to determine type of expression $functionCall and generate column.")
	}

	override val builder = PropertySpec.builder(
		getPropertyNameForColumn(column),
		ExposedColumn::class.asTypeName()
			.parameterizedBy(mappedColumnType.asTypeName().copy(nullable = columnInfo.nullable))
	)

	override fun CodeBlock.Builder.generateExposedColumnFunctionCall(
		columnInfo: ColumnInfo,
		columnToPropertySpec: Map<Column, PropertySpec>,
		columnToTableSpec: Map<Column, TypeSpec>
	) {
		add(CodeBlock.of(columnMapping))
	}
}

/**
 * Builds a [PropertySpec] used for id column code generation for a column using [data] and mapping [columnMapping].
 * The column is initialized with [columnMapping] instead of Exposed code generated by using column metadata.
 */
class MappedIdColumnBuilder(column: Column, columnMapping: String, data: TableBuilderData) :
	MappedColumnBuilder(column, columnMapping, data) {
	override val builder = PropertySpec.builder(
		getPropertyNameForColumn(column),
		ExposedColumn::class.asTypeName()
			.parameterizedBy(EntityID::class.asTypeName().parameterizedBy(mappedColumnType.asTypeName())),
		KModifier.OVERRIDE
	)
}